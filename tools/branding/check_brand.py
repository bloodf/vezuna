#!/usr/bin/env python3
from pathlib import Path
import hashlib
import json
import plistlib
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
BRAND = ROOT / "brand"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def assert_same(source, target):
    assert target.is_file(), f"Missing branded asset: {target}"
    assert digest(source) == digest(target), f"Asset differs from supplied kit: {target}"


def luminance(hex_color):
    values = [int(hex_color[index:index + 2], 16) / 255 for index in (1, 3, 5)]
    linear = [value / 12.92 if value <= 0.04045 else ((value + 0.055) / 1.055) ** 2.4 for value in values]
    return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]


def ratio(foreground, background):
    first, second = luminance(foreground), luminance(background)
    return (max(first, second) + 0.05) / (min(first, second) + 0.05)


def main():
    for name in ("lockup-color", "lockup-dark", "mark-color", "mark-dark"):
        assert_same(BRAND / f"logos/{name}.png", ROOT / f"shared/src/commonMain/composeResources/drawable/vezuna_{name.replace('-', '_')}.png")
    for name in ("NotoSans-Regular.ttf", "NotoSans-Bold.ttf", "NOTO-LICENSE.txt"):
        assert_same(BRAND / f"fonts/{name}", ROOT / f"iosApp/Vezuna/Fonts/{name}")
    assert_same(BRAND / "fonts/NOTO-LICENSE.txt", ROOT / "shared/src/commonMain/composeResources/files/NOTO-LICENSE.txt")
    assert_same(BRAND / "fonts/NOTO-LICENSE.txt", ROOT / "composeApp/src/main/assets/NOTO-LICENSE.txt")
    for source in (BRAND / "icons/android/res").rglob("*"):
        if source.is_file():
            assert_same(source, ROOT / "composeApp/src/main/res" / source.relative_to(BRAND / "icons/android/res"))
    for source in (BRAND / "icons/ios/AppIcon.appiconset").rglob("*"):
        if source.is_file():
            assert_same(source, ROOT / "iosApp/Vezuna/Assets.xcassets/AppIcon.appiconset" / source.name)

    token_text = (ROOT / "shared/src/commonMain/kotlin/app/vezuna/ui/VezunaTokens.kt").read_text()
    tokens = json.loads((BRAND / "tokens/tokens.json").read_text())
    for group in ("light", "dark"):
        for name, value in tokens["color"][group].items():
            assert value.removeprefix("#") in token_text, f"Missing {group}/{name} token {value}"
    for value in ("#123B46", "#087F8C", "#71E2CC", "#F5FAF9", "#152E35", "#0C2027", "#E7F5F2"):
        assert value.removeprefix("#") in token_text, f"Missing brand primitive {value}"

    android_build = (ROOT / "composeApp/build.gradle.kts").read_text()
    assert 'applicationId = "app.vezuna"' in android_build
    pbx = (ROOT / "iosApp/Vezuna.xcodeproj/project.pbxproj").read_text()
    assert "PRODUCT_BUNDLE_IDENTIFIER = app.vezuna;" in pbx
    assert "ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon;" in pbx
    assert "INFOPLIST_KEY_CFBundleDisplayName = Vezuna;" in pbx
    assert 'INFOPLIST_FILE = "Vezuna-Info.plist";' in pbx
    with (ROOT / "iosApp/Vezuna-Info.plist").open("rb") as source:
        ios_info = plistlib.load(source)
    assert ios_info.get("UIAppFonts") == ["NotoSans-Regular.ttf", "NotoSans-Bold.ttf"], "Missing iOS font registration array"
    scheduler = (ROOT / "composeApp/src/main/java/app/vezuna/notifications/MedicationNotificationScheduler.kt").read_text()
    assert 'CHANNEL_ID = "recorded-medication-reminders"' in scheduler
    health = (ROOT / "composeApp/src/main/java/app/vezuna/health/LegacyHealthConnectMigration.kt").read_text()
    assert 'KEY = "health-connect-v1"' in health

    expected = {
        "": "Your health,\nin one place.",
        "-pt": "Sua saúde,\nem um só lugar.",
        "-es": "Tu salud,\nen un solo lugar.",
    }
    for suffix, tagline in expected.items():
        catalog = ET.parse(ROOT / f"shared/src/commonMain/composeResources/values{suffix}/strings.xml").getroot()
        values = {element.attrib["name"]: "".join(element.itertext()) for element in catalog.findall("string")}
        assert values["home_hero"] == tagline, f"Tagline mismatch in values{suffix}"
        assert values["vezuna"] == "Vezuna"
    for suffix in ("", "-en", "-pt", "-es"):
        catalog = ET.parse(ROOT / f"composeApp/src/main/res/values{suffix}/strings.xml").getroot()
        values = {element.attrib["name"]: "".join(element.itertext()) for element in catalog.findall("string")}
        assert values["app_name"] == "Vezuna"

    pairs = [
        ("light", "text", "background"), ("light", "text", "surface"),
        ("light", "textMuted", "surface"), ("light", "onPrimary", "primary"),
        ("light", "onAccent", "accent"), ("light", "danger", "surface"),
        ("light", "warning", "surface"), ("light", "success", "surface"), ("light", "info", "surface"),
        ("dark", "text", "background"), ("dark", "text", "surface"),
        ("dark", "textMuted", "surface"), ("dark", "onPrimary", "primary"),
        ("dark", "onAccent", "accent"), ("dark", "danger", "surface"),
        ("dark", "warning", "surface"), ("dark", "success", "surface"), ("dark", "info", "surface"),
    ]
    for theme, foreground, background in pairs:
        measured = ratio(tokens["color"][theme][foreground], tokens["color"][theme][background])
        print(f"CONTRAST {theme} {foreground}/{background}={measured:.2f}:1")
        assert measured >= 4.5, f"Insufficient text contrast: {theme} {foreground}/{background} {measured:.2f}"
    for background in ("#123B46", "#0C2027"):
        measured = ratio("#71E2CC", background)
        print(f"CONTRAST snackbar-action {background}={measured:.2f}:1")
        assert measured >= 4.5, f"Insufficient snackbar action contrast: {measured:.2f}"
    print("VEZUNA_BRAND_PASSED")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"VEZUNA_BRAND_FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)
