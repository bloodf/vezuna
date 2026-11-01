#!/usr/bin/env python3
"""Keep the offline app policy and public website text identical."""
import argparse
import html
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "docs/PUBLISHER_PRIVACY_POLICY.txt"
COPIES = [
    ROOT / "composeApp/src/main/assets/PrivacyPolicy.txt",
    ROOT / "iosApp/Vezuna/PrivacyPolicy.txt",
    ROOT / "website/public/privacy-policy.txt",
]


def outputs():
    text = SOURCE.read_text(encoding="utf-8")
    if not text.startswith("Vezuna privacy policy\n\n") or not text.endswith("\n"):
        raise ValueError("invalid policy title or missing final newline")
    blocks = text.strip().split("\n\n")
    elements = []
    for index, block in enumerate(blocks):
        tag = "h1" if index == 0 else "h2" if index > 1 and "\n" not in block and len(block) < 65 else "p"
        elements.append(f"<{tag}>{html.escape(block)}</{tag}>\n")
    result = {path: text.encode() for path in COPIES}
    result[ROOT / "website/src/data/privacy-policy.html"] = "".join(elements).encode()
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--artifact", type=Path, help="also inspect an APK, AAB, IPA, or built iOS app")
    args = parser.parse_args()
    for path, expected in outputs().items():
        if args.check or args.artifact:
            if not path.is_file() or path.read_bytes() != expected:
                parser.exit(1, f"FAIL policy copy differs: {path.relative_to(ROOT)}\n")
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(expected)
    if args.artifact:
        artifact = args.artifact
        expected = SOURCE.read_bytes()
        try:
            if artifact.is_dir():
                actual = (artifact / "PrivacyPolicy.txt").read_bytes()
            else:
                with zipfile.ZipFile(artifact) as archive:
                    if artifact.suffix == ".apk":
                        entry = "assets/PrivacyPolicy.txt"
                    elif artifact.suffix == ".aab":
                        entry = "base/assets/PrivacyPolicy.txt"
                    elif artifact.suffix == ".ipa":
                        entries = [name for name in archive.namelist() if name.startswith("Payload/")
                                   and len(name.split("/")) == 3 and name.endswith(".app/PrivacyPolicy.txt")]
                        if len(entries) != 1:
                            raise ValueError("expected one main-app policy in IPA")
                        entry = entries[0]
                    else:
                        raise ValueError("expected APK, AAB, IPA, or built iOS app")
                    actual = archive.read(entry)
            if actual != expected:
                raise ValueError("built app policy differs from publisher policy")
        except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
            parser.exit(1, f"FAIL {error}\n")
        print("BUNDLED_PRIVACY_POLICY_PASSED")
    print("PRIVACY_POLICY_SYNC_PASSED")


if __name__ == "__main__":
    main()
