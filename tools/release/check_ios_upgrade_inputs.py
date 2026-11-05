#!/usr/bin/env python3
"""Check locally supplied simulator upgrade artifacts; does not run an upgrade."""
import argparse
import contextlib
import hashlib
import io
import json
from pathlib import Path
import plistlib
import re
import struct
import sys

import check_ios_metadata

OLD_SHA = "b9415e6f65a1e21d99d59dbc4b4c04e4e01b622c"
SHA = re.compile(r"[0-9a-f]{40}\Z")
DIGEST = re.compile(r"[0-9a-f]{64}\Z")


def bundle_digest(app):
    """Hash a symlink-free bundle by names, kinds, executable mode and bytes."""
    digest = hashlib.sha256()
    for path in sorted(app.rglob('*'), key=lambda p: p.relative_to(app).as_posix()):
        name = path.relative_to(app).as_posix().encode()
        if path.is_symlink():
            raise ValueError(f'unsupported bundle symlink: {path}')
        elif path.is_dir():
            kind, data = b'D', b''
        elif path.is_file():
            kind = b'X' if path.stat().st_mode & 0o111 else b'F'
            data = path.read_bytes()
        else:
            raise ValueError(f"unsupported bundle entry: {path}")
        digest.update(kind + len(name).to_bytes(8, 'big') + name + len(data).to_bytes(8, 'big') + data)
    return digest.hexdigest()


def check_macho(path):
    # Require a thin arm64 simulator executable with LC_BUILD_VERSION. Fat binaries
    # are intentionally rejected until their slices can all be checked here.
    data = path.read_bytes()
    if len(data) < 32:
        raise ValueError('executable is not a complete Mach-O')
    magic, cpu, _, filetype, ncmds, sizeofcmds, _, _ = struct.unpack_from('<8I', data)
    if magic != 0xfeedfacf or cpu != 0x0100000c or filetype != 2:
        raise ValueError('executable must be a thin arm64 Mach-O executable')
    if sizeofcmds > len(data) - 32 or ncmds > sizeofcmds // 8:
        raise ValueError('invalid Mach-O load commands')
    offset, end, platforms = 32, 32 + sizeofcmds, []
    for _ in range(ncmds):
        if offset + 8 > end:
            raise ValueError('truncated Mach-O load command')
        cmd, size = struct.unpack_from('<II', data, offset)
        if size < 8 or offset + size > end:
            raise ValueError('invalid Mach-O load command size')
        if cmd == 0x32:
            if size < 24:
                raise ValueError('invalid LC_BUILD_VERSION')
            platforms.append(struct.unpack_from('<I', data, offset + 8)[0])
        offset += size
    if offset != end or platforms != [7]:
        raise ValueError('executable must identify exactly one iOS simulator platform')


def inspect(app, manifest_path, expected_sha, current):
    if not app.is_dir() or app.is_symlink() or app.suffix != '.app':
        raise ValueError(f'expected an explicit .app directory: {app}')
    raw = manifest_path.read_bytes()
    manifest = json.loads(raw)
    if not isinstance(manifest, dict) or set(manifest) != {'source_sha', 'bundle_sha256'}:
        raise ValueError('manifest requires exactly source_sha and bundle_sha256')
    if manifest['source_sha'] != expected_sha or not isinstance(manifest['bundle_sha256'], str) or not DIGEST.fullmatch(manifest['bundle_sha256']):
        raise ValueError('manifest source SHA or bundle digest is invalid')
    digest = bundle_digest(app)
    if digest != manifest['bundle_sha256']:
        raise ValueError('bundle digest does not match manifest')
    with (app / 'Info.plist').open('rb') as source:
        info = plistlib.load(source)
    if not isinstance(info, dict):
        raise ValueError('Info.plist must contain a dictionary')
    if info.get('CFBundleIdentifier') != 'app.vezuna':
        raise ValueError('bundle identifier must be app.vezuna')
    for key, pattern in [('CFBundleShortVersionString', r'\d+(?:\.\d+){0,2}'), ('CFBundleVersion', r'\d+(?:\.\d+){0,2}')]:
        if not isinstance(info.get(key), str) or not re.fullmatch(pattern, info[key]):
            raise ValueError(f'invalid {key}')
    sdk = info.get('DTSDKName')
    if info.get('CFBundleSupportedPlatforms') != ['iPhoneSimulator'] or not isinstance(sdk, str) or not re.fullmatch(r'iphonesimulator\d+(?:\.\d+)*', sdk):
        raise ValueError('bundle metadata must identify iOS simulator platform and SDK')
    executable = info.get('CFBundleExecutable')
    if not isinstance(executable, str) or not executable or Path(executable).name != executable:
        raise ValueError('invalid CFBundleExecutable')
    binary = app / executable
    if binary.is_symlink() or not binary.is_file():
        raise ValueError('bundle executable must be a regular file')
    check_macho(binary)
    if current:
        with contextlib.redirect_stdout(io.StringIO()):
            check_ios_metadata.check(app)
    return {'source_sha': expected_sha, 'bundle_sha256': digest,
            'manifest_sha256': hashlib.sha256(raw).hexdigest(),
            'version': info['CFBundleShortVersionString'], 'build': info['CFBundleVersion'],
            'sdk': info['DTSDKName'], 'architecture': 'arm64'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('old-app', 'old-manifest', 'current-app', 'current-manifest'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--current-source-sha', required=True)
    args = parser.parse_args()
    try:
        if not SHA.fullmatch(args.current_source_sha):
            raise ValueError('current source SHA must be 40 lowercase hexadecimal characters')
        if args.old_app.resolve() == args.current_app.resolve():
            raise ValueError('old and current app paths must be distinct')
        old = inspect(args.old_app, args.old_manifest, OLD_SHA, False)
        current = inspect(args.current_app, args.current_manifest, args.current_source_sha, True)
        print(json.dumps({'old': old, 'current': current, 'provenance': 'manifest attestation only; build origin not independently verified'}, sort_keys=True))
        print('IOS_UPGRADE_INPUTS_PREFLIGHT_PASSED')
    except (OSError, ValueError, TypeError, KeyError, struct.error, plistlib.InvalidFileException) as error:
        parser.exit(1, f'FAIL {error}\n')


if __name__ == '__main__':
    main()
