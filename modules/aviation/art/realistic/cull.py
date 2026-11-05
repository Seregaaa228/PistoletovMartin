"""Hidden-face removal: drop faces that lie completely inside other solid cubes.

A face is sampled on a fine grid just outside its plane; it is hidden when every sample
lies inside some occluding cube. Occluders are opaque cubes that never move relative to
the face: for fixed bones every fixed bone, for an animated bone only its own cubes.
Glass never occludes (it is see-through), and nothing is culled behind the canopy or the
windscreen, which are hidden in the cockpit view. A cube keeps at least one face in bones
whose cube count the contract tests pin.

    python3 cull.py [su25 f16 j10]   # report how many faces would be removed
"""
import importlib
import os
import sys

import numpy as np

from kit import rot_matrix

FIXED = {'body', 'wing_l', 'wing_r', 'tail_fin', 'stabilizer_l', 'stabilizer_r', 'engine', 'engine_l', 'engine_r',
         'chin_intake', 'pylon_l', 'pylon_r', 'pylon_outer_1', 'pylon_outer_2', 'f16_tip_pylon_left',
         'f16_tip_pylon_right', 'pilot_anchor'}
CLEAR = {'glass'}
KEEP_ONE = ('gear_', 'pylon_outer', 'dummy_', 'f16_tip_pylon')
STEP = 0.35        # px between samples
OUT = 0.03         # px the samples sit outside the face


def _group(bone):
    return 'fixed' if bone in FIXED else bone


def _aabb(c):
    o, s = np.array(c.origin), np.array(c.size)
    pts = np.array([[o[0] + s[0] * i, o[1] + s[1] * j, o[2] + s[2] * k] for i in (0, 1) for j in (0, 1) for k in (0, 1)])
    w = c.world(pts)
    return w.min(0), w.max(0)


def _inside(c, pts, tol=1e-4):
    if c.rotation:
        m = rot_matrix(c.rotation)
        p = np.array(c.pivot)
        pts = (pts - p) @ m + p
    o, s = np.array(c.origin), np.array(c.size)
    return np.all((pts > o + tol) & (pts < o + s - tol), 1)


def cull(cubes):
    """Adds hidden faces to each cube's skip set; returns the number of faces removed."""
    boxes = [_aabb(c) for c in cubes]
    groups = {}
    for i, c in enumerate(cubes):
        if c.mat in CLEAR or min(c.size) <= 0.02:
            continue
        groups.setdefault(_group(c.bone), []).append(i)
    removed = 0
    for i, c in enumerate(cubes):
        occ = groups.get(_group(c.bone), [])
        hidden = []
        for face, corner, sv, tv, n in c.faces():
            ls, lt = np.linalg.norm(sv), np.linalg.norm(tv)
            ns, nt = max(2, min(64, int(ls / STEP) + 2)), max(2, min(64, int(lt / STEP) + 2))
            es, et = min(0.25, 0.02 / ls), min(0.25, 0.02 / lt)       # samples 0.02 px inside the edges
            ss, tt = np.meshgrid(np.linspace(es, 1 - es, ns), np.linspace(et, 1 - et, nt))
            pts = corner + ss.reshape(-1, 1) * sv + tt.reshape(-1, 1) * tv + n * OUT
            w = c.world(pts)
            lo, hi = w.min(0), w.max(0)
            left = np.ones(len(w), bool)
            for j in occ:
                if j == i:
                    continue
                blo, bhi = boxes[j]
                if np.any(blo > hi + 1e-3) or np.any(bhi < lo - 1e-3):
                    continue
                left &= ~_inside(cubes[j], w)
                if not left.any():
                    break
            if not left.any():
                hidden.append((face, ls * lt))
        if not hidden:
            continue
        visible = sum(1 for _ in c.faces())
        if len(hidden) == visible and c.bone.startswith(KEEP_ONE):
            hidden.sort(key=lambda h: -h[1])
            hidden = hidden[1:]
        for face, _ in hidden:
            c.skip.add(face)
        removed += len(hidden)
    return removed


def main():
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    for name in sys.argv[1:] or ['su25', 'f16', 'j10']:
        mod = importlib.import_module(name)
        for lod in (False, True):
            m = mod.build(lod=lod)
            total = sum(1 for c in m.cubes for _ in c.faces())
            print('%s%s: %d of %d faces hidden' % (name, ' LOD' if lod else '', cull(m.cubes), total))


if __name__ == '__main__':
    main()
