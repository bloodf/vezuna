"""Behaviour tests for tools/release/stage_android_model.py.

The fixtures are synthetic byte strings with digests written down by hand, so a
broken digest in the tool cannot agree with the expectation.
"""
import contextlib
import hashlib
import io
import json
from pathlib import Path
import tempfile
import tracemalloc
import unittest
from unittest.mock import patch

import stage_android_model as staging

PAYLOAD = b"vezuna-synthetic-gguf-fixture\n" * 64
PAYLOAD_SIZE = 1920
PAYLOAD_SHA256 = "aa0988c91b6efc0c74749155aa923f7153fda2bc526d787380355e5f9c8f5b4a"
LICENSE_TEXT = b"Vezuna synthetic license evidence. Not a real license text.\n"
LICENSE_SHA256 = "58912a7e858f833ac74eb93102cee22ebbbb6c7ba153c7f04739c9426105ce13"
REVISION = "91cad51170dc346986eccefdc2dd33a9da36ead9"
FILE_NAME = "synthetic-model.gguf"


class StagingTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="vezuna-model-staging-test-")
        self.root = Path(self.temp.name)
        self.source = self.root / "download-name-is-not-the-staged-name.bin"
        self.source.write_bytes(PAYLOAD)
        self.license = self.root / "LICENSE"
        self.license.write_bytes(LICENSE_TEXT)
        self.metadata_path = self.root / "metadata.json"
        self.write_metadata()
        self.output = self.root / "generated" / "modelAssets"

    def tearDown(self):
        self.temp.cleanup()

    def metadata(self, **overrides):
        document = {
            "fileName": FILE_NAME,
            "sizeBytes": PAYLOAD_SIZE,
            "sha256": PAYLOAD_SHA256,
            "modelId": "synthetic-model",
            "upstreamRepository": "https://example.invalid/synthetic/synthetic-model-gguf",
            "upstreamRevision": REVISION,
            "upstreamFilePath": FILE_NAME,
            "license": "Apache-2.0",
            "licenseUrl": f"https://example.invalid/synthetic/synthetic-model-gguf/blob/{REVISION}/LICENSE",
            "licenseEvidenceSha256": LICENSE_SHA256,
        }
        document.update(overrides)
        return document

    def write_metadata(self, **overrides):
        self.metadata_path.write_text(json.dumps(self.metadata(**overrides)), encoding="utf-8")
        return self.metadata_path

    def run_cli(self, *extra, source=None, output=None, metadata=None, license_file=None):
        argv = [
            "stage_android_model.py",
            "--source",
            str(self.source if source is None else source),
            "--output-dir",
            str(self.output if output is None else output),
            "--metadata",
            str(self.metadata_path if metadata is None else metadata),
            "--license-file",
            str(self.license if license_file is None else license_file),
            *extra,
        ]
        out, err = io.StringIO(), io.StringIO()
        with patch("sys.argv", argv), contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            try:
                staging.main()
                code = 0
            except SystemExit as exit_error:
                code = exit_error.code
        return code, out.getvalue(), err.getvalue()

    def staged_model(self):
        return self.output / FILE_NAME

    def staged_sidecar(self):
        return self.output / (FILE_NAME + ".provenance.json")

    def staged_license(self):
        return self.output / (FILE_NAME + ".LICENSE.txt")

    def assert_only_staged_files_exist(self):
        names = sorted(path.name for path in self.output.iterdir())
        self.assertEqual(
            names,
            sorted([FILE_NAME, FILE_NAME + ".provenance.json", FILE_NAME + ".LICENSE.txt"]),
        )

    def test_each_publication_failure_restores_all_previous_files(self):
        for failed_call in (1, 2, 3):
            with self.subTest(failed_call=failed_call):
                self.assertEqual(self.run_cli()[0], 0)
                old = {p.name: p.read_bytes() for p in self.output.iterdir()}
                self.source.write_bytes(PAYLOAD + b"new")
                self.license.write_bytes(LICENSE_TEXT + b"new")
                self.write_metadata(sizeBytes=len(PAYLOAD) + 3,
                                    sha256=hashlib.sha256(PAYLOAD + b"new").hexdigest(),
                                    licenseEvidenceSha256=hashlib.sha256(LICENSE_TEXT + b"new").hexdigest())
                real_replace = staging._replace
                calls = 0

                def fail_once(source, destination):
                    nonlocal calls
                    calls += 1
                    if calls == failed_call:
                        raise OSError("synthetic publication failure")
                    real_replace(source, destination)

                with patch.object(staging, "_replace", side_effect=fail_once):
                    code, out, _ = self.run_cli()
                self.assertNotEqual(code, 0)
                self.assertNotIn("ANDROID_MODEL_STAGED", out)
                self.assertEqual({p.name: p.read_bytes() for p in self.output.iterdir()}, old)
                self.source.write_bytes(PAYLOAD)
                self.license.write_bytes(LICENSE_TEXT)
                self.write_metadata()

    def test_failed_first_publication_leaves_no_generation(self):
        real_replace = staging._replace
        calls = 0

        def fail_second(source, destination):
            nonlocal calls
            calls += 1
            if calls == 2:
                raise OSError("synthetic late failure")
            real_replace(source, destination)

        with patch.object(staging, "_replace", side_effect=fail_second):
            self.assertNotEqual(self.run_cli()[0], 0)
        self.assertEqual(list(self.output.iterdir()), [])

    def test_failed_rollback_retains_recovery_files_and_reports_failure(self):
        self.assertEqual(self.run_cli()[0], 0)
        old = {p.name: p.read_bytes() for p in self.output.iterdir()}
        self.source.write_bytes(PAYLOAD + b"new")
        self.write_metadata(sizeBytes=len(PAYLOAD) + 3,
                            sha256=hashlib.sha256(PAYLOAD + b"new").hexdigest())
        real_replace = staging.os.replace
        calls = 0

        def fail_publish_and_rollback(source, destination):
            nonlocal calls
            calls += 1
            if calls >= 2:
                raise OSError("synthetic unavailable filesystem")
            real_replace(source, destination)

        with patch.object(staging.os, "replace", side_effect=fail_publish_and_rollback):
            code, out, err = self.run_cli()
        self.assertNotEqual(code, 0)
        self.assertNotIn("ANDROID_MODEL_STAGED", out)
        self.assertIn("rollback is incomplete", err)
        backups = list(self.output.glob(".stage-backup-*"))
        self.assertEqual(len(backups), 1)
        self.assertEqual({p.name: p.read_bytes() for p in backups[0].iterdir()}, old)

    def test_missing_upstream_locator_fails_without_mutating_output(self):
        self.assertEqual(self.run_cli()[0], 0)
        old = {p.name: p.read_bytes() for p in self.output.iterdir()}
        for field in ("upstreamRepository", "upstreamRevision", "upstreamFilePath"):
            document = self.metadata()
            del document[field]
            self.metadata_path.write_text(json.dumps(document))
            code, out, _ = self.run_cli()
            self.assertNotEqual(code, 0, field)
            self.assertNotIn("ANDROID_MODEL_STAGED", out)
            self.assertEqual({p.name: p.read_bytes() for p in self.output.iterdir()}, old)

    def test_allocation_failure_removes_only_owned_partial_temporaries(self):
        for fail_at in (2, 3):
            real_new = staging._new_temporary
            calls = 0

            def fail_allocate(directory, prefix):
                nonlocal calls
                calls += 1
                if calls == fail_at:
                    raise OSError("synthetic allocation failure")
                return real_new(directory, prefix)

            with patch.object(staging, "_new_temporary", side_effect=fail_allocate):
                self.assertNotEqual(self.run_cli()[0], 0)
            self.assertEqual(list(self.output.iterdir()), [])

    def test_output_directory_entry_rejected_before_any_replacement(self):
        self.assertEqual(self.run_cli()[0], 0)
        old = self.staged_model().read_bytes()
        self.staged_license().unlink()
        self.staged_license().mkdir()
        self.assertNotEqual(self.run_cli()[0], 0)
        self.assertEqual(self.staged_model().read_bytes(), old)
        self.assertTrue(self.staged_license().is_dir())

    def test_input_destination_collision_is_rejected(self):
        self.output.mkdir(parents=True)
        collision = self.staged_license()
        collision.write_bytes(self.metadata_path.read_bytes())
        before = collision.read_bytes()
        self.assertNotEqual(self.run_cli(metadata=collision)[0], 0)
        self.assertEqual(collision.read_bytes(), before)
        self.assertFalse(self.staged_model().exists())

    def test_protected_output_is_rejected_before_directory_creation(self):
        with patch.object(staging, "ROOT", self.root):
            for name in ("samples", "models"):
                destination = self.root / name / "new-child"
                self.assertNotEqual(self.run_cli(output=destination)[0], 0)
                self.assertFalse(destination.exists())

    def test_exact_bytes_are_staged_and_the_sidecar_records_the_pinned_revision(self):
        code, out, err = self.run_cli()
        self.assertEqual(code, 0, err)
        self.assertIn("ANDROID_MODEL_STAGED", out)
        self.assertEqual(self.staged_model().read_bytes(), PAYLOAD)
        self.assertEqual(self.staged_license().read_bytes(), LICENSE_TEXT)
        sidecar = json.loads(self.staged_sidecar().read_text(encoding="utf-8"))
        self.assertEqual(sidecar["sha256"], PAYLOAD_SHA256)
        self.assertEqual(sidecar["sizeBytes"], PAYLOAD_SIZE)
        self.assertEqual(sidecar["upstreamRevision"], REVISION)
        self.assertEqual(sidecar["license"], "Apache-2.0")
        self.assertEqual(sidecar["licenseEvidenceSha256"], LICENSE_SHA256)
        self.assertNotIn("minimumRamBytes", sidecar)
        self.assertNotIn("androidAssetPackId", sidecar)
        self.assertNotIn("iosAssetPackId", sidecar)
        self.assertNotIn("maximumContextLength", sidecar)

    def test_the_staged_name_comes_from_the_metadata_not_from_the_source_file(self):
        code, out, err = self.run_cli()
        self.assertEqual(code, 0, err)
        self.assertTrue(self.staged_model().is_file())
        self.assertFalse((self.output / self.source.name).exists())

    def test_digest_mismatch_fails_and_keeps_the_previous_output(self):
        self.assertEqual(self.run_cli()[0], 0)
        previous = self.staged_model().read_bytes()
        self.source.write_bytes(b"V" + PAYLOAD[1:])
        code, out, err = self.run_cli()
        self.assertNotEqual(code, 0)
        self.assertIn("SHA-256", err)
        self.assertNotIn("ANDROID_MODEL_STAGED", out)
        self.assertEqual(self.staged_model().read_bytes(), previous)
        self.assert_only_staged_files_exist()

    def test_size_mismatch_fails_and_keeps_the_previous_output(self):
        self.assertEqual(self.run_cli()[0], 0)
        previous = self.staged_model().read_bytes()
        self.source.write_bytes(PAYLOAD[:-1])
        code, out, err = self.run_cli()
        self.assertNotEqual(code, 0)
        self.assertIn("size", err)
        self.assertNotIn("ANDROID_MODEL_STAGED", out)
        self.assertEqual(self.staged_model().read_bytes(), previous)
        self.assert_only_staged_files_exist()

    def test_license_evidence_mismatch_fails_and_keeps_the_previous_output(self):
        self.assertEqual(self.run_cli()[0], 0)
        previous = self.staged_model().read_bytes()
        self.license.write_bytes(b"different license text")
        code, out, err = self.run_cli()
        self.assertNotEqual(code, 0)
        self.assertIn("licenseEvidenceSha256", err)
        self.assertNotIn("ANDROID_MODEL_STAGED", out)
        self.assertEqual(self.staged_model().read_bytes(), previous)
        self.assert_only_staged_files_exist()

    def test_a_failed_run_leaves_unrelated_files_in_the_output_directory_alone(self):
        self.output.mkdir(parents=True)
        unrelated = self.output / "keep-me.txt"
        unrelated.write_bytes(b"unrelated")
        self.source.write_bytes(b"wrong")
        code, out, err = self.run_cli()
        self.assertNotEqual(code, 0)
        self.assertEqual(unrelated.read_bytes(), b"unrelated")
        self.assertEqual(sorted(path.name for path in self.output.iterdir()), ["keep-me.txt"])

    def test_unsafe_metadata_names_are_rejected(self):
        for unsafe in ("../escape.gguf", "sub/model.gguf", "/absolute.gguf", ".hidden.gguf", "..", "model.txt"):
            with self.subTest(fileName=unsafe):
                self.write_metadata(fileName=unsafe)
                code, out, err = self.run_cli()
                self.assertNotEqual(code, 0)
                self.assertIn("fileName", err)
                self.assertNotIn("ANDROID_MODEL_STAGED", out)
                if self.output.exists():
                    self.assertEqual(list(self.output.iterdir()), [])

    def test_a_symlinked_source_is_rejected(self):
        real = self.root / "real-source.bin"
        real.write_bytes(PAYLOAD)
        link = self.root / "linked-source.bin"
        link.symlink_to(real)
        code, out, err = self.run_cli(source=link)
        self.assertNotEqual(code, 0)
        self.assertIn("symlink", err)
        self.assertNotIn("ANDROID_MODEL_STAGED", out)
        self.assertFalse(self.staged_model().exists())

    def test_a_symlinked_destination_is_rejected_and_the_link_survives(self):
        self.output.mkdir(parents=True)
        target = self.root / "target.gguf"
        target.write_bytes(b"untouched")
        link = self.staged_model()
        link.symlink_to(target)
        code, out, err = self.run_cli()
        self.assertNotEqual(code, 0)
        self.assertIn("symlink", err)
        self.assertNotIn("ANDROID_MODEL_STAGED", out)
        self.assertTrue(link.is_symlink())
        self.assertEqual(target.read_bytes(), b"untouched")

    def test_a_symlinked_output_directory_is_rejected(self):
        real_output = self.root / "real-output"
        real_output.mkdir()
        link = self.root / "linked-output"
        link.symlink_to(real_output)
        code, out, err = self.run_cli(output=link)
        self.assertNotEqual(code, 0)
        self.assertIn("symlink", err)
        self.assertNotIn("ANDROID_MODEL_STAGED", out)
        self.assertEqual(list(real_output.iterdir()), [])

    def test_a_source_inside_the_output_directory_is_rejected(self):
        self.output.mkdir(parents=True)
        inside = self.output / "source.gguf"
        inside.write_bytes(PAYLOAD)
        code, out, err = self.run_cli(source=inside)
        self.assertNotEqual(code, 0)
        self.assertIn("output directory", err)
        self.assertNotIn("ANDROID_MODEL_STAGED", out)
        self.assertEqual(list(self.output.iterdir()), [inside])

    def test_a_relative_path_is_rejected(self):
        code, out, err = self.run_cli(source=Path("relative-source.bin"))
        self.assertNotEqual(code, 0)
        self.assertIn("absolute", err)
        self.assertNotIn("ANDROID_MODEL_STAGED", out)

    def test_metadata_cannot_smuggle_catalog_values(self):
        for field in ("minimumRamBytes", "maximumContextLength", "androidAssetPackId", "iosAssetPackId"):
            with self.subTest(field=field):
                self.write_metadata(**{field: 1024})
                code, out, err = self.run_cli()
                self.assertNotEqual(code, 0)
                self.assertIn(field, err)
                self.assertNotIn("ANDROID_MODEL_STAGED", out)
                self.assertFalse(self.staged_model().exists())

    def test_incomplete_metadata_is_rejected(self):
        for missing in ("fileName", "sizeBytes", "sha256", "licenseEvidenceSha256"):
            with self.subTest(missing=missing):
                document = self.metadata()
                document.pop(missing)
                self.metadata_path.write_text(json.dumps(document), encoding="utf-8")
                code, out, err = self.run_cli()
                self.assertNotEqual(code, 0)
                self.assertIn(missing, err)
                self.assertNotIn("ANDROID_MODEL_STAGED", out)

    def test_final_backup_rmdir_failure_warns_after_successful_publication(self):
        self.assertEqual(self.run_cli()[0], 0)
        replacement = b"q" * PAYLOAD_SIZE
        self.source.write_bytes(replacement)
        self.write_metadata(sha256=hashlib.sha256(replacement).hexdigest())
        original_rmdir = staging.os.rmdir

        def fail_backup_rmdir(path, *args, **kwargs):
            if Path(path).name.startswith(".stage-backup-"):
                raise OSError("injected final backup rmdir failure")
            return original_rmdir(path, *args, **kwargs)

        with patch.object(staging.os, "rmdir", side_effect=fail_backup_rmdir):
            code, out, err = self.run_cli()
        self.assertEqual(code, 0, err)
        self.assertIn("ANDROID_MODEL_STAGED", out)
        self.assertIn("WARNING backup cleanup incomplete", err)
        self.assertNotIn("FAIL", err)
        self.assertEqual(self.staged_model().read_bytes(), replacement)
        sidecar = json.loads(self.staged_sidecar().read_text())
        self.assertEqual(sidecar["sha256"], hashlib.sha256(replacement).hexdigest())
        self.assertEqual((self.output / sidecar["licenseCopy"]).read_bytes(), LICENSE_TEXT)
        backups = list(self.output.glob(".stage-backup-*"))
        self.assertEqual(len(backups), 1)
        self.assertEqual(list(backups[0].iterdir()), [])

    def test_staging_is_deterministic(self):
        self.assertEqual(self.run_cli()[0], 0)
        first_model = self.staged_model().read_bytes()
        first_sidecar = self.staged_sidecar().read_bytes()
        self.assertEqual(self.run_cli()[0], 0)
        self.assertEqual(self.staged_model().read_bytes(), first_model)
        self.assertEqual(self.staged_sidecar().read_bytes(), first_sidecar)

    def test_a_second_run_replaces_the_previous_artifact(self):
        self.assertEqual(self.run_cli()[0], 0)
        replacement = b"vezuna-synthetic-gguf-fixture\n" * 63 + b"vezuna-synthetic-gguf-fixture!"
        self.source.write_bytes(replacement)
        self.write_metadata(sha256=hashlib.sha256(replacement).hexdigest())
        code, out, err = self.run_cli()
        self.assertEqual(code, 0, err)
        self.assertEqual(self.staged_model().read_bytes(), replacement)
        sidecar = json.loads(self.staged_sidecar().read_text(encoding="utf-8"))
        self.assertEqual(sidecar["sha256"], hashlib.sha256(replacement).hexdigest())
        self.assert_only_staged_files_exist()

    def test_the_output_directory_is_created_when_it_does_not_exist(self):
        self.assertFalse(self.output.exists())
        code, out, err = self.run_cli()
        self.assertEqual(code, 0, err)
        self.assertTrue(self.output.is_dir())
        self.assertEqual(self.staged_model().read_bytes(), PAYLOAD)

    def test_the_model_is_streamed_and_never_buffered_whole(self):
        big = self.root / "big-source.gguf"
        chunk = b"z" * (1024 * 1024 - 7)
        with big.open("wb") as handle:
            for _ in range(24):
                handle.write(chunk)
        big_size = big.stat().st_size
        self.assertGreater(big_size, 8 * 1024 * 1024)
        digest = hashlib.sha256()
        with big.open("rb") as handle:
            for block in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(block)
        self.write_metadata(sizeBytes=big_size, sha256=digest.hexdigest())
        tracemalloc.start()
        try:
            code, out, err = self.run_cli(source=big)
            _, peak = tracemalloc.get_traced_memory()
        finally:
            tracemalloc.stop()
        self.assertEqual(code, 0, err)
        self.assertLess(peak, 4 * 1024 * 1024)
        self.assertEqual(self.staged_model().stat().st_size, big_size)


if __name__ == "__main__":
    unittest.main()
