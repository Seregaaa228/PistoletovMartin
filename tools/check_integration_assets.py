#!/usr/bin/env python3
"""Check internal Patchouli/Gecko resource links without claiming a game render."""
from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / 'modules/industry/src/main/resources'


def main():
    book = RESOURCES / 'assets/frontier_industry/patchouli_books/field_guide'
    checked = 0
    for language in ('en_us', 'ru_ru'):
        categories = {path.stem for path in (book / language / 'categories').glob('*.json')}
        entries = list((book / language / 'entries').glob('*.json'))
        assert len(categories) == 4 and len(entries) == 11
        for path in entries:
            entry = json.loads(path.read_text())
            assert entry['category'].split(':', 1)[1] in categories, path
            assert entry['pages'], path
            for page in entry['pages']:
                for image in page.get('images', []):
                    namespace, name = image.split(':', 1)
                    assert (RESOURCES / 'assets' / namespace / name).is_file(), (path, image)
            checked += 1
    for name, animations in (('worker', ('idle', 'walk', 'repair')), ('press', ('stop', 'work'))):
        model = json.loads((RESOURCES / f'assets/frontier_industry/geo/{name}.geo.json').read_text())
        actual = json.loads((RESOURCES / f'assets/frontier_industry/animations/{name}.animation.json').read_text())
        assert model['minecraft:geometry'][0]['bones']
        # Animation names are explicit in the renderer/controller source.
        for animation in animations:
            expected = f'animation.{name}.{animation}'
            assert expected in actual['animations'], expected
        checked += 1
    print(f'PASS: {checked} book entries/model links; no in-game rendering implied.')


if __name__ == '__main__':
    main()
