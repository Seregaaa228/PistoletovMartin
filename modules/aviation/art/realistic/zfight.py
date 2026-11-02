"""Coplanar face detection and repair (z-fighting / flickering textures).

Two visible faces of different cubes that lie in the same plane, face the same
way and overlap fight for the depth buffer and flicker in game. resolve()
pushes the smaller of the two faces outward by EPS so it always wins; run it
on the finished cube list before the atlas is packed.

    python3 zfight.py [su25 f16 j10]   # report remaining pairs after repair
"""
import importlib
import os
import sys
from collections import defaultdict

import numpy as np

from kit import face_frame

EPS = 0.06          # px (4 mm): invisible, still resolved by the depth buffer at range
PLANE_TOL = 0.02    # px

GROW = {  # local face -> (axis, grows towards +axis?)
    'north': (2, False), 'south': (2, True), 'east': (0, False), 'west': (0, True),
    'up': (1, True), 'down': (1, False),
}


def _faces(cubes):
    out = []
    for i, c in enumerate(cubes):
        if min(c.size) <= 0:
            continue
        for face, corner, sv, tv, n in c.faces():
            pts = np.array([corner, corner + sv, corner + tv, corner + sv + tv])
            w = c.world(pts)
            wn = c.world_normal(n)
            wn = wn / np.linalg.norm(wn)
            out.append((i, face, w, wn, float(wn @ w[0]), np.linalg.norm(sv) * np.linalg.norm(tv)))
    return out


def _basis(n):
    a = np.array([1.0, 0, 0]) if abs(n[0]) < 0.9 else np.array([0, 1.0, 0])
    u = np.cross(n, a)
    u /= np.linalg.norm(u)
    return u, np.cross(n, u)


def _overlap(qa, qb, tol=0.05):
    """Separating-axis test for two convex quads; True if they share more than a sliver."""
    for q in (qa, qb):
        for k in range(4):
            e = q[(k + 1) % 4] - q[k]
            axis = np.array([-e[1], e[0]])
            norm = np.linalg.norm(axis)
            if norm < 1e-9:
                continue
            axis /= norm
            pa, pb = qa @ axis, qb @ axis
            if min(pa.max(), pb.max()) - max(pa.min(), pb.min()) <= tol:
                return False
    return True


def coplanar_pairs(cubes):
    faces = _faces(cubes)
    buckets = defaultdict(list)
    for k, f in enumerate(faces):
        key = tuple(np.round(f[3], 2))
        buckets[key].append(k)
    pairs = []
    for key, ks in buckets.items():
        ks.sort(key=lambda k: faces[k][4])
        for a in range(len(ks)):
            fa = faces[ks[a]]
            u, v = _basis(fa[3])
            for b in range(a + 1, len(ks)):
                fb = faces[ks[b]]
                if fb[4] - fa[4] > PLANE_TOL:
                    break
                if fa[0] == fb[0] or np.dot(fa[3], fb[3]) < 0.9999:
                    continue
                qa = np.stack([fa[2] @ u, fa[2] @ v], 1)[[0, 1, 3, 2]]
                qb = np.stack([fb[2] @ u, fb[2] @ v], 1)[[0, 1, 3, 2]]
                if _overlap(qa, qb):
                    pairs.append((fa, fb))
    return pairs


def _grow(cube, face, eps):
    axis, positive = GROW[face]
    cube.size[axis] += eps
    if not positive:
        cube.origin[axis] -= eps


def resolve(cubes, eps=EPS, rounds=4):
    """Push the smaller face of every coplanar pair outward; returns the pairs left."""
    for _ in range(rounds):
        pairs = coplanar_pairs(cubes)
        if not pairs:
            return []
        done = set()
        for fa, fb in pairs:
            small = fa if (fa[5], fa[0]) < (fb[5], fb[0]) else fb
            key = (small[0], small[1])
            if key in done:
                continue
            done.add(key)
            _grow(cubes[small[0]], small[1], eps)
    return coplanar_pairs(cubes)


def main():
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    for name in sys.argv[1:] or ['su25', 'f16', 'j10']:
        mod = importlib.import_module(name)
        for lod in (False, True):
            m = mod.build(lod=lod)
            before = len(coplanar_pairs(m.cubes))
            left = resolve(m.cubes)
            print('%s%s: %d coplanar pairs, %d left after repair' % (name, ' LOD' if lod else '', before, len(left)))
            for fa, fb in left[:10]:
                print('   ', m.cubes[fa[0]].bone, fa[1], '|', m.cubes[fb[0]].bone, fb[1], np.round(fa[2][0], 1))


if __name__ == '__main__':
    main()
