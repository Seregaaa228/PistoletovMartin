"""Find visible slits between parts of a generated model.

    python3 gaps.py [su25 f16 j10] [--res 0.5] [--width 2.0] [--min 6]

The model is voxelised (cube rotations included). An empty voxel is reported
when it belongs to the outside air and has solid on both sides along one axis
within --width pixels: the thin slots that appear where a swept surface or a
loft step does not quite meet its neighbour. Clusters are printed with their
position (px and metres aft of the model's reference) and the bones nearby.
"""
import argparse
import importlib
import os
import sys

import numpy as np
from scipy import ndimage

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from kit import rot_matrix  # noqa: E402


def corners(c):
    o, s = np.array(c.origin), np.array(c.size)
    pts = np.array([[o[0] + s[0] * i, o[1] + s[1] * j, o[2] + s[2] * k] for i in (0, 1) for j in (0, 1) for k in (0, 1)])
    return c.world(pts)


def voxelise(cubes, res):
    allc = np.concatenate([corners(c) for c in cubes])
    lo = np.floor(allc.min(0)) - 2 * res
    hi = np.ceil(allc.max(0)) + 2 * res
    shape = np.ceil((hi - lo) / res).astype(int)
    grid = np.zeros(shape, bool)
    owner = np.full(shape, -1, np.int32)
    for idx, c in enumerate(cubes):
        if min(c.size) <= 0:
            continue
        cc = corners(c)
        a = np.maximum(np.floor((cc.min(0) - lo) / res).astype(int), 0)
        b = np.minimum(np.ceil((cc.max(0) - lo) / res).astype(int) + 1, shape)
        xs, ys, zs = [lo[i] + (np.arange(a[i], b[i]) + 0.5) * res for i in range(3)]
        P = np.stack(np.meshgrid(xs, ys, zs, indexing='ij'), -1).reshape(-1, 3)
        if c.rotation:
            m = rot_matrix(c.rotation)
            p = np.array(c.pivot)
            P = (P - p) @ m + p
        o, s = np.array(c.origin), np.array(c.size)
        inside = np.all((P >= o - 1e-3) & (P <= o + s + 1e-3), 1).reshape(b - a)
        sl = tuple(slice(a[i], b[i]) for i in range(3))
        grid[sl] |= inside
        owner[sl][inside] = idx
    return grid, owner, lo


def find(model, res=0.5, width=2.0, min_vox=6):
    cubes = list(model.cubes)
    solid, owner, lo = voxelise(cubes, res)
    air, n = ndimage.label(~solid)
    border = set(np.unique(np.concatenate([air[0].ravel(), air[-1].ravel(), air[:, 0].ravel(), air[:, -1].ravel(),
                                           air[:, :, 0].ravel(), air[:, :, -1].ravel()])))
    outside = np.isin(air, list(border - {0}))
    k = max(1, int(round(width / res)))
    flag = np.zeros_like(solid)
    for axis in range(3):
        plus = np.zeros_like(solid)
        minus = np.zeros_like(solid)
        n = solid.shape[axis]
        for d in range(1, min(k, n - 1) + 1):
            src = [slice(None)] * 3
            dst = [slice(None)] * 3
            src[axis], dst[axis] = slice(d, None), slice(None, n - d)
            plus[tuple(dst)] |= solid[tuple(src)]       # solid d voxels ahead (no wrap-around)
            minus[tuple(src)] |= solid[tuple(dst)]      # solid d voxels behind
        flag |= plus & minus
    flag &= outside
    lab, m = ndimage.label(flag)
    out = []
    for i, sl in enumerate(ndimage.find_objects(lab), 1):
        count = int((lab[sl] == i).sum())
        if count < min_vox:
            continue
        box_lo = lo + np.array([s.start for s in sl]) * res
        box_hi = lo + np.array([s.stop for s in sl]) * res
        grown = tuple(slice(max(s.start - 3, 0), s.stop + 3) for s in sl)
        near = {cubes[j].bone for j in np.unique(owner[grown]) if j >= 0}
        out.append((count, box_lo, box_hi, sorted(near)))
    out.sort(key=lambda r: -r[0])
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('names', nargs='*', default=['su25', 'f16', 'j10'])
    ap.add_argument('--res', type=float, default=0.5)
    ap.add_argument('--width', type=float, default=2.0)
    ap.add_argument('--min', type=int, default=6)
    ap.add_argument('--lod', action='store_true')
    a = ap.parse_args()
    for name in a.names:
        mod = importlib.import_module(name)
        model = mod.build(lod=a.lod)
        found = find(model, a.res, a.width, a.min)
        print('== %s: %d slit clusters' % (name, len(found)))
        for count, l, h, near in found[:40]:
            ctr = (l + h) / 2
            print('  %5d vox  x %6.1f..%6.1f  y %6.1f..%6.1f  z %6.1f..%6.1f  (x_m %.2f)  %s' % (
                count, l[0], h[0], l[1], h[1], l[2], h[2], (ctr[2] - mod.NOSE) / 16.0, ','.join(near)))


if __name__ == '__main__':
    main()
