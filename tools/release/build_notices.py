#!/usr/bin/env python3
"""Generate or verify the app notices from reviewed, hash-pinned license inputs."""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def verified_bytes(record):
    path = (ROOT / record["path"]).resolve()
    if not path.is_relative_to(ROOT):
        raise ValueError("Notice input must be inside the repository")
    data = path.read_bytes()
    if hashlib.sha256(data).hexdigest() != record["sha256"]:
        raise ValueError(f"Notice input changed; review its license and refresh the manifest: {record['path']}")
    return data


def render(manifest, platform):
    inventory = json.loads(verified_bytes(manifest["inventories"][platform]))
    sections = [
        "Vezuna third-party notices\n\n"
        "Vezuna source and original artwork are licensed under MIT. "
        "Fonts and third-party components retain their own terms below.\n\n"
        "Llamatik's published package metadata declares Apache-2.0; its pinned "
        "source release declares MIT. Both texts and the collected native notices are retained.\n",
        "Dependency license declarations\n",
    ]
    if platform == "ios":
        sections.insert(1, "\nThis software is based in part on the work of the Independent JPEG Group.\n\n")
    for artifact in inventory:
        if not artifact["pomLicenses"]:
            raise ValueError(f"Missing license declaration: {artifact['component']}")
        licenses = "; ".join(f"{entry['name']} ({entry['url']})" for entry in artifact["pomLicenses"])
        sections.append(f"{artifact['component']}: {licenses}\n")
    sections.append("\nLicense texts and copyright notices\n")
    groups = {}
    for record in manifest["inputs"]:
        data = verified_bytes(record)
        if platform in record["platforms"]:
            group = groups.setdefault(record["sha256"], {"data": data, "components": set()})
            group["components"].add(record["component"])
    for group in groups.values():
        sections.append("\n" + "=" * 72 + "\n")
        sections.append("\n".join(sorted(group["components"])) + "\n\n")
        sections.append(group["data"].decode("utf-8"))
        if not group["data"].endswith(b"\n"):
            sections.append("\n")
    return "".join(sections).encode("utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Verify existing files without writing")
    args = parser.parse_args()
    manifest = json.loads((ROOT / "third_party/notices-manifest.json").read_text())
    for platform, relative in manifest["outputs"].items():
        expected = render(manifest, platform)
        output = (ROOT / relative).resolve()
        if not output.is_relative_to(ROOT):
            raise ValueError("Notice output must be inside the repository")
        if args.check:
            if not output.is_file() or output.read_bytes() != expected:
                parser.exit(1, f"FAIL regenerate notices: {relative}\n")
        else:
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_bytes(expected)
        print(f"{platform.upper()}_NOTICES_VERIFIED {len(expected)} bytes")
    print("APP_NOTICES_PASSED")


if __name__ == "__main__":
    main()
