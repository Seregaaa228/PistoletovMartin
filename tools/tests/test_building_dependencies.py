import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import building_dependencies as deps


class BuildingDependenciesTest(unittest.TestCase):
    def test_rejects_missing_and_modified_archive(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            jar = root / 'vendor/building-mods/example.jar'
            jar.parent.mkdir(parents=True)
            with zipfile.ZipFile(jar, 'w') as archive:
                archive.writestr('META-INF/mods.toml', 'test')
            digest = hashlib.sha256(jar.read_bytes()).hexdigest()
            lock = {'mods': [{'file': jar.name, 'sha256': digest}]}
            (root / 'buildings-dependencies.lock.json').write_text(json.dumps(lock), encoding='utf8')
            with patch.object(deps, 'ROOT', root):
                self.assertEqual(deps.verify_building_mods(), [jar])
                jar.write_bytes(b'tampered')
                with self.assertRaises(ValueError): deps.verify_building_mods()
                jar.unlink()
                with self.assertRaises(ValueError): deps.verify_building_mods()


if __name__ == '__main__':
    unittest.main()
