import contextlib
import io
import json
import mmap
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import run_test_client as launcher


class NativePublicationTest(unittest.TestCase):
    def prepare_fixture(self, root, payload):
        archive_path = root / 'libraries/test-natives-runtime.jar'
        archive_path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(archive_path, 'w') as archive:
            archive.writestr('libtest.so', payload)
        index = root / 'assets/indexes/test.json'
        index.parent.mkdir(parents=True, exist_ok=True)
        index.write_text(json.dumps({'objects': {}}))
        vanilla = {
            'libraries': [{'name': 'test:native:1:natives-' + launcher.NATIVE_CLASSIFIER,
                           'downloads': {'artifact': {'url': 'https://example.invalid/native',
                                                     'path': archive_path.relative_to(root / 'libraries').as_posix()}}}],
            'downloads': {'client': {'url': 'https://example.invalid/client', 'sha1': 'unused'}},
            'assetIndex': {'id': 'test', 'url': 'https://example.invalid/index', 'sha1': 'unused'},
        }

        def cached_download(url, path, digest=None):
            if not path.exists():
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b'client-fixture')
            return path

        with patch.object(launcher, 'RUNTIME', root), patch.object(launcher, 'download', cached_download), \
                contextlib.redirect_stdout(io.StringIO()):
            launcher.prepare(vanilla, {'libraries': []})
        return root / 'natives' / launcher.SYSTEM / 'libtest.so'

    def test_second_launch_preserves_unchanged_native_inode_and_timestamp(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = self.prepare_fixture(root, b'unchanged native')
            os.utime(target, ns=(1700000000000000000, 1700000000000000000))
            before = target.stat()
            self.prepare_fixture(root, b'unchanged native')
            after = target.stat()
            self.assertEqual((after.st_ino, after.st_mtime_ns), (before.st_ino, before.st_mtime_ns))

    @unittest.skipIf(os.name == 'nt', 'Windows locks mapped files instead of allowing inode replacement')
    def test_changed_cache_preserves_already_mapped_native_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = self.prepare_fixture(root, b'original-native')
            with target.open('rb') as file, mmap.mmap(file.fileno(), 0, access=mmap.ACCESS_READ) as loaded:
                self.prepare_fixture(root, b'replaced-native')
                self.assertEqual(loaded[:], b'original-native')
                self.assertEqual(target.read_bytes(), b'replaced-native')


if __name__ == '__main__':
    unittest.main()
