"""Owned finite adapter worker. No device operation runs on import or by default."""
from dataclasses import asdict
import argparse
import hashlib
import json
import os
from pathlib import Path
import selectors
import shlex
import signal
import subprocess
import time

from android_adapter import (CONTROL_BOUND, OUTPUT_BOUND, read_file, verify_inputs,
                             lease_current, READY)
from android_snapshot import BoundedAdbReader, inspect_snapshot
from runner import APP, TEST, PACKAGES, SERIAL, Plan, Identity, Refusal, require

STOPPED = False
PARENT_PID = None


def stop_requested(signum, frame):
    # Do not raise inside Popen creation. Every loop and bounded read checks this.
    global STOPPED
    STOPPED = True


def check(plan, end, global_end):
    require(not STOPPED, "worker terminated; remote exit unresolved")
    require(PARENT_PID is None or os.getppid() == PARENT_PID,
            "controller parent lost; remote exit unresolved")
    require(time.monotonic() < end, "operation deadline; remote exit unresolved")
    lease_current(plan, global_end)
    require(time.monotonic() < end and not STOPPED,
            "operation deadline or worker termination after lease read; remote exit unresolved")


def capture(argv, prefix, *, deadline, maximum, guard):
    """Preserve <=maximum aggregate bytes plus one detecting byte, including failures.

    Only our direct Popen child receives TERM/KILL here. The adapter parent owns
    the worker's session and cleans up same-group descendants before reaping it.
    Host exit is transport evidence only; it never proves device/native exit.
    """
    output = {}
    hashes = {key: hashlib.sha256() for key in ("stdout", "stderr")}
    sizes = {key: 0 for key in hashes}
    total = 0
    child = None
    error = None
    code = None
    started = time.monotonic()
    try:
        for label in hashes:
            output[label] = Path(str(prefix) + "." + label).open("xb")
        guard()
        child = subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                 stderr=subprocess.PIPE, shell=False)
        with selectors.DefaultSelector() as selector:
            for stream, label in ((child.stdout, "stdout"), (child.stderr, "stderr")):
                os.set_blocking(stream.fileno(), False)
                selector.register(stream, selectors.EVENT_READ, label)
            while selector.get_map() or child.poll() is None:
                guard()
                require(time.monotonic() < deadline, "command timeout; remote exit unresolved")
                for key, _ in selector.select(min(0.05, max(0, deadline - time.monotonic()))):
                    data = os.read(key.fileobj.fileno(), min(65536, maximum - total + 1))
                    if not data:
                        selector.unregister(key.fileobj)
                        continue
                    output[key.data].write(data)
                    output[key.data].flush()
                    hashes[key.data].update(data)
                    sizes[key.data] += len(data)
                    total += len(data)
                    require(total <= maximum, "aggregate command output overflow")
            require(time.monotonic() < deadline, "late command completion")
            guard()
            code = child.returncode
            require(code == 0, "host command exit " + str(code) + "; remote exit unresolved")
    except BaseException as exc:
        error = str(exc)
        raise
    finally:
        try:
            if child is not None:
                if child.poll() is None:
                    child.terminate()
                    try:
                        child.wait(timeout=0.25)
                    except subprocess.TimeoutExpired:
                        child.kill()
                        child.wait(timeout=0.25)
                code = child.returncode
                child.stdout.close()
                child.stderr.close()
        finally:
            for sink in output.values():
                sink.close()
            metadata = {"argv": argv, "host_returncode": code, "error": error,
                        "total_bytes": total, "maximum_bytes": maximum,
                        "overflow": total > maximum, "capture_partial": error is not None,
                        "started_monotonic": started,
                        "ended_monotonic": time.monotonic(),
                        "streams": {key: {"bytes": sizes[key], "sha256": hashes[key].hexdigest()}
                                    for key in hashes}, "semantic_acceptance": "NOT_EVALUATED"}
            with Path(str(prefix) + ".capture.json").open("x") as stream:
                json.dump(metadata, stream, indent=2, allow_nan=False)
    return {"host_returncode": code, "transport_complete": True,
            "semantic_acceptance": "NOT_EVALUATED", "capture": str(prefix) + ".capture.json"}


def perform(request):
    plan = Plan(**request["plan"])
    # Validate schema/consistency without moving the original run's lease bound.
    plan.validate(request["global_deadline"] - 900)
    end, global_end = request["deadline"], request["global_deadline"]
    require(type(end) in (int, float) and type(global_end) in (int, float)
            and time.monotonic() < end <= global_end, "invalid worker deadline")
    maximum = request["output_bound"]
    require(type(maximum) is int and 0 < maximum <= OUTPUT_BOUND, "invalid aggregate output bound")
    verify_inputs(plan, request["preflight"], global_end, request["adb"])
    guard = lambda: check(plan, end, global_end)
    guard()
    operation, payload = request["operation"], request["payload"]
    prefix = Path(request["prefix"])
    expected = {APP: plan.manifest["target_uid"], TEST: plan.manifest["test_uid"]}
    read_bytes = 0

    def read(argv):
        nonlocal read_bytes
        guard()
        # Existing frozen reader, <=0.75s read + <=0.5s direct-child reap.
        # Deferred TERM therefore reaches the next guard within <=1.25s.
        result = BoundedAdbReader(request["adb"], min(end, time.monotonic() + 0.75))(argv)
        read_bytes += len(result.encode("utf-8"))
        require(read_bytes <= maximum, "aggregate snapshot output overflow")
        guard()
        return result

    if operation == "lease":
        require(payload == plan.lease["id"], "lease payload mismatch")
        return True
    if operation == "snapshot":
        require(payload == {"packages": list(PACKAGES)}, "snapshot payload mismatch")
        return asdict(inspect_snapshot(read, expected))
    if operation == "diagnostic":
        require(isinstance(payload, dict) and isinstance(payload.get("identities"), list)
                and bool(payload["identities"]), "diagnostic owned identity missing")
        owned = tuple(Identity(**x) for x in payload["identities"])
        current = inspect_snapshot(read, expected)
        require(set(current.identities) == set(owned), "diagnostic identity changed/uncertain")
        # Identity-only diagnostic. No all-app logcat, health data, model or UI.
        return asdict(current)
    if operation == "instrument":
        require(payload == plan.describe(), "instrument payload mismatch")
        arguments = plan.describe()["instrumentation_arguments"]
        # adb shell joins remote argv through a shell. Quote the single exact
        # class#method, including legal Java '$', instead of allowing expansion.
        arguments[4] = shlex.quote(plan.method)
        argv = [request["adb"], "-s", SERIAL, "shell", "am", "instrument",
                *arguments]
    elif operation == "supported_close":
        argv = plan.binding["command"] + ["close", APP]
        require(payload == argv, "close target/binding mismatch")
        # Controller alone performs the immediate identity recheck. No device
        # query here can intervene between that recheck and this exact close.
    else:
        raise Refusal("unsupported worker operation")
    return capture(argv, prefix, deadline=end, maximum=maximum, guard=guard)


def main(argv=None):
    global PARENT_PID
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--request", required=True)
    args = parser.parse_args(argv)
    require(args.execute, "worker runtime launch requires explicit opt-in")
    require(os.getsid(0) == os.getpid() and os.getpgrp() == os.getpid(),
            "worker requires its exclusively owned host session")
    signal.signal(signal.SIGTERM, stop_requested)
    signal.signal(signal.SIGINT, stop_requested)
    request = json.loads(read_file(str(Path(args.request).resolve())))
    PARENT_PID = request["parent_pid"]
    require(type(PARENT_PID) is int and PARENT_PID > 1 and os.getppid() == PARENT_PID,
            "worker controller parent mismatch")
    prefix = Path(request["prefix"])
    try:
        value = perform(request)
        result = {"ok": True, "value": value}
    except (ValueError, OSError, TypeError, KeyError, subprocess.SubprocessError) as exc:
        result = {"ok": False, "error": str(exc), "remote_exit": "UNRESOLVED"}
    encoded = json.dumps(result, allow_nan=False).encode()
    require(len(encoded) <= CONTROL_BOUND, "worker result overflow")
    # Exclusive result creation preserves every previous attempt. The ready
    # notification is sent only after closing the completed receipt.
    with Path(str(prefix) + ".result.json").open("xb") as stream:
        stream.write(encoded)
    os.write(1, READY)
    # Keep our session leader alive until the parent cleans the whole owned
    # host group. Parent then reaps us; SIGKILL here is the deliberate handoff,
    # never instrumentation exit or semantic acceptance.
    while time.monotonic() < request["global_deadline"] and os.getppid() == PARENT_PID:
        time.sleep(0.05)
    # Parent death or its final bound must not leave a live orphan helper.
    # This is our own verified session, never a device PID/session.
    os.killpg(os.getpid(), signal.SIGKILL)


if __name__ == "__main__":
    main()
