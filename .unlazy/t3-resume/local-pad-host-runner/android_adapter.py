"""Opt-in Android transport adapter. Authoring is not runtime authorization.

All device I/O occurs in adapter_worker children. CLI defaults to inert plan;
execution additionally requires explicit root-supplied hashed preflight receipts.
Receipt integrity checks do not authenticate the receipt author's assertions.
"""
from __future__ import annotations

import argparse
from dataclasses import asdict
import hashlib
import json
import os
from pathlib import Path
import selectors
import signal
import stat
import subprocess
import sys
import time

from runner import (APP, TEST, PACKAGES, SERIAL, Plan, Controller, Completion,
                    Identity, Snapshot, Refusal, require, receipt, validate_snapshot)

CONTROL_BOUND = 128 * 1024
OUTPUT_BOUND = 8 * 1024 * 1024
OP_SECONDS = 2.75  # Cleanup reserves leave ordinary operations inside Controller's 5s cap.
REAP_SECONDS = 1.5  # Lets the frozen reader finish <=0.75s read + <=0.5s reap.
SCHEMA = "local-pad-adapter-preflight-v1"
READY = b"ADAPTER_RECEIPT_READY\n"


def read_file(path, maximum=CONTROL_BOUND):
    """Read a finite regular local file, refusing symlinks and special files."""
    require(isinstance(path, str) and Path(path).is_absolute(), "absolute receipt path required")
    fd = os.open(path, os.O_RDONLY | os.O_NONBLOCK | os.O_NOFOLLOW)
    with os.fdopen(fd, "rb") as stream:
        info = os.fstat(stream.fileno())
        require(stat.S_ISREG(info.st_mode) and info.st_size <= maximum, "receipt size/type refused")
        data = stream.read(maximum + 1)
        require(len(data) <= maximum, "receipt exceeds bound")
        return data


def hashed_receipt(value):
    require(receipt(value), "missing on-disk receipt")
    data = read_file(value["path"])
    require(hashlib.sha256(data).hexdigest() == value["sha256"], "receipt SHA256 mismatch")
    return data


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"),
                                     allow_nan=False).encode()).hexdigest()


def implementation_hashes():
    directory = Path(__file__).resolve().parent
    return {name: hashlib.sha256(read_file(str(directory / name))).hexdigest()
            for name in ("android_adapter.py", "adapter_worker.py", "runner.py", "android_snapshot.py")}


def lease_current(plan, deadline):
    """The lease receipt must contain actual current fields, not an opaque flag."""
    live = json.loads(hashed_receipt(plan.lease["evidence"]))
    require(isinstance(live, dict), "current lease object required")
    for key in ("id", "owner", "serial", "exclusive", "active", "expires_monotonic"):
        require(type(live.get(key)) is type(plan.lease.get(key))
                and live.get(key) == plan.lease.get(key), "current lease mismatch: " + key)
    require(live["active"] is True and live["exclusive"] is True
            and live["expires_monotonic"] > deadline
            and time.monotonic() < deadline, "lost/expired lease")
    return True


def verify_inputs(plan, preflight, deadline, adb=None):
    """Root attestations are inputs to later review, never generated here."""
    require(isinstance(preflight, dict), "preflight object required")
    for key in ("host_id", "device_id"):
        require(isinstance(plan.binding.get(key), str) and bool(plan.binding[key].strip()),
                "actual binding ID required: " + key)
    require(isinstance(plan.manifest.get("owner"), str) and bool(plan.manifest["owner"].strip()),
            "actual lease owner required")
    require(preflight.get("schema") == SCHEMA, "explicit preflight input required")
    require(isinstance(preflight.get("adb"), str) and Path(preflight["adb"]).is_absolute(),
            "preflight must name the exact adb executable")
    if adb is not None:
        require(adb == preflight["adb"], "adb/preflight mismatch")
    context = {"serial": SERIAL, "lease_id": plan.lease["id"], "method": plan.method,
               "manifest_sha256": digest(plan.manifest), "binding_sha256": digest(plan.binding),
               "implementation_sha256": implementation_hashes(), "adb": preflight["adb"]}
    for key, value in context.items():
        require(preflight.get(key) == value, "preflight context mismatch: " + key)
    for key in ("review", "final_artifact", "lease_preflight"):
        attestation = json.loads(hashed_receipt(preflight.get(key)))
        require(isinstance(attestation, dict) and attestation.get("kind") == key,
                "preflight receipt kind mismatch: " + key)
        require(attestation.get("decision") == "APPROVED_FOR_ASSIGNED_RUNTIME",
                "runtime preflight not approved: " + key)
        require(all(attestation.get(k) == v for k, v in context.items()),
                "preflight receipt binding mismatch: " + key)
    for key in ("apk_identity", "installed_identity", "ownership"):
        hashed_receipt(plan.manifest[key])
    binding = json.loads(hashed_receipt(plan.binding["evidence"]))
    require(isinstance(binding, dict), "binding receipt object required")
    for key in ("source", "fresh", "serial", "lease_id", "host_id", "device_id", "command"):
        require(type(binding.get(key)) is type(plan.binding.get(key))
                and binding.get(key) == plan.binding.get(key), "binding receipt mismatch: " + key)
    # The separately retained supported-help/preflight receipt must assert this
    # exact close command. No probing or UI operation intervenes before close.
    require(binding.get("supported_close_argv") == plan.binding["command"] + ["close", APP],
            "supported close receipt missing")
    lease_current(plan, deadline)


class WorkerHandle:
    """Nonblocking controller handle; owns one host session and its Popen child.

    Do not reap the session leader before killing its group: the unreaped leader
    pins the group ID, preventing reuse. No arbitrary PID or device signals.
    Worker pipes carry bounded diagnostics, not instrumentation output.
    """
    def __init__(self, argv, prefix, deadline, operation, plan):
        self.prefix, self.deadline = prefix, deadline
        self.operation, self.plan = operation, plan
        self.result = None
        self.reason = None
        self.stopping = None
        self.killed = False
        self.total = 0
        self.notice = bytearray()
        self.ready = False
        self.selector = selectors.DefaultSelector()
        self.sinks = {}
        try:
            for label in ("stdout", "stderr"):
                self.sinks[label] = Path(str(prefix) + ".worker." + label).open("xb")
            self.child = subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                          stderr=subprocess.PIPE, shell=False, start_new_session=True)
        except BaseException:
            for sink in self.sinks.values():
                sink.close()
            self.selector.close()
            raise
        for stream, label in ((self.child.stdout, "stdout"), (self.child.stderr, "stderr")):
            os.set_blocking(stream.fileno(), False)
            self.selector.register(stream, selectors.EVENT_READ, label)

    def _signal_group(self, sig):
        try:
            os.killpg(self.child.pid, sig)  # Exclusively our unreaped host session leader.
        except ProcessLookupError:
            self.reason = self.reason or "host session cleanup unresolved: group missing"
        except PermissionError as exc:
            self.reason = self.reason or "host session cleanup unresolved: " + str(exc)

    def terminate(self):
        if self.result is None and self.stopping is None:
            self.reason = self.reason or "worker terminated; remote exit unresolved"
            self.stopping = time.monotonic()
            self._signal_group(signal.SIGTERM)

    def _drain(self):
        # Fixed work per poll, even if a worker writes indefinitely.
        if self.total > CONTROL_BOUND:
            return
        for key, _ in self.selector.select(0):
            data = os.read(key.fileobj.fileno(), min(65536, CONTROL_BOUND - self.total + 1))
            if not data:
                self.selector.unregister(key.fileobj)
                key.fileobj.close()
                continue
            self.total += len(data)
            self.sinks[key.data].write(data)
            self.sinks[key.data].flush()
            if key.data == "stdout":
                self.notice.extend(data)
                self.ready = self.notice == READY
            if self.total > CONTROL_BOUND:
                self.reason = "worker control output overflow"
                self.terminate()
                return

    def poll(self):
        if self.result is not None:
            return self.result
        self._drain()
        now = time.monotonic()
        if now >= self.deadline and self.stopping is None:
            self.reason = "worker deadline; remote exit unresolved"
            self.terminate()
        exited = os.waitid(os.P_PID, self.child.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
        if not self.ready and exited is None and (self.stopping is None or now - self.stopping < REAP_SECONDS):
            return None
        if not self.killed:
            self._signal_group(signal.SIGKILL)  # Removes descendants before reaping the pinned leader.
            self.killed = True
        code = self.child.poll()  # First reap attempt, always AFTER host group cleanup.
        if code is None:
            return None
        self._drain()
        self.selector.close()
        for stream in (self.child.stdout, self.child.stderr):
            stream.close()
        for sink in self.sinks.values():
            sink.close()
        try:
            require(self.reason is None, self.reason)
            require(self.ready and code == -signal.SIGKILL, "worker host exit without receipt handoff: " + str(code))
            value = json.loads(read_file(str(self.prefix) + ".result.json"))
            require(isinstance(value, dict), "worker result object required")
            require(value.get("ok") is True, value.get("error", "worker failed"))
            if self.operation == "snapshot":
                raw = value["value"]
                answer = Snapshot(raw["serial"], tuple(raw["packages"]), raw["complete"],
                                  tuple(Identity(**x) for x in raw["identities"]), raw["execution_uid"])
                validate_snapshot(answer, self.plan)
            else:
                answer = value["value"]
            self.result = Completion(True, answer)
        except (ValueError, OSError, TypeError, KeyError) as exc:
            self.result = Completion(False, error=str(exc))
        return self.result


class AndroidAdapter:
    def __init__(self, plan, *, preflight, adb, evidence, execute=False,
                 output_bound=OUTPUT_BOUND):
        require(execute is True, "runtime launch is opt-in; authoring does not authorize execution")
        plan.validate(time.monotonic())
        require(Path(adb).is_absolute(), "absolute adb executable required")
        require(type(output_bound) is int and 0 < output_bound <= OUTPUT_BOUND, "invalid output bound")
        self.plan, self.preflight, self.adb = plan, preflight, adb
        self.evidence = Path(evidence).resolve()
        require(self.evidence.is_dir(), "existing exclusively assigned evidence directory required")
        self.ends = time.monotonic() + 900
        self.output_bound = output_bound
        self.count = 0
        self.instrumented = False
        self.closed = False

    def start(self, operation, payload=None):
        require(operation in {"lease", "snapshot", "instrument", "supported_close", "diagnostic"},
                "unsupported operation")
        if operation == "lease":
            require(payload == self.plan.lease["id"], "lease payload mismatch")
        elif operation == "snapshot":
            require(payload == {"packages": PACKAGES}, "snapshot payload mismatch")
        elif operation == "instrument":
            require(not self.instrumented and payload == self.plan.describe(), "instrument payload/relaunch refused")
            self.instrumented = True
        elif operation == "supported_close":
            require(not self.closed and payload == self.plan.binding["command"] + ["close", APP],
                    "close payload/retry refused")
            self.closed = True
        else:
            require(isinstance(payload, dict) and set(payload) == {"identities"}, "diagnostic payload mismatch")
            require(payload["identities"] is not None, "diagnostic identities unknown")
            validate_snapshot(Snapshot(SERIAL, PACKAGES, True, tuple(payload["identities"]),
                                       self.plan.manifest["execution_uid"]), self.plan)
            payload = {"identities": [asdict(x) for x in payload["identities"]]}
        self.count += 1
        prefix = self.evidence / ("adapter-%04d-%s" % (self.count, operation))
        deadline = min(self.ends, time.monotonic() + OP_SECONDS)
        if operation == "instrument":
            deadline = self.ends - 60
        request = {"operation": operation, "payload": payload,
                   "plan": asdict(self.plan), "preflight": self.preflight,
                   "parent_pid": os.getpid(),
                   "adb": self.adb, "deadline": deadline, "global_deadline": self.ends,
                   "output_bound": self.output_bound, "prefix": str(prefix)}
        encoded = json.dumps(request, allow_nan=False).encode()
        require(len(encoded) <= CONTROL_BOUND, "request exceeds bound")
        path = Path(str(prefix) + ".request.json")
        with path.open("xb") as stream:
            stream.write(encoded)
        return WorkerHandle([sys.executable, "-B", str(Path(__file__).with_name("adapter_worker.py")),
                             "--execute", "--request", str(path)], prefix, deadline + 0.5, operation, self.plan)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute", action="store_true", help="explicit assigned runtime only; never used in authoring")
    for name in ("manifest", "lease", "binding", "method"):
        parser.add_argument("--" + name, required=True)
    for name in ("preflight", "adb", "evidence"):
        parser.add_argument("--" + name)
    args = parser.parse_args(argv)
    try:
        plan = Plan(args.method, *(json.loads(read_file(str(Path(getattr(args, key)).resolve())))
                                  for key in ("manifest", "lease", "binding")))
        require(all(isinstance(value, dict) for value in (plan.manifest, plan.lease, plan.binding)),
                "evidence objects required")
        plan.validate(time.monotonic())
        if not args.execute:
            print(json.dumps(plan.describe(), indent=2))
            return 0
        require(all((args.preflight, args.adb, args.evidence)), "review/final-artifact/lease preflight, adb and evidence required")
        preflight = json.loads(read_file(str(Path(args.preflight).resolve())))
        verify_inputs(plan, preflight, time.monotonic() + 900, args.adb)
        adapter = AndroidAdapter(plan, preflight=preflight, adb=args.adb, evidence=args.evidence, execute=True)
        result = Controller(plan, adapter).run()
        print(json.dumps(asdict(result), indent=2))
        # No final semantic contract exists. CLI cannot report an acceptance pass.
        return 1
    except (ValueError, OSError, TypeError, KeyError) as exc:
        parser.exit(2, "REFUSED: " + str(exc) + "\n")


if __name__ == "__main__":
    sys.exit(main())
