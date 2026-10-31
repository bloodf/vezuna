#!/usr/bin/env python3
"""Golden and privacy regression tests using synthetic fixtures only."""

from __future__ import annotations

import difflib
import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from tools.lab_importer.importer import (  # noqa: E402
    assess_range,
    canonical_clinical_text,
    canonical_test_code,
    clinical_fingerprint,
    import_text,
    normalize_text,
    observation_fingerprint,
)
from tools.lab_importer.privacy import scan_fixtures, scan_text  # noqa: E402

FIXTURES = ROOT / "fixtures" / "lab_importer"


class GoldenImporterTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.sources = {
            path.stem: path.read_text(encoding="utf-8") for path in sorted(FIXTURES.glob("*.txt"))
        }
        cls.documents = {name: import_text(text) for name, text in cls.sources.items()}

    def observation(self, fixture: str, analyte: str) -> dict:
        return next(
            observation
            for observation in self.documents[fixture]["observations"]
            if observation["analyte"] == analyte
        )

    def test_01_committed_golden_documents(self) -> None:
        expected_paths = sorted(FIXTURES.glob("*.expected.json"))
        self.assertEqual(len(expected_paths), len(self.sources))
        for expected_path in expected_paths:
            fixture = expected_path.name[: -len(".expected.json")]
            with self.subTest(fixture=fixture):
                expected = json.loads(expected_path.read_text(encoding="utf-8"))
                self.assertEqual(self.documents[fixture], expected)

    def test_02_unicode_nfc_ligature_and_nbsp_normalization(self) -> None:
        raw = "Sele\u0302nio\u00a0sérico\r\nquanti\ufb01cação\u202fclínica"
        self.assertEqual(normalize_text(raw), "Selênio sérico\nquantificação clínica")
        selenium = self.observation("01_selenio", "Selênio sérico")
        self.assertEqual(selenium["value"], 82)
        self.assertEqual(selenium["normalized_value"], 0.082)
        self.assertEqual(selenium["normalized_unit"], "mg/L")

    def test_03_digit_bearing_analytes_and_historical_rows(self) -> None:
        document = self.documents["04_comprehensive"]
        names = {observation["analyte"] for observation in document["observations"]}
        self.assertIn("Vitamina B-12, Dosagem", names)
        self.assertIn("25-Hidroxivitamina D (Vitamina D)", names)
        self.assertIn("1,25-Dihidroxi Vitamina D", names)
        self.assertIn("T4 Livre (Tiroxina Livre)", names)
        self.assertEqual(len(document["observations"]), 12)
        creatinine = self.observation("04_comprehensive", "Creatinina")
        potassium = self.observation("04_comprehensive", "Potássio")
        self.assertEqual(creatinine["value"], 0.82)
        self.assertEqual(potassium["value"], 4.4)

    def test_04_cbc_dual_value_uses_absolute_result(self) -> None:
        leukocytes = self.observation("04_comprehensive", "Leucócitos")
        self.assertEqual(leukocytes["value"], 5600)
        self.assertEqual(leukocytes["unit"], "/µL")
        self.assertEqual(leukocytes["reference"]["lower"], 4000)
        self.assertEqual(leukocytes["reference"]["upper"], 10000)
        self.assertEqual(leukocytes["additional_values"][0]["value"], 43)
        self.assertEqual(leukocytes["additional_values"][0]["unit"], "%")
        self.assertEqual(leukocytes["additional_values"][0]["reference"]["lower"], 40)

    def test_05_qualitative_and_near_duplicate_canonical_identity(self) -> None:
        first = self.documents["02_qualitative_a"]
        second = self.documents["03_qualitative_b"]
        self.assertEqual([row["value"] for row in first["observations"]], ["Ausente", "Ausente"])
        self.assertTrue(all(row["value_type"] == "qualitative" for row in first["observations"]))
        self.assertEqual(clinical_fingerprint(first), clinical_fingerprint(second))
        similarity = difflib.SequenceMatcher(
            None, canonical_clinical_text(first), canonical_clinical_text(second)
        ).ratio()
        self.assertGreaterEqual(similarity, 0.99)

    def test_06_validation_signatures_and_footers_do_not_change_fingerprint(self) -> None:
        baseline = self.documents["02_qualitative_a"]
        augmented = import_text(
            self.sources["02_qualitative_a"]
            + "Validação: TESTE_FICTICIO_123456\n"
            + "Assinatura: TESTE_FICTICIO_654321\n"
            + "Página 7 de 7\n"
        )
        self.assertEqual(clinical_fingerprint(baseline), clinical_fingerprint(augmented))
        self.assertNotIn("TESTE_FICTICIO", canonical_clinical_text(augmented))

    def test_07_observation_fingerprint_uses_normalized_clinical_value(self) -> None:
        first = {
            "analyte": "Glicose",
            "value_type": "numeric",
            "normalized_value": 880,
            "normalized_unit": "mg/L",
        }
        equivalent = dict(first, normalized_value=88, normalized_unit="mg/dL")
        different = dict(first, normalized_value=881)
        self.assertEqual(observation_fingerprint(first), observation_fingerprint(equivalent))
        self.assertNotEqual(observation_fingerprint(first), observation_fingerprint(different))
        self.assertNotEqual(
            observation_fingerprint(first, profile_id="A"),
            observation_fingerprint(first, profile_id="B"),
        )
        self.assertEqual(canonical_test_code("Selênio sérico"), "SELNIOSRICO")
        negative_tie = dict(first, normalized_value=-1.5e-9, normalized_unit="%")
        positive_tie = dict(first, normalized_value=1.5e-9, normalized_unit="%")
        self.assertEqual(observation_fingerprint(negative_tie).split("|")[3], "-1")
        self.assertEqual(observation_fingerprint(positive_tie).split("|")[3], "2")

    def test_08_reference_types_and_incompatible_units(self) -> None:
        document = self.documents["08_thresholds_units"]
        kinds = {observation["reference"]["kind"] for observation in document["observations"]}
        self.assertEqual(kinds, {"range", "threshold", "deferred", "undefined"})
        antibody = self.observation("08_thresholds_units", "Antiperoxidase Tireoideana - Anticorpos")
        self.assertEqual(antibody["value_operator"], "<")
        self.assertEqual(antibody["reference"]["operator"], "<")
        strict_boundary = dict(antibody, value=5, normalized_value=5)
        self.assertEqual(assess_range(strict_boundary), "above")
        inclusive_boundary = dict(strict_boundary, reference=dict(antibody["reference"], operator="<="))
        self.assertEqual(assess_range(inclusive_boundary), "within")
        creatinine = self.observation("08_thresholds_units", "Creatinina")
        self.assertEqual(creatinine["range_assessment"], "incompatible_units")
        self.assertEqual(creatinine["normalized_value"], 1)
        deferred = self.observation("08_thresholds_units", "Triglicérides")
        self.assertIsNone(deferred["reference"].get("lower"))
        self.assertEqual(deferred["range_assessment"], "unknown")

    def test_09_ultrasound_findings_measurements_and_impression_boundaries(self) -> None:
        thyroid = self.documents["05_ultrasound_thyroid"]["study"]
        self.assertEqual(len(thyroid["measurements"]), 3)
        self.assertEqual(
            {measurement["laterality"] for measurement in thyroid["measurements"][:2]},
            {"right", "left"},
        )
        self.assertTrue(all(len(measurement["values"]) == 3 for measurement in thyroid["measurements"][:2]))
        abdomen = self.documents["06_ultrasound_abdomen"]["study"]
        self.assertEqual(len(abdomen["measurements"]), 2)
        self.assertIn("Fígado", abdomen["diagnostic_impression"])
        self.assertIn("Rins", abdomen["diagnostic_impression"])
        augmented = import_text(
            self.sources["05_ultrasound_thyroid"]
            + "Validação: TOKEN_FICTICIO_123456\nAssinatura: TOKEN_FICTICIO_654321\n"
        )
        impression = augmented["study"]["diagnostic_impression"]
        self.assertNotIn("TOKEN_FICTICIO", impression)
        self.assertNotIn("Página", impression)

    def test_10_image_series_is_attachment_without_fabricated_results(self) -> None:
        document = self.documents["07_image_series"]
        self.assertEqual(document["document_type"], "attachment")
        self.assertEqual(document["extraction"]["status"], "ocr_required")
        self.assertEqual(document["observations"], [])
        self.assertIsNone(document["study"])
        self.assertEqual(canonical_clinical_text(document), "")


class FixturePrivacyTests(unittest.TestCase):
    def test_01_committed_fixtures_have_no_direct_identifiers(self) -> None:
        self.assertEqual(scan_fixtures(FIXTURES), {})

    def test_02_scanner_positive_controls(self) -> None:
        positive_control = "\n".join(
            (
                "Paciente: Pessoa Fictícia",
                "Data de nascimento: 01/01/1980",
                "CPF: 123.456.789-09",
                "Endereço: local fictício",
                "Validação: TOKEN_FICTICIO_123456",
                "Hash: " + "a" * 64,
            )
        )
        detected = set(scan_text(positive_control))
        self.assertTrue(
            {"person-label", "birth-date", "government-id", "address", "validation-token", "source-hash"}
            <= detected
        )

    def test_03_scanner_returns_categories_not_identifier_values(self) -> None:
        secret_like_value = "TOKEN_FICTICIO_123456"
        findings = scan_text("Validação: " + secret_like_value)
        self.assertIn("validation-token", findings)
        self.assertNotIn(secret_like_value, findings)
        self.assertTrue(all(isinstance(item, str) for item in findings))

    def test_04_fixture_tree_has_only_synthetic_text_and_json(self) -> None:
        files = [path for path in FIXTURES.rglob("*") if path.is_file()]
        self.assertTrue(files)
        self.assertTrue(all(path.suffix in (".txt", ".json") for path in files))
        self.assertFalse(any(path.suffix.lower() == ".pdf" for path in files))

    def test_05_every_source_has_exactly_one_expected_document(self) -> None:
        sources = {path.stem for path in FIXTURES.glob("*.txt")}
        expected = {path.name[: -len(".expected.json")] for path in FIXTURES.glob("*.expected.json")}
        self.assertEqual(sources, expected)


def main() -> int:
    suite = unittest.defaultTestLoader.loadTestsFromModule(sys.modules[__name__])
    result = unittest.TextTestRunner(stream=sys.stderr, verbosity=1).run(suite)
    if result.wasSuccessful():
        print("LAB_IMPORTER_TESTS_PASSED")
        return 0
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
