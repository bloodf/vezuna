"""De-identified, text-first reference parser for Vezuna lab reports.

The module deliberately does not perform OCR.  Native callers can use the
``ocr_required`` classification to invoke their image/OCR pipeline.  No source
text or source digest is included in the returned document.
"""

from __future__ import annotations

import hashlib
import json
import math
import re
import unicodedata
from pathlib import Path
from typing import Any, Iterable


LIGATURES = str.maketrans(
    {
        "\ufb00": "ff",
        "\ufb01": "fi",
        "\ufb02": "fl",
        "\ufb03": "ffi",
        "\ufb04": "ffl",
        "\ufb05": "st",
        "\ufb06": "st",
    }
)
NBSP = dict.fromkeys(map(ord, "\u00a0\u202f\u2007\u2009"), " ")
NUMBER = r"[+-]?(?:\d{1,3}(?:\.\d{3})+(?:,\d+)?|\d+\.\d+|\d+(?:,\d+)?)"
UNITS = (
    "µUI/mL",
    "μUI/mL",
    "uUI/mL",
    "mUI/mL",
    "UI/mL",
    "U/mL",
    "ng/dL",
    "ng/mL FEU",
    "ng/mL",
    "pg/mL",
    "µg/dL",
    "μg/dL",
    "ug/dL",
    "µg/L",
    "μg/L",
    "ug/L",
    "mg/dL",
    "mg/L",
    "g/dL",
    "g/L",
    "mmol/L",
    "µmol/L",
    "μmol/L",
    "umol/L",
    "nmol/L",
    "10^9/L",
    "10^12/L",
    "10^6/µL",
    "10^6/μL",
    "10^3/µL",
    "10^3/μL",
    "U/L",
    "UI/L",
    "/µL",
    "/μL",
    "/uL",
    "/mL",
    "fL",
    "%",
)
UNIT_PATTERN = "(?:" + "|".join(re.escape(unit) for unit in UNITS) + ")"
QUALITATIVE_VALUES = (
    "Ausente",
    "Presente",
    "Negativo",
    "Positivo",
    "Não reagente",
    "Nao reagente",
    "Reagente",
)

# These are the conversions in shared LabLogic.kt, plus /µL and /mL used by
# the supplied report layouts.  Unknown units remain unchanged and therefore
# cannot be compared accidentally.
UNIT_SCALES = {
    "g/l": ("g/L", 1.0),
    "g/dl": ("g/L", 10.0),
    "mg/dl": ("mg/L", 10.0),
    "mg/l": ("mg/L", 1.0),
    "ug/l": ("mg/L", 0.001),
    "µg/l": ("mg/L", 0.001),
    "μg/l": ("mg/L", 0.001),
    "ng/ml": ("µg/L", 1.0),
    "ug/ml": ("mg/L", 1.0),
    "mg": ("g", 0.001),
    "g": ("g", 1.0),
    "kg": ("g", 1000.0),
    "mmol/l": ("mmol/L", 1.0),
    "umol/l": ("mmol/L", 0.001),
    "µmol/l": ("mmol/L", 0.001),
    "μmol/l": ("mmol/L", 0.001),
    "10^9/l": ("10^9/L", 1.0),
    "10^12/l": ("10^12/L", 1.0),
    "/µl": ("/µL", 1.0),
    "/μl": ("/µL", 1.0),
    "/ul": ("/µL", 1.0),
    "/ml": ("/mL", 1.0),
    "%": ("%", 1.0),
}

SKIP_PREFIXES = (
    "resultado",
    "resultados",
    "material",
    "método",
    "metodo",
    "nota",
    "notas",
    "observação",
    "observacao",
    "referência",
    "referencia",
    "histórico",
    "historico",
    "tabela de referência",
    "tabela de referencia",
    "página",
    "pagina",
    "assinatura",
    "validação",
    "validacao",
    "autenticação",
    "autenticacao",
    "cnes",
    "dasa",
    "rua ",
    "avenida ",
    "av. ",
)
SKIP_CONTAINS = (
    "a conclusão diagnóstica",
    "a conclusao diagnostica",
    "valores de referência",
    "valores de referencia",
    "bibliográ",
    "bibliogra",
    "código de validação",
    "codigo de validacao",
)


def normalize_text(value: str) -> str:
    """Apply the mobile text cleanup contract without destroying line breaks."""

    value = value.translate(LIGATURES).translate(NBSP).replace("\u00ad", "")
    value = unicodedata.normalize("NFC", value).replace("\r\n", "\n").replace("\r", "\n")
    return "\n".join(re.sub(r"[\t\f\v ]+", " ", line).strip() for line in value.split("\n"))


def folded(value: str) -> str:
    return unicodedata.normalize("NFKD", value).encode("ascii", "ignore").decode().casefold()


def parse_number(token: str) -> float:
    token = token.strip()
    if "," in token:
        return float(token.replace(".", "").replace(",", "."))
    # Brazilian lab reports use a period as a thousands separator when there
    # are exactly three following digits (5.600 /µL).
    if re.fullmatch(r"[+-]?[1-9]\d{0,2}(?:\.\d{3})+", token):
        return float(token.replace(".", ""))
    return float(token)


def format_number(value: float) -> float | int:
    return int(value) if math.isfinite(value) and value.is_integer() else value


def normalize_quantity(value: float, unit: str) -> tuple[float, str]:
    if not math.isfinite(value):
        raise ValueError("non-finite laboratory value")
    key = re.sub(r"\s+", "", unit.strip()).casefold()
    normalized_unit, scale = UNIT_SCALES.get(key, (unit.strip(), 1.0))
    return value * scale, normalized_unit


def canonical_test_code(value: str) -> str:
    # Match LabLogic.kt exactly: uppercase, then remove every non-ASCII
    # alphanumeric.  In particular, "Selênio" becomes "SELNIO" natively.
    return re.sub(r"[^A-Z0-9]", "", unicodedata.normalize("NFC", value).upper())


def observation_fingerprint(
    observation: dict[str, Any], profile_id: str = "", collected_at: str = ""
) -> str:
    """Canonical clinical identity; document tokens and footers are not inputs."""

    if observation["value_type"] == "numeric":
        value, unit = normalize_quantity(
            float(observation["normalized_value"]), observation["normalized_unit"]
        )
        # Kotlin roundToLong uses nearest-integer rounding with ties toward
        # positive infinity; Python's round() would instead use banker's ties.
        canonical_value = str(math.floor(value * 1_000_000_000 + 0.5))
    else:
        canonical_value = folded(str(observation["value"])).strip()
        unit = ""
    return "|".join(
        (profile_id, canonical_test_code(observation["analyte"]), collected_at, canonical_value, unit)
    )


def _reference(lower: float | None = None, upper: float | None = None, **extra: Any) -> dict[str, Any]:
    result: dict[str, Any] = {
        "kind": "range",
        "lower": format_number(lower) if lower is not None else None,
        "upper": format_number(upper) if upper is not None else None,
        "unit": None,
        "text": None,
    }
    result.update(extra)
    return result


def _find_references(rest: str) -> list[dict[str, Any]]:
    references: list[dict[str, Any]] = []
    range_pattern = re.compile(rf"({NUMBER})\s*(?:a|até|ate|to|–|—|-)\s*({NUMBER})\s*({UNIT_PATTERN})?", re.I)
    threshold_pattern = re.compile(
        rf"(inferior(?:\s+[àa])?|menor\s+que|até|ate|no\s+m[áa]ximo|superior(?:\s+[àa])?|maior\s+que|acima\s+de)\s*[:=]?\s*({NUMBER})\s*({UNIT_PATTERN})?",
        re.I,
    )
    occupied: list[tuple[int, int]] = []
    for match in range_pattern.finditer(rest):
        lower, upper = parse_number(match.group(1)), parse_number(match.group(2))
        if lower > upper:
            continue
        unit = match.group(3)
        references.append(
            _reference(lower, upper, unit=unit, text=match.group(0).strip(), _start=match.start())
        )
        occupied.append(match.span())
    for match in threshold_pattern.finditer(rest):
        if any(match.start() >= start and match.end() <= end for start, end in occupied):
            continue
        operator_text = folded(match.group(1))
        value = parse_number(match.group(2))
        if operator_text.startswith(("inferior", "menor", "até", "ate", "no maximo")):
            reference = _reference(
                upper=value,
                kind="threshold",
                operator="<=" if operator_text.startswith(("até", "ate", "no maximo")) else "<",
                unit=match.group(3),
                text=match.group(0).strip(),
                _start=match.start(),
            )
        else:
            reference = _reference(
                lower=value,
                kind="threshold",
                operator=">",
                unit=match.group(3),
                text=match.group(0).strip(),
                _start=match.start(),
            )
        references.append(reference)
    return sorted(references, key=lambda item: item.pop("_start"))


def _parse_reference(rest: str, unit: str | None) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    flat = folded(rest)
    if re.search(r"vide.*intervalo|confira\s+na\s+tabela|tabela\s+de\s+referencia|ver\s+referencia", flat):
        reference = _reference(kind="deferred", text=rest.strip() or "Referência em tabela")
        reference.pop("lower", None)
        reference.pop("upper", None)
        return reference, []
    if re.search(r"nao\s+apresenta.*referencia.*definid|sem\s+valor\s+de\s+referencia\s+definido", flat):
        reference = _reference(kind="undefined", text=rest.strip())
        reference.pop("lower", None)
        reference.pop("upper", None)
        return reference, []
    references = _find_references(rest)
    if references:
        matching = [item for item in references if item.get("unit") == unit]
        return (matching[-1] if matching else references[-1]), references
    reference = _reference(kind="undefined", text=rest.strip() or None)
    reference.pop("lower", None)
    reference.pop("upper", None)
    return reference, []


def _valid_analyte(name: str) -> bool:
    name = name.strip(" .;:-")
    flat = folded(name)
    if not name or len(name) > 100 or not re.search(r"[A-Za-zÀ-ÿ]", name):
        return False
    if flat.startswith(SKIP_PREFIXES) or any(part in flat for part in SKIP_CONTAINS):
        return False
    if flat.startswith(
        (
            "desejavel",
            "normal",
            "baixo",
            "alto",
            "risco",
            "homens",
            "mulheres",
            "adultos",
            "prematuros",
            "populacao saudavel",
            "com jejum",
            "extremo",
            "quando os niveis",
            "os valores de",
            "sensibilidade do metodo",
            "valor de referencia",
            "inferior a",
            "superior a",
            "* valor padrao",
        )
    ):
        return False
    if re.match(rf"^{NUMBER}\s*(?:a|–|—)", name):
        return False
    if re.search(rf"{NUMBER}\s*(?:{UNIT_PATTERN})\b", name, re.I):
        return False
    return True


def _base_observation(name: str) -> dict[str, Any]:
    return {"analyte": name.strip(" .;:-")}


def _finish_numeric(
    observation: dict[str, Any], value: float, unit: str, reference: dict[str, Any]
) -> dict[str, Any]:
    normalized_value, normalized_unit = normalize_quantity(value, unit)
    observation.update(
        value_type="numeric",
        value=format_number(value),
        value_operator=None,
        unit=unit,
        normalized_value=format_number(normalized_value),
        normalized_unit=normalized_unit,
        reference=reference,
        additional_values=[],
        range_assessment="unknown",
    )
    observation["range_assessment"] = assess_range(observation)
    return observation


def assess_range(observation: dict[str, Any]) -> str:
    reference = observation["reference"]
    if reference["kind"] not in ("range", "threshold"):
        return "unknown"
    value = float(observation["normalized_value"])
    result_unit = observation["normalized_unit"]
    reference_unit = reference.get("unit") or observation["unit"]
    converted: dict[str, float] = {}
    for key in ("lower", "upper"):
        if reference.get(key) is None:
            continue
        converted_value, converted_unit = normalize_quantity(float(reference[key]), reference_unit)
        if converted_unit.casefold() != result_unit.casefold():
            return "incompatible_units"
        converted[key] = converted_value
    if "lower" in converted and (
        value < converted["lower"]
        or (reference["kind"] == "threshold" and reference.get("operator") == ">" and value == converted["lower"])
    ):
        return "below"
    if "upper" in converted and (
        value > converted["upper"]
        or (reference["kind"] == "threshold" and reference.get("operator") == "<" and value == converted["upper"])
    ):
        return "above"
    if converted:
        return "within"
    return "unknown"


def _parse_qualified_numeric(line: str) -> dict[str, Any] | None:
    pattern = re.compile(
        rf"^(?P<name>.+?)\s+(?P<qualifier>inferior(?:\s+[àa])?|menor\s+que|superior(?:\s+[àa])?|maior\s+que)\s*[:=]?\s*(?P<value>{NUMBER})\s*(?P<unit>{UNIT_PATTERN})(?P<rest>.*)$",
        re.I,
    )
    match = pattern.match(line)
    if not match or not _valid_analyte(match.group("name")):
        return None
    # If the putative analyte already ends in a result, the qualifier belongs
    # to the reference (for example, a numeric TRAB result followed by a
    # "Inferior a" threshold), not to the result itself.
    if re.search(rf"{NUMBER}\s*{UNIT_PATTERN}$", match.group("name"), re.I):
        return None
    observation = _base_observation(match.group("name"))
    value = parse_number(match.group("value"))
    unit = match.group("unit")
    reference, _ = _parse_reference(match.group("rest"), unit)
    _finish_numeric(observation, value, unit, reference)
    observation["value_operator"] = "<" if folded(match.group("qualifier")).startswith(("inferior", "menor")) else ">"
    return observation


def _parse_numeric(line: str) -> dict[str, Any] | None:
    # Identify numeric values only when immediately followed by a clinical
    # unit.  This prevents digits in B-12, T4 and 25-OH-D names becoming values.
    candidates: list[tuple[re.Match[str], re.Match[str]]] = []
    unit_matches = list(re.finditer(rf"\s*({UNIT_PATTERN})(?=\s|$|[,.;)\]])", line, re.I))
    for unit_match in unit_matches:
        prefix = line[: unit_match.start()].rstrip()
        number_match = re.search(rf"({NUMBER})$", prefix)
        if number_match:
            candidates.append((number_match, unit_match))
    if not candidates:
        return None

    first_number, first_unit = candidates[0]
    name = line[: first_number.start()].strip(" .;:-")
    if not _valid_analyte(name):
        return None

    # Two adjacent value/unit pairs are a relative + absolute result, not a
    # reference interval.  Keep the absolute result as the trendable value.
    primary_index = 0
    if len(candidates) >= 2:
        second_number, second_unit = candidates[1]
        between = line[first_unit.end() : second_number.start()].strip()
        if not between:
            primary_index = 1
    number_match, unit_match = candidates[primary_index]
    value = parse_number(number_match.group(1))
    unit = unit_match.group(1)
    rest = line[unit_match.end() :].strip(" ;")
    reference, all_references = _parse_reference(rest, unit)
    observation = _base_observation(name)
    _finish_numeric(observation, value, unit, reference)
    if primary_index == 1:
        additional_value = parse_number(first_number.group(1))
        additional_unit = first_unit.group(1)
        additional_reference = next(
            (item for item in all_references if item is not reference), _reference(kind="undefined")
        )
        observation["additional_values"] = [
            {
                "value": format_number(additional_value),
                "unit": additional_unit,
                "reference": additional_reference,
            }
        ]
    return observation


def _parse_qualitative(line: str) -> dict[str, Any] | None:
    values = "|".join(re.escape(item) for item in QUALITATIVE_VALUES)
    match = re.match(rf"^(?P<name>.+?)\s+(?P<value>{values})(?:\s+(?P<reference>{values}))?\s*$", line, re.I)
    if not match or not _valid_analyte(match.group("name")):
        return None
    observation = _base_observation(match.group("name"))
    observation.update(
        value_type="qualitative",
        value=match.group("value"),
        value_operator=None,
        unit="",
        normalized_value=None,
        normalized_unit="",
        reference={
            "kind": "qualitative" if match.group("reference") else "undefined",
            "lower": None,
            "upper": None,
            "unit": None,
            "text": match.group("reference"),
        },
        additional_values=[],
        range_assessment="unknown",
    )
    return observation


def parse_clinical_row(line: str) -> dict[str, Any] | None:
    line = normalize_text(line)
    flat = folded(line)
    if not line or flat.startswith(SKIP_PREFIXES) or any(part in flat for part in SKIP_CONTAINS):
        return None
    return _parse_qualified_numeric(line) or _parse_numeric(line) or _parse_qualitative(line)


def _is_ultrasound(text: str) -> bool:
    flat = folded(text)
    return "ultrassonografia" in flat or "ecografia" in flat


def _footer_start(line: str) -> bool:
    flat = folded(line).strip()
    return flat.startswith(
        (
            "página",
            "pagina",
            "assinatura",
            "validação",
            "validacao",
            "autenticação",
            "autenticacao",
            "médico",
            "medico",
            "crm",
            "dasa",
            "o valor preditivo",
        )
    )


def parse_ultrasound(text: str) -> dict[str, Any]:
    lines = [line for line in normalize_text(text).splitlines() if line]
    title = next((line for line in lines if "ultrassonografia" in folded(line)), "Ultrassonografia")
    impression_index = next(
        (index for index, line in enumerate(lines) if folded(line).strip() in ("impressao diagnostica", "impressao diagnostica:")),
        None,
    )
    if impression_index is None:
        impression_index = next(
            (index for index, line in enumerate(lines) if "impressao diagnostica" in folded(line)), len(lines)
        )
    start = lines.index(title) + 1 if title in lines else 1
    finding_lines = [line for line in lines[start:impression_index] if not _footer_start(line)]
    impression_lines: list[str] = []
    for line in lines[impression_index + 1 :]:
        if _footer_start(line):
            break
        impression_lines.append(line)
    findings = " ".join(finding_lines).strip()
    impression = " ".join(impression_lines).strip()
    return {
        "modality": "ultrasound",
        "title": title,
        "findings": findings,
        "measurements": _extract_measurements(findings),
        "diagnostic_impression": impression,
    }


def _extract_measurements(findings: str) -> list[dict[str, Any]]:
    measurements: list[dict[str, Any]] = []
    dimension_pattern = re.compile(
        rf"(?P<structure>lobo\s+tireoidiano|tireoide)\s+(?P<side>direito|esquerdo)\s+(?:mede|medindo)\s*:?\s*(?P<a>{NUMBER})\s*x\s*(?P<b>{NUMBER})(?:\s*x\s*(?P<c>{NUMBER}))?\s*(?P<unit>cm|mm)\b",
        re.I,
    )
    for match in dimension_pattern.finditer(findings):
        values = [parse_number(match.group(key)) for key in ("a", "b", "c") if match.group(key)]
        measurements.append(
            {
                "structure": re.sub(r"\s+", " ", match.group("structure")).lower(),
                "laterality": "right" if folded(match.group("side")) == "direito" else "left",
                "values": [format_number(value) for value in values],
                "unit": match.group("unit"),
            }
        )
    volume_pattern = re.compile(rf"volume\s+tireoidiano\s*:?\s*({NUMBER})\s*(cm³|cm3|mL)\b", re.I)
    for match in volume_pattern.finditer(findings):
        measurements.append(
            {
                "structure": "tireoide",
                "laterality": None,
                "values": [format_number(parse_number(match.group(1)))],
                "unit": match.group(2),
            }
        )
    kidney_pattern = re.compile(
        rf"rim\s+(?P<side>direito|esquerdo)(?:\s+mede)?\s*:?\s*(?P<value>{NUMBER})\s*(?P<unit>cm|mm)\b",
        re.I,
    )
    for match in kidney_pattern.finditer(findings):
        measurements.append(
            {
                "structure": "rim",
                "laterality": "right" if folded(match.group("side")) == "direito" else "left",
                "values": [format_number(parse_number(match.group("value")))],
                "unit": match.group("unit"),
            }
        )
    return measurements


def canonical_clinical_text(document: dict[str, Any]) -> str:
    """Return only clinical content, never validation/signature/footer text."""

    rows: list[str] = []
    for observation in document.get("observations", []):
        rows.append(
            json.dumps(observation, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        )
    study = document.get("study")
    if study:
        rows.append(
            json.dumps(study, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        )
    return normalize_text("\n".join(rows))


def clinical_fingerprint(document: dict[str, Any]) -> str:
    return hashlib.sha256(canonical_clinical_text(document).encode("utf-8")).hexdigest()


def _blank_document(page_count: int, character_count: int, extraction_status: str) -> dict[str, Any]:
    return {
        "document_type": "attachment",
        "extraction": {
            "status": extraction_status,
            "page_count": page_count,
            "character_count": character_count,
        },
        "observations": [],
        "study": None,
        "attachments": [],
    }


def import_text(text: str, page_count: int = 1, force_text: bool = False) -> dict[str, Any]:
    normalized = normalize_text(text)
    character_count = len(normalized.strip())
    inadequate = character_count < max(200, 150 * page_count)
    document = _blank_document(page_count, character_count, "text")
    if _is_ultrasound(normalized):
        study = parse_ultrasound(normalized)
        if not inadequate or force_text or study["findings"] or study["diagnostic_impression"]:
            document["document_type"] = "ultrasound"
            document["study"] = study
            return document

    document["document_type"] = "laboratory"
    historical = False
    for line in normalized.splitlines():
        flat = folded(line).strip()
        if flat.startswith(("histórico", "historico")):
            historical = True
            continue
        observation = parse_clinical_row(line)
        if historical:
            if observation is None or flat.startswith("resultado"):
                continue
            historical = False
        if observation is not None:
            document["observations"].append(observation)
    if not inadequate or force_text or document["observations"]:
        return document
    attachment = _blank_document(page_count, character_count, "ocr_required")
    attachment["attachments"] = [{"page": page, "role": "source_image"} for page in range(1, page_count + 1)]
    return attachment


def _extract_pdf_pages(path: Path) -> list[str]:
    from pypdf import PdfReader

    reader = PdfReader(path)
    pages = [page.extract_text() or "" for page in reader.pages]
    # pdfplumber is a text-extraction fallback only, never an OCR fallback.
    if sum(len(text.strip()) for text in pages) < 150 * len(pages):
        try:
            import pdfplumber

            with pdfplumber.open(path) as pdf:
                fallback = [page.extract_text() or "" for page in pdf.pages]
            if sum(len(text.strip()) for text in fallback) > sum(len(text.strip()) for text in pages):
                pages = fallback
        except (ImportError, OSError, ValueError):
            pass
    return pages


def import_pdf(path: str | Path) -> dict[str, Any]:
    pages = _extract_pdf_pages(Path(path))
    return import_text("\n".join(pages), page_count=len(pages))


def import_documents(paths: Iterable[str | Path]) -> list[dict[str, Any]]:
    return [import_pdf(path) for path in paths]
