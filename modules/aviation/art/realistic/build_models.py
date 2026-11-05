"""Generate the realistic aircraft geometry and textures.

    python3 build_models.py [su25 f16 j10] [--out DIR] [--render DIR] [--install]

--install writes into modules/aviation/src/main/resources; without it, files go
to --out (default ./out) for inspection. --render also writes Blockbench
previews through the vendored headless renderer.
"""
import argparse
import importlib
import json
import os
import sys

import cull
import zfight
from kit import Atlas, dump, geo_json

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.normpath(os.path.join(HERE, '../../src/main/resources/assets/frontier_aviation'))
DATA = os.path.normpath(os.path.join(HERE, '../../src/main/resources/data/frontier_aviation'))
ATLAS = 1024


def skin_data(name, schemes):
    """SBW vehicle skins (data/<ns>/sbw/vehicle_skins/<vehicle>.json): the spray-can menu. The
    first scheme is the default texture; SBW applies a skin to the near model only."""
    skins = []
    for k, (sid, title, desc) in enumerate(schemes):
        tex = name if k == 0 else '%s_%s' % (name, sid)
        skins.append({'Id': sid, 'Name': title, 'Description': desc,
                      'Texture': 'frontier_aviation:textures/bedrock/vehicle/%s.png' % tex, 'Priority': k + 1})
    return {'Skins': skins}


def build(name, out, install, render_dir, views):
    mod = importlib.import_module(name)
    full = mod.build(lod=False)
    lod = mod.build(lod=True)
    for model in (full, lod):
        hidden = cull.cull(model.cubes)
        print('%s: %d hidden faces removed' % (model.identifier, hidden))
        left = zfight.resolve(model.cubes)
        if left:
            print('%s: %d coplanar face pairs could not be separated' % (model.identifier, len(left)))
    density = getattr(mod, 'DENSITY', 2.0)
    while True:
        atlas = Atlas(ATLAS, density)
        try:
            used = atlas.pack(full.cubes + lod.cubes)
            break
        except ValueError:
            density -= 0.1
    atlas.paint(full.cubes + lod.cubes, mod.painter())
    png = atlas.png()
    schemes = getattr(mod, 'SCHEMES', [])
    skins = {}
    for sid, _, _ in schemes[1:]:
        atlas.image[:] = 0
        atlas.paint(full.cubes + lod.cubes, mod.painter(sid))
        skins[sid] = atlas.png()
    extra = getattr(mod, 'finish', None)
    geo_full = geo_json(full, ATLAS, identifier='geometry.' + name)
    geo_lod = geo_json(lod, ATLAS, identifier='geometry.' + name + '_lod')
    if extra:
        extra(geo_full, geo_lod)
    faces = [sum(len(c['uv']) for b in g['minecraft:geometry'][0]['bones'] for c in b['cubes'])
             for g in (geo_full, geo_lod)]
    print('%s: %d cubes / %d faces (LOD %d / %d), density %.2f texel/px, atlas rows used %d/%d, png %d KiB, %d skins'
          % (name, len(full.cubes), faces[0], len(lod.cubes), faces[1], density, used, ATLAS, len(png) // 1024,
             len(skins)))
    if install:
        paths = {
            'models/bedrock/vehicle/%s.geo.json' % name: geo_full,
            'models/bedrock/vehicle/%s_lod.geo.json' % name: geo_lod,
            'models/bedrock/vehicle_lod/%s.geo.json' % name: geo_lod,
        }
        for rel, data in paths.items():
            dump(os.path.join(RES, rel), data)
        with open(os.path.join(RES, 'textures/bedrock/vehicle/%s.png' % name), 'wb') as f:
            f.write(png)
        for sid, data in skins.items():
            with open(os.path.join(RES, 'textures/bedrock/vehicle/%s_%s.png' % (name, sid)), 'wb') as f:
                f.write(data)
        if schemes:
            os.makedirs(os.path.join(DATA, 'sbw/vehicle_skins'), exist_ok=True)
            with open(os.path.join(DATA, 'sbw/vehicle_skins/%s.json' % name), 'w', encoding='utf-8') as f:
                json.dump(skin_data(name, schemes), f, indent=2, ensure_ascii=False)
                f.write('\n')
    else:
        os.makedirs(out, exist_ok=True)
        dump(os.path.join(out, name + '.geo.json'), geo_full)
        dump(os.path.join(out, name + '_lod.geo.json'), geo_lod)
        with open(os.path.join(out, name + '.png'), 'wb') as f:
            f.write(png)
        for sid, data in skins.items():
            with open(os.path.join(out, '%s_%s.png' % (name, sid)), 'wb') as f:
                f.write(data)
    if render_dir:
        from render import render
        render(geo_full, png, render_dir, name, views=views)
        render(geo_lod, png, render_dir, name + '_lod', views=('iso',))
        for sid, data in skins.items():
            render(geo_full, data, render_dir, '%s_%s' % (name, sid), views=('iso',))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('names', nargs='*', default=['su25', 'f16', 'j10'])
    ap.add_argument('--out', default=os.path.join(HERE, 'out'))
    ap.add_argument('--render')
    ap.add_argument('--views', default='iso,side,top,front')
    ap.add_argument('--install', action='store_true')
    a = ap.parse_args()
    sys.path.insert(0, HERE)
    for name in a.names:
        build(name, a.out, a.install, a.render, tuple(a.views.split(',')))


if __name__ == '__main__':
    main()
