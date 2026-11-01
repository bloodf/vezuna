#!/usr/bin/env python3
"""Check maintained source naming and, optionally, actual native build identity."""
import argparse
import json
from pathlib import Path
import plistlib
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]
OLD_NAME = re.compile("vita" + "hearth", re.IGNORECASE)
APP_ID = "app.vezuna"


def check_source():
    names = subprocess.check_output(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=ROOT
    ).decode().split("\0")
    failures = []
    for name in sorted(set(names)):
        path = ROOT / name
        if not name or not path.is_file():
            continue
        if OLD_NAME.search(name):
            failures.append(f"old product name in path: {name}")
        if re.search(OLD_NAME.pattern.encode(), path.read_bytes(), re.IGNORECASE):
            failures.append(f"old product name in content: {name}")
    if failures:
        raise ValueError("\n".join(failures))
    app = json.loads((ROOT / "app.json").read_text())["expo"]
    submit = json.loads((ROOT / "eas.json").read_text())["submit"]["production"]
    assert app["android"]["package"] == app["ios"]["bundleIdentifier"] == APP_ID
    assert submit["android"]["applicationId"] == submit["ios"]["bundleIdentifier"] == APP_ID
    android = (ROOT / "composeApp/build.gradle.kts").read_text()
    assert f'applicationId = "{APP_ID}"' in android and f'namespace = "{APP_ID}"' in android
    project = (ROOT / "iosApp/Vezuna.xcodeproj/project.pbxproj").read_text()
    bundle_ids = re.findall(r"PRODUCT_BUNDLE_IDENTIFIER = ([^;]+);", project)
    assert APP_ID in bundle_ids and set(bundle_ids) <= {APP_ID, APP_ID + ".tests"}, bundle_ids
    with (ROOT / "iosApp/Vezuna/Vezuna.entitlements").open("rb") as file:
        assert plistlib.load(file)["com.apple.security.application-groups"] == ["group.app.vezuna"]
    print("VEZUNA_IDENTITY_PASSED")


def check_artifacts():
    android_key = "{http://schemas.android.com/apk/res/android}"
    for variant in ["debug", "release"]:
        manifest = ROOT / f"composeApp/build/intermediates/merged_manifest/{variant}/process{variant.title()}MainManifest/AndroidManifest.xml"
        data = ET.parse(manifest).getroot()
        assert data.attrib["package"] == APP_ID, manifest
        assert data.find("application").get(android_key + "name") == APP_ID + ".VezunaApplication"
    info_file = ROOT / "iosApp/.ios-build/derived-data/Debug/Build/Products/Debug-iphonesimulator/Vezuna.app/Info.plist"
    with info_file.open("rb") as file:
        info = plistlib.load(file)
    assert info["CFBundleIdentifier"] == APP_ID and info["CFBundleName"] == "Vezuna"
    assert info["BAAppGroupID"] == "group.app.vezuna"
    apk = ROOT / "composeApp/build/outputs/apk/debug/composeApp-debug.apk"
    with zipfile.ZipFile(apk) as archive:
        for entry in archive.infolist():
            assert not OLD_NAME.search(entry.filename), entry.filename
            assert not re.search(OLD_NAME.pattern.encode(), archive.read(entry), re.IGNORECASE), entry.filename
    app = info_file.parent
    for path in app.rglob("*"):
        if path.is_file():
            assert not OLD_NAME.search(str(path.relative_to(app))), path
            assert not re.search(OLD_NAME.pattern.encode(), path.read_bytes(), re.IGNORECASE), path
    print("VEZUNA_ARTIFACT_IDENTITY_PASSED")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifacts", action="store_true")
    args = parser.parse_args()
    try:
        check_source()
        if args.artifacts:
            check_artifacts()
    except (AssertionError, OSError, ValueError, ET.ParseError) as error:
        parser.exit(1, f"FAIL {error}\n")


if __name__ == "__main__":
    main()
