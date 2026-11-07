import contextlib
import io
import json
from pathlib import Path
import plistlib
import shutil
import struct
import tempfile
import unittest
from unittest.mock import patch

import check_ios_upgrade_inputs as preflight


class UpgradeInputsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='vezuna-upgrade-input-test-')
        self.root = Path(self.temp.name)
        self.old = self.make_app('old.app', False)
        self.current = self.make_app('current.app', True)
        self.sha = 'a' * 40
        self.old_manifest = self.manifest(self.old, preflight.OLD_SHA)
        self.current_manifest = self.manifest(self.current, self.sha)

    def tearDown(self):
        self.temp.cleanup()

    def make_app(self, name, current):
        app = self.root / name
        app.mkdir()
        info = {'CFBundleIdentifier': 'app.vezuna', 'CFBundleExecutable': 'Vezuna',
                'CFBundleShortVersionString': '1.0', 'CFBundleVersion': '1',
                'CFBundleSupportedPlatforms': ['iPhoneSimulator'], 'DTSDKName': 'iphonesimulator27.0'}
        if current:
            info.update({'MinimumOSVersion': '18.5', 'CADisableMinimumFrameDurationOnPhone': True,
                         'BAAppGroupID': 'group.app.vezuna', 'BAHasManagedAssetPacks': True,
                         'BAUsesAppleHosting': True, 'UIAppFonts': ['NotoSans-Regular.ttf', 'NotoSans-Bold.ttf'],
                         'UIApplicationSceneManifest': {'UISceneConfigurations': {'UIWindowSceneSessionRoleApplication': [
                             {'UISceneConfigurationName': 'Default', 'UISceneDelegateClassName': 'Vezuna.SceneDelegate'}]}},
                         'NSCameraUsageDescription': 'camera', 'NSPhotoLibraryUsageDescription': 'photos',
                         'NSHealthShareUsageDescription': 'health'})
            for rel in ['PrivacyInfo.xcprivacy', 'ComposeMultiplatformPrivacy.bundle/PrivacyInfo.xcprivacy']:
                target = app / rel
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(preflight.check_ios_metadata.ROOT / 'iosApp/Vezuna' / rel, target)
            for rel in ['NotoSans-Regular.ttf', 'NotoSans-Bold.ttf', *[f'compose-resources/composeResources/vezuna.shared.generated.resources/{p}' for p in ['font/NotoSans-Regular.ttf', 'font/NotoSans-Bold.ttf', 'values/strings.commonMain.cvr', 'values-pt/strings.commonMain.cvr', 'values-es/strings.commonMain.cvr', 'files/NOTO-LICENSE.txt']]]:
                target = app / rel
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(b'fixture')
        (app / 'Info.plist').write_bytes(plistlib.dumps(info))
        # Synthetic header only, not a signed or installable build.
        (app / 'Vezuna').write_bytes(struct.pack('<8I6I', 0xfeedfacf, 0x0100000c, 0, 2, 1, 24, 0, 0, 0x32, 24, 7, 0, 0, 0))
        return app

    def manifest(self, app, sha):
        path = self.root / (app.name + '.json')
        path.write_text(json.dumps({'source_sha': sha, 'bundle_sha256': preflight.bundle_digest(app)}))
        return path

    def run_cli(self):
        args = ['check', '--old-app', str(self.old), '--old-manifest', str(self.old_manifest),
                '--current-app', str(self.current), '--current-manifest', str(self.current_manifest),
                '--current-source-sha', self.sha]
        out, err = io.StringIO(), io.StringIO()
        with patch('sys.argv', args), contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            try:
                preflight.main()
                code = 0
            except SystemExit as exc:
                code = exc.code
        return code, out.getvalue(), err.getvalue()

    def test_valid_same_version_and_limit_reported(self):
        code, out, _ = self.run_cli()
        self.assertEqual(code, 0)
        self.assertIn('IOS_UPGRADE_INPUTS_PREFLIGHT_PASSED', out)
        self.assertIn('build origin not independently verified', out)
        self.assertNotIn('IOS_BUNDLE_METADATA_PASSED', out)
        self.assertNotIn('IOS_UPGRADE_PASSED', out)

    def test_symlinks_are_rejected_before_metadata_is_read(self):
        external = self.root / 'external'
        external.mkdir()
        (external / 'Info.plist').write_bytes((self.old / 'Info.plist').read_bytes())
        (external / 'font.ttf').write_bytes(b'original font')
        cases = [
            ('Info.plist', external / 'Info.plist'),
            ('NotoSans-Regular.ttf', external / 'font.ttf'),
            ('compose-resources', external),
            ('Info.plist', Path('../external/Info.plist')),
            ('missing', Path('../absent')),
            ('internal', Path('NotoSans-Regular.ttf')),
        ]
        original_current = self.current
        for index, (name, target) in enumerate(cases):
            with self.subTest(name=name, target=str(target)):
                self.current = self.root / f'linked-{index}.app'
                shutil.copytree(original_current, self.current)
                link = self.current / name
                if link.is_dir():
                    shutil.rmtree(link)
                elif link.exists():
                    link.unlink()
                link.symlink_to(target)
                with self.assertRaisesRegex(ValueError, 'symlink'):
                    preflight.bundle_digest(self.current)
                code, out, err = self.run_cli()
                self.assertNotEqual(code, 0)
                self.assertIn('symlink', err)
                self.assertNotIn('IOS_UPGRADE_INPUTS_PREFLIGHT_PASSED', out)
                # Changing bytes outside the app must never turn rejection into success.
                (external / 'font.ttf').write_bytes(b'changed font')
                self.assertNotEqual(self.run_cli()[0], 0)
        self.current = original_current

    def test_missing_app_and_mismatched_digest(self):
        self.old.rename(self.root / 'moved.app')
        self.assertNotEqual(self.run_cli()[0], 0)
        (self.root / 'moved.app').rename(self.old)
        (self.old / 'Vezuna').write_bytes(b'changed')
        self.assertIn('digest does not match', self.run_cli()[2])

    def test_unproven_source_and_invalid_bundle(self):
        self.manifest(self.old, 'b' * 40)
        self.assertNotEqual(self.run_cli()[0], 0)
        self.manifest(self.old, preflight.OLD_SHA)
        info = plistlib.loads((self.old / 'Info.plist').read_bytes())
        info['CFBundleIdentifier'] = 'other'
        (self.old / 'Info.plist').write_bytes(plistlib.dumps(info))
        self.manifest(self.old, preflight.OLD_SHA)
        self.assertIn('bundle identifier', self.run_cli()[2])

    def test_platform_arch_version_and_current_scene(self):
        for key, value, message in [('CFBundleSupportedPlatforms', ['iPhoneOS'], 'platform'),
                                    ('CFBundleVersion', '', 'CFBundleVersion')]:
            path = self.old / 'Info.plist'
            original = path.read_bytes()
            info = plistlib.loads(original)
            info[key] = value
            path.write_bytes(plistlib.dumps(info))
            self.manifest(self.old, preflight.OLD_SHA)
            self.assertIn(message, self.run_cli()[2])
            path.write_bytes(original)
        binary = self.old / 'Vezuna'
        original = binary.read_bytes()
        binary.write_bytes(original[:4] + struct.pack('<I', 0x01000007) + original[8:])
        self.manifest(self.old, preflight.OLD_SHA)
        self.assertIn('arm64', self.run_cli()[2])
        binary.write_bytes(original)
        self.manifest(self.old, preflight.OLD_SHA)
        path = self.current / 'Info.plist'
        info = plistlib.loads(path.read_bytes())
        del info['UIApplicationSceneManifest']
        path.write_bytes(plistlib.dumps(info))
        self.manifest(self.current, self.sha)
        self.assertIn('SceneDelegate', self.run_cli()[2])


if __name__ == '__main__':
    unittest.main()
