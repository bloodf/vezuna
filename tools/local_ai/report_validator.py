#!/usr/bin/env python3
"""Native AI acceptance report validator.

Validates that a local AI acceptance report satisfies contract boundaries,
including fixture SHA binding, target settings, device classification, safe
evidence path containment, and all required operations (partial_cancel, successor,
restart, offline). Checks declared physical identity, without authenticating hardware; rejects inconsistent claims, unknown fields, and any
contradictory or failing operation claiming PASS.

Standard library only. Never reads health data, private samples, or keystores.
"""
from dataclasses import dataclass, field
import hashlib
import json
import math
from pathlib import Path
import re
import sys
from typing import Any, Dict, List, Optional, Set

ROOT = Path(__file__).resolve().parents[2]

REQUIRED_TOP_LEVEL_KEYS: Set[str] = {
    "contract",
    "target",
    "operations",
    "evidence_references",
    "overall_status",
}

REQUIRED_OPERATIONS: Set[str] = {
    "load",
    "generation",
    "partial_cancel",
    "successor",
    "restart",
    "offline",
}

VALID_PLATFORMS: Set[str] = {"android", "ios", "linux"}
VALID_EXECUTION_TYPES: Set[str] = {"physical", "emulator", "synthetic", "unexecuted"}
VALID_OPERATION_STATUSES: Set[str] = {"PASS", "FAIL", "NOT_RUN", "UNKNOWN"}
VALID_OVERALL_STATUSES: Set[str] = {"PASS", "FAIL", "NOT_RUN", "REFUSED"}

SHA256_REGEX = re.compile(r"^[0-9a-f]{64}$")

FORBIDDEN_PATH_PARTS: Set[str] = {
    "samples",
    "health_data",
    "private",
    "vitals",
    "records",
    ".ssh",
    ".gnupg",
}

FORBIDDEN_EXTENSIONS: Set[str] = {
    ".keystore",
    ".jks",
}


class ReportValidationError(ValueError):
    """Raised when an acceptance report violates contract or validation rules."""


@dataclass(frozen=True)
class ValidationResult:
    is_valid: bool
    overall_status: str
    errors: List[str] = field(default_factory=list)
    warnings: List[str] = field(default_factory=list)

    def summary(self) -> str:
        if self.is_valid:
            return f"NATIVE_AI_ACCEPTANCE_VALID: {self.overall_status}"
        return f"NATIVE_AI_ACCEPTANCE_INVALID: {'; '.join(self.errors)}"


def validate_safe_path(rel_path_str: str, root_dir: Path) -> Path:
    """Ensure a relative path is safely contained within root_dir and avoids private paths."""
    if not isinstance(rel_path_str, str) or not rel_path_str.strip():
        raise ReportValidationError("Path reference must be a non-empty string")

    path_obj = Path(rel_path_str)
    if path_obj.is_absolute():
        raise ReportValidationError("Path must be relative to repository root")
    if ".." in path_obj.parts:
        raise ReportValidationError("Path traversal detected")

    def check_forbidden(path):
        if set(path.parts) & FORBIDDEN_PATH_PARTS:
            raise ReportValidationError("Path references forbidden private or health data segment")
        if path.suffix.lower() in FORBIDDEN_EXTENSIONS:
            raise ReportValidationError("Path references forbidden file extension")

    check_forbidden(path_obj)
    try:
        resolved = (root_dir / path_obj).resolve()
        relative = resolved.relative_to(root_dir.resolve())
        check_forbidden(relative)
        if resolved.exists() and not resolved.is_file():
            raise ReportValidationError("Reference must be a regular file")
    except (OSError, RuntimeError, ValueError) as exc:
        raise ReportValidationError("Unsafe or inaccessible path reference") from exc
    return relative


def _compute_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(64 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_report_data(
    data: Dict[str, Any],
    root_dir: Optional[Path] = None,
    verify_files: bool = False,
) -> ValidationResult:
    """Validates the parsed dictionary structure of a Native AI Acceptance Report."""
    root = (root_dir or ROOT).resolve()
    errors: List[str] = []
    warnings: List[str] = []

    if not isinstance(data, dict):
        return ValidationResult(False, "REFUSED", ["Report root must be a JSON object"])

    # Top-level keys: reject unknown fields
    top_keys = set(data.keys())
    missing_top_keys = REQUIRED_TOP_LEVEL_KEYS - top_keys
    if missing_top_keys:
        errors.append(f"Missing required top-level keys: {sorted(missing_top_keys)}")
    extra_top_keys = top_keys - REQUIRED_TOP_LEVEL_KEYS
    if extra_top_keys:
        errors.append(f"Unknown top-level keys not in schema: {sorted(extra_top_keys)}")

    if errors:
        return ValidationResult(False, "REFUSED", errors, warnings)

    # 1. Contract section
    contract = data.get("contract")
    if not isinstance(contract, dict):
        errors.append("'contract' must be a JSON object")
    else:
        lease_id = contract.get("lease_id")
        if not lease_id or not isinstance(lease_id, str):
            errors.append("'contract.lease_id' must be a non-empty string")

        schema_version = contract.get("schema_version")
        if type(schema_version) is not str or schema_version != "1.0":
            errors.append("'contract.schema_version' must be supported version 1.0")

        fixture = contract.get("fixture")
        if not isinstance(fixture, dict):
            errors.append("'contract.fixture' must be an object defining fixture binding")
        else:
            fix_path_str = fixture.get("path")
            fix_sha = fixture.get("sha256")
            fix_size = fixture.get("size_bytes")

            if not fix_path_str or not isinstance(fix_path_str, str):
                errors.append("'contract.fixture.path' must be a non-empty string")
            else:
                try:
                    rel_fixture_path = validate_safe_path(fix_path_str, root)
                    if verify_files:
                        full_fix_path = root / rel_fixture_path
                        if not full_fix_path.is_file():
                            errors.append(f"Fixture file does not exist on disk: {fix_path_str}")
                        else:
                            actual_sha = _compute_sha256(full_fix_path)
                            if fix_sha and actual_sha != fix_sha:
                                errors.append(
                                    f"Fixture SHA-256 mismatch: declared={fix_sha}, actual={actual_sha}"
                                )
                            if fix_size and full_fix_path.stat().st_size != fix_size:
                                errors.append(
                                    f"Fixture size mismatch: declared={fix_size}, actual={full_fix_path.stat().st_size}"
                                )
                except (ReportValidationError, OSError, ValueError) as e:
                    errors.append(f"Fixture path validation error: {e}")

            if not fix_sha or not isinstance(fix_sha, str) or not SHA256_REGEX.fullmatch(fix_sha):
                errors.append(
                    f"'contract.fixture.sha256' must be a 64-character lowercase hex string, got {fix_sha!r}"
                )

            if type(fix_size) is not int or fix_size <= 0:
                errors.append(f"'contract.fixture.size_bytes' must be a positive integer, got {fix_size!r}")

    # 2. Target section
    target = data.get("target")
    if not isinstance(target, dict):
        errors.append("'target' must be a JSON object")
    else:
        platform = target.get("platform")
        if not isinstance(platform, str) or platform not in VALID_PLATFORMS:
            errors.append(f"'target.platform' must be one of {sorted(VALID_PLATFORMS)}, got {platform!r}")

        native_build = target.get("native_build")
        if not native_build or not isinstance(native_build, str):
            errors.append("'target.native_build' must be a non-empty string")

        settings = target.get("settings")
        if not isinstance(settings, dict):
            errors.append("'target.settings' must be an object")
        else:
            ctx_len = settings.get("context_length")
            max_tok = settings.get("max_tokens")
            temp = settings.get("temperature")
            if type(ctx_len) is not int or ctx_len <= 0:
                errors.append(f"'target.settings.context_length' must be positive integer, got {ctx_len!r}")
            if type(max_tok) is not int or max_tok <= 0:
                errors.append(f"'target.settings.max_tokens' must be positive integer, got {max_tok!r}")
            if type(temp) not in (int, float) or not math.isfinite(temp) or temp < 0:
                errors.append(f"'target.settings.temperature' must be non-negative float, got {temp!r}")

        device = target.get("device")
        if not isinstance(device, dict):
            errors.append("'target.device' must be an object")
        else:
            exec_type = device.get("execution_type")
            if not isinstance(exec_type, str) or exec_type not in VALID_EXECUTION_TYPES:
                errors.append(
                    f"'target.device.execution_type' must be one of {sorted(VALID_EXECUTION_TYPES)}, got {exec_type!r}"
                )

            verified_phys = device.get("verified_physical")
            if not isinstance(verified_phys, bool):
                errors.append("'target.device.verified_physical' must be a boolean")
            elif verified_phys:
                if exec_type != "physical":
                    errors.append(
                        f"Fabricated physical acceptance: verified_physical is true but execution_type is {exec_type!r}"
                    )
                device_id = device.get("device_id")
                if not device_id or not isinstance(device_id, str):
                    errors.append("Physical acceptance requires explicit non-empty 'target.device.device_id'")

    # 3. Evidence references
    evidence_refs = data.get("evidence_references")
    if not isinstance(evidence_refs, list):
        errors.append("'evidence_references' must be a list of evidence references")
    else:
        for idx, item in enumerate(evidence_refs):
            if not isinstance(item, dict):
                errors.append(f"Evidence reference item #{idx} must be a dict")
                continue
            path_str = item.get("path")
            if not path_str or not isinstance(path_str, str):
                errors.append(f"Evidence reference item #{idx} missing 'path'")
                continue
            exp_sha = item.get("sha256")
            if not isinstance(exp_sha, str) or not SHA256_REGEX.fullmatch(exp_sha):
                errors.append(f"Invalid sha256 in evidence #{idx}")
            try:
                checked = root / validate_safe_path(path_str, root)
                if verify_files:
                    if not checked.is_file():
                        errors.append(f"Evidence #{idx} must be a regular file")
                    elif _compute_sha256(checked) != exp_sha:
                        errors.append(f"Evidence SHA mismatch for reference #{idx}")
            except (ReportValidationError, OSError, ValueError) as e:
                errors.append(f"Evidence reference #{idx} path error: {e}")

    # 4. Operations
    operations = data.get("operations")
    has_failing_op = False
    has_non_pass_op = False
    if not isinstance(operations, dict):
        errors.append("'operations' must be an object mapping operation names to status")
    else:
        op_keys = set(operations.keys())
        missing_ops = REQUIRED_OPERATIONS - op_keys
        if missing_ops:
            errors.append(f"Missing required operations: {sorted(missing_ops)}")

        for op_name, op_body in operations.items():
            if not isinstance(op_body, dict):
                errors.append(f"Operation {op_name!r} must be an object with 'status'")
                continue
            status = op_body.get("status")
            if not isinstance(status, str) or status not in VALID_OPERATION_STATUSES:
                errors.append(
                    f"Operation {op_name!r} has invalid status {status!r}; expected one of {sorted(VALID_OPERATION_STATUSES)}"
                )
                has_non_pass_op = True
            elif status != "PASS":
                has_non_pass_op = True
                if status == "FAIL":
                    has_failing_op = True

    # Only closed, typed identity objects can be compared with evidence bindings.
    def fields(obj, allowed, label):
        if isinstance(obj, dict) and set(obj) - set(allowed):
            errors.append(f"Unknown nested fields in {label}")

    fields(contract, ("lease_id", "schema_version", "fixture"), "contract")
    fields(target, ("platform", "native_build", "settings", "device"), "target")
    if isinstance(contract, dict):
        fields(contract.get("fixture"), ("path", "sha256", "size_bytes"), "fixture")
    if isinstance(target, dict):
        fields(target.get("settings"), ("context_length", "max_tokens", "temperature"), "settings")
        fields(target.get("device"), ("execution_type", "verified_physical", "device_id"), "device")
        device = target.get("device")
        if isinstance(device, dict):
            device_id = device.get("device_id")
            if device_id is not None and (not isinstance(device_id, str) or not device_id.strip()):
                errors.append("device_id must be null or a non-empty string")

    if isinstance(evidence_refs, list):
        for item in evidence_refs:
            fields(item, ("path", "sha256", "description", "operation", "contract", "target"), "evidence")
            if isinstance(item, dict):
                if "operation" in item and (not isinstance(item["operation"], str) or item["operation"] not in REQUIRED_OPERATIONS):
                    errors.append("Invalid evidence operation")
                for key, expected in (("contract", contract), ("target", target)):
                    if key in item and (type(item[key]) is not dict or json.dumps(item[key], sort_keys=True) != json.dumps(expected, sort_keys=True)):
                        errors.append(f"Evidence {key} identity mismatch")
    if isinstance(operations, dict):
        if set(operations) - REQUIRED_OPERATIONS:
            errors.append("Unknown operations")
        for name, body in operations.items():
            fields(body, ("status", "details"), "operation")
            if not isinstance(body, dict) or body.get("status") != "PASS":
                continue
            device = target.get("device", {}) if isinstance(target, dict) else {}
            if not isinstance(device, dict) or device.get("execution_type") == "unexecuted":
                errors.append("Unexecuted operation cannot PASS")
            if isinstance(device, dict) and device.get("execution_type") == "physical" and (not device.get("verified_physical") or not device.get("device_id")):
                errors.append("Physical PASS requires declared verified identity")
            bound = [item for item in evidence_refs if isinstance(item, dict)
                     and item.get("operation") == name
                     and type(item.get("contract")) is dict
                     and type(item.get("target")) is dict
                     and json.dumps(item["contract"], sort_keys=True) == json.dumps(contract, sort_keys=True)
                     and json.dumps(item["target"], sort_keys=True) == json.dumps(target, sort_keys=True)] if isinstance(evidence_refs, list) else []
            if not bound:
                errors.append(f"PASS operation {name} requires bound evidence")

    # 5. Overall status and consistency
    declared_overall = data.get("overall_status")
    if not isinstance(declared_overall, str) or declared_overall not in VALID_OVERALL_STATUSES:
        errors.append(
            f"'overall_status' must be one of {sorted(VALID_OVERALL_STATUSES)}, got {declared_overall!r}"
        )
        return ValidationResult(False, "REFUSED", errors, warnings)

    # Check contradictions
    if declared_overall == "PASS":
        if has_non_pass_op:
            errors.append(
                "Contradictory status: 'overall_status' is PASS but one or more operations are not PASS"
            )
        # Physical acceptance claim verification
        if isinstance(target, dict) and isinstance(target.get("device"), dict):
            device_info = target["device"]
            if device_info.get("verified_physical") and not evidence_refs:
                errors.append("Physical PASS claim requires non-empty evidence references")
    elif declared_overall == "NOT_RUN":
        if not has_non_pass_op:
            warnings.append("Report marked NOT_RUN even though all operations reported PASS")
    elif declared_overall == "FAIL":
        if not has_failing_op and not has_non_pass_op:
            warnings.append("Report marked FAIL without an explicit failing operation")

    effective_status = declared_overall if not errors else "REFUSED"
    is_valid = len(errors) == 0

    return ValidationResult(is_valid, effective_status, errors, warnings)


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON key")
        result[key] = value
    return result


def _reject_constant(value):
    raise ValueError("Nonstandard JSON numeric constant")


def validate_report_file(
    report_path: Path,
    root_dir: Optional[Path] = None,
    verify_files: bool = False,
) -> ValidationResult:
    """Reads and validates an acceptance report JSON file."""
    root = (root_dir or ROOT).resolve()
    try:
        lexical = report_path.absolute().relative_to(root)
        report_path = root / validate_safe_path(str(lexical), root)
    except (ValueError, OSError, RuntimeError):
        return ValidationResult(False, "REFUSED", ["Unsafe report path"])
    if not report_path.is_file():
        return ValidationResult(
            False, "REFUSED", [f"Acceptance report file does not exist: {report_path}"]
        )

    try:
        content = json.loads(report_path.read_text(encoding="utf-8"),
                             object_pairs_hook=_unique_object, parse_constant=_reject_constant)
    except (ValueError, UnicodeError, RecursionError) as err:
        return ValidationResult(
            False, "REFUSED", [f"Malformed JSON in report file {report_path}: {err}"]
        )
    except OSError as err:
        return ValidationResult(
            False, "REFUSED", [f"Failed to read report file {report_path}: {err}"]
        )

    return validate_report_data(content, root_dir=root_dir, verify_files=verify_files)


def main() -> None:
    import argparse

    parser = argparse.ArgumentParser(
        description="Validate Native AI Acceptance Report against contract schema."
    )
    parser.add_argument("report", type=Path, help="Path to report JSON file")
    parser.add_argument(
        "--root",
        type=Path,
        default=ROOT,
        help="Repository root directory for relative path containment",
    )
    parser.add_argument(
        "--verify-files",
        action="store_true",
        help="Verify on-disk existence and SHA-256 of referenced fixtures and evidence",
    )
    args = parser.parse_args()

    result = validate_report_file(
        args.report, root_dir=args.root, verify_files=args.verify_files
    )
    print(result.summary())
    if not result.is_valid:
        for err in result.errors:
            print(f"  ERROR: {err}", file=sys.stderr)
        sys.exit(1)
    sys.exit(0)


if __name__ == "__main__":
    main()
