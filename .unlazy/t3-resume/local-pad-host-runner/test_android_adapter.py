"""SYNTHETIC controls. Only local Python children; never execute adb/agent-device.

All fixtures and first-failure output remain in a new adapter-* evidence folder.
Device argv is inspected through mocks; no fake adb executable is invoked.
"""
from dataclasses import asdict
import hashlib
import json
import os
from pathlib import Path
import signal
import shlex
import subprocess
import sys
import time
import unittest
from unittest.mock import patch

import android_adapter as adapter
import adapter_worker as worker
from runner import APP, TEST, PACKAGES, SERIAL, Plan, Identity, Refusal

HERE = Path(__file__).resolve().parent
EVIDENCE = Path(os.environ.get("ADAPTER_TEST_EVIDENCE", str(HERE / ("adapter-controls-" + str(time.time_ns())))))


class AdapterControls(unittest.TestCase):
    def setUp(self):
        self.directory = EVIDENCE / self.id().split(".")[-1]
        self.directory.mkdir(parents=True, exist_ok=False)
        self.number = 0
        worker.STOPPED = False
        worker.PARENT_PID = None
        lease = {"id": "SYNTHETIC-LEASE", "owner": "SYNTHETIC-OWNER", "serial": SERIAL,
                 "active": True, "exclusive": True, "expires_monotonic": time.monotonic() + 5000.0}
        lease["evidence"] = self.receipt({**lease, "synthetic": True})
        binding = {"source": "device_open", "fresh": True, "serial": SERIAL,
                   "lease_id": lease["id"], "host_id": "SYNTHETIC-HOST", "device_id": "SYNTHETIC-DEVICE",
                   "command": ["/SYNTHETIC-NEVER-EXECUTED/agent-device"]}
        binding["evidence"] = self.receipt({**binding, "synthetic": True,
                                             "supported_close_argv": binding["command"] + ["close", APP]})
        manifest = {"serial": SERIAL, "target": APP, "test": TEST, "instrumentation_target": APP,
                    "runner": "androidx.test.runner.AndroidJUnitRunner", "target_uid": 10101,
                    "test_uid": 10102, "app_vezuna_uid": 10103, "execution_uid": 10101,
                    "owner": lease["owner"], "owned_install": True, "pristine_install": True,
                    "no_shared_uid": True, "verified": True}
        for key in ("apk_identity", "installed_identity", "ownership"):
            manifest[key] = self.receipt({"synthetic": True, "kind": key})
        self.plan = Plan("app.vezuna.SyntheticControl#notARuntimeAcceptanceMethod", manifest, lease, binding)
        self.preflight = self.make_preflight()
        self.end = time.monotonic() + 900

    def receipt(self, value):
        self.number += 1
        path = self.directory / ("synthetic-receipt-%02d.json" % self.number)
        data = json.dumps(value).encode()
        path.write_bytes(data)
        return {"path": str(path), "sha256": hashlib.sha256(data).hexdigest()}

    def make_preflight(self):
        context = {"serial": SERIAL, "lease_id": self.plan.lease["id"], "method": self.plan.method,
                   "manifest_sha256": adapter.digest(self.plan.manifest),
                   "binding_sha256": adapter.digest(self.plan.binding),
                   "implementation_sha256": adapter.implementation_hashes(),
                   "adb": "/SYNTHETIC-NEVER-EXECUTED/adb"}
        result = {"schema": adapter.SCHEMA, **context}
        for key in ("review", "final_artifact", "lease_preflight"):
            result[key] = self.receipt({**context, "kind": key, "synthetic": True,
                                        "decision": "APPROVED_FOR_ASSIGNED_RUNTIME"})
        return result

    def configured(self, **kwargs):
        return adapter.AndroidAdapter(self.plan, preflight=self.preflight,
                                      adb="/SYNTHETIC-NEVER-EXECUTED/adb",
                                      evidence=str(self.directory), execute=True, **kwargs)

    def request(self, operation, payload):
        return {"plan": asdict(self.plan), "preflight": self.preflight, "operation": operation,
                "payload": payload, "adb": "/SYNTHETIC-NEVER-EXECUTED/adb",
                "prefix": str(self.directory / "adapter-operation"),
                "deadline": time.monotonic() + 2, "global_deadline": self.end, "output_bound": 4096}

    def wait(self, handle, seconds=5):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            result = handle.poll()
            if result is not None:
                return result
            time.sleep(0.01)
        handle.terminate()
        self.fail("own local Python worker did not reap within control deadline")

    def fake_handle(self, source, *, timeout=2, operation="lease"):
        path = self.directory / "adapter-fake-worker.py"
        path.write_text(source)
        prefix = self.directory / "adapter-fake"
        return adapter.WorkerHandle([sys.executable, "-B", str(path), str(prefix)], prefix,
                                    time.monotonic() + timeout, operation, self.plan)

    def handoff(self):
        return "\nimport os,signal\nos.write(1," + repr(adapter.READY) + ")\nwhile True: signal.pause()\n"

    def test_receipts_valid_on_disk(self):
        adapter.verify_inputs(self.plan, self.preflight, self.end)

    def test_missing_receipt_refused_despite_true_flags(self):
        self.plan.manifest["installed_identity"]["path"] = str(self.directory / "adapter-deliberately-missing")
        self.preflight = self.make_preflight()
        with self.assertRaises(FileNotFoundError):
            adapter.verify_inputs(self.plan, self.preflight, self.end)

    def test_receipt_hash_mismatch(self):
        Path(self.plan.manifest["ownership"]["path"]).write_text("changed after review")
        with self.assertRaisesRegex(Refusal, "SHA256"):
            adapter.verify_inputs(self.plan, self.preflight, self.end)

    def test_symlink_receipt_refused(self):
        original = self.plan.manifest["ownership"]
        link = self.directory / "adapter-symlink"
        link.symlink_to(original["path"])
        with self.assertRaises(OSError):
            adapter.hashed_receipt({**original, "path": str(link)})

    def test_receipt_special_file_refused_without_blocking(self):
        fifo = self.directory / "adapter-fifo"
        os.mkfifo(fifo)
        with self.assertRaisesRegex(Refusal, "size/type"):
            adapter.read_file(str(fifo))

    def test_receipt_size_bound(self):
        path = self.directory / "adapter-large"
        path.write_bytes(b"x" * (adapter.CONTROL_BOUND + 1))
        with self.assertRaisesRegex(Refusal, "size/type"):
            adapter.read_file(str(path))

    def test_executable_path_cannot_drift_from_preflight(self):
        with self.assertRaisesRegex(Refusal, "adb/preflight"):
            adapter.verify_inputs(self.plan, self.preflight, self.end, "/OTHER/adb")

    def test_implementation_hash_drift_refused(self):
        self.preflight["implementation_sha256"]["android_adapter.py"] = "0" * 64
        with self.assertRaisesRegex(Refusal, "implementation_sha256"):
            adapter.verify_inputs(self.plan, self.preflight, self.end)

    def test_current_lease_fields_checked(self):
        self.plan.lease["evidence"] = self.receipt({**self.plan.lease, "owner": "OTHER"})
        with self.assertRaisesRegex(Refusal, "current lease mismatch: owner"):
            adapter.lease_current(self.plan, self.end)

    def test_controller_parent_loss_refused(self):
        with patch.object(worker, "PARENT_PID", 4242), patch.object(worker.os, "getppid", return_value=4243):
            with self.assertRaisesRegex(Refusal, "controller parent lost"):
                worker.check(self.plan, self.end - 60, self.end)

    def test_lease_read_cannot_restore_expired_operation(self):
        end = time.monotonic() + 0.01
        with patch.object(worker, "lease_current", side_effect=lambda *args: time.sleep(0.02)):
            with self.assertRaisesRegex(Refusal, "operation deadline"):
                worker.check(self.plan, end, self.end)

    def test_lost_lease_checked(self):
        self.plan.lease["evidence"] = self.receipt({**self.plan.lease, "active": False})
        with self.assertRaisesRegex(Refusal, "current lease mismatch: active"):
            adapter.lease_current(self.plan, self.end)

    def test_expired_lease_refused(self):
        self.plan.lease["expires_monotonic"] = time.monotonic() - 1
        self.plan.lease["evidence"] = self.receipt(self.plan.lease)
        with self.assertRaisesRegex(Refusal, "expired"):
            adapter.lease_current(self.plan, self.end)

    def test_binding_contents_must_match(self):
        data = {**self.plan.binding, "device_id": "OTHER", "supported_close_argv": self.plan.binding["command"] + ["close", APP]}
        self.plan.binding["evidence"] = self.receipt(data)
        self.preflight = self.make_preflight()
        with self.assertRaisesRegex(Refusal, "binding receipt mismatch: device_id"):
            adapter.verify_inputs(self.plan, self.preflight, self.end)

    def test_binding_id_cannot_be_a_flag(self):
        self.plan.binding["device_id"] = True
        with self.assertRaisesRegex(Refusal, "actual binding ID"):
            adapter.verify_inputs(self.plan, self.preflight, self.end)

    def test_lease_owner_cannot_be_a_flag(self):
        self.plan.manifest["owner"] = True
        with self.assertRaisesRegex(Refusal, "actual lease owner"):
            adapter.verify_inputs(self.plan, self.preflight, self.end)

    def test_historical_binding_rejected(self):
        self.plan.binding["command"] = ["/t3-580cfea7980dc309024b79ff/agent-device"]
        with self.assertRaisesRegex(Refusal, "fresh absolute"):
            self.configured()

    def test_supported_close_help_receipt_required(self):
        self.plan.binding["evidence"] = self.receipt(self.plan.binding)
        self.preflight = self.make_preflight()
        with self.assertRaisesRegex(Refusal, "supported close"):
            adapter.verify_inputs(self.plan, self.preflight, self.end)

    def test_missing_review_preflight_refused(self):
        del self.preflight["review"]
        with self.assertRaisesRegex(Refusal, "missing on-disk receipt"):
            adapter.verify_inputs(self.plan, self.preflight, self.end)

    def test_preflight_method_cannot_drift(self):
        self.preflight["method"] += "Other"
        with self.assertRaisesRegex(Refusal, "context mismatch: method"):
            adapter.verify_inputs(self.plan, self.preflight, self.end)

    def test_authoring_review_cannot_authorize_runtime(self):
        self.preflight["review"] = self.receipt({"kind": "review", "decision": "APPROVE_BOUNDED_AUTHORING"})
        with self.assertRaisesRegex(Refusal, "not approved"):
            adapter.verify_inputs(self.plan, self.preflight, self.end)

    def test_constructor_default_inert(self):
        with patch.object(subprocess, "Popen") as popen:
            with self.assertRaisesRegex(Refusal, "opt-in"):
                adapter.AndroidAdapter(self.plan, preflight={}, adb="/never", evidence=str(self.directory))
            popen.assert_not_called()

    def test_cli_default_plan_does_not_spawn(self):
        paths = {}
        for key in ("manifest", "lease", "binding"):
            path = self.directory / ("adapter-" + key + ".json")
            path.write_text(json.dumps(getattr(self.plan, key)))
            paths[key] = str(path)
        argv = ["--method", self.plan.method]
        for key, path in paths.items():
            argv.extend(["--" + key, path])
        with patch.object(subprocess, "Popen") as popen, patch("builtins.print") as output:
            self.assertEqual(adapter.main(argv), 0)
            popen.assert_not_called()
            self.assertIn("PLAN_ONLY_NO_DEVICE_EXECUTION", output.call_args.args[0])

    def test_invalid_exact_method_rejected(self):
        for method in ("app.vezuna.Test#one,two", "app.vezuna.Test#*", "app.vezuna.Test#one --evil"):
            with self.subTest(method=method):
                with self.assertRaises(Refusal):
                    Plan(method, self.plan.manifest, self.plan.lease, self.plan.binding).validate(time.monotonic())

    def test_worker_launcher_argv_and_receipt(self):
        with patch.object(adapter, "WorkerHandle") as handle:
            self.configured().start("lease", self.plan.lease["id"])
            argv = handle.call_args.args[0]
            self.assertEqual(argv[:4], [sys.executable, "-B", str(HERE / "adapter_worker.py"), "--execute"])
            self.assertEqual(argv[4], "--request")
            data = json.loads(Path(argv[5]).read_text())
            self.assertEqual(data["payload"], "SYNTHETIC-LEASE")
            self.assertLess(data["deadline"] - time.monotonic(), adapter.OP_SECONDS)

    def test_instrument_argv_exact_no_semantic_pass(self):
        with patch.object(worker, "capture", return_value={"transport_complete": True, "semantic_acceptance": "NOT_EVALUATED"}) as capture:
            result = worker.perform(self.request("instrument", self.plan.describe()))
        self.assertEqual(capture.call_args.args[0], ["/SYNTHETIC-NEVER-EXECUTED/adb", "-s", SERIAL,
                         "shell", "am", "instrument", "-w", "-r", "-e", "class", shlex.quote(self.plan.method),
                         TEST + "/androidx.test.runner.AndroidJUnitRunner"])
        self.assertEqual(result["semantic_acceptance"], "NOT_EVALUATED")

    def test_remote_shell_method_preserves_dollar_identifiers(self):
        self.plan = Plan("app.vezuna.Synthetic$Nested#method$Name", self.plan.manifest,
                         self.plan.lease, self.plan.binding)
        self.preflight = self.make_preflight()
        with patch.object(worker, "capture", return_value=True) as capture:
            worker.perform(self.request("instrument", self.plan.describe()))
        remote = shlex.split(" ".join(capture.call_args.args[0][4:]))
        self.assertEqual(remote[remote.index("class") + 1], self.plan.method)

    def test_close_argv_exact_no_device_probe(self):
        argv = self.plan.binding["command"] + ["close", APP]
        with patch.object(worker, "capture", return_value=True) as capture, patch.object(worker, "BoundedAdbReader") as reader:
            self.assertTrue(worker.perform(self.request("supported_close", argv)))
        self.assertEqual(capture.call_args.args[0], argv)
        reader.assert_not_called()

    def test_close_other_package_rejected(self):
        with patch.object(adapter, "WorkerHandle") as handle:
            with self.assertRaises(Refusal):
                self.configured().start("supported_close", self.plan.binding["command"] + ["close", "app.vezuna"])
            handle.assert_not_called()

    def test_no_instrument_relaunch_or_close_retry(self):
        configured = self.configured()
        with patch.object(adapter, "WorkerHandle"):
            configured.start("instrument", self.plan.describe())
            with self.assertRaises(Refusal):
                configured.start("instrument", self.plan.describe())
            command = self.plan.binding["command"] + ["close", APP]
            configured.start("supported_close", command)
            with self.assertRaises(Refusal):
                configured.start("supported_close", command)

    def test_diagnostic_unknown_identity_refused(self):
        with patch.object(adapter, "WorkerHandle") as handle:
            with self.assertRaisesRegex(Refusal, "unknown"):
                self.configured().start("diagnostic", {"identities": None})
            handle.assert_not_called()

    def test_diagnostic_cannot_read_other_package(self):
        identity = Identity("app.vezuna", "app.vezuna", 10103, 42, 100)
        with patch.object(adapter, "WorkerHandle") as handle:
            with self.assertRaises(Refusal):
                self.configured().start("diagnostic", {"identities": [identity]})
            handle.assert_not_called()

    def test_snapshot_uses_existing_reader_and_inspector(self):
        from runner import Snapshot
        expected = Snapshot(SERIAL, PACKAGES, True, (), 10101)
        with patch.object(worker, "BoundedAdbReader", return_value=lambda argv: "SYNTHETIC") as reader:
            with patch.object(worker, "inspect_snapshot", side_effect=lambda read, uids: (read(["ps"]), expected)[1]) as inspect:
                result = worker.perform(self.request("snapshot", {"packages": list(PACKAGES)}))
        self.assertEqual(result, asdict(expected))
        self.assertEqual(inspect.call_args.args[1], {APP: 10101, TEST: 10102})
        self.assertEqual(reader.call_args.args[0], "/SYNTHETIC-NEVER-EXECUTED/adb")

    def test_failed_snapshot_never_means_absent(self):
        with patch.object(worker, "BoundedAdbReader", return_value=lambda argv: (_ for _ in ()).throw(Refusal("unreachable"))):
            with self.assertRaisesRegex(Refusal, "unreachable"):
                worker.perform(self.request("snapshot", {"packages": list(PACKAGES)}))

    def test_snapshot_aggregate_overflow_refused(self):
        request = self.request("snapshot", {"packages": list(PACKAGES)})
        request["output_bound"] = 10
        def inspect(read, expected):
            read(["ps"])
            read(["ps"])
        with patch.object(worker, "BoundedAdbReader", return_value=lambda argv: "123456"):
            with patch.object(worker, "inspect_snapshot", side_effect=inspect):
                with self.assertRaisesRegex(Refusal, "aggregate snapshot"):
                    worker.perform(request)

    def test_unknown_diagnostic_never_invokes_reader(self):
        with patch.object(worker, "BoundedAdbReader") as reader:
            with self.assertRaisesRegex(Refusal, "identity missing"):
                worker.perform(self.request("diagnostic", {"identities": []}))
            reader.assert_not_called()

    def test_controller_identity_mismatch_still_refuses_close(self):
        from runner import Controller, Completion, Snapshot
        calls = []
        good = Identity(APP, APP, 10101, 123, 456)
        wrong = Identity(APP, APP, 10101, 123, 457)
        class Immediate:
            def __init__(self, answer): self.answer = answer
            def poll(self): return self.answer
            def terminate(self): raise AssertionError("completed child must not be signalled")
        class Fake:
            count = 0
            def start(inner, operation, payload=None):
                calls.append(operation)
                if operation == "snapshot":
                    inner.count += 1
                    identities = () if inner.count == 1 else ((good,) if inner.count == 2 else (wrong,))
                    return Immediate(Completion(True, Snapshot(SERIAL, PACKAGES, True, identities, 10101)))
                if operation == "instrument":
                    return Immediate(Completion(False, error="SYNTHETIC transport failure"))
                return Immediate(Completion(True, True))
        result = Controller(self.plan, Fake()).run()
        self.assertFalse(result.release_lease)
        self.assertIn("mismatch", " ".join(result.cleanup_errors))
        self.assertNotIn("supported_close", calls)

    def test_local_capture_normal_preserves_both_streams(self):
        prefix = self.directory / "adapter-capture"
        result = worker.capture([sys.executable, "-B", "-c", "import sys;sys.stdout.write('out');sys.stderr.write('err')"],
                                prefix, deadline=time.monotonic() + 2, maximum=6, guard=lambda: None)
        self.assertEqual(Path(str(prefix) + ".stdout").read_bytes(), b"out")
        self.assertEqual(Path(str(prefix) + ".stderr").read_bytes(), b"err")
        self.assertEqual(result["semantic_acceptance"], "NOT_EVALUATED")

    def test_local_capture_aggregate_overflow_preserved(self):
        prefix = self.directory / "adapter-overflow"
        with self.assertRaisesRegex(Refusal, "overflow"):
            worker.capture([sys.executable, "-B", "-c", "import os;os.write(1,b'a'*60);os.write(2,b'b'*60)"],
                           prefix, deadline=time.monotonic() + 2, maximum=100, guard=lambda: None)
        sizes = [Path(str(prefix) + "." + label).stat().st_size for label in ("stdout", "stderr")]
        self.assertEqual(sum(sizes), 101)
        self.assertTrue(json.loads(Path(str(prefix) + ".capture.json").read_text())["overflow"])

    def test_local_capture_timeout_reaps_own_child(self):
        prefix = self.directory / "adapter-timeout"
        with self.assertRaisesRegex(Refusal, "timeout"):
            worker.capture([sys.executable, "-B", "-c", "import time;print('before',flush=True);time.sleep(60)"],
                           prefix, deadline=time.monotonic() + 0.15, maximum=100, guard=lambda: None)
        metadata = json.loads(Path(str(prefix) + ".capture.json").read_text())
        self.assertEqual(metadata["host_returncode"], -signal.SIGTERM)
        self.assertIn(b"before", Path(str(prefix) + ".stdout").read_bytes())

    def test_local_capture_child_ignoring_term_is_killed_and_reaped(self):
        prefix = self.directory / "adapter-kill"
        with self.assertRaises(Refusal):
            worker.capture([sys.executable, "-B", "-c", "import signal,time;signal.signal(signal.SIGTERM,signal.SIG_IGN);time.sleep(60)"],
                           prefix, deadline=time.monotonic() + 0.15, maximum=100, guard=lambda: None)
        metadata = json.loads(Path(str(prefix) + ".capture.json").read_text())
        self.assertEqual(metadata["host_returncode"], -signal.SIGKILL)

    def test_command_failure_preserves_stdout_stderr(self):
        prefix = self.directory / "adapter-fail"
        with self.assertRaisesRegex(Refusal, "host command exit 7"):
            worker.capture([sys.executable, "-B", "-c", "import sys;print('partial');print('failure',file=sys.stderr);sys.exit(7)"],
                           prefix, deadline=time.monotonic() + 2, maximum=100, guard=lambda: None)
        self.assertEqual(Path(str(prefix) + ".stdout").read_bytes(), b"partial\n")
        self.assertEqual(Path(str(prefix) + ".stderr").read_bytes(), b"failure\n")

    def test_lost_lease_during_local_capture_stops_without_retry(self):
        prefix = self.directory / "adapter-lost-lease"
        captured = Path(str(prefix) + ".stdout")
        def guard():
            if captured.exists() and captured.stat().st_size > 0:
                raise Refusal("SYNTHETIC exclusive lease lost")
        with self.assertRaisesRegex(Refusal, "lease lost"):
            worker.capture([sys.executable, "-B", "-c", "import time;print('partial',flush=True);time.sleep(60)"],
                           prefix, deadline=time.monotonic() + 2, maximum=100, guard=guard)
        self.assertIn(b"partial", Path(str(prefix) + ".stdout").read_bytes())
        self.assertTrue(json.loads(Path(str(prefix) + ".capture.json").read_text())["capture_partial"])

    def test_local_worker_normal_completion_and_nonblocking_poll(self):
        handle = self.fake_handle("import json,sys,time\ntime.sleep(.1)\nopen(sys.argv[1]+'.result.json','x').write(json.dumps({'ok':True,'value':{'semantic_acceptance':'NOT_EVALUATED'}}))\n" + self.handoff())
        started = time.monotonic()
        self.assertIsNone(handle.poll())
        self.assertLess(time.monotonic() - started, 0.1)
        result = self.wait(handle)
        self.assertTrue(result.ok)
        self.assertEqual(result.value["semantic_acceptance"], "NOT_EVALUATED")
        self.assertIsNotNone(handle.child.returncode)

    def test_host_exit_zero_without_receipt_fails(self):
        result = self.wait(self.fake_handle("pass\n" + self.handoff()))
        self.assertFalse(result.ok)
        self.assertIn("result.json", result.error)

    def test_host_exit_zero_without_handoff_never_passes(self):
        result = self.wait(self.fake_handle("pass\n"))
        self.assertFalse(result.ok)
        self.assertTrue("handoff" in result.error or "cleanup unresolved" in result.error)

    def test_wrong_snapshot_in_worker_result_refused(self):
        raw = {"serial": SERIAL, "packages": [APP, TEST], "complete": False,
               "identities": [], "execution_uid": 10101}
        source = "import json,sys\nopen(sys.argv[1]+'.result.json','x').write(" + repr(json.dumps({"ok": True, "value": raw})) + ")\n"
        result = self.wait(self.fake_handle(source + self.handoff(), operation="snapshot"))
        self.assertFalse(result.ok)
        self.assertIn("uncertainty", result.error)

    def test_host_exit_zero_worker_failure_is_unresolved(self):
        result = self.wait(self.fake_handle("import json,sys\nopen(sys.argv[1]+'.result.json','x').write(json.dumps({'ok':False,'error':'unreachable device; absence unknown'}))\n" + self.handoff()))
        self.assertFalse(result.ok)
        self.assertIn("absence unknown", result.error)

    def test_worker_control_overflow_hard_bound(self):
        handle = self.fake_handle("import os,time\nwhile True: os.write(1,b'x'*65536)\n")
        result = self.wait(handle)
        self.assertFalse(result.ok)
        self.assertIn("overflow", result.error)
        total = sum(Path(str(handle.prefix) + ".worker." + label).stat().st_size for label in ("stdout", "stderr"))
        self.assertEqual(total, adapter.CONTROL_BOUND + 1)

    def test_worker_deadline_and_repeated_terminate(self):
        handle = self.fake_handle("import time\ntime.sleep(60)\n", timeout=0.1)
        result = self.wait(handle)
        handle.terminate()
        handle.terminate()
        self.assertFalse(result.ok)
        self.assertIn("deadline", result.error)
        self.assertIsNotNone(handle.child.returncode)

    def test_real_worker_lease_only_no_transport_child(self):
        result = self.wait(self.configured().start("lease", self.plan.lease["id"]))
        self.assertTrue(result.ok, result.error)
        self.assertIs(result.value, True)

    def test_real_worker_rejects_missing_receipt_before_any_transport(self):
        self.preflight["review"]["path"] = str(self.directory / "adapter-deliberately-missing")
        result = self.wait(self.configured().start("lease", self.plan.lease["id"]))
        self.assertFalse(result.ok)
        self.assertIn("No such file", result.error)

    def test_worker_termination_cleans_local_descendant_group(self):
        heartbeat = self.directory / "adapter-heartbeat"
        grandchild = "import time,pathlib,signal\nsignal.signal(signal.SIGTERM,signal.SIG_IGN)\np=pathlib.Path(" + repr(str(heartbeat)) + ")\nwhile True:\n p.write_text(str(time.monotonic()))\n time.sleep(.02)\n"
        command = "import subprocess,sys,time\nsubprocess.Popen([sys.executable,'-B','-c'," + repr(grandchild) + "])\ntime.sleep(60)\n"
        source = ("import sys,signal,time\nsys.path.insert(0," + repr(str(HERE)) + ")\n"
                  "import adapter_worker as w\nsignal.signal(signal.SIGTERM,w.stop_requested)\n"
                  "try:\n w.capture([sys.executable,'-B','-c'," + repr(command) + "],sys.argv[1],deadline=time.monotonic()+60,maximum=100,guard=lambda: w.require(not w.STOPPED,'terminated'))\n"
                  "except ValueError: pass\n")
        handle = self.fake_handle(source)
        end = time.monotonic() + 2
        while not heartbeat.exists() and time.monotonic() < end:
            self.assertIsNone(handle.poll())
            time.sleep(0.01)
        self.assertTrue(heartbeat.exists())
        handle.terminate()
        result = self.wait(handle)
        self.assertFalse(result.ok)
        self.assertIsNotNone(handle.child.returncode)
        before = heartbeat.read_text()
        time.sleep(0.1)
        self.assertEqual(heartbeat.read_text(), before, "owned same-group descendant still running")


if __name__ == "__main__":
    unittest.main(verbosity=2)
