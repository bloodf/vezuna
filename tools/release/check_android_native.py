#!/usr/bin/env python3
"""Check every 64-bit ELF PT_LOAD segment in an APK, AAB, or AAR.

This checks ELF alignment only, not ZIP alignment, signing, or runtime behavior.
It reads archive entries without extracting them.
"""
import argparse
from collections import Counter
import hashlib
from pathlib import Path
import struct
import sys
import zipfile

PAGE_SIZE = 16384
ABIS = {"arm64-v8a", "x86_64"}


def check_elf(data):
    if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01":
        raise ValueError("expected a little-endian ELF64 header")
    offset = struct.unpack_from("<Q", data, 32)[0]
    entry_size, count = struct.unpack_from("<HH", data, 54)
    if entry_size < 56 or not count or offset < 64 or offset + entry_size * count > len(data):
        raise ValueError("invalid or truncated program header table")
    loads = []
    for index in range(count):
        kind, _, file_offset, address, _, _, _, alignment = struct.unpack_from(
            "<IIQQQQQQ", data, offset + index * entry_size
        )
        if kind == 1:
            loads.append(alignment)
            if alignment < PAGE_SIZE or alignment & (alignment - 1):
                raise ValueError(f"PT_LOAD[{index}] alignment={alignment}, requires a power of two >= {PAGE_SIZE}")
            if file_offset % PAGE_SIZE != address % PAGE_SIZE:
                raise ValueError(f"PT_LOAD[{index}] file offset and virtual address disagree modulo {PAGE_SIZE}")
    if not loads:
        raise ValueError("no PT_LOAD segments")
    return min(loads)


def check_archive(path):
    counts = Counter()
    failures = []
    with zipfile.ZipFile(path) as archive:
        for entry in archive.infolist():
            parts = entry.filename.split("/")
            if len(parts) < 3 or not entry.filename.endswith(".so") or parts[-2] not in ABIS:
                continue
            try:
                check_elf(archive.read(entry))
            except ValueError as error:
                failures.append(f"{entry.filename}: {error}")
            counts[parts[-2]] += 1
    if not counts:
        failures.append("no arm64-v8a or x86_64 native libraries found; verify the input artifact")
    return counts, failures


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    args = parser.parse_args()
    try:
        counts, failures = check_archive(args.archive)
        with args.archive.open("rb") as source:
            digest = hashlib.sha256()
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
        print(f"SHA256={digest.hexdigest()}")
        for abi, count in sorted(counts.items()):
            print(f"{abi}: {count} libraries checked")
        for failure in failures:
            print(f"FAIL {failure}", file=sys.stderr)
        if failures:
            return 1
    except (OSError, ValueError, zipfile.BadZipFile, RuntimeError) as error:
        print(f"FAIL {error}", file=sys.stderr)
        return 1
    print("ANDROID_ELF_16KB_PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
