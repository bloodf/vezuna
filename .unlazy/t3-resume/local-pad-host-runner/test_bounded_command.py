"""Real local children only. No Android command runs in these tests."""
import sys
import time
import unittest

from android_snapshot import bounded_command
from runner import Refusal


class BoundedCommandTests(unittest.TestCase):
    def run_python(self, source, maximum=1024, seconds=2):
        return bounded_command([sys.executable, "-c", source],
                               deadline=time.monotonic() + seconds, maximum=maximum)

    def test_exact_limit_stdout_and_stderr(self):
        result = self.run_python("import os; os.write(1,b'x'*512); os.write(2,b'y'*512)")
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout, b"x" * 512)
        self.assertEqual(result.stderr, b"y" * 512)

    def test_aggregate_overflow_is_rejected(self):
        with self.assertRaisesRegex(Refusal, "exceeds bound"):
            self.run_python("import os; os.write(1,b'x'*512); os.write(2,b'y'*513)")

    def test_continuous_flood_stops_without_waiting_for_natural_exit(self):
        start = time.monotonic()
        with self.assertRaisesRegex(Refusal, "exceeds bound"):
            self.run_python("import os\nwhile True: os.write(1,b'x'*65536)")
        self.assertLess(time.monotonic() - start, 2)

    def test_timeout_reaps_local_child(self):
        start = time.monotonic()
        with self.assertRaisesRegex(Refusal, "timeout"):
            self.run_python("import time; time.sleep(60)", seconds=0.2)
        self.assertLess(time.monotonic() - start, 1)

    def test_nonzero_exit_is_not_discarded(self):
        result = self.run_python("import sys; print('failure', file=sys.stderr); sys.exit(7)")
        self.assertEqual(result.returncode, 7)
        self.assertEqual(result.stderr, b"failure\n")

    def test_empty_success_is_empty_bytes_not_inferred_device_absence(self):
        result = self.run_python("pass")
        self.assertEqual((result.stdout, result.stderr), (b"", b""))


if __name__ == "__main__":
    unittest.main()
