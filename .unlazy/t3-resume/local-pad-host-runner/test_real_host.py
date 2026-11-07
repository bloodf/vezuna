"""Real owned host children; no device, adb, native engine or model calls."""
import pathlib
import subprocess
import sys
import tempfile
import time
import unittest

from runner import OwnedPopen, Refusal


class RealHostChildTests(unittest.TestCase):
    def wait_bounded(self, child, seconds=3):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            result = child.poll()
            if result is not None:
                return result
            time.sleep(0.01)
        self.fail("owned child did not exit within host-test deadline")

    def test_real_exit_and_stdout_are_retained(self):
        with tempfile.TemporaryDirectory(prefix="vezuna-owned-host-") as directory:
            output = pathlib.Path(directory) / "out"
            with output.open("wb") as sink:
                child = OwnedPopen([sys.executable, "-c", "print('OWNED_HOST_EXIT')"],
                                   stdout=sink, stderr=sink)
                try:
                    result = self.wait_bounded(child)
                    self.assertTrue(result.ok)
                    self.assertEqual(result.value, 0)
                finally:
                    child.terminate()
            self.assertEqual(output.read_text().strip(), "OWNED_HOST_EXIT")

    def test_real_blocking_child_poll_is_nonblocking_and_term_is_reaped(self):
        # The independent child blocks for 60s, while poll must remain responsive.
        # No process-group/PID lookup or shared process signal is used.
        with tempfile.TemporaryFile() as sink:
            child = OwnedPopen([sys.executable, "-c", "import time; time.sleep(60)"],
                               stdout=sink, stderr=sink)
            try:
                started = time.monotonic()
                self.assertIsNone(child.poll())
                self.assertLess(time.monotonic() - started, 0.5)
                child.terminate()
                child.terminate()
                result = self.wait_bounded(child)
                self.assertFalse(result.ok)
                self.assertLess(result.value, 0)
                self.assertTrue(child.signalled)
            finally:
                child.terminate()

    def test_pipe_refused_before_child_creation(self):
        with self.assertRaisesRegex(Refusal, "PIPE forbidden"):
            OwnedPopen([sys.executable, "-c", "raise SystemExit(99)"],
                       stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)


if __name__ == "__main__":
    unittest.main()
