#!/usr/bin/env python3
"""Stage a verified official model in generated Android test assets only."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import tempfile

ROOT = Path(__file__).resolve().parents[2]
PROVENANCE = ROOT / "third_party/qwen2.5-0.5b-instruct-q4_k_m"
OUTPUT = ROOT / "composeApp/build/generated/modelSmokeAssets"


def stage(source):
    metadata = json.loads((PROVENANCE / "provenance.json").read_text())
    license_file = PROVENANCE / "LICENSE"
    if not license_file.is_file():
        raise ValueError("model license text is missing")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    # Validate the bytes copied, so a changing source cannot bypass the hash check.
    with tempfile.TemporaryDirectory(dir=OUTPUT.parent) as directory:
        temporary = Path(directory) / "smoke.gguf"
        digest = hashlib.sha256()
        size = 0
        with source.open("rb") as input_file, temporary.open("wb") as output_file:
            for chunk in iter(lambda: input_file.read(1024 * 1024), b""):
                digest.update(chunk)
                size += len(chunk)
                output_file.write(chunk)
        if size != metadata["sizeBytes"] or digest.hexdigest() != metadata["sha256"]:
            raise ValueError("model size or SHA-256 does not match the pinned official Qwen artifact")
        shutil.copyfile(license_file, OUTPUT / "smoke-model-LICENSE.txt")
        shutil.copyfile(PROVENANCE / "provenance.json", OUTPUT / "smoke-model-provenance.json")
        temporary.replace(OUTPUT / "smoke.gguf")
    print("VERIFIED_MODEL_SMOKE_ASSET_STAGED")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("model", type=Path)
    args = parser.parse_args()
    try:
        stage(args.model)
    except (OSError, ValueError) as error:
        parser.exit(1, f"FAIL {error}\n")


if __name__ == "__main__":
    main()
