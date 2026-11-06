"""F-16 SBW projectile bones must stay aligned with the configured pylons."""
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'main/resources/assets/frontier_aviation'


def load(path):
    return json.loads(path.read_text())


class MissileMountsTest(unittest.TestCase):
    def test_models_animation_and_blockbench_match_shoot_positions(self):
        config = load(ROOT / 'main/resources/data/frontier_aviation/sbw/vehicles/f16.json')
        seat = config['Seats'][0]['Weapons']
        weapon_index = seat.index('Missile')
        positions = config['Weapons']['Missile']['ShootPos']['Positions']
        expected = {f'dummy_0_{weapon_index}_{i + 1}': [x * 16, y * 16, -z * 16]
                    for i, (x, y, z) in enumerate(positions)}
        self.assertEqual(len(expected), 2)
        for path in ('models/bedrock/vehicle/f16.geo.json',
                     'models/bedrock/vehicle/f16_lod.geo.json',
                     'models/bedrock/vehicle_lod/f16.geo.json'):
            bones = load(ASSETS / path)['minecraft:geometry'][0]['bones']
            for i, (name, pivot) in enumerate(expected.items(), 1):
                outer = next(b for b in bones if b['name'] == f'pylon_outer_{i}')
                self.assertEqual(len(outer['cubes']), 2)
                self.assertAlmostEqual(outer['pivot'][0], pivot[0])
                self.assertGreater(abs(outer['pivot'][0]), 33.6)
                found = [b for b in bones if b['name'] == name]
                self.assertEqual(len(found), 1, (path, name))
                self.assertEqual(found[0]['parent'], 'body')
                for actual, target in zip(found[0]['pivot'], pivot):
                    self.assertAlmostEqual(actual, target)
        for path in ('animations/bedrock/vehicle/f16.animation.json',
                     'animations/bedrock/vehicle_lod/f16.animation.json'):
            bones = load(ASSETS / path)['animations']['animation.frontier_aviation.bind']['bones']
            self.assertTrue(expected.keys() <= bones.keys())
        for path in ('art/f16-armament.bbmodel', 'art/f16-lod-armament.bbmodel'):
            groups = load(ROOT.parent / path)['groups']
            for name, pivot in expected.items():
                group = next(g for g in groups if g['name'] == name)
                for actual, target in zip(group['origin'], [-pivot[0], pivot[1], pivot[2]]):
                    self.assertAlmostEqual(actual, target)


if __name__ == '__main__':
    unittest.main()
