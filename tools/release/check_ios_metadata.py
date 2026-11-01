#!/usr/bin/env python3
"""Inspect actual built app metadata. Does not validate signing or store delivery."""
import argparse
from pathlib import Path
import plistlib
import re

ROOT = Path(__file__).resolve().parents[2]


def check_privacy_manifests(app):
    paths = ["PrivacyInfo.xcprivacy", "ComposeMultiplatformPrivacy.bundle/PrivacyInfo.xcprivacy"]
    for relative in paths:
        with (app / relative).open("rb") as source:
            actual = plistlib.load(source)
        with (ROOT / "iosApp/Vezuna" / relative).open("rb") as source:
            expected = plistlib.load(source)
        if actual != expected:
            raise ValueError(f"privacy manifest differs from reviewed input: {relative}")
    with (app / paths[0]).open("rb") as source:
        main = plistlib.load(source)
    if main.get("NSPrivacyTracking") is not False or main.get("NSPrivacyTrackingDomains") != []:
        raise ValueError("unexpected app tracking declaration")
    if main.get("NSPrivacyCollectedDataTypes") != []:
        raise ValueError("app manifest must declare no collected data types (data stays on device)")
    # stat/fstat are statically linked (Skia/ICU file mapping of bundled and app-container files).
    if main.get("NSPrivacyAccessedAPITypes") != [{
        "NSPrivacyAccessedAPIType": "NSPrivacyAccessedAPICategoryFileTimestamp",
        "NSPrivacyAccessedAPITypeReasons": ["C617.1"],
    }]:
        raise ValueError("app manifest must declare file-timestamp reason C617.1 and no other required-reason API")
    with (app / paths[1]).open("rb") as source:
        sdk = plistlib.load(source)
    if sdk.get("NSPrivacyAccessedAPITypes") != [{
        "NSPrivacyAccessedAPIType": "NSPrivacyAccessedAPICategoryFileTimestamp",
        "NSPrivacyAccessedAPITypeReasons": ["0A2A.1"],
    }]:
        raise ValueError("unexpected Compose SDK required-reason declaration")
    print("IOS_PRIVACY_MANIFEST_PACKAGING_PASSED")


def check(app):
    check_privacy_manifests(app)
    with (app / "Info.plist").open("rb") as source:
        info = plistlib.load(source)
    expected = {
        "CFBundleIdentifier": "app.vezuna",
        "MinimumOSVersion": "18.5",
        "CADisableMinimumFrameDurationOnPhone": True,
        "BAAppGroupID": "group.app.vezuna",
        "BAHasManagedAssetPacks": True,
        "BAUsesAppleHosting": True,
        "UIAppFonts": ["NotoSans-Regular.ttf", "NotoSans-Bold.ttf"],
    }
    for key, value in expected.items():
        if type(info.get(key)) is not type(value) or info.get(key) != value:
            raise ValueError(f"{key}: expected {value!r}, got {info.get(key)!r}")
    for font in info["UIAppFonts"]:
        if not (app / font).is_file():
            raise ValueError(f"font declared but absent from bundle: {font}")
    resources = app / "compose-resources/composeResources/vezuna.shared.generated.resources"
    for relative in ["font/NotoSans-Regular.ttf", "font/NotoSans-Bold.ttf",
                     "values/strings.commonMain.cvr", "values-pt/strings.commonMain.cvr",
                     "values-es/strings.commonMain.cvr", "files/NOTO-LICENSE.txt"]:
        if not (resources / relative).is_file():
            raise ValueError(f"Compose resource absent from runtime lookup path: {relative}")
    for key in ["NSCameraUsageDescription", "NSPhotoLibraryUsageDescription", "NSHealthShareUsageDescription"]:
        if not isinstance(info.get(key), str) or not info[key].strip():
            raise ValueError(f"missing usage description: {key}")
    sdk = info.get("DTSDKName", "")
    match = re.fullmatch(r"iphone(?:os|simulator)(\d+)(?:\.\d+)*", sdk)
    if not match or int(match[1]) < 26:
        raise ValueError(f"requires iOS SDK >= 26, got {sdk!r}")
    print(f"BUNDLE_ID={info['CFBundleIdentifier']}")
    print(f"MINIMUM_IOS={info['MinimumOSVersion']}")
    print(f"SDK={sdk}")
    print("IOS_BUNDLE_METADATA_PASSED")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("app", type=Path)
    args = parser.parse_args()
    try:
        check(args.app)
    except (OSError, ValueError, plistlib.InvalidFileException) as error:
        parser.exit(1, f"FAIL {error}\n")


if __name__ == "__main__":
    main()
