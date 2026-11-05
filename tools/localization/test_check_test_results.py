#!/usr/bin/env python3
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

CHECKER = Path(__file__).with_name("check_test_results.py")
CLASSES = (
    "app.vezuna.llm.AndroidEngineOwnershipTest",
    "app.vezuna.ModelManagerLifecycleContractTest",
    "app.vezuna.ExportModelAndPromptTest",
)


class ResultsCheckTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="results-check-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.checker = self.root / "tools/localization/check_test_results.py"
        self.checker.parent.mkdir(parents=True)
        shutil.copyfile(CHECKER, self.checker)
        self.write("shared/build/test-results/jvmTest", "jvm")
        self.write("composeApp/build/test-results/testDebugUnitTest", "app")
        self.host = "shared/build/test-results/testAndroidHostTest"
        for name in CLASSES:
            self.write(self.host, name)

    def write(self, directory, name, *, cases=None, counts='tests="1" failures="0" errors="0" skipped="0"'):
        path = self.root / directory / f"TEST-{name}.xml"
        path.parent.mkdir(parents=True, exist_ok=True)
        if cases is None:
            cases = f'<testcase classname="{name}" name="works"/>'
        path.write_text(f'<testsuite name="{name}" {counts}>{cases}</testsuite>')
        return path

    def run_checker(self, *args):
        return subprocess.run(["python3", str(self.checker), *args], capture_output=True, text=True)

    def test_happy_and_default_compatibility(self):
        result = self.run_checker("--android-host")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("ANDROID_HOST_TESTS=3", result.stdout)
        shutil.rmtree(self.root / self.host)
        self.assertIn("UNIT_TESTS_PASSED", self.run_checker().stdout)
        result = self.run_checker("--android-host")
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("UNIT_TESTS_PASSED", result.stdout)

    def test_missing_class_and_zero_cases(self):
        (self.root / self.host / f"TEST-{CLASSES[0]}.xml").unlink()
        self.assertNotEqual(self.run_checker("--android-host").returncode, 0)
        self.write(self.host, CLASSES[0], cases="", counts='tests="0" failures="0" errors="0" skipped="0"')
        self.assertNotEqual(self.run_checker("--android-host").returncode, 0)

    def test_outcomes_and_counter_disagreement(self):
        for outcome in ("failure", "error", "skipped"):
            with self.subTest(outcome=outcome):
                case = f'<testcase classname="{CLASSES[0]}" name="bad"><{outcome}/></testcase>'
                self.write(self.host, CLASSES[0], cases=case)
                self.assertNotEqual(self.run_checker("--android-host").returncode, 0)
                key = {"failure": "failures", "error": "errors", "skipped": "skipped"}[outcome]
                counts = 'tests="1" ' + ' '.join(f'{k}="{int(k == key)}"' for k in ("failures", "errors", "skipped"))
                self.write(self.host, CLASSES[0], cases=case, counts=counts)
                self.assertNotEqual(self.run_checker("--android-host").returncode, 0)

    def test_malformed_and_conflicting_modes(self):
        (self.root / self.host / f"TEST-{CLASSES[0]}.xml").write_text("<testsuite")
        self.assertNotEqual(self.run_checker("--android-host").returncode, 0)
        self.assertNotEqual(self.run_checker("--android-host", "--instrumentation").returncode, 0)

    def test_instrumentation_skip_allowlist_is_not_host_allowlist(self):
        directory = "composeApp/build/outputs/androidTest-results/connected"
        self.write(directory, "app.vezuna.LocalizationInstrumentedTest")
        self.write(directory, "app.vezuna.LocalLlmInstrumentedTest", cases='<testcase name="skip"><skipped/></testcase>', counts='tests="1" failures="0" errors="0" skipped="1"')
        self.assertEqual(self.run_checker("--instrumentation").returncode, 0)
        self.write(self.host, "app.vezuna.LocalLlmInstrumentedTest", cases='<testcase name="skip"><skipped/></testcase>', counts='tests="1" failures="0" errors="0" skipped="1"')
        self.assertNotEqual(self.run_checker("--android-host").returncode, 0)


if __name__ == "__main__":
    unittest.main()
