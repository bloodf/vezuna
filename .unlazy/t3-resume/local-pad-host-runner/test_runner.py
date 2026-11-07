"""Synthetic verification only. No device commands or real subprocesses."""
from contextlib import redirect_stdout, redirect_stderr
from copy import deepcopy
from dataclasses import asdict, replace
import io
import json
from pathlib import Path
import unittest
from unittest.mock import patch

import runner as r


class Clock:
    def __init__(self):
        self.value = 1000.0

    def now(self):
        return self.value

    def sleep(self, seconds):
        self.value += seconds


def plan():
    # Deliberately invented SYNTHETIC inputs, never device/preflight evidence.
    evidence = {"path": "SYNTHETIC_NOT_RUNTIME", "sha256": "0" * 64}
    manifest = dict(serial=r.SERIAL, target=r.APP, test=r.TEST,
                    instrumentation_target=r.APP, runner="androidx.test.runner.AndroidJUnitRunner",
                    target_uid=11001, test_uid=11002, app_vezuna_uid=11003, execution_uid=11001,
                    owned_install=True, pristine_install=True, no_shared_uid=True,
                    verified=True, apk_identity=evidence, installed_identity=evidence,
                    ownership=evidence, owner="SYNTHETIC_OWNER")
    lease = dict(serial=r.SERIAL, exclusive=True, active=True, id="SYNTHETIC_LEASE",
                 owner="SYNTHETIC_OWNER", evidence=evidence, expires_monotonic=3000.0)
    binding = dict(source="device_open", fresh=True, evidence=evidence, serial=r.SERIAL,
                   lease_id="SYNTHETIC_LEASE", host_id="SYNTHETIC_HOST", device_id="SYNTHETIC_DEVICE",
                   command=["/SYNTHETIC_NOT_EXECUTABLE/agent-device"])
    return r.Plan("app.vezuna.SyntheticContract#syntheticMethod", manifest, lease, binding)


IDENTITY = r.Identity(r.APP, r.APP, 11001, 321, 98765)


def snapshot(identities=(), **overrides):
    return replace(r.Snapshot(r.SERIAL, r.PACKAGES, True, tuple(identities), 11001), **overrides)


class Child:
    def __init__(self, clock, *, delay=0, answer=None, ignore_term=False, late_poll=False):
        self.clock, self.ready = clock, clock.now() + delay
        self.answer = answer if answer is not None else r.Completion(True)
        self.ignore_term, self.late_poll = ignore_term, late_poll
        self.terms = 0
        self.terminated = False

    def poll(self):
        if self.late_poll:
            self.late_poll = False
            self.clock.value = 1841.0
            return r.Completion(True)
        if self.terminated:
            return r.Completion(False, error="SYNTHETIC TERM")
        return self.answer if self.clock.now() >= self.ready else None

    def terminate(self):
        if not self.terms:
            self.terms += 1
            if not self.ignore_term:
                self.terminated = True


class Adapter:
    def __init__(self, clock, mode="normal"):
        self.clock, self.mode = clock, mode
        self.calls, self.children = [], []
        self.instrument, self.closed = None, False
        self.snapshots = 0
        self.custom_snapshot = None
        self.close_error = False
        self.diagnostic_hang = False
        self.lease_ok = True
        self.instrument_ok = True
        self.hang_operation = None

    def start(self, operation, payload):
        self.calls.append((operation, payload, self.clock.now()))
        answer, delay = r.Completion(True), 0
        if operation == "lease":
            answer = r.Completion(True, self.lease_ok)
        elif operation == "instrument":
            delay = .2 if self.mode == "normal" else float("inf")
            answer = r.Completion(self.instrument_ok, error="SYNTHETIC instrument failure")
        elif operation == "snapshot":
            self.snapshots += 1
            active = self.instrument is not None and not self.closed
            if self.mode == "normal" and self.instrument and self.clock.now() >= self.instrument.ready:
                active = False
            value = snapshot([IDENTITY] if active else [])
            if self.custom_snapshot:
                value = self.custom_snapshot(self.snapshots, value)
            answer = r.Completion(True, value)
        elif operation == "diagnostic":
            delay = float("inf") if self.diagnostic_hang else 0
        elif operation == "supported_close":
            assert payload == ["/SYNTHETIC_NOT_EXECUTABLE/agent-device", "close", r.APP]
            self.closed = not self.close_error
            answer = r.Completion(not self.close_error, error="SYNTHETIC close failure")
        else:
            raise AssertionError(operation)
        if operation == self.hang_operation:
            delay = float("inf")
        child = Child(self.clock, delay=delay, answer=answer,
                      ignore_term=self.mode == "unkillable_host" and operation == "instrument",
                      late_poll=self.mode == "late" and operation == "instrument")
        self.children.append(child)
        if operation == "instrument":
            self.instrument = child
        return child


class ControllerTests(unittest.TestCase):
    def run_case(self, mode="normal", setup=None, configured=None):
        clock = Clock()
        adapter = Adapter(clock, mode)
        if setup:
            setup(adapter)
        result = r.Controller(configured or plan(), adapter, monotonic=clock.now, sleep=clock.sleep).run()
        self.assertLessEqual(result.elapsed, r.GLOBAL_SECONDS + 1e-6)
        self.assertEqual(result.semantic_acceptance, "NOT_EVALUATED")
        return result, adapter

    def assert_no_close(self, adapter):
        self.assertNotIn("supported_close", [op for op, _, _ in adapter.calls])

    def test_normal_completion_is_transport_only_and_independent_absence(self):
        result, adapter = self.run_case()
        self.assertIsNone(result.primary)
        self.assertTrue(result.transport_complete)
        self.assertTrue(result.teardown_confirmed)
        self.assertTrue(result.release_lease)
        self.assert_no_close(adapter)
        self.assertEqual(sum(op == "instrument" for op, _, _ in adapter.calls), 1)
        self.assertEqual(adapter.snapshots, 3)

    def test_native_hang_deadline_recovery_is_failed_even_after_confirmed_close(self):
        result, adapter = self.run_case("native_hang")
        self.assertEqual(result.primary, "DEADLINE")
        self.assertTrue(result.deadline_failed)
        self.assertTrue(result.forced)
        self.assertTrue(result.teardown_confirmed)
        self.assertTrue(result.release_lease)
        self.assertFalse(result.transport_complete)
        ops = [op for op, _, _ in adapter.calls]
        close = ops.index("supported_close")
        self.assertEqual(ops[close - 2:close], ["lease", "snapshot"])
        self.assertEqual(ops[close + 1], "snapshot")
        self.assertEqual(adapter.instrument.terms, 1)

    def test_host_hang_after_native_exit_never_requires_close(self):
        def setup(a):
            a.custom_snapshot = lambda n, value: snapshot() if n >= 3 else value
        result, adapter = self.run_case("host_hang", setup)
        self.assertEqual(result.primary, "DEADLINE")
        self.assertTrue(result.release_lease)
        self.assertEqual(adapter.instrument.terms, 1)
        self.assert_no_close(adapter)

    def test_bounded_diagnostic_timeout_preserves_deadline(self):
        result, adapter = self.run_case("native_hang", lambda a: setattr(a, "diagnostic_hang", True))
        self.assertEqual(result.primary, "DEADLINE")
        self.assertIn("diagnostic: diagnostic timeout", result.cleanup_errors)
        self.assertTrue(result.teardown_confirmed)
        self.assertGreaterEqual(result.elapsed, 845)
        self.assertLess(result.elapsed, 846)
        diagnostic = [e for e in result.events if e.get("operation") == "diagnostic"]
        self.assertEqual(diagnostic[-1]["event"], "operation_timeout")

    def test_pid_reuse_immediately_before_close_retains_lease(self):
        def setup(a):
            a.custom_snapshot = lambda n, value: snapshot([replace(IDENTITY, start=98766)]) if n == 4 else value
        result, adapter = self.run_case("native_hang", setup)
        self.assertEqual(result.primary, "DEADLINE")
        self.assertFalse(result.release_lease)
        self.assertFalse(result.teardown_confirmed)
        self.assertTrue(any("identity changed" in e for e in result.cleanup_errors))
        self.assert_no_close(adapter)

    def test_pid_mismatch_on_reconciliation_never_closes(self):
        def setup(a):
            a.custom_snapshot = lambda n, value: snapshot([replace(IDENTITY, pid=322)]) if n >= 3 else value
        result, adapter = self.run_case("native_hang", setup)
        self.assertFalse(result.release_lease)
        self.assert_no_close(adapter)

    def test_target_uid_mismatch_never_closes(self):
        def setup(a):
            a.custom_snapshot = lambda n, value: snapshot([replace(IDENTITY, uid=11003)]) if n >= 2 else value
        result, adapter = self.run_case("native_hang", setup)
        self.assertEqual(result.primary, "target UID mismatch")
        self.assertFalse(result.release_lease)
        self.assert_no_close(adapter)

    def test_execution_uid_mismatch_never_launches(self):
        result, adapter = self.run_case(setup=lambda a: setattr(a, "custom_snapshot", lambda n, v: replace(v, execution_uid=11003)))
        self.assertEqual(result.primary, "target UID mismatch")
        self.assertNotIn("instrument", [op for op, _, _ in adapter.calls])
        self.assertFalse(result.release_lease)

    def test_close_failure_retains_lease_preserves_primary(self):
        result, adapter = self.run_case("native_hang", lambda a: setattr(a, "close_error", True))
        self.assertEqual(result.primary, "DEADLINE")
        self.assertFalse(result.release_lease)
        self.assertTrue(any("close failure" in e for e in result.cleanup_errors))
        self.assertEqual(sum(op == "supported_close" for op, _, _ in adapter.calls), 1)

    def test_absence_uncertainty_after_close_retains_lease(self):
        def setup(a):
            a.custom_snapshot = lambda n, v: replace(v, complete=False) if n >= 5 else v
        result, adapter = self.run_case("native_hang", setup)
        self.assertTrue(result.forced)
        self.assertFalse(result.release_lease)
        self.assertTrue(any("uncertainty" in e for e in result.cleanup_errors))

    def test_host_exit_with_remaining_process_is_not_success(self):
        def setup(a):
            a.custom_snapshot = lambda n, v: snapshot([IDENTITY]) if n >= 2 else v
        result, adapter = self.run_case(setup=setup)
        self.assertTrue(result.transport_complete)
        self.assertEqual(result.primary, "FORCED_TERMINATION")
        self.assertFalse(result.teardown_confirmed)
        self.assertFalse(result.release_lease)

    def test_late_success_after_work_deadline_cannot_erase_failure(self):
        result, adapter = self.run_case("late")
        self.assertEqual(result.primary, "DEADLINE")
        self.assertTrue(result.deadline_failed)
        self.assertFalse(result.transport_complete)

    def test_late_success_after_global_deadline_remains_unresolved(self):
        original = Child.poll
        def late(child):
            if child.late_poll:
                child.late_poll = False
                child.clock.value = 1900.0
                return r.Completion(True)
            return original(child)
        with patch.object(Child, "poll", late):
            result, adapter = self.run_case("late")
        self.assertEqual(result.primary, "DEADLINE")
        self.assertFalse(result.release_lease)
        self.assert_no_close(adapter)

    def test_unreaped_owned_host_retains_lease_even_if_device_absent(self):
        result, adapter = self.run_case("unkillable_host")
        self.assertEqual(result.primary, "DEADLINE")
        self.assertTrue(result.teardown_confirmed)
        self.assertFalse(result.release_lease)
        self.assertIn("owned host child unresolved", result.cleanup_errors)
        self.assertEqual(adapter.instrument.terms, 1)

    def test_primary_transport_failure_survives_cleanup_failure(self):
        def setup(a):
            a.instrument_ok = False
            a.custom_snapshot = lambda n, v: replace(v, complete=False) if n >= 3 else v
        result, adapter = self.run_case(setup=setup)
        self.assertIn("instrument host failed", result.primary)
        self.assertTrue(any("uncertainty" in e for e in result.cleanup_errors))
        self.assertFalse(result.release_lease)

    def test_unowned_preexisting_process_refuses_launch_and_close(self):
        result, adapter = self.run_case(setup=lambda a: setattr(a, "custom_snapshot", lambda n, v: snapshot([IDENTITY])))
        self.assertEqual(result.primary, "unowned preexisting process")
        self.assertNotIn("instrument", [op for op, _, _ in adapter.calls])
        self.assert_no_close(adapter)
        self.assertFalse(result.release_lease)

    def test_unknown_start_refuses_close(self):
        def setup(a):
            a.custom_snapshot = lambda n, v: snapshot([replace(IDENTITY, start=0)]) if n >= 2 else v
        result, adapter = self.run_case("native_hang", setup)
        self.assertIn("unknown PID/start", result.primary)
        self.assertFalse(result.release_lease)
        self.assert_no_close(adapter)

    def test_missing_identity_does_not_authorize_close(self):
        def setup(a):
            def snap(n, value):
                if n == 2:
                    a.instrument.ready = a.clock.now()
                    return snapshot()
                return snapshot([IDENTITY]) if n >= 3 else value
            a.custom_snapshot = snap
        result, adapter = self.run_case(setup=setup)
        self.assertEqual(result.primary, "identity not observed")
        self.assertFalse(result.release_lease)
        self.assert_no_close(adapter)

    def test_lost_lease_prevents_close(self):
        def setup(a):
            def snap(n, value):
                if n == 2:
                    a.lease_ok = False
                return value
            a.custom_snapshot = snap
        result, adapter = self.run_case("native_hang", setup)
        self.assertFalse(result.release_lease)
        self.assert_no_close(adapter)

    def test_incomplete_preflight_snapshot_refuses_launch(self):
        result, adapter = self.run_case(setup=lambda a: setattr(a, "custom_snapshot", lambda n, v: replace(v, complete=False)))
        self.assertEqual(result.primary, "absence/identity uncertainty")
        self.assertNotIn("instrument", [op for op, _, _ in adapter.calls])
        self.assertFalse(result.release_lease)
        self.assertEqual(adapter.snapshots, 1)

    def test_snapshot_timeout_is_bounded_and_not_retried(self):
        result, adapter = self.run_case(setup=lambda a: setattr(a, "hang_operation", "snapshot"))
        self.assertEqual(result.primary, "snapshot timeout")
        self.assertLess(result.elapsed, 6)
        self.assertFalse(result.release_lease)
        self.assertEqual(adapter.snapshots, 1)
        self.assert_no_close(adapter)

    def test_close_timeout_is_bounded_and_not_retried(self):
        result, adapter = self.run_case("native_hang", lambda a: setattr(a, "hang_operation", "supported_close"))
        self.assertEqual(result.primary, "DEADLINE")
        self.assertLess(result.elapsed, 846)
        self.assertFalse(result.release_lease)
        self.assertEqual(sum(op == "supported_close" for op, _, _ in adapter.calls), 1)

    def test_identity_uncertainty_cannot_be_cured_by_later_empty_probe(self):
        def setup(a):
            a.custom_snapshot = lambda n, value: replace(value, complete=False) if n == 2 else snapshot()
        result, adapter = self.run_case("native_hang", setup)
        self.assertEqual(result.primary, "absence/identity uncertainty")
        self.assertFalse(result.release_lease)
        self.assertEqual(adapter.snapshots, 2)
        self.assert_no_close(adapter)

    def test_new_package_subprocess_before_close_is_unowned(self):
        def setup(a):
            a.custom_snapshot = lambda n, value: snapshot([IDENTITY, replace(IDENTITY, name=r.APP + ":worker", pid=322)]) if n >= 3 else value
        result, adapter = self.run_case("native_hang", setup)
        self.assertFalse(result.release_lease)
        self.assert_no_close(adapter)

    def test_test_package_process_remaining_after_close_is_unresolved(self):
        test_process = r.Identity(r.TEST, r.TEST, 11002, 400, 99999)
        def setup(a):
            def snap(n, value):
                if n >= 5:
                    return snapshot([test_process])
                return snapshot([IDENTITY, test_process]) if n >= 2 else value
            a.custom_snapshot = snap
        result, adapter = self.run_case("native_hang", setup)
        self.assertTrue(result.forced)
        self.assertFalse(result.release_lease)
        self.assertTrue(any("process remains" in e for e in result.cleanup_errors))

    def test_wrong_snapshot_target_refuses_launch(self):
        def setup(a):
            a.custom_snapshot = lambda n, value: replace(value, packages=("app.vezuna",))
        result, adapter = self.run_case(setup=setup)
        self.assertEqual(result.primary, "snapshot target mismatch")
        self.assertNotIn("instrument", [op for op, _, _ in adapter.calls])
        self.assert_no_close(adapter)


class BoundaryTests(unittest.TestCase):
    def test_plan_rejects_each_missing_or_wrong_boundary(self):
        mutations = [
            ("manifest", "target", "app.vezuna"), ("manifest", "serial", "OTHER"),
            ("manifest", "target_uid", None), ("manifest", "execution_uid", 11002),
            ("manifest", "target_uid", 11003), ("manifest", "owned_install", False),
            ("manifest", "ownership", None), ("manifest", "apk_identity", None),
            ("manifest", "no_shared_uid", False), ("manifest", "pristine_install", False),
            ("lease", "exclusive", False), ("lease", "active", False),
            ("lease", "expires_monotonic", 1800), ("lease", "evidence", None),
            ("lease", "expires_monotonic", float("nan")),
            ("binding", "fresh", False), ("binding", "evidence", None),
            ("binding", "lease_id", "OTHER"), ("binding", "source", "old-help"),
            ("binding", "command", ["/" + r.DEAD_BINDING + "/agent-device"]),
            ("binding", "command", ["/x/agent-device", "--unsafe"]),
        ]
        for section, key, value in mutations:
            with self.subTest(section=section, key=key, value=value):
                configured = deepcopy(plan())
                getattr(configured, section)[key] = value
                with self.assertRaises(r.Refusal):
                    configured.validate(1000)

    def test_exact_named_method_only(self):
        for value in ("", "a#b", "app.vezuna.Test", "app.vezuna.Test#m,app.vezuna.Test#n",
                      "app.vezuna.Test#m --extra", "app.vezuna.Test#m\n", "app.vezuna.Test#*"):
            with self.subTest(value=value), self.assertRaises(r.Refusal):
                replace(plan(), method=value).validate(1000)

    def test_process_start_parser_with_spaces_and_parentheses(self):
        for comm in ("app.vezuna.padtest", "a worker (native) thread", "a ) ( b ))"):
            raw = "321 (" + comm + ") S " + " ".join(["0"] * 18 + ["98765", "1234"])
            self.assertEqual(r.parse_proc_stat(raw), (321, comm, 98765))

    def test_process_start_parser_refuses_unknown(self):
        for raw in ("", "321 (a) S 0", "321 a S", "0 (a) S " + "0 " * 20,
                    "321 (a) ? " + "1 " * 20, "321 (a) S " + "0 " * 20):
            with self.subTest(raw=raw), self.assertRaises(r.Refusal):
                r.parse_proc_stat(raw)

    def test_only_created_popen_is_signalled_no_wait_no_kill(self):
        fake = unittest.mock.Mock()
        fake.poll.side_effect = [None, None]
        with patch.object(r.subprocess, "Popen", return_value=fake) as spawn:
            child = r.OwnedPopen(["SYNTHETIC_HOST_COMMAND"], stdout=None, stderr=None)
            child.terminate()
            child.terminate()
            fake.terminate.assert_called_once()
            fake.wait.assert_not_called()
            fake.kill.assert_not_called()
            self.assertFalse(spawn.call_args.kwargs["shell"])

    def test_popen_pipe_is_refused_before_spawn(self):
        with patch.object(r.subprocess, "Popen") as spawn, self.assertRaises(r.Refusal):
            r.OwnedPopen(["SYNTHETIC"], stdout=r.subprocess.PIPE, stderr=None)
        spawn.assert_not_called()

    def test_cli_missing_evidence_and_execute_are_refused(self):
        for args in (["plan"], ["execute"]):
            with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
                r.main(args)
            self.assertEqual(error.exception.code, 2)

    def test_cli_emits_only_inert_plan_with_explicit_inputs(self):
        configured = plan()
        payloads = {name: json.dumps(getattr(configured, name)) for name in ("manifest", "lease", "binding")}
        with patch.object(Path, "read_text", lambda p: payloads[str(p)]), patch.object(r.time, "monotonic", return_value=1000), patch.object(r.subprocess, "Popen") as spawn:
            output = io.StringIO()
            with redirect_stdout(output):
                r.main(["plan", "--method", configured.method, "--manifest", "manifest",
                        "--lease", "lease", "--binding", "binding"])
            value = json.loads(output.getvalue())
            self.assertEqual(value["mode"], "PLAN_ONLY_NO_DEVICE_EXECUTION")
            self.assertEqual(value["supported_close_arguments"][-1], r.APP)
            spawn.assert_not_called()


if __name__ == "__main__":
    unittest.main(verbosity=2)
