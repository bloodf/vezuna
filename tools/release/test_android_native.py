import struct
from pathlib import Path
import tempfile
import unittest
import zipfile

from check_android_native import check_archive, check_elf


def elf(*alignments):
    data = bytearray(64 + 56 * len(alignments))
    data[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HH", data, 54, 56, len(alignments))
    for index, alignment in enumerate(alignments):
        struct.pack_into("<IIQQQQQQ", data, 64 + index * 56, 1, 5, 0, 0, 0, 0, 0, alignment)
    return data


class NativeAlignmentTest(unittest.TestCase):
    def test_aligned_segments(self):
        self.assertEqual(check_elf(elf(16384, 65536)), 16384)

    def test_later_misaligned_segment_is_rejected(self):
        with self.assertRaisesRegex(ValueError, r"PT_LOAD\[1\] alignment=4096"):
            check_elf(elf(16384, 4096))

    def test_segment_address_mismatch_is_rejected(self):
        data = elf(16384)
        struct.pack_into("<Q", data, 64 + 16, 4096)
        with self.assertRaisesRegex(ValueError, "disagree modulo"):
            check_elf(data)

    def test_truncated_and_non_elf_inputs_are_rejected(self):
        for data in (b"", b"synthetic data", elf(16384)[:-1]):
            with self.subTest(data=data[:6]), self.assertRaises(ValueError):
                check_elf(data)

    def test_archive_checks_each_library_and_rejects_empty_input(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "synthetic.aab"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("base/lib/arm64-v8a/good.so", elf(16384))
                archive.writestr("base/lib/x86_64/bad.so", elf(16384, 4096))
            counts, failures = check_archive(path)
            self.assertEqual(dict(counts), {"arm64-v8a": 1, "x86_64": 1})
            self.assertEqual(failures, ["base/lib/x86_64/bad.so: PT_LOAD[1] alignment=4096, requires a power of two >= 16384"])
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("README.txt", "no native code")
            self.assertEqual(check_archive(path)[1], ["no arm64-v8a or x86_64 native libraries found; verify the input artifact"])


if __name__ == "__main__":
    unittest.main()
