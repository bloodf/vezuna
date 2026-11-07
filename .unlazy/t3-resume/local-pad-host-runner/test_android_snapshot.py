import unittest

from android_snapshot import inspect_snapshot, parse_ps, parse_packages, parse_status_uid
from runner import APP, TEST, Refusal

UIDS = {APP: 11001, TEST: 11002}
PACKAGES = f"package:{APP} uid:11001\npackage:{TEST} uid:11002\n"
EMPTY = "PID UID NAME\n1 0 init\n"
LIVE = EMPTY + f"41 11001 {APP}\n"


def stat(start=100):
    return "41 (app.vezuna.padtest) S " + " ".join(["0"] * 18 + [str(start)])


class Reads:
    def __init__(self, overrides=None, live=True):
        self.overrides = overrides or {}
        self.count = {}
        self.live = live

    def __call__(self, argv):
        key = argv[-1]
        index = self.count.get(key, 0)
        self.count[key] = index + 1
        if key in self.overrides:
            values = self.overrides[key]
            value = values[min(index, len(values) - 1)]
            if isinstance(value, Exception):
                raise value
            return value
        if argv[0] == "cmd":
            return PACKAGES
        if argv[0] == "ps":
            return LIVE if self.live else EMPTY
        if key.endswith("/stat"):
            return stat()
        if key.endswith("/status"):
            return "Name:\tapp.vezuna.padtest\nUid:\t11001\t11001\t11001\t11001\n"
        if key.endswith("/cmdline"):
            return APP + "\0"
        raise AssertionError(argv)


class SnapshotTests(unittest.TestCase):
    def test_complete_empty_snapshot_needs_both_enumerations_and_package_checks(self):
        reads = Reads(live=False)
        result = inspect_snapshot(reads, UIDS)
        self.assertEqual(result.identities, ())
        self.assertTrue(result.complete)
        self.assertEqual(reads.count["PID,UID,NAME"], 2)
        self.assertEqual(reads.count[APP], 2)

    def test_realistic_live_identity(self):
        result = inspect_snapshot(Reads(), UIDS)
        self.assertEqual((result.identities[0].pid, result.identities[0].uid,
                          result.identities[0].start), (41, 11001, 100))

    def test_ps_error_cannot_be_absence(self):
        with self.assertRaises(Refusal):
            inspect_snapshot(Reads({"PID,UID,NAME": [Refusal("permission denied")]}), UIDS)

    def test_starttime_change_refused(self):
        with self.assertRaisesRegex(Refusal, "changed"):
            inspect_snapshot(Reads({"/proc/41/stat": [stat(), stat(101)]}), UIDS)

    def test_process_appears_or_disappears_refused(self):
        for first, second in [(EMPTY, LIVE), (LIVE, EMPTY)]:
            with self.subTest(first=first), self.assertRaisesRegex(Refusal, "set changed"):
                inspect_snapshot(Reads({"PID,UID,NAME": [first, second]}), UIDS)

    def test_wrong_uid_or_unknown_owned_uid_name_refused(self):
        for line in [f"41 11002 {APP}", "41 11001 truncated", f"41 0 {APP}"]:
            with self.subTest(line=line), self.assertRaises(Refusal):
                parse_ps(EMPTY + line + "\n", UIDS)

    def test_ps_requires_complete_supported_rows(self):
        for text in ["", "PID USER NAME\n", EMPTY + "41 11001\n", EMPTY + "1 0 duplicate\n"]:
            with self.subTest(text=text), self.assertRaises(Refusal):
                parse_ps(text, UIDS)

    def test_subprocess_names_supported(self):
        parsed = parse_ps(EMPTY + f"41 11001 {APP}:worker\n", UIDS)
        self.assertEqual(parsed[41], (APP, 11001, APP + ":worker"))

    def test_missing_package_and_shared_uid_refused(self):
        with self.assertRaises(Refusal):
            parse_packages(f"package:{APP} uid:11001\n", UIDS)
        with self.assertRaises(Refusal):
            inspect_snapshot(Reads(), {APP: 11001, TEST: 11001})

    def test_uid_credentials_or_cmdline_mismatch_refused(self):
        with self.assertRaises(Refusal):
            parse_status_uid("Uid: 11001 0 11001 11001\n")
        with self.assertRaises(Refusal):
            inspect_snapshot(Reads({"/proc/41/cmdline": ["app.vezuna\0"]}), UIDS)


if __name__ == "__main__":
    unittest.main()
