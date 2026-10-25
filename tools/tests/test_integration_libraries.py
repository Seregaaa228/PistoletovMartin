import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile
import contextlib
import io

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from integration_libraries import declared_mod_ids, verify_libraries, verify_unique_mod_ids
from verify_project import verify_profile
from unittest.mock import patch
import package_project


def jar(path, mod_id):
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, 'w') as archive:
        archive.writestr('META-INF/mods.toml',
                         f'[[mods]]\nmodId="{mod_id}"\n[[dependencies.{mod_id}]]\nmodId="forge"\n')


class IntegrationInputsTest(unittest.TestCase):
    def test_dependency_tables_do_not_register_duplicate_mods(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'one.jar'
            jar(path, 'one')
            self.assertEqual(declared_mod_ids(path), ['one'])

    def test_two_jars_cannot_claim_one_mod_id(self):
        with tempfile.TemporaryDirectory() as directory:
            paths = [Path(directory) / name for name in ('one.jar', 'two.jar')]
            for path in paths:
                jar(path, 'same')
            with self.assertRaisesRegex(ValueError, 'Duplicate mod ID same'):
                verify_unique_mod_ids(paths)

    def test_tampered_dependency_fails_before_profile_copy(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / 'vendor/integration/one.jar'
            jar(path, 'one')
            manifest = {'schema': 1, 'minecraft': '1.20.1', 'forge': '47.4.0', 'java': 17,
                        'libraries': [{'file': 'one.jar', 'size': path.stat().st_size,
                                       'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}]}
            (root / 'integration-libraries.lock.json').write_text(json.dumps(manifest))
            verify_libraries(root)
            with path.open('ab') as stream:
                stream.write(b'tampered')
            with self.assertRaisesRegex(ValueError, 'hash/size mismatch'):
                verify_libraries(root)

    def test_parent_traversal_in_library_name_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'integration-libraries.lock.json').write_text(json.dumps({
                'schema': 1, 'minecraft': '1.20.1', 'forge': '47.4.0', 'java': 17,
                'libraries': [{'file': '../escape.jar', 'size': 1, 'sha256': 'a' * 64}]}))
            with self.assertRaisesRegex(ValueError, 'filename'):
                verify_libraries(root)

    def test_extra_executable_script_invalidates_run_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'instance/kubejs/server_scripts').mkdir(parents=True)
            (root / 'modpack.lock.json').write_text('{"mods":{}}')
            (root / 'integration-libraries.lock.json').write_text(json.dumps({
                'schema': 1, 'minecraft': '1.20.1', 'forge': '47.4.0', 'java': 17, 'libraries': []}))
            profile = root / 'profile'
            (profile / 'mods').mkdir(parents=True)
            (profile / 'kubejs/server_scripts').mkdir(parents=True)
            (profile / 'test-profile.json').write_text(json.dumps({
                'runId': 'isolated123', 'baselineOnly': False, 'mods': {}, 'scripts': {}}))
            with patch('verify_project.ROOT', root):
                verify_profile(profile)
                (profile / 'kubejs/server_scripts/untracked.js').write_text('ServerEvents.loaded(e => {})')
                with self.assertRaisesRegex(RuntimeError, 'executable KubeJS'):
                    verify_profile(profile)

    def test_export_skips_any_test_profile_and_deduplicates_bundled_libraries(self):
        with tempfile.TemporaryDirectory() as directory:
            parent = Path(directory)
            root = parent / 'project'
            library = root / 'vendor/integration/example.jar'
            jar(library, 'example')
            bundled = root / 'integration-libraries/example.jar'
            bundled.parent.mkdir()
            bundled.write_bytes(library.read_bytes())
            (root / 'integration-libraries.lock.json').write_text(json.dumps({
                'schema': 1, 'minecraft': '1.20.1', 'forge': '47.4.0', 'java': 17,
                'libraries': [{'file': 'example.jar', 'size': library.stat().st_size,
                               'sha256': hashlib.sha256(library.read_bytes()).hexdigest()}]}))
            profile = root / 'profiles/arbitrary-name'
            (profile / 'saves/temporary-world').mkdir(parents=True)
            (profile / 'test-profile.json').write_text('{}')
            (profile / 'saves/temporary-world/level.dat').write_bytes(b'world must stay local')
            output = parent / 'handoff.zip'
            with patch.object(package_project, 'ROOT', root), patch('sys.argv', ['package_project', str(output)]), \
                    patch.object(package_project.subprocess, 'run'), contextlib.redirect_stdout(io.StringIO()):
                package_project.main()
            with zipfile.ZipFile(output) as archive:
                names = archive.namelist()
            self.assertEqual(len(names), len(set(names)))
            self.assertEqual(names.count('project/integration-libraries/example.jar'), 1)
            self.assertFalse(any('/profiles/' in name for name in names))


if __name__ == '__main__':
    unittest.main()
