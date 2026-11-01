#!/usr/bin/env python3
"""Compare a built app's notices with the reviewed, pinned notice inputs."""
import argparse
import json
from pathlib import Path
import zipfile

from build_notices import ROOT, render


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact", type=Path)
    args = parser.parse_args()
    artifact = args.artifact
    try:
        manifest = json.loads((ROOT / "third_party/notices-manifest.json").read_text())
        if artifact.is_dir():
            platform = "ios"
            actual = (artifact / "ThirdPartyNotices.txt").read_bytes()
        else:
            with zipfile.ZipFile(artifact) as archive:
                if artifact.suffix == ".apk":
                    platform, entry = "android", "assets/ThirdPartyNotices.txt"
                elif artifact.suffix == ".aab":
                    platform, entry = "android", "base/assets/ThirdPartyNotices.txt"
                elif artifact.suffix == ".ipa":
                    platform = "ios"
                    entries = [name for name in archive.namelist() if name.startswith("Payload/")
                               and len(name.split("/")) == 3 and name.endswith(".app/ThirdPartyNotices.txt")]
                    if len(entries) != 1:
                        raise ValueError("Expected one main-app notices file in IPA")
                    entry = entries[0]
                else:
                    raise ValueError("Expected APK, AAB, IPA, or built iOS .app")
                actual = archive.read(entry)
        if actual != render(manifest, platform):
            raise ValueError("Bundled notices differ from the reviewed inputs")
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        parser.exit(1, f"FAIL {error}\n")
    print(f"BUNDLED_{platform.upper()}_NOTICES_PASSED")


if __name__ == "__main__":
    main()
