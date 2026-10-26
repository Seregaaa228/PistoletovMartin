import hashlib
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import create_test_profile as profile


def jar(path, mod_id):
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, 'w') as archive:
        archive.writestr('META-INF/mods.toml', '[[mods]]\nmodId="' + mod_id + '"\n')


class OptionalArmorProfileTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'project'
        base = self.root / 'instance/mods/base.jar'
        jar(base, 'base')
        (self.root / 'modpack.lock.json').write_text(json.dumps({'mods': {'base.jar': profile.sha(base)}}))
        self.optional = self.root / 'vendor/optional-mods/armor.jar'
        jar(self.optional, 'tacteq')
        self.digest = hashlib.sha256(self.optional.read_bytes()).hexdigest()
        (self.root / 'optional-mods.lock.json').write_text(json.dumps({'mods': {
            'caps_awim_tactical_equipment': {'file': 'armor.jar', 'sha256': self.digest}}}))
        self.dest = Path(self.temp.name) / 'profile'

    def run_profile(self, *args):
        with patch.object(profile, 'ROOT', self.root), patch.object(profile, 'verify_libraries', return_value=[]), patch.object(sys, 'argv', ['create_test_profile.py', str(self.dest), *args]):
            profile.main()

    def test_optional_armor_is_copied_and_recorded(self):
        self.run_profile('--tactical-gear')
        self.assertEqual(profile.sha(self.dest / 'mods/armor.jar'), self.digest)
        manifest = json.loads((self.dest / 'test-profile.json').read_text())
        self.assertEqual(manifest['optionalMods']['armor.jar'], self.digest)

    def test_duplicate_mod_id_is_rejected_before_profile_creation(self):
        jar(self.root / 'instance/mods/base.jar', 'tacteq')
        (self.root / 'modpack.lock.json').write_text(json.dumps({'mods': {'base.jar': profile.sha(self.root / 'instance/mods/base.jar')}}))
        with self.assertRaisesRegex(ValueError, 'Duplicate mod ID'):
            self.run_profile('--tactical-gear')
        self.assertFalse(self.dest.exists())

    def test_baseline_rejects_optional_armor(self):
        with self.assertRaisesRegex(RuntimeError, '--baseline'):
            self.run_profile('--baseline', '--tactical-gear')
        self.assertFalse(self.dest.exists())


if __name__ == '__main__':
    unittest.main()
