#!/usr/bin/env python3
from pathlib import Path
import argparse
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
# Skipped only when tools/release/stage_smoke_model.py has not staged the smoke model; any other skip is a failure.
ALLOWED_SKIPPED_CLASSES = {"app.vezuna.LocalLlmInstrumentedTest"}

def summarize(directory: Path):
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    classes = set()
    skipped_classes = set()
    for report in directory.rglob("TEST-*.xml"):
        root = ET.parse(report).getroot()
        suites = [root] if root.tag == "testsuite" else list(root.iter("testsuite"))
        for suite in suites:
            for key in totals:
                totals[key] += int(suite.attrib.get(key, 0))
            classes.add(suite.attrib.get("name", ""))
            if int(suite.attrib.get("skipped", 0)):
                skipped_classes.add(suite.attrib.get("name", ""))
    return totals, classes, skipped_classes

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--instrumentation", action="store_true")
    args = parser.parse_args()
    if args.instrumentation:
        totals, classes, skipped = summarize(ROOT / "composeApp/build/outputs/androidTest-results/connected")
        assert "app.vezuna.LocalizationInstrumentedTest" in classes, "Missing locale instrumentation results"
        assert totals["tests"] > 0 and totals["failures"] == 0 and totals["errors"] == 0, totals
        assert skipped <= ALLOWED_SKIPPED_CLASSES, f"Unexpected skipped classes: {sorted(skipped - ALLOWED_SKIPPED_CLASSES)}"
        if skipped:
            print(f"ANDROID_INSTRUMENTATION_SKIPPED={','.join(sorted(skipped))} (stage the smoke model to run it)")
        print(f"ANDROID_INSTRUMENTATION_TESTS={totals['tests']}")
        print("ANDROID_INSTRUMENTATION_PASSED")
    else:
        shared, _, _ = summarize(ROOT / "shared/build/test-results/jvmTest")
        android, _, _ = summarize(ROOT / "composeApp/build/test-results/testDebugUnitTest")
        for totals in (shared, android):
            assert totals["tests"] > 0 and totals["failures"] == 0 and totals["errors"] == 0 and totals["skipped"] == 0, totals
        print(f"SHARED_JVM_TESTS={shared['tests']}")
        print(f"ANDROID_UNIT_TESTS={android['tests']}")
        print("UNIT_TESTS_PASSED")

if __name__ == "__main__":
    main()
