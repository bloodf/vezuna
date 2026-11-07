"""Unit tests for Native AI acceptance report validator."""
import json
from pathlib import Path
import tempfile
import unittest

from tools.local_ai.report_validator import (
    FORBIDDEN_EXTENSIONS,
    FORBIDDEN_PATH_PARTS,
    REQUIRED_OPERATIONS,
    ReportValidationError,
    ValidationResult,
    validate_report_data,
    validate_report_file,
    validate_safe_path,
)


def _make_valid_report_data() -> dict:
    return {
        "contract": {
            "lease_id": "10f23362-4159-4698-8447-aa6bec705dc6",
            "schema_version": "1.0",
            "fixture": {
                "path": "fixtures/smoke.gguf",
                "sha256": "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
                "size_bytes": 491400032,
            },
        },
        "target": {
            "platform": "android",
            "native_build": "2026.11.07-local",
            "settings": {
                "context_length": 2048,
                "max_tokens": 512,
                "temperature": 0.0,
            },
            "device": {
                "execution_type": "synthetic",
                "verified_physical": False,
                "device_id": None,
            },
        },
        "operations": {
            "partial_cancel": {"status": "NOT_RUN", "details": "Pending admission"},
            "successor": {"status": "NOT_RUN", "details": "Pending admission"},
            "restart": {"status": "NOT_RUN", "details": "Pending admission"},
            "offline": {"status": "NOT_RUN", "details": "Pending admission"},
        },
        "evidence_references": [
            {
                "path": "tools/local_ai/report_validator.py",
                "sha256": "0" * 64,
                "description": "Validator script reference",
            }
        ],
        "overall_status": "NOT_RUN",
    }


class TestSafePathValidation(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name).resolve()

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_valid_contained_relative_path(self):
        rel = "docs/NATIVE_AI_ACCEPTANCE.md"
        result = validate_safe_path(rel, self.root)
        self.assertEqual(str(result), rel)

    def test_absolute_path_rejected(self):
        with self.assertRaises(ReportValidationError) as ctx:
            validate_safe_path("/etc/passwd", self.root)
        self.assertIn("relative to repository root", str(ctx.exception))

    def test_path_traversal_rejected(self):
        with self.assertRaises(ReportValidationError) as ctx:
            validate_safe_path("docs/../../secret.txt", self.root)
        self.assertIn("traversal", str(ctx.exception).lower())

    def test_forbidden_private_or_health_path_rejected(self):
        for forbidden in FORBIDDEN_PATH_PARTS:
            with self.assertRaises(ReportValidationError) as ctx:
                validate_safe_path(f"data/{forbidden}/file.txt", self.root)
            self.assertIn("forbidden", str(ctx.exception).lower())

    def test_forbidden_extension_rejected(self):
        for ext in FORBIDDEN_EXTENSIONS:
            with self.assertRaises(ReportValidationError) as ctx:
                validate_safe_path(f"assets/model{ext}", self.root)
            self.assertIn("forbidden file extension", str(ctx.exception).lower())


class TestReportValidator(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name).resolve()

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_valid_not_run_report(self):
        data = _make_valid_report_data()
        res = validate_report_data(data, root_dir=self.root, verify_files=False)
        self.assertTrue(res.is_valid, msg=f"Errors: {res.errors}")
        self.assertEqual(res.overall_status, "NOT_RUN")
        self.assertIn("NATIVE_AI_ACCEPTANCE_VALID", res.summary())

    def test_missing_contract_fixture_binding(self):
        data = _make_valid_report_data()
        del data["contract"]["fixture"]["sha256"]
        res = validate_report_data(data, root_dir=self.root)
        self.assertFalse(res.is_valid)
        self.assertEqual(res.overall_status, "REFUSED")
        self.assertTrue(any("sha256" in err for err in res.errors))

    def test_unknown_top_level_key_rejected(self):
        data = _make_valid_report_data()
        data["unexpected_custom_metric"] = 12345
        res = validate_report_data(data, root_dir=self.root)
        self.assertFalse(res.is_valid)
        self.assertTrue(any("Unknown top-level keys" in err for err in res.errors))

    def test_missing_required_operation_rejected(self):
        for op in REQUIRED_OPERATIONS:
            data = _make_valid_report_data()
            del data["operations"][op]
            res = validate_report_data(data, root_dir=self.root)
            self.assertFalse(res.is_valid)
            self.assertTrue(any(f"Missing required operations" in err for err in res.errors))

    def test_unknown_operation_status_rejected(self):
        data = _make_valid_report_data()
        data["operations"]["successor"]["status"] = "MAYBE"
        res = validate_report_data(data, root_dir=self.root)
        self.assertFalse(res.is_valid)
        self.assertTrue(any("invalid status 'MAYBE'" in err for err in res.errors))

    def test_contradictory_overall_pass_when_op_not_pass(self):
        data = _make_valid_report_data()
        data["overall_status"] = "PASS"
        # operations are still NOT_RUN
        res = validate_report_data(data, root_dir=self.root)
        self.assertFalse(res.is_valid)
        self.assertTrue(any("Contradictory status" in err for err in res.errors))

    def test_contradictory_overall_pass_when_op_fails(self):
        data = _make_valid_report_data()
        data["overall_status"] = "PASS"
        for op in data["operations"]:
            data["operations"][op]["status"] = "PASS"
        data["operations"]["partial_cancel"]["status"] = "FAIL"
        res = validate_report_data(data, root_dir=self.root)
        self.assertFalse(res.is_valid)
        self.assertTrue(any("Contradictory status" in err for err in res.errors))

    def test_fabricated_physical_acceptance_rejected(self):
        data = _make_valid_report_data()
        data["target"]["device"]["verified_physical"] = True
        data["target"]["device"]["execution_type"] = "synthetic"
        res = validate_report_data(data, root_dir=self.root)
        self.assertFalse(res.is_valid)
        self.assertTrue(any("Fabricated physical acceptance" in err for err in res.errors))

    def test_physical_acceptance_requires_device_id(self):
        data = _make_valid_report_data()
        data["target"]["device"]["verified_physical"] = True
        data["target"]["device"]["execution_type"] = "physical"
        data["target"]["device"]["device_id"] = None
        res = validate_report_data(data, root_dir=self.root)
        self.assertFalse(res.is_valid)
        self.assertTrue(any("device_id" in err for err in res.errors))

    def test_file_validation_with_hash_binding(self):
        fix_path = self.root / "fixtures" / "smoke.txt"
        fix_path.parent.mkdir(parents=True, exist_ok=True)
        fix_path.write_text("synthetic fixture content", encoding="utf-8")

        import hashlib
        real_sha = hashlib.sha256(b"synthetic fixture content").hexdigest()
        real_size = len(b"synthetic fixture content")

        data = _make_valid_report_data()
        data["contract"]["fixture"]["path"] = "fixtures/smoke.txt"
        data["contract"]["fixture"]["sha256"] = real_sha
        data["contract"]["fixture"]["size_bytes"] = real_size

        report_file = self.root / "report.json"
        report_file.write_text(json.dumps(data), encoding="utf-8")

        # Verify against disk files
        res = validate_report_file(report_file, root_dir=self.root, verify_files=True)
        # evidence_references has dummy "0"*64, so it should report evidence sha mismatch
        self.assertFalse(res.is_valid)
        self.assertTrue(any("Evidence" in err or "evidence" in err for err in res.errors))

        # Clear evidence_references to test fixture alone
        data["evidence_references"] = []
        report_file.write_text(json.dumps(data), encoding="utf-8")
        res2 = validate_report_file(report_file, root_dir=self.root, verify_files=True)
        self.assertTrue(res2.is_valid, msg=f"Errors: {res2.errors}")


if __name__ == "__main__":
    unittest.main()
