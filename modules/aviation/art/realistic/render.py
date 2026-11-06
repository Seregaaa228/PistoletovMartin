"""Preview renders through the vendored headless Blockbench renderer (authoring only)."""
import os
import subprocess
import sys

from kit import bbmodel_from_geo, dump

RENDERER = os.environ.get('BB_RENDER', '/home/chpoka/Documents/GitHub/PistoletovMartin/vendor/blockbench-render/cli.mjs')
VIEWS = {
    'iso': ['--azimuth', '35', '--elevation', '22'],
    'iso_back': ['--azimuth', '215', '--elevation', '18'],
    'front': ['--view', 'front'],
    'side': ['--view', 'right', '--ortho', '--elevation', '0'],
    'top': ['--view', 'top', '--ortho'],
    'back': ['--view', 'back'],
    'below': ['--azimuth', '30', '--elevation=-25'],
}


def render(geo, png_bytes, out_dir, name, views=('iso', 'side', 'top', 'front'), size=(1200, 800)):
    os.makedirs(out_dir, exist_ok=True)
    bb = os.path.join(out_dir, name + '.bbmodel')
    dump(bb, bbmodel_from_geo(geo, png_bytes, name))
    outs = []
    for v in views:
        out = os.path.join(out_dir, '%s_%s.png' % (name, v))
        cmd = ['node', RENDERER, bb, '-o', out, '--width', str(size[0]), '--height', str(size[1]), '-q',
               '--texture-filter', 'nearest'] + VIEWS[v]
        r = subprocess.run(cmd, capture_output=True, text=True)
        if r.returncode:
            sys.stderr.write(r.stderr[-2000:])
            raise SystemExit('render failed')
        outs.append(out)
    return outs
