"""Experimental host controller. CLI is plan-only; no device adapter is installed.

Injected adapters MUST start nonblocking owned operations and expose nonblocking
poll/terminate methods. Blocking device calls belong in owned Popen children,
never on the controller thread. No adapter may read unrelated application data.
"""
from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass, field
import json
import math
from pathlib import Path
import re
import subprocess
import time

APP = "app.vezuna.padtest"
TEST = APP + ".test"
PACKAGES = (APP, TEST)
SERIAL = "RQGL1034B8Z"
GLOBAL_SECONDS = 900.0
TEARDOWN_RESERVE = 60.0
CALL_SECONDS = 5.0
IDENTITY_SECONDS = 20.0
TICK = 0.05
DEAD_BINDING = "t3-580cfea7980dc309024b79ff"
METHOD = re.compile(r"app\.vezuna\.[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*#[A-Za-z_$][\w$]*", re.ASCII)


class Refusal(ValueError):
    pass


def require(ok, reason):
    if not ok:
        raise Refusal(reason)


def positive_int(value):
    return type(value) is int and value > 0


def receipt(value):
    return (isinstance(value, dict) and isinstance(value.get("path"), str)
            and bool(value["path"].strip()) and isinstance(value.get("sha256"), str)
            and re.fullmatch(r"[0-9a-f]{64}", value["sha256"]) is not None)


@dataclass(frozen=True)
class Plan:
    method: str
    manifest: dict
    lease: dict
    binding: dict

    def validate(self, now):
        m, l, b = self.manifest, self.lease, self.binding
        require(isinstance(self.method, str) and METHOD.fullmatch(self.method), "one fully qualified class#method required")
        require(m.get("serial") == SERIAL, "manifest device mismatch")
        require(m.get("target") == APP and m.get("test") == TEST, "package target mismatch")
        require(m.get("instrumentation_target") == APP, "instrumentation target mismatch")
        require(m.get("runner") == "androidx.test.runner.AndroidJUnitRunner", "runner mismatch")
        for key in ("target_uid", "test_uid", "app_vezuna_uid", "execution_uid"):
            require(positive_int(m.get(key)), "unknown UID: " + key)
        require(m["execution_uid"] == m["target_uid"], "execution target UID mismatch")
        require(len({m["target_uid"], m["test_uid"], m["app_vezuna_uid"]}) == 3, "UID isolation missing")
        for key in ("owned_install", "pristine_install", "no_shared_uid", "verified"):
            require(m.get(key) is True, "missing manifest ownership evidence: " + key)
        for key in ("apk_identity", "installed_identity", "ownership"):
            require(receipt(m.get(key)), "missing verified manifest receipt: " + key)
        require(m.get("owner") == l.get("owner") and bool(m.get("owner")), "ownership mismatch")
        require(l.get("serial") == SERIAL and l.get("exclusive") is True and l.get("active") is True,
                "exclusive active lease required")
        require(isinstance(l.get("id"), str) and bool(l["id"].strip()) and receipt(l.get("evidence")), "missing lease evidence")
        expiry = l.get("expires_monotonic")
        require(type(expiry) in (int, float) and math.isfinite(expiry)
                and expiry > now + GLOBAL_SECONDS, "lease must cover entire global deadline")
        require(b.get("source") == "device_open" and b.get("fresh") is True and receipt(b.get("evidence")),
                "fresh device_open binding receipt required")
        require(b.get("serial") == SERIAL and b.get("lease_id") == l["id"], "binding/lease device mismatch")
        require(bool(b.get("host_id")) and bool(b.get("device_id")), "missing runtime host/device IDs")
        command = b.get("command")
        require(isinstance(command, list) and len(command) == 1 and isinstance(command[0], str)
                and Path(command[0]).is_absolute() and Path(command[0]).name == "agent-device"
                and DEAD_BINDING not in command[0], "fresh absolute bound agent-device command required")
        return self

    def describe(self):
        return {
            "mode": "PLAN_ONLY_NO_DEVICE_EXECUTION", "serial": SERIAL,
            "method": self.method, "packages": list(PACKAGES),
            "instrumentation_arguments": ["-w", "-r", "-e", "class", self.method,
                                           TEST + "/androidx.test.runner.AndroidJUnitRunner"],
            "supported_close_arguments": [self.binding["command"][0], "close", APP],
            "global_seconds": GLOBAL_SECONDS, "teardown_reserve_seconds": TEARDOWN_RESERVE,
            "operation_timeout_seconds": CALL_SECONDS,
            "lease_id": self.lease["id"],
            "semantic_contract": "PENDING final method, markers, zero-skip and runtime assertions review",
        }


@dataclass(frozen=True)
class Identity:
    package: str
    name: str
    uid: int
    pid: int
    start: int


def parse_proc_stat(raw):
    """Linux stat comm may contain spaces and parentheses; field 22 is starttime."""
    require(isinstance(raw, str), "missing proc stat")
    left, right = raw.find(" ("), raw.rfind(") ")
    require(left > 0 and right > left, "malformed proc stat comm")
    pid, tail = raw[:left], raw[right + 2:].split()
    require(pid.isascii() and pid.isdigit() and int(pid) > 0 and len(tail) >= 20, "incomplete proc stat")
    require(len(tail[0]) == 1 and tail[0] in "RSDZTWtXxKIP", "unknown process state")
    require(tail[19].isascii() and tail[19].isdigit() and int(tail[19]) > 0, "unknown start identity")
    return int(pid), raw[left + 2:right], int(tail[19])


@dataclass(frozen=True)
class Snapshot:
    # complete means authoritative enumeration including package subprocesses;
    # empty pidof output or a failed command must never manufacture this value.
    serial: str
    packages: tuple[str, ...]
    complete: bool
    identities: tuple[Identity, ...]
    execution_uid: int


def validate_snapshot(snapshot, plan):
    require(isinstance(snapshot, Snapshot) and snapshot.complete is True, "absence/identity uncertainty")
    require(snapshot.serial == SERIAL and snapshot.packages == PACKAGES, "snapshot target mismatch")
    require(snapshot.execution_uid == plan.manifest["execution_uid"], "target UID mismatch")
    seen = set()
    for identity in snapshot.identities:
        require(isinstance(identity, Identity) and identity.package in PACKAGES, "unknown package process")
        require(identity.name == identity.package or identity.name.startswith(identity.package + ":"), "process target mismatch")
        uid = plan.manifest["target_uid" if identity.package == APP else "test_uid"]
        require(identity.uid == uid and positive_int(identity.uid), "target UID mismatch")
        require(positive_int(identity.pid) and positive_int(identity.start), "unknown PID/start identity")
        require(identity.pid not in seen, "duplicate PID")
        seen.add(identity.pid)
    return snapshot.identities


@dataclass(frozen=True)
class Completion:
    ok: bool
    value: object = None
    error: str = ""


class OwnedPopen:
    """Only children created here can be signalled. No PID-based kill API.

    Output must go to caller-owned bounded evidence sinks, never PIPE (which
    could block). Adapter decodes results only after poll returns an exit code.
    This utility is not connected to the plan-only CLI or any device command.
    """
    def __init__(self, argv, *, stdout, stderr):
        require(stdout is not subprocess.PIPE and stderr is not subprocess.PIPE, "PIPE forbidden")
        self.__child = subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=stdout,
                                        stderr=stderr, shell=False)
        self.signalled = False

    def poll(self):
        code = self.__child.poll()
        return None if code is None else Completion(code == 0, code, "host exit " + str(code))

    def terminate(self):
        if not self.signalled and self.__child.poll() is None:
            self.signalled = True
            self.__child.terminate()


@dataclass
class Result:
    primary: str | None = None
    cleanup_errors: list[str] = field(default_factory=list)
    events: list[dict] = field(default_factory=list)
    forced: bool = False
    deadline_failed: bool = False
    transport_complete: bool = False
    teardown_confirmed: bool = False
    release_lease: bool = False
    semantic_acceptance: str = "NOT_EVALUATED"
    elapsed: float = 0.0


class Controller:
    """Single attempt. Adapter.start(op, payload) returns an OWNED pollable child.

    start/poll/terminate must be bounded local work, never blocking device I/O.
    Every device diagnostic and identity/absence check is an asynchronous child.
    No retry, fallback, raw device signal, package deletion or implicit release.
    An external lease owner must act on release_lease; this code never releases it.
    """
    def __init__(self, plan, adapter, *, monotonic=time.monotonic, sleep=time.sleep):
        self.plan, self.adapter = plan, adapter
        self.now, self.sleep = monotonic, sleep
        self.result = Result()
        self.children = []
        self.owned = None
        self.launched = False
        self.identity_uncertain = False

    def event(self, name, **values):
        self.result.events.append({"at": self.now() - self.started, "event": name, **values})

    def fail(self, reason, *, cleanup=False):
        if self.result.primary is None:
            self.result.primary = reason
        elif cleanup:
            self.result.cleanup_errors.append(reason)
        self.event("failure", reason=reason)

    def deadline(self, limit):
        if self.now() >= limit:
            self.result.deadline_failed = True
            self.fail("DEADLINE", cleanup=self.result.primary is not None)
            return True
        return False

    def start(self, operation, payload=None):
        self.event("request", operation=operation)
        child = self.adapter.start(operation, payload)
        self.children.append(child)
        return child

    def call(self, operation, *, limit, payload=None):
        end = min(limit, self.now() + CALL_SECONDS)
        if self.deadline(limit):
            raise Refusal("DEADLINE")
        child = self.start(operation, payload)
        while self.now() < end:
            answer = child.poll()
            # A poll or delayed scheduler can return after the bound. Never accept it.
            if self.now() >= end:
                break
            if answer is not None:
                require(isinstance(answer, Completion), "invalid adapter completion")
                self.event("response", operation=operation, ok=answer.ok)
                require(answer.ok, operation + ": " + answer.error)
                return answer.value
            self.sleep(min(TICK, max(0, end - self.now())))
        child.terminate()  # signal only this operation's owned host child
        self.event("operation_timeout", operation=operation)
        self.deadline(limit)
        raise Refusal(operation + " timeout")

    def snapshot(self, limit):
        try:
            value = self.call("snapshot", limit=limit, payload={"packages": PACKAGES})
            validate_snapshot(value, self.plan)
            return value.identities
        except Exception:
            self.identity_uncertain = True
            raise

    def lease(self, limit):
        require(self.plan.lease["expires_monotonic"] > self.ends, "lease expired")
        require(self.call("lease", limit=limit, payload=self.plan.lease["id"]) is True,
                "exclusive lease lost")

    def reconcile(self):
        # No close when initial absence or launch attribution was never established.
        self.lease(self.ends)
        current = self.snapshot(self.ends)
        if not current:
            self.result.teardown_confirmed = True
            self.event("independent_absence", packages=list(PACKAGES))
            return
        require(self.launched and self.owned is not None, "unowned process; teardown unresolved")
        require(set(current).issubset(set(self.owned)), "PID/start/process mismatch; teardown unresolved")
        self.fail("FORCED_TERMINATION", cleanup=self.result.primary is not None)
        # Lease verification precedes the final identity check. NOTHING that
        # queries the device may intervene between this check and close.
        self.lease(self.ends)
        immediately_before = self.snapshot(self.ends)
        require(bool(immediately_before) and set(immediately_before) == set(current),
                "identity changed immediately before close; teardown unresolved")
        self.result.forced = True
        self.call("supported_close", limit=self.ends,
                  payload=self.plan.binding["command"] + ["close", APP])
        require(not self.snapshot(self.ends), "package process remains; teardown unresolved")
        self.result.teardown_confirmed = True
        self.event("independent_absence", packages=list(PACKAGES))

    def cleanup_children(self):
        # No wait(), communicate(), process-group signal or PID lookup. One TERM
        # per owned child, then bounded polling. No kill fallback.
        for child in self.children:
            try:
                if child.poll() is None:
                    child.terminate()
            except Exception as exc:
                self.fail("host cleanup: " + str(exc), cleanup=True)
        stop = min(self.ends, self.now() + 2.0)
        while self.now() < stop:
            try:
                if all(child.poll() is not None for child in self.children):
                    return True
            except Exception as exc:
                self.fail("host reap: " + str(exc), cleanup=True)
                return False
            self.sleep(min(TICK, max(0, stop - self.now())))
        try:
            if all(child.poll() is not None for child in self.children):
                return True
        except Exception as exc:
            self.fail("host reap: " + str(exc), cleanup=True)
        self.fail("owned host child unresolved", cleanup=True)
        return False

    def run(self):
        self.started = self.now()
        self.ends = self.started + GLOBAL_SECONDS
        work_end = self.ends - TEARDOWN_RESERVE
        validated = False
        try:
            self.plan.validate(self.started)
            validated = True
            self.lease(work_end)
            require(not self.snapshot(work_end), "unowned preexisting process")
            instrument = self.start("instrument", self.plan.describe())
            self.launched = True
            identity_end = min(work_end, self.now() + IDENTITY_SECONDS)
            while True:
                if self.deadline(work_end):
                    break
                if self.owned is None:
                    identities = self.snapshot(min(identity_end, work_end))
                    if identities:
                        require(any(p.package == APP and p.uid == self.plan.manifest["execution_uid"]
                                    for p in identities), "instrumentation execution identity missing")
                        self.owned = identities
                        self.event("owned_identity", identities=[asdict(p) for p in identities])
                    else:
                        require(self.now() < identity_end, "identity not observed")
                answer = instrument.poll()
                if self.deadline(work_end):
                    break
                if answer is not None:
                    require(isinstance(answer, Completion), "invalid instrumentation completion")
                    require(self.owned is not None, "identity not observed")
                    require(answer.ok, "instrument host failed: " + answer.error)
                    self.result.transport_complete = True
                    self.event("transport_complete", semantic_acceptance="NOT_EVALUATED")
                    break
                self.sleep(min(TICK, max(0, work_end - self.now())))
        except Exception as exc:
            self.fail(str(exc))
        finally:
            # Bounded diagnostics are optional. Failure never replaces primary.
            if self.launched and self.result.primary and self.now() < self.ends:
                try:
                    self.call("diagnostic", limit=self.ends, payload={"identities": self.owned})
                except Exception as exc:
                    self.fail("diagnostic: " + str(exc), cleanup=True)
            if validated and self.launched and not self.identity_uncertain and self.now() < self.ends:
                try:
                    self.reconcile()
                except Exception as exc:
                    self.fail("teardown: " + str(exc), cleanup=True)
            host_clean = self.cleanup_children()
            if self.now() >= self.ends:
                self.deadline(self.ends)
            self.result.release_lease = self.result.teardown_confirmed and host_clean
            if not self.result.teardown_confirmed:
                self.fail("TEARDOWN_UNRESOLVED", cleanup=True)
            self.result.elapsed = self.now() - self.started
        return self.result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    plan = sub.add_parser("plan", help="validate explicit evidence and print an inert plan")
    for name in ("manifest", "lease", "binding", "method"):
        plan.add_argument("--" + name, required=True)
    args = parser.parse_args(argv)
    try:
        inputs = [json.loads(Path(getattr(args, name)).read_text()) for name in ("manifest", "lease", "binding")]
        require(all(isinstance(value, dict) for value in inputs), "evidence objects required")
        configured = Plan(args.method, *inputs).validate(time.monotonic())
        print(json.dumps(configured.describe(), indent=2))
    except (ValueError, OSError, TypeError, KeyError) as exc:
        parser.exit(2, "REFUSED: " + str(exc) + "\n")


if __name__ == "__main__":
    main()
