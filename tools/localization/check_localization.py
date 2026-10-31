#!/usr/bin/env python3
from pathlib import Path
import json
import plistlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
PLACEHOLDER = re.compile(r"%(\d+)\$([sd@])")


def placeholders(value):
    return sorted((int(position), kind) for position, kind in PLACEHOLDER.findall(value))


def read_xml_catalog(path):
    entries = {}
    plurals = {}
    duplicates = set()
    for element in ET.parse(path).getroot():
        if element.tag not in {"string", "plurals"}:
            continue
        name = element.attrib["name"]
        target = plurals if element.tag == "plurals" else entries
        if name in target:
            duplicates.add(name)
        if element.tag == "string":
            target[name] = "".join(element.itertext())
        else:
            target[name] = {item.attrib["quantity"]: "".join(item.itertext()) for item in element.findall("item")}
    assert not duplicates, f"{path}: duplicate keys {sorted(duplicates)}"
    assert all(value.strip() for value in entries.values()), f"{path}: blank string"
    assert all(forms.get("one", "").strip() and forms.get("other", "").strip() for forms in plurals.values()), f"{path}: missing plural form"
    return entries, plurals


def read_strings(path):
    entries = {}
    for number, line in enumerate(path.read_text().splitlines(), 1):
        if not line.strip():
            continue
        match = re.fullmatch(r'"([^"\\]+)"\s*=\s*"((?:\\.|[^"\\])*)";', line)
        assert match, f"{path}:{number}: invalid .strings entry"
        key, raw = match.groups()
        assert key not in entries, f"{path}: duplicate key {key}"
        entries[key] = json.loads(f'"{raw}"')
    assert all(value.strip() for value in entries.values()), f"{path}: blank string"
    return entries


def read_stringsdict(path):
    with path.open("rb") as handle:
        document = plistlib.load(handle)
    result = {}
    for key, value in document.items():
        variable = next(key for key in value if key != "NSStringLocalizedFormatKey")
        forms = value[variable]
        assert forms["NSStringFormatSpecTypeKey"] == "NSStringPluralRuleType", f"{path}: not a plural rule"
        assert forms.get("one", "").strip() and forms.get("other", "").strip(), f"{path}: missing plural form"
        result[key] = {"one": forms["one"], "other": forms["other"]}
    return result


def compare_catalogs(named, plural=False):
    reference_name = next(iter(named))
    reference = named[reference_name]
    for name, catalog in named.items():
        assert catalog.keys() == reference.keys(), f"key parity drift: {reference_name} versus {name}: missing={sorted(reference.keys() - catalog.keys())}, extra={sorted(catalog.keys() - reference.keys())}"
    for key in reference:
        for name, catalog in named.items():
            if not plural:
                expected = [(position, "s" if kind == "@" else kind) for position, kind in placeholders(reference[key])]
                current = [(position, "s" if kind == "@" else kind) for position, kind in placeholders(catalog[key])]
                assert current == expected, f"{key}: placeholder drift in {name}: {current} != {expected}"
                continue
            reference_forms = reference[key]
            forms = catalog[key]
            assert reference_forms.keys() == forms.keys(), f"{key}: plural/form parity drift in {name}"
            for form, signature in ((form, placeholders(value)) for form, value in reference_forms.items()):
                current = [(position, "s" if kind == "@" else kind) for position, kind in placeholders(forms[form])]
                expected = [(position, "s" if kind == "@" else kind) for position, kind in signature]
                assert current == expected, f"{key}/{form}: placeholder drift in {name}: {current} != {expected}"


def kotlin_keys(path, enum_name):
    text = path.read_text()
    block = re.search(rf"enum class {enum_name}\s*\{{(.*?)\n\}}", text, re.S).group(1)
    return {item.strip().lower() for item in block.replace("\n", " ").split(",") if item.strip()}


def swift_keys(path):
    text = path.read_text()
    block = re.search(r"enum NativeString: String\s*\{(.*?)\n\}", text, re.S).group(1)
    return set(re.findall(r"\b(?:case|,)\s*([a-z][a-z0-9_]*)", block))


def expect_negative_controls():
    try:
        compare_catalogs({"en": {"key": "value %1$s"}, "pt": {"key": "valor %1$d"}})
        raise AssertionError("negative placeholder control did not fail")
    except AssertionError as error:
        assert "placeholder drift" in str(error)
    try:
        compare_catalogs({"en": {"key": "value"}, "pt": {"other": "valor"}})
        raise AssertionError("negative parity control did not fail")
    except AssertionError as error:
        assert "key parity drift" in str(error)


def main():
    shared = {}
    android = {}
    ios = {}
    ios_plurals = {}
    for language, suffix in (("en", ""), ("pt", "-pt"), ("es", "-es")):
        shared[language] = read_xml_catalog(ROOT / f"shared/src/commonMain/composeResources/values{suffix}/strings.xml")
        android[language] = read_xml_catalog(ROOT / f"composeApp/src/main/res/values{suffix}/strings.xml")
        ios[language] = read_strings(ROOT / f"iosApp/VitaHearth/{language}.lproj/Localizable.strings")
        ios_plurals[language] = read_stringsdict(ROOT / f"iosApp/VitaHearth/{language}.lproj/Localizable.stringsdict")
        info = read_strings(ROOT / f"iosApp/VitaHearth/{language}.lproj/InfoPlist.strings")
        assert {"CFBundleDisplayName", "NSCameraUsageDescription", "NSHealthShareUsageDescription", "NSPhotoLibraryUsageDescription"} <= info.keys(), f"{language}: missing permission descriptions"
    compare_catalogs({language: value[0] for language, value in shared.items()})
    compare_catalogs({language: value[1] for language, value in shared.items()}, plural=True)
    compare_catalogs({language: value[0] for language, value in android.items()})
    compare_catalogs({language: value[1] for language, value in android.items()}, plural=True)
    compare_catalogs(ios)
    compare_catalogs(ios_plurals, plural=True)

    android_en, android_en_plurals = read_xml_catalog(ROOT / "composeApp/src/main/res/values-en/strings.xml")
    assert android_en == android["en"][0] and android_en_plurals == android["en"][1], "Android default and values-en fallback differ"
    assert kotlin_keys(ROOT / "composeApp/src/main/java/com/bloodf/vitahearth/NativeMessage.kt", "NativeKey") == (android["en"][0].keys() | android["en"][1].keys()) - {"app_name"}, "Android NativeKey/catalog drift"
    assert kotlin_keys(ROOT / "shared/src/commonMain/kotlin/com/bloodf/vitahearth/localization/Localization.kt", "L10nKey") == shared["en"][0].keys() | shared["en"][1].keys(), "Shared L10nKey/catalog drift"
    mapped = set(re.findall(r"L10nKey\.([A-Z0-9_]+)\s+to\s+Res\.", (ROOT / "shared/src/commonMain/kotlin/com/bloodf/vitahearth/localization/ResourceMappings.kt").read_text()))
    assert {key.lower() for key in mapped} == shared["en"][0].keys() | shared["en"][1].keys(), "Shared resource-map drift"
    assert swift_keys(ROOT / "iosApp/VitaHearth/L10n.swift") == ios["en"].keys(), "iOS NativeString/catalog drift"
    bridge_keys = set(re.findall(r'nativeMessage\("([a-z0-9_]+)"', (ROOT / "iosApp/SharedBridge/ComposeBridge.kt").read_text()))
    assert bridge_keys <= ios["en"].keys(), f"Unlocalized iOS bridge keys: {sorted(bridge_keys - ios['en'].keys())}"
    locale_config = (ROOT / "composeApp/src/main/res/xml/locales_config.xml").read_text()
    assert all(f'android:name="{language}"' in locale_config for language in ("en", "pt", "es")), "Incomplete Android locale config"
    assert 'android:localeConfig="@xml/locales_config"' in (ROOT / "composeApp/src/main/AndroidManifest.xml").read_text(), "Android locale config not linked"
    expect_negative_controls()
    print(f"SHARED_CATALOG_KEYS={len(shared['en'][0]) + len(shared['en'][1])}")
    print(f"ANDROID_CATALOG_KEYS={len(android['en'][0]) + len(android['en'][1])}")
    print(f"IOS_CATALOG_KEYS={len(ios['en']) + len(ios_plurals['en'])}")
    print("LOCALIZATION_CATALOGS_PASSED")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"LOCALIZATION_CATALOGS_FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)
