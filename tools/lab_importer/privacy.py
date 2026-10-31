"""Fail-closed privacy checks for committed synthetic fixtures.

Only pattern categories are returned; matched text is intentionally omitted so
a failing test cannot echo an identifier into a CI log.
"""

from __future__ import annotations

import re
from pathlib import Path


PATTERNS: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("person-label", re.compile(r"(?i)\b(?:paciente|nome\s+completo|patient\s+name|full\s+name)\s*[:=]")),
    ("birth-date", re.compile(r"(?i)\b(?:nascimento|data\s+de\s+nascimento|birth\s*date|dob)\s*[:=]?\s*\d")),
    ("government-id", re.compile(r"(?i)\b(?:cpf|rg|cns|identidade|passaporte|prontu[áa]rio)\s*[:=#]?\s*[A-Z0-9.\-/]{3,}")),
    ("cpf-number", re.compile(r"(?<!\d)\d{3}\.?\d{3}\.?\d{3}-?\d{2}(?!\d)")),
    ("address", re.compile(r"(?i)\b(?:endere[çc]o|rua|avenida|av\.|quadra|cep|address)\s*[:=]")),
    ("email", re.compile(r"(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b")),
    ("phone", re.compile(r"(?i)\b(?:telefone|celular|phone)\s*[:=]?\s*(?:\+?\d[\d\s().-]{7,}\d)")),
    ("validation-token", re.compile(r"(?i)\b(?:token|valida[çc][aã]o|autentica[çc][aã]o|assinatura|verification)\s*[:=#]?\s*[A-Z0-9][A-Z0-9_\-/=+.]{5,}")),
    ("source-hash", re.compile(r"(?i)(?<![A-F0-9])[A-F0-9]{32}(?:[A-F0-9]{8})?(?:[A-F0-9]{24})?(?![A-F0-9])")),
    ("record-id", re.compile(r"(?i)\b(?:requisi[çc][aã]o|protocolo|pedido|record\s*id|document\s*id)\s*[:=#]\s*[A-Z0-9./-]{3,}")),
)


def scan_text(text: str) -> list[str]:
    """Return sorted violation categories without returning matched content."""

    return sorted({category for category, pattern in PATTERNS if pattern.search(text)})


def scan_fixtures(root: str | Path) -> dict[str, list[str]]:
    """Scan fixture contents; keys are safe relative paths, values categories."""

    root_path = Path(root)
    violations: dict[str, list[str]] = {}
    for path in sorted(root_path.rglob("*")):
        if not path.is_file() or path.suffix.lower() not in (".txt", ".json", ".md", ".csv"):
            continue
        categories = scan_text(path.read_text(encoding="utf-8"))
        if categories:
            violations[path.relative_to(root_path).as_posix()] = categories
    return violations
