#!/usr/bin/env python3
"""Check the conservative OBB query reach against the pinned vehicle definitions."""
from __future__ import annotations

import json
import math
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main() -> int:
    source = ROOT / 'modules/arcade/src/main/java/dev/frontier/arcade/combat/DroneSweep.java'
    match = re.search(r'MAX_VEHICLE_REACH\s*=\s*([\d.]+)', source.read_text())
    if not match:
        raise ValueError('DroneSweep must declare its conservative vehicle query reach')
    reach = float(match.group(1))
    missile = ROOT / 'modules/missile-fix/src/main/java/dev/frontier/flamingofix/NativeMissileSweep.java'
    missile_match = re.search(r'MAX_VEHICLE_REACH\s*=\s*([\d.]+)', missile.read_text())
    if not missile_match or float(missile_match.group(1)) != reach:
        raise ValueError('Drone and native missile broad-phase reach must agree')
    definitions: list[tuple[str, dict]] = []
    for jar in sorted((ROOT / 'instance/mods').glob('*.jar')):
        with zipfile.ZipFile(jar) as archive:
            for name in archive.namelist():
                if name.endswith('.json') and '/sbw/vehicles/' in name:
                    definitions.append((jar.name + ':' + name, json.loads(archive.read(name))))
    for path in sorted((ROOT / 'modules').glob('*/src/main/resources/data/*/sbw/vehicles/*.json')):
        definitions.append((str(path.relative_to(ROOT)), json.loads(path.read_text())))
    rows = []
    for name, definition in definitions:
        for box in definition.get('OBB', []):
            size, position = box.get('Size', [0, 0, 0]), box.get('Position', [0, 0, 0])
            if len(size) != 3 or len(position) != 3:
                raise ValueError(f'Invalid OBB dimensions: {name}')
            if any(key in box for key in ('Rotation', 'Transform', 'CustomRotate')):
                # A part may rotate independently of its root. Sum of radii
                # covers every orientation around its authored center.
                radius = math.sqrt(sum(p * p for p in position)) + math.sqrt(sum(s * s for s in size))
            else:
                radius = math.sqrt(sum((abs(s) + abs(p)) ** 2 for s, p in zip(size, position)))
            if not math.isfinite(radius) or radius > reach:
                raise ValueError(f'OBB exceeds {reach:g}-block query reach: {name}, radius={radius:g}')
            rows.append((radius, name))
    if not rows:
        raise ValueError('No pinned OBB definitions found')
    maximum, largest = max(rows)
    report = {'definitions': len(definitions), 'boxes': len(rows), 'queryReach': reach,
              'maximumAuthoredRadius': maximum, 'largest': largest,
              'scope': 'authored root-relative OBB position and size; actual target traces are tested in Forge'}
    target = ROOT / 'vendor/combat-vehicle-bounds.json'
    target.parent.mkdir(exist_ok=True)
    target.write_text(json.dumps(report, indent=2) + '\n')
    print(f'PASS: {len(rows)} authored OBBs in {len(definitions)} definitions; maximum {maximum:.4f} < {reach:g} blocks')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
