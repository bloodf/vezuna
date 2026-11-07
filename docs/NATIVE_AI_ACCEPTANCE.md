# Native AI Acceptance Report Validation

This document describes the schema, execution boundary, and verification rules for native on-device AI acceptance reports under the Local AI backlog (`#77`, `#84`).

The companion validator is `tools/local_ai/report_validator.py`.

## Purpose and Boundaries

Acceptance reports record declared verification of on-device LLM operations without inflating claims or bypassing required admission boundaries.

Key constraints:
1. **Never fabricate physical acceptance:** Physical execution requires verified hardware leases, explicit device IDs, and actual evidence references. Synthetic test runs, emulators, or unexecuted configurations must not claim physical pass.
2. **Never access private paths or health data:** Path references are checked for repository containment and cannot point to `samples/`, `health_data/`, `records/`, `vitals/`, keystores, or private credentials.
3. **Strict fixture and hash binding:** Contract fixtures must bind the explicit file path, exact lowercase SHA-256 hex digest, and byte size.
4. **Mandatory core operations:** Every acceptance report must evaluate or explicitly record status for:
   - `load`: Model loading completes.
   - `generation`: Initial generation completes.
   - `partial_cancel`: Immediate cancellation during streaming without corrupting engine state.
   - `successor`: Successful generation after cancellation or unload.
   - `restart`: Engine or service reinitialization and reload.
   - `offline`: True on-device operation with network disconnected.
5. **Rejection of contradictions:** An `overall_status` of `PASS` is rejected if any required operation is `FAIL`, `NOT_RUN`, or `UNKNOWN`.

## Minimal JSON Report Schema

```json
{
  "contract": {
    "lease_id": "<uuid-or-lease-token>",
    "schema_version": "1.0",
    "fixture": {
      "path": "fixtures/smoke.gguf",
      "sha256": "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
      "size_bytes": 491400032
    }
  },
  "target": {
    "platform": "android",
    "native_build": "2026.11.07-local",
    "settings": {
      "context_length": 2048,
      "max_tokens": 512,
      "temperature": 0.0
    },
    "device": {
      "execution_type": "synthetic",
      "verified_physical": false,
      "device_id": null
    }
  },
  "operations": {
    "load": {"status": "NOT_RUN"},
    "generation": {"status": "NOT_RUN"},
    "partial_cancel": {
      "status": "NOT_RUN",
      "details": "Pending admission"
    },
    "successor": {
      "status": "NOT_RUN",
      "details": "Pending admission"
    },
    "restart": {
      "status": "NOT_RUN",
      "details": "Pending admission"
    },
    "offline": {
      "status": "NOT_RUN",
      "details": "Pending admission"
    }
  },
  "evidence_references": [
    {
      "path": "tools/local_ai/report_validator.py",
      "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
      "description": "Validation tool"
    }
  ],
  "overall_status": "NOT_RUN"
}
```

## Running the Validator

```sh
python3 tools/local_ai/report_validator.py <path-to-report.json>
```

To also verify on-disk file existence and SHA-256 bindings:

```sh
python3 tools/local_ai/report_validator.py <path-to-report.json> --verify-files
```

To run the unit test suite:

```sh
python3 -m unittest tools/local_ai/test_report_validator.py
```

## PASS evidence and interpretation

Each PASS operation requires an evidence reference with `operation` equal to its
name, a mandatory SHA-256 digest, and complete `contract` and `target` copies
matching the report. This binds the lease, platform, device classification and
identity, native build, fixture path/digest/size, and settings to that operation.
Unexecuted operations cannot PASS. Physical PASS requires a non-empty device ID
and declared `verified_physical: true`. These declarations require independent
hardware lease and execution review. Software cannot authenticate hardware or
establish physical acceptance from self-declared JSON and hashes.

The default command checks structure and declared identity. `--verify-files` also
checks regular files and their digests. Both lexical and resolved root-relative
paths must avoid forbidden paths before any referenced file is read or hashed.
Exit zero means schema validity, including valid FAIL and NOT_RUN reports. It
does not mean acceptance success or independent physical proof. Read the status.

Only schema version `1.0` and documented nested fields are supported. Duplicate
JSON keys, nonstandard constants, booleans as numbers, and nonfinite temperatures
are refused. All six operations remain required regardless of execution status.
