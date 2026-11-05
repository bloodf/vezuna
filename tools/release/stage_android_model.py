#!/usr/bin/env python3
"""Stage a verified GGUF model into an owned generated Android asset directory.

The tool copies bytes from an explicitly supplied local source file into an
explicitly supplied output directory. The expected size, the expected SHA-256,
the staged file name, the pinned upstream revision and the license evidence
all come from an explicit metadata document. A staged artifact is replaced only
after the new bytes match that metadata, so a failed run leaves the previous
artifact untouched.

The tool never downloads a model, never builds the app, never writes the
runtime catalog, never runs Gradle and never removes files it did not create.
"""
import argparse
import hashlib
import json
import os
import shutil
from dataclasses import dataclass
from pathlib import Path
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CHUNK_BYTES = 1024 * 1024
MINIMUM_CHUNK_BYTES = 64 * 1024
SIDECAR_SUFFIX = ".provenance.json"
LICENSE_SUFFIX = ".LICENSE.txt"
SHA256_HEX_LENGTH = 64
REVISION_HEX_LENGTH = 40
REQUIRED_METADATA = ("fileName", "sizeBytes", "sha256", "upstreamRepository", "upstreamRevision", "upstreamFilePath")
OPTIONAL_METADATA = (
    "modelId",
    "upstreamRepository",
    "upstreamRevision",
    "upstreamFilePath",
    "license",
    "licenseUrl",
    "licenseEvidenceSha256",
)
CATALOG_FIELDS = (
    "androidAssetPackId",
    "iosAssetPackId",
    "minimumRamBytes",
    "maximumContextLength",
)


class StagingError(ValueError):
    """Raised when the request is refused or the copied bytes fail verification."""


@dataclass(frozen=True)
class StagingResult:
    file_name: str
    output_dir: Path
    size_bytes: int
    sha256: str
    model_path: Path
    sidecar_path: Path
    license_copy_path: Path
    replaced_existing: bool


def _require_absolute(path, label):
    if not path.is_absolute():
        raise StagingError(f"{label} must be an absolute path, got {path}")
    return path


def _read_regular_file(path, label):
    _require_absolute(path, label)
    if path.is_symlink():
        raise StagingError(f"{label} must not be a symlink: {path}")
    if not path.exists():
        raise StagingError(f"{label} does not exist: {path}")
    if not path.is_file():
        raise StagingError(f"{label} is not a regular file: {path}")
    return path.resolve()


def _is_lower_hex(value, length):
    return len(value) == length and all(character in "0123456789abcdef" for character in value)


def _safe_file_name(name):
    if not isinstance(name, str) or not name:
        raise StagingError("metadata fileName must be a non-empty string")
    if name in {".", ".."} or "/" in name or "\\" in name or name != os.path.basename(name):
        raise StagingError(f"metadata fileName must be a plain file name without path segments: {name}")
    if name.startswith("."):
        raise StagingError(f"metadata fileName must not start with a dot: {name}")
    if any(ord(character) < 32 or ord(character) == 127 for character in name):
        raise StagingError(f"metadata fileName must not contain control characters: {name!r}")
    if not name.lower().endswith(".gguf"):
        raise StagingError(f"metadata fileName must end with .gguf: {name}")
    return name


def _validate_metadata(document):
    if not isinstance(document, dict):
        raise StagingError("metadata document must be a JSON object")
    for field in CATALOG_FIELDS:
        if field in document:
            raise StagingError(
                f"metadata field {field} belongs to the runtime catalog and needs owner approval, "
                "so staging refuses it"
            )
    for field in REQUIRED_METADATA:
        if field not in document:
            raise StagingError(f"metadata is missing the required field {field}")
    for field in OPTIONAL_METADATA:
        if field in document and (not isinstance(document[field], str) or not document[field].strip()):
            raise StagingError(f"metadata field {field} must be a non-empty string")
    file_name = _safe_file_name(document["fileName"])
    size_bytes = document["sizeBytes"]
    if isinstance(size_bytes, bool) or not isinstance(size_bytes, int) or size_bytes <= 0:
        raise StagingError("metadata sizeBytes must be a positive integer")
    digest = document["sha256"]
    if not isinstance(digest, str) or not _is_lower_hex(digest, SHA256_HEX_LENGTH):
        raise StagingError(f"metadata sha256 must be {SHA256_HEX_LENGTH} lowercase hex characters")
    revision = document.get("upstreamRevision")
    if revision is not None and not _is_lower_hex(revision, REVISION_HEX_LENGTH):
        raise StagingError(f"metadata upstreamRevision must be {REVISION_HEX_LENGTH} lowercase hex characters")
    license_digest = document.get("licenseEvidenceSha256")
    if license_digest is not None and not _is_lower_hex(license_digest, SHA256_HEX_LENGTH):
        raise StagingError(
            f"metadata licenseEvidenceSha256 must be {SHA256_HEX_LENGTH} lowercase hex characters"
        )
    if license_digest is None:
        raise StagingError("metadata must declare licenseEvidenceSha256 so the license copy can be verified")
    return document


def _load_metadata(metadata_path):
    path = _read_regular_file(metadata_path, "metadata")
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise StagingError(f"metadata is not valid JSON: {error}") from error
    return _validate_metadata(document)


def _prepare_output_dir(output_dir):
    _require_absolute(output_dir, "output directory")
    if output_dir.is_symlink():
        raise StagingError(f"output directory must not be a symlink: {output_dir}")
    if output_dir.exists() and not output_dir.is_dir():
        raise StagingError(f"output directory is not a directory: {output_dir}")
    resolved = output_dir.resolve()
    for name in ("samples", "models"):
        protected = (ROOT / name).resolve()
        if resolved == protected or protected in resolved.parents:
            raise StagingError(f"refusing to stage into the protected {name} directory: {resolved}")
    resolved.mkdir(parents=True, exist_ok=True)
    return resolved


def _new_temporary(directory, prefix):
    handle, name = tempfile.mkstemp(dir=directory, prefix=prefix, suffix=".tmp")
    os.close(handle)
    return Path(name)


def _apply_default_mode(path):
    umask = os.umask(0)
    os.umask(umask)
    os.chmod(path, 0o666 & ~umask)


def _stream_file(source, destination, chunk_bytes):
    digest = hashlib.sha256()
    total = 0
    with source.open("rb") as reader, destination.open("wb") as writer:
        while True:
            chunk = reader.read(chunk_bytes)
            if not chunk:
                break
            digest.update(chunk)
            total += len(chunk)
            writer.write(chunk)
        writer.flush()
        os.fsync(writer.fileno())
    return total, digest.hexdigest()


def _sidecar_document(metadata, license_copy_name):
    document = {
        "schema": "vezuna.android-model-staging/1",
        "fileName": metadata["fileName"],
        "sizeBytes": metadata["sizeBytes"],
        "sha256": metadata["sha256"],
        "licenseCopy": license_copy_name,
        "stagedBy": "tools/release/stage_android_model.py",
        "verification": "streamed byte count and SHA-256 matched the explicit metadata before the artifact was replaced",
        "approval": "provenance record only; it is not an owner approval of a production model",
        "packagingAcceptance": "not covered; this tool stages bytes and does not build, deliver or run a model",
    }
    for field in OPTIONAL_METADATA:
        if field in metadata:
            document[field] = metadata[field]
    return json.dumps(document, indent=2, sort_keys=True) + "\n"


def _replace(source, destination):
    os.replace(source, destination)


def _publish(prepared, directory):
    """Roll back handled replacement errors; preserve recovery links if rollback fails."""
    backup_dir = Path(tempfile.mkdtemp(dir=directory, prefix=".stage-backup-"))
    previous = {}
    published = []
    retain_backups = False
    try:
        # Hard links retain the previous bytes without copying a multi-GB model.
        # All destinations were checked as regular files, on this same filesystem.
        for _, destination in prepared:
            if destination.exists():
                backup = backup_dir / destination.name
                os.link(destination, backup)
                previous[destination] = backup
        try:
            for temporary, destination in prepared:
                _replace(temporary, destination)
                published.append(destination)
        except OSError as publication_error:
            failures = []
            for destination in reversed(published):
                try:
                    if destination in previous:
                        os.replace(previous[destination], destination)
                    else:
                        destination.unlink()
                except OSError as rollback_error:
                    failures.append(str(rollback_error))
            if failures:
                retain_backups = True
                raise StagingError(
                    f"publication failed and rollback is incomplete; preserve output and recovery files "
                    f"at {backup_dir}: {'; '.join(failures)}"
                ) from publication_error
            raise
    finally:
        if not retain_backups:
            shutil.rmtree(backup_dir)


def _fsync_directory(directory):
    try:
        handle = os.open(directory, os.O_RDONLY)
    except OSError:
        return
    try:
        os.fsync(handle)
    except OSError:
        pass
    finally:
        os.close(handle)


def stage_model(source, output_dir, metadata_path, license_file, chunk_bytes=DEFAULT_CHUNK_BYTES):
    """Stage one verified model and return what was written."""
    if isinstance(chunk_bytes, bool) or not isinstance(chunk_bytes, int):
        raise StagingError("chunk size must be an integer number of bytes")
    if chunk_bytes < MINIMUM_CHUNK_BYTES:
        raise StagingError(f"chunk size must be at least {MINIMUM_CHUNK_BYTES} bytes")
    source_path = _read_regular_file(source, "source")
    license_path = _read_regular_file(license_file, "license file")
    metadata = _load_metadata(metadata_path)
    file_name = metadata["fileName"]
    expected_size = metadata["sizeBytes"]
    expected_digest = metadata["sha256"]
    expected_license_digest = metadata["licenseEvidenceSha256"]

    resolved_output = _prepare_output_dir(output_dir)
    model_path = resolved_output / file_name
    sidecar_path = resolved_output / (file_name + SIDECAR_SUFFIX)
    license_copy_path = resolved_output / (file_name + LICENSE_SUFFIX)
    for path in (model_path, sidecar_path, license_copy_path):
        if path.is_symlink():
            raise StagingError(f"staged artifact must not be a symlink: {path}")
        if path.exists() and not path.is_file():
            raise StagingError(f"staged artifact must be a regular file: {path}")
        if path in (metadata_path.resolve(), license_path):
            raise StagingError(f"publication destination collides with an input: {path}")
    if source_path == model_path:
        raise StagingError("source and staged destination are the same file")
    if resolved_output in source_path.parents:
        raise StagingError(f"source must not live inside the output directory: {source_path}")
    if source_path.stat().st_size != expected_size:
        raise StagingError(
            f"source size {source_path.stat().st_size} does not match the expected size {expected_size}"
        )

    replaced_existing = model_path.exists()
    temporaries = []
    try:
        for prefix in (".stage-model-", ".stage-license-", ".stage-provenance-"):
            temporaries.append(_new_temporary(resolved_output, prefix))
        model_temporary, license_temporary, sidecar_temporary = temporaries
        measured_size, measured_digest = _stream_file(source_path, model_temporary, chunk_bytes)
        if measured_size != expected_size:
            raise StagingError(
                f"staged byte count {measured_size} does not match the expected size {expected_size}"
            )
        if measured_digest != expected_digest:
            raise StagingError(
                f"staged SHA-256 {measured_digest} does not match the expected SHA-256 {expected_digest}"
            )
        _, measured_license_digest = _stream_file(license_path, license_temporary, chunk_bytes)
        if measured_license_digest != expected_license_digest:
            raise StagingError(
                f"license file SHA-256 {measured_license_digest} does not match the metadata "
                f"licenseEvidenceSha256 {expected_license_digest}"
            )
        sidecar_temporary.write_text(
            _sidecar_document(metadata, license_copy_path.name), encoding="utf-8"
        )
        for temporary in (model_temporary, license_temporary, sidecar_temporary):
            _apply_default_mode(temporary)
        _publish([(model_temporary, model_path), (license_temporary, license_copy_path),
                  (sidecar_temporary, sidecar_path)], resolved_output)
        _fsync_directory(resolved_output)
    finally:
        for temporary in temporaries:
            if temporary.exists():
                temporary.unlink()
    return StagingResult(
        file_name=file_name,
        output_dir=resolved_output,
        size_bytes=expected_size,
        sha256=expected_digest,
        model_path=model_path,
        sidecar_path=sidecar_path,
        license_copy_path=license_copy_path,
        replaced_existing=replaced_existing,
    )


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True, help="absolute path to the local GGUF source file")
    parser.add_argument(
        "--output-dir",
        type=Path,
        required=True,
        help="absolute path to the owned generated output directory",
    )
    parser.add_argument("--metadata", type=Path, required=True, help="absolute path to the JSON metadata document")
    parser.add_argument("--license-file", type=Path, required=True, help="absolute path to the local license text")
    parser.add_argument(
        "--chunk-bytes",
        type=int,
        default=DEFAULT_CHUNK_BYTES,
        help=f"streaming chunk size in bytes, default {DEFAULT_CHUNK_BYTES}",
    )
    args = parser.parse_args(argv)
    try:
        result = stage_model(
            args.source, args.output_dir, args.metadata, args.license_file, args.chunk_bytes
        )
    except (StagingError, OSError) as error:
        parser.exit(1, f"FAIL {error}\n")
    print(
        json.dumps(
            {
                "fileName": result.file_name,
                "outputDir": str(result.output_dir),
                "sizeBytes": result.size_bytes,
                "sha256": result.sha256,
                "model": str(result.model_path),
                "provenance": str(result.sidecar_path),
                "licenseCopy": str(result.license_copy_path),
                "replacedExisting": result.replaced_existing,
            },
            sort_keys=True,
        )
    )
    print("ANDROID_MODEL_STAGED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
