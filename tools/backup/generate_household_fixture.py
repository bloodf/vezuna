#!/usr/bin/env python3
"""Independent household vector. Python 3.9.6 / cryptography 46.0.4 recipe.
Sorted compact JSON, UTF-8, ensure_ascii=False, allow_nan=False, no newline/BOM.
Fixed salt/nonces are synthetic fixture inputs only. Never imports app code.
--check is read-only; default explicitly regenerates the Kotlin constants.
"""
import argparse
import base64
import copy
import hashlib
import json
from pathlib import Path
import unicodedata

try:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
except ImportError as error:
    raise SystemExit("Requires installed Python package cryptography (AESGCM); nothing installed") from error

SPEC = json.loads(r'''
{
  "exportedAt": "2026-01-03T00:00:00Z",
  "members": [
    {
      "data": {
        "documents": [
          {
            "displayName": "Synthetic scan.bin",
            "documentId": "a-doc",
            "importedAt": "2026-01-01T00:00:00Z",
            "mediaType": "application/octet-stream",
            "pageVaultKeys": [
              "old-a-page-2"
            ],
            "profileId": "house-a",
            "sha256": "0150a92bb1212cd00516b65fde0704614760000963874fcbb11eaa734ee87809",
            "vaultKey": "old-a-page-1"
          }
        ],
        "profile": {
          "createdAt": "2026-01-01T00:00:00Z",
          "displayName": "Synthetic Ana",
          "profileId": "house-a"
        },
        "reports": [
          {
            "importedAt": "2026-01-01T00:00:00Z",
            "laboratoryName": "Synthetic Lab",
            "profileId": "house-a",
            "reportId": "a-report",
            "sourceDocumentId": "a-doc"
          }
        ],
        "vitals": {
          "heartRateDaily": [
            {
              "avg": 66.0,
              "count": 4,
              "date": "2025-12-01",
              "max": 86.0,
              "min": 56.0
            }
          ],
          "heartRateHourly": [
            {
              "avg": 71.0,
              "max": 81.0,
              "min": 61.0,
              "start": "2026-01-01T00:00:00Z"
            }
          ],
          "lastSync": "2026-01-02T00:00:00Z",
          "steps": [
            {
              "count": 1234,
              "date": "2026-01-01"
            }
          ],
          "weights": [
            {
              "kg": 61.25,
              "recordId": "platform-weight-1",
              "time": "2026-01-01T00:00:00Z"
            }
          ]
        }
      },
      "files": [
        {
          "content": "AAF/gP8=",
          "documentId": "a-doc"
        },
        {
          "content": "cGFnZSAyAA0K",
          "documentId": "a-doc-page-2"
        }
      ]
    },
    {
      "data": {
        "profile": {
          "createdAt": "2026-01-01T00:00:00Z",
          "displayName": "Synthetic Bia",
          "profileId": "house-b"
        },
        "vaultDocuments": [
          {
            "source": {
              "displayName": "Synthetic note.txt",
              "documentId": "b-omitted",
              "importedAt": "2026-01-01T00:00:00Z",
              "mediaType": "text/plain",
              "pageVaultKeys": [
                "old-b-page-2"
              ],
              "profileId": "house-b",
              "sha256": "26fd516b2a65fd8ece29347b515badc6fbe409e8d986aa92fab9c7989a2b95d3",
              "vaultKey": "old-b-page-1"
            },
            "tags": [
              "Family",
              "Exame"
            ]
          }
        ],
        "vitals": {
          "heartRateDaily": [
            {
              "avg": 67.0,
              "count": 5,
              "date": "2025-12-01",
              "max": 87.0,
              "min": 57.0
            }
          ],
          "heartRateHourly": [
            {
              "avg": 72.0,
              "max": 82.0,
              "min": 62.0,
              "start": "2026-01-01T00:00:00Z"
            }
          ],
          "lastSync": "2026-01-02T00:00:00Z",
          "steps": [
            {
              "count": 4321,
              "date": "2026-01-01"
            }
          ],
          "weights": [
            {
              "kg": 82.5,
              "recordId": "platform-weight-2",
              "time": "2026-01-01T00:00:00Z"
            }
          ]
        }
      },
      "omitted": [
        {
          "displayName": "Synthetic note.txt",
          "documentId": "b-omitted"
        }
      ]
    }
  ],
  "schemaVersion": 7
}
''')
FROZEN = {
    7: (1955, "59142507547fcf7b6759c26eb6bd73ec3580df7b8e76440a90aced41c4536c30", 2011, "d24bad225218b62147979e989f6a4f89d38038b1e84fed0e77e06db11dad3250"),
    4: (1545, "0c0f64e35551314b044ad23b46362811bd73ad2a5906e76328be775c8f2c45a7", 1601, "b91f3df3a629bc09ee53cbfbeaaba611486d8cc621a5a534c935fcde77ea9abf"),
}


def verify(plain, sealed, expected):
    actual = (len(plain), hashlib.sha256(plain).hexdigest(), len(sealed), hashlib.sha256(sealed).hexdigest())
    if actual != expected:
        raise ValueError(f"Frozen vector mismatch: {actual}")


def render():
    nfc = "p\u00e4ssw\u00f6rd correct horse"
    nfd = "pa\u0308sswo\u0308rd correct horse"
    assert nfc != nfd
    salt = bytes(range(16))
    key = hashlib.pbkdf2_hmac("sha256", unicodedata.normalize("NFC", nfc).encode("utf-8"), salt, 600000, 32)
    assert key.hex() == "670607ccb1c1ff5d15779935001a9f594d11ae42ac4edf25c969cebe835dd428"
    assert unicodedata.normalize("NFC", nfd) == nfc
    header = b"VZBK" + bytes([1, 1]) + (600000).to_bytes(4, "big") + bytes([16]) + salt + bytes([12])
    assert header.hex() == "565a424b0101000927c010000102030405060708090a0b0c0d0e0f0c"
    lines = ["package app.vezuna", "", "// Generated independently by tools/backup/generate_household_fixture.py. Do not edit.",
             "// Canonical JSON and Python hashlib PBKDF2 + cryptography AESGCM; fixed synthetic salt/nonces.",
             "internal object GoldenHouseholdBackup {",
             '    const val PASSPHRASE_NFC = "p\\u00e4ssw\\u00f6rd correct horse"',
             '    const val PASSPHRASE_NFD = "pa\\u0308sswo\\u0308rd correct horse"']
    for schema, start in [(7, 0xb0), (4, 0xc0)]:
        spec = copy.deepcopy(SPEC)
        if schema == 4:
            spec["schemaVersion"] = 4
            del spec["members"][1]["data"]["vaultDocuments"]
            del spec["members"][1]["omitted"]
        plain = json.dumps(spec, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False).encode("utf-8")
        nonce = bytes(range(start, start + 12))
        sealed = header + nonce + AESGCM(key).encrypt(nonce, plain, header)
        verify(plain, sealed, FROZEN[schema])
        assert AESGCM(key).decrypt(nonce, sealed[40:], header) == plain
        # Prove the frozen-hash guard rejects an altered expectation without touching files.
        altered = list(FROZEN[schema])
        altered[1] = "0" * 64
        try:
            verify(plain, sealed, tuple(altered))
        except ValueError:
            pass
        else:
            raise AssertionError("negative hash control did not reject")
        for name, value in [("PLAIN", plain.decode("utf-8")), ("BASE64", base64.b64encode(sealed).decode("ascii")), ("PLAIN_SHA256", FROZEN[schema][1]), ("SEALED_SHA256", FROZEN[schema][3])]:
            lines.append(f"    const val V{schema}_{name} = " + json.dumps(value, ensure_ascii=True))
    return "\n".join(lines + ["}", ""])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    target = Path(__file__).resolve().parents[2] / "shared/src/commonTest/kotlin/app/vezuna/GoldenHouseholdBackup.kt"
    rendered = render()
    if args.check:
        if not target.exists() or target.read_bytes() != rendered.encode("utf-8"):
            raise SystemExit("Household fixture differs; --check wrote nothing")
    else:
        target.write_text(rendered, encoding="utf-8")
    print("HOUSEHOLD_FIXTURES_PASSED: schema7 schema4 frozen-hashes negative-hash-control")


if __name__ == "__main__":
    main()
