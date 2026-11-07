"""Read-only Android identity worker; no launch/stop/install command exists here.

Designed to run as an owned host child, never on the watchdog controller thread.
Device support and authoritative ps visibility require a separate real preflight.
"""
from dataclasses import asdict
import json
import os
import selectors
import subprocess
import time

from runner import APP, TEST, PACKAGES, SERIAL, Identity, Snapshot, Refusal, require, parse_proc_stat

MAX_OUTPUT = 1024 * 1024


def bounded_command(argv, *, deadline, maximum=MAX_OUTPUT):
    """Bound aggregate stdout/stderr while streaming, not after allocation.

    Only this function's direct host child can be terminated. Terminating adb
    never proves its remote target exited. Failure allows up to 0.5s local reap
    time beyond the command deadline; the watchdog's reserve must include it.
    """
    require(time.monotonic() < deadline and type(maximum) is int and maximum > 0,
            "invalid command deadline/output bound")
    child = subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE, shell=False)
    output = {"stdout": bytearray(), "stderr": bytearray()}
    total = 0
    try:
        with selectors.DefaultSelector() as selector:
            for stream, label in [(child.stdout, "stdout"), (child.stderr, "stderr")]:
                os.set_blocking(stream.fileno(), False)
                selector.register(stream, selectors.EVENT_READ, label)
            while selector.get_map() or child.poll() is None:
                remaining = deadline - time.monotonic()
                require(remaining > 0, "read command timeout; remote exit unknown")
                for key, _ in selector.select(min(remaining, 0.05)):
                    # Read at most one byte beyond the permitted aggregate size.
                    data = os.read(key.fileobj.fileno(), min(65536, maximum - total + 1))
                    if not data:
                        selector.unregister(key.fileobj)
                        continue
                    total += len(data)
                    require(total <= maximum, "read command output exceeds bound")
                    output[key.data].extend(data)
            require(time.monotonic() < deadline, "late command completion")
            return subprocess.CompletedProcess(argv, child.returncode,
                                               bytes(output["stdout"]), bytes(output["stderr"]))
    finally:
        if child.poll() is None:
            child.terminate()
            try:
                child.wait(timeout=0.25)
            except subprocess.TimeoutExpired:
                child.kill()  # Exact Popen-owned host child, never a device PID.
                child.wait(timeout=0.25)
        child.stdout.close()
        child.stderr.close()


def parse_packages(text, expected):
    found = {}
    for line in text.splitlines():
        parts = line.split()
        require(len(parts) == 2 and parts[0].startswith("package:")
                and parts[1].startswith("uid:"), "package UID output malformed")
        package = parts[0][8:]
        # pm's filter is substring-based; only exact matches are authoritative.
        if package not in PACKAGES:
            continue
        value = parts[1][4:]
        require(value.isascii() and value.isdigit(), "package UID is not numeric")
        require(package not in found, "duplicate package UID")
        found[package] = int(value)
    require(found == expected, "installed package UID mismatch or missing package")


def parse_ps(text, expected):
    lines = text.splitlines()
    require(bool(lines) and lines[0].split() == ["PID", "UID", "NAME"],
            "unsupported ps header; completeness unproved")
    matched = {}
    all_pids = set()
    for line in lines[1:]:
        fields = line.split()
        require(len(fields) == 3, "incomplete ps row")
        pid, uid, name = fields
        require(pid.isascii() and pid.isdigit() and int(pid) > 0
                and uid.isascii() and uid.isdigit(), "non-numeric ps identity")
        pid, uid = int(pid), int(uid)
        require(pid not in all_pids, "duplicate ps PID")
        all_pids.add(pid)
        package = next((p for p in PACKAGES if name == p or name.startswith(p + ":")), None)
        # A process under either isolated UID with an unexpected/truncated name
        # is uncertainty, not evidence that the target is absent.
        if package is None:
            require(uid not in expected.values(), "unrecognized process under owned UID")
            continue
        require(uid == expected[package], "target process UID mismatch")
        matched[pid] = (package, uid, name)
    return matched


def parse_status_uid(text):
    rows = [line.split()[1:] for line in text.splitlines() if line.startswith("Uid:")]
    require(len(rows) == 1 and len(rows[0]) == 4, "missing process Uid fields")
    require(all(x.isascii() and x.isdigit() for x in rows[0]), "invalid process Uid")
    values = {int(x) for x in rows[0]}
    require(len(values) == 1, "process credential UIDs differ")
    return values.pop()


def inspect_snapshot(read, expected):
    """read(argv) returns decoded stdout only for successful bounded commands.

    Two process enumerations and repeated stat reads reject observed churn.
    This is not an atomic kernel snapshot; exclusive lease remains mandatory.
    """
    require(set(expected) == set(PACKAGES) and len(set(expected.values())) == 2
            and all(type(v) is int and v > 0 for v in expected.values()), "invalid expected UIDs")
    parse_packages(read(["cmd", "package", "list", "packages", "-U", APP]), expected)
    command = ["ps", "-A", "-w", "-o", "PID,UID,NAME"]
    first = parse_ps(read(command), expected)
    identities = []
    for pid, (package, uid, name) in sorted(first.items()):
        base = "/proc/" + str(pid) + "/"
        before = parse_proc_stat(read(["cat", base + "stat"]))
        actual_uid = parse_status_uid(read(["cat", base + "status"]))
        cmdline = read(["cat", base + "cmdline"])
        require("\0" in cmdline and cmdline.split("\0", 1)[0] == name,
                "process cmdline identity mismatch")
        after = parse_proc_stat(read(["cat", base + "stat"]))
        require(before == after and before[0] == pid and actual_uid == uid,
                "process changed while reading identity")
        identities.append(Identity(package, name, uid, pid, before[2]))
    require(parse_ps(read(command), expected) == first, "process set changed during snapshot")
    parse_packages(read(["cmd", "package", "list", "packages", "-U", APP]), expected)
    return Snapshot(SERIAL, PACKAGES, True, tuple(identities), expected[APP])


class BoundedAdbReader:
    """Only read argv made by inspect_snapshot; caller owns this worker process.

    Timeout of this helper does not attest device process exit. No UI operations.
    Streaming capture and owned host reap are bounded; remote exit is separate.
    """
    def __init__(self, adb, deadline):
        self.adb, self.deadline = adb, deadline

    def __call__(self, argv):
        remaining = self.deadline - time.monotonic()
        require(remaining > 0, "snapshot deadline")
        result = bounded_command([self.adb, "-s", SERIAL, "shell", *argv],
                                 deadline=min(self.deadline, time.monotonic() + 2))
        require(result.returncode == 0, "read command failed; absence unknown")
        require(not result.stderr.strip(), "read command diagnostic; completeness unknown")
        require(len(result.stdout) <= MAX_OUTPUT, "snapshot output exceeds bound")
        try:
            return result.stdout.decode("utf-8", errors="strict")
        except UnicodeDecodeError as error:
            raise Refusal("invalid UTF-8 in snapshot") from error


def serialize(snapshot):
    return json.dumps(asdict(snapshot), sort_keys=True)
