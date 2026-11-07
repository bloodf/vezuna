#!/usr/bin/env python3
from pathlib import Path
import argparse
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
# Skipped only when tools/release/stage_smoke_model.py has not staged the smoke model; any other skip is a failure.
ALLOWED_SKIPPED_CLASSES = {"app.vezuna.LocalLlmInstrumentedTest"}
SMOKE_METHODS = {
    "loadsVerifiedGgufAndGeneratesOnDevice",
    "cancelsCollectorAfterPartialAndAllowsNextGeneration",
}

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

def check_android_host(directory: Path):
    required = {
        "app.vezuna.llm.AndroidEngineOwnershipTest",
        "app.vezuna.ModelManagerLifecycleContractTest",
        "app.vezuna.ExportModelAndPromptTest",
    }
    seen = set()
    count = 0
    reports = list(directory.rglob("TEST-*.xml"))
    if not reports:
        raise ValueError(f"Missing Android host reports: {directory}")
    for report in reports:
        root = ET.parse(report).getroot()
        suites = [root] if root.tag == "testsuite" else list(root.iter("testsuite"))
        if not suites:
            raise ValueError(f"No test suites in {report}")
        for suite in suites:
            cases = suite.findall("testcase")
            actual = {
                "tests": len(cases),
                "failures": sum(case.find("failure") is not None for case in cases),
                "errors": sum(case.find("error") is not None for case in cases),
                "skipped": sum(case.find("skipped") is not None for case in cases),
            }
            declared = {key: int(suite.attrib[key]) for key in actual}
            if not cases or actual != declared or any(actual[key] for key in ("failures", "errors", "skipped")):
                raise ValueError(f"Invalid Android host outcomes in {report}: declared={declared}, actual={actual}")
            name = suite.attrib.get("name", "")
            if name in required and any(case.attrib.get("classname") == name and case.attrib.get("name") for case in cases):
                seen.add(name)
            count += len(cases)
    if required - seen:
        raise ValueError(f"Missing Android host test cases: {sorted(required - seen)}")
    print(f"ANDROID_HOST_TESTS={count}")
    print("ANDROID_HOST_TESTS_PASSED")


def check_local_llm_smoke(directory: Path):
    methods = []
    reports = set()
    for report in directory.rglob("TEST-*.xml"):
        root = ET.parse(report).getroot()
        suites = [root] if root.tag == "testsuite" else list(root.iter("testsuite"))
        for suite in suites:
            cases = suite.findall("testcase")
            smoke = [case for case in cases if case.get("classname") == "app.vezuna.LocalLlmInstrumentedTest"]
            actual = {
                "tests": len(cases),
                "failures": sum(case.find("failure") is not None for case in cases),
                "errors": sum(case.find("error") is not None for case in cases),
                "skipped": sum(case.find("skipped") is not None for case in cases),
            }
            declared = {key: int(suite.attrib[key]) for key in actual}
            if actual != declared or any(actual[key] for key in ("failures", "errors", "skipped")):
                raise ValueError(f"Invalid local LLM smoke outcomes in {report}: declared={declared}, actual={actual}")
            if not smoke:
                continue
            reports.add(report)
            methods.extend(case.get("name") for case in smoke)
    # Both methods must succeed in one device report, without duplicates or assumption skips.
    if len(reports) != 1 or len(methods) != len(SMOKE_METHODS) or set(methods) != SMOKE_METHODS:
        raise ValueError("Local LLM smoke requires both named methods exactly once in one device report")
    print(f"LOCAL_LLM_SMOKE_TESTS={len(methods)}")
    print("LOCAL_LLM_SMOKE_TESTS_PASSED")


def main():
    parser = argparse.ArgumentParser()
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--instrumentation", action="store_true")
    modes.add_argument("--android-host", action="store_true")
    modes.add_argument("--local-llm-smoke", action="store_true")
    args = parser.parse_args()
    instrumentation = ROOT / "composeApp/build/outputs/androidTest-results/connected"
    if args.local_llm_smoke or (args.instrumentation and (ROOT / "composeApp/build/generated/modelSmokeAssets/smoke.gguf").is_file()):
        try:
            check_local_llm_smoke(instrumentation)
        except (OSError, ValueError, KeyError, ET.ParseError) as error:
            parser.exit(1, f"FAIL {error}\n")
        if args.local_llm_smoke:
            return
    if args.instrumentation:
        totals, classes, skipped = summarize(instrumentation)
        if "app.vezuna.LocalizationInstrumentedTest" not in classes:
            raise AssertionError("Missing locale instrumentation results")
        if not (totals["tests"] > 0 and totals["failures"] == 0 and totals["errors"] == 0):
            raise AssertionError(totals)
        if not skipped <= ALLOWED_SKIPPED_CLASSES:
            raise AssertionError(f"Unexpected skipped classes: {sorted(skipped - ALLOWED_SKIPPED_CLASSES)}")
        if skipped:
            print(f"ANDROID_INSTRUMENTATION_SKIPPED={','.join(sorted(skipped))} (stage the smoke model to run it)")
        print(f"ANDROID_INSTRUMENTATION_TESTS={totals['tests']}")
        print("ANDROID_INSTRUMENTATION_PASSED")
    else:
        shared, _, _ = summarize(ROOT / "shared/build/test-results/jvmTest")
        android, _, _ = summarize(ROOT / "composeApp/build/test-results/testDebugUnitTest")
        for totals in (shared, android):
            if not (totals["tests"] > 0 and totals["failures"] == 0 and totals["errors"] == 0 and totals["skipped"] == 0):
                raise AssertionError(totals)
        print(f"SHARED_JVM_TESTS={shared['tests']}")
        print(f"ANDROID_UNIT_TESTS={android['tests']}")
        if args.android_host:
            check_android_host(ROOT / "shared/build/test-results/testAndroidHostTest")
        print("UNIT_TESTS_PASSED")

if __name__ == "__main__":
    main()
