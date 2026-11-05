#!/usr/bin/env python3
from pathlib import Path
import hashlib
import json
import plistlib
import re
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parent))
from sync_assets import DRAWABLE, NOTES, converted_outputs, vector_masters  # noqa: E402

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


def composite(foreground, background, alpha):
    channels = [round(int(foreground[i:i + 2], 16) * alpha + int(background[i:i + 2], 16) * (1 - alpha)) for i in (1, 3, 5)]
    return "#" + "".join(f"{channel:02X}" for channel in channels)


def check_vectors():
    """Every SVG master has its committed vector drawable, byte-identical to a fresh conversion."""
    masters = vector_masters()
    assert len(masters) >= 8 + 86 + 30, f"Vector masters missing: {len(masters)}"
    for master, _ in masters:
        text = master.read_text()
        assert "<image" not in text and "data:" not in text and "<script" not in text, f"Raster or script in vector master: {master}"
    outputs, notes = converted_outputs()
    for path, text in outputs.items():
        assert path.is_file(), f"Missing vector drawable {path.name}; run tools/branding/sync_assets.py"
        assert path.read_text() == text, f"Vector drawable drifted from its master: {path.name}; run tools/branding/sync_assets.py"
    stray = sorted(path.name for path in DRAWABLE.glob("*") if path not in outputs)
    assert not stray, f"Drawables without a vector master (no raster UI assets): {stray}"
    assert NOTES.read_text() == notes, "brand/vectors/conversion-notes.txt is stale; run tools/branding/sync_assets.py"
    print(f"VECTOR_DRAWABLES={len(outputs)}")


def kotlin_roles(token_text):
    """{'light': {role: '#RRGGBB'}, 'dark': {...}, 'category': {...}} parsed by name from VezunaTokens.kt."""
    primitives = {name: f"#{value.upper()}" for name, value in re.findall(r"val (\w+) = Color\(0xFF([0-9A-Fa-f]{6})\)", token_text)}
    for name, alias in re.findall(r"val (\w+) = (\w+)\n", token_text):
        if alias in primitives:
            primitives[name] = primitives[alias]

    def resolve(expression):
        literal = re.fullmatch(r"Color\(0xFF([0-9A-Fa-f]{6})\)", expression)
        return f"#{literal.group(1).upper()}" if literal else primitives[expression.removeprefix("VezunaTokens.")]

    roles = {}
    for theme, block in re.findall(r"val (Light|Dark) = Roles\((.*?)\n    \)", token_text, re.S):
        roles[theme.lower()] = {name: resolve(expression) for name, expression in re.findall(r"(\w+) = ([\w.]+(?:\(0xFF[0-9A-Fa-f]{6}\))?),", block)}
    names = {"RECORDS": "mint", "CARE": "coral", "TRENDS": "lavender", "ACTIVITY": "citron"}
    roles["category"] = {names[entry]: resolve(fill) for entry, fill in re.findall(r"(RECORDS|CARE|TRENDS|ACTIVITY)\(([\w.]+)", token_text)}
    roles["category"]["onCategory"] = primitives["OnCategory"]
    return roles


def swift_roles(swift):
    """Role values from VezunaStyle.swift: `vezunaX = adaptive(light:, dark:)` or a single `Color(hex())`."""
    # vezunaAction tints native controls and links, so it mirrors the link role.
    rename = {"action": "link", "onAction": "onPrimary"}
    roles = {"light": {}, "dark": {}}
    for name, light, dark in re.findall(r"static let vezuna(\w+) = adaptive\(light: 0x([0-9A-Fa-f]{6}), dark: 0x([0-9A-Fa-f]{6})\)", swift):
        role = rename.get(name[0].lower() + name[1:], name[0].lower() + name[1:])
        roles["light"][role], roles["dark"][role] = f"#{light.upper()}", f"#{dark.upper()}"
    for name, value in re.findall(r"static let vezuna(\w+) = Color\(hex\(0x([0-9A-Fa-f]{6})\)\)", swift):
        role = name[0].lower() + name[1:]
        roles["light"].setdefault(role, f"#{value.upper()}")
        roles["dark"].setdefault(role, f"#{value.upper()}")
    return roles


def check_living_color(living, token_text):
    swift = swift_roles((ROOT / "iosApp/Vezuna/VezunaStyle.swift").read_text())
    kotlin = kotlin_roles(token_text)
    swift_primitives = {"mint": "aqua", "onCategory": "onCategory", "coral": "coral", "lavender": "lavender", "citron": "citron", "pine": "navy", "teal": "teal", "paper": "paper"}
    for group in ("light", "dark"):
        for role, value in living[group].items():
            assert kotlin[group].get(role, "").upper() == value.upper(), f"VezunaTokens.kt {group}.{role} is {kotlin[group].get(role)}, tokens.json says {value}"
            assert swift[group].get(role, "").upper() == value.upper(), f"VezunaStyle.swift {group}.{role} is {swift[group].get(role)}, tokens.json says {value}"
    for name, value in living["category"].items():
        if name in kotlin["category"]:
            assert kotlin["category"][name] == value.upper(), f"VezunaTokens.kt category {name} is {kotlin['category'][name]}, tokens.json says {value}"
        assert swift["light"].get(swift_primitives[name], "") == value.upper(), f"VezunaStyle.swift category {name} drifted from {value}"
    text_pairs = [
        ("text", "background"), ("text", "surface"), ("text", "surfaceSubtle"),
        ("textMuted", "background"), ("textMuted", "surface"), ("textMuted", "surfaceSubtle"),
        ("onPrimary", "primary"), ("primary", "surface"), ("onPrimary", "focus"),
        # Teal on paper is 4.41:1, so text actions on the page background use the focus teal (Theme.kt primary).
        ("focus", "background"), ("focus", "surface"), ("focus", "surfaceSubtle"),
        ("link", "background"), ("link", "surface"), ("link", "surfaceSubtle"),
        ("danger", "surface"), ("danger", "background"), ("warning", "surface"), ("success", "surface"), ("info", "surface"),
        ("onHeader", "header"), ("onHeaderMuted", "header"), ("onHeaderAction", "headerAction"), ("headerAction", "header"),
        ("onNavIndicator", "navIndicator"),
    ]
    for theme in ("light", "dark"):
        roles = living[theme]
        for foreground, background in text_pairs:
            measured = ratio(roles[foreground], roles[background])
            print(f"CONTRAST living {theme} {foreground}/{background}={measured:.2f}:1")
            assert measured >= 4.5, f"Insufficient text contrast: living {theme} {foreground}/{background} {measured:.2f}"
        # Selected tab indicator is a non-text boundary on the bar surface.
        measured = ratio(roles["navIndicator"], roles["surface"])
        print(f"CONTRAST living {theme} navIndicator/surface={measured:.2f}:1")
        assert measured >= 3, f"Selected tab indicator too faint: {theme} {measured:.2f}"
        for control in ("border", "focus"):
            for background in ("surface", "background", "surfaceSubtle"):
                measured = ratio(roles[control], roles[background])
                print(f"CONTRAST living {theme} {control}/{background}={measured:.2f}:1")
                assert measured >= 3, f"Insufficient control contrast: living {theme} {control}/{background} {measured:.2f}"
    category = living["category"]
    for fill in ("mint", "coral", "lavender", "citron"):
        measured = ratio(category["onCategory"], category[fill])
        print(f"CONTRAST living onCategory/{fill}={measured:.2f}:1")
        assert measured >= 4.5, f"Insufficient category ink contrast on {fill}: {measured:.2f}"
    # StatusTag draws a 12% status tint over its opaque theme surface.
    # Theme.primary maps to link in light and primary in dark.
    for theme in ("light", "dark"):
        roles = living[theme]
        for status in ("warning", "success", "danger", "link" if theme == "light" else "primary"):
            background = composite(roles[status], roles["surface"], 0.12)
            for foreground, minimum in ((status, 4.5),):
                measured = ratio(roles[foreground], background)
                print(f"CONTRAST status {theme} {foreground}/{status}-tinted-surface={measured:.2f}:1")
                assert measured >= minimum, f"Insufficient status contrast: {theme} {foreground}/{status} {measured:.2f}"
    # Category segmented buttons use white on pine for the selected segment.
    measured = ratio("#FFFFFF", category["pine"])
    print(f"CONTRAST category selected white/pine={measured:.2f}:1")
    assert measured >= 4.5, f"Insufficient selected segment text contrast: {measured:.2f}"
    for background in (living["light"]["header"], living["dark"]["background"]):
        measured = ratio(category["mint"], background)
        assert measured >= 4.5, f"Mint selection on {background} too low: {measured:.2f}"
    # Chart lines, markers and informative icons are graphics: 3:1 against every surface they sit on.
    for theme in ("light", "dark"):
        for name, value in living["series"][theme].items():
            for background in ("background", "surface"):
                measured = ratio(value, living[theme][background])
                print(f"CONTRAST living series {theme} {name}/{background}={measured:.2f}:1")
                assert measured >= 3, f"Series color too faint: {theme} {name} on {background} {measured:.2f}"
    # Not measured because they are banned by rule: muted, status or teal text on category fills or the pine band.


def main():
    check_vectors()
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
    # tokens["color"] is the website palette; the app follows tokens["livingColor"] (checked below).
    for value in ("#123B46", "#087F8C", "#71E2CC", "#F5FAF9", "#152E35", "#0C2027", "#E7F5F2"):
        assert value.removeprefix("#") in token_text, f"Missing brand primitive {value}"
    check_living_color(tokens["livingColor"], token_text)

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
