"""Cube-model authoring kit for the realistic Su-25, F-16 and J-10.

Models are written as Bedrock geometry (pixels, 16 px = 1 block = 1 metre,
nose towards -Z, ground at Y=0) with per-face UVs into one procedurally
painted atlas. Every face is painted from its world position, so camouflage
and panel lines continue across neighbouring cubes, as in DeepStrike's Su-24M.

Coordinates: geo X is the model X used by AircraftRenderer/renderCustomPart.
Rotation order and signs follow Blockbench's Bedrock codec (checked against the
Su-24M geometry): R = Rz(-rz) * Ry(ry) * Rx(-rx) in right-handed terms.
"""
import base64
import io
import json
import math
import uuid

import numpy as np
from PIL import Image

FACES = ('north', 'south', 'east', 'west', 'up', 'down')
LOFT_REACH = 0.15   # px a loft segment's hidden end reaches into a larger neighbour (> its 0.12 px end inset)


def rot_matrix(rotation):
    if not rotation:
        return np.eye(3)
    rx, ry, rz = (math.radians(a) for a in rotation)
    rx, rz = -rx, -rz
    cx, sx, cy, sy, cz, sz = math.cos(rx), math.sin(rx), math.cos(ry), math.sin(ry), math.cos(rz), math.sin(rz)
    mx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    my = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    mz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return mz @ my @ mx


def euler_from_matrix(r):
    """Geo rotation (rx, ry, rz) in degrees whose rot_matrix() equals the proper rotation r."""
    r = np.asarray(r, float)
    b = math.asin(max(-1.0, min(1.0, -r[2, 0])))
    if abs(math.cos(b)) > 1e-9:
        c = math.atan2(r[2, 1], r[2, 2])
        a = math.atan2(r[1, 0], r[0, 0])
    else:  # gimbal lock: fold the roll into a
        c = 0.0
        a = math.atan2(-r[0, 1], r[1, 1])
    return -math.degrees(c), math.degrees(b), -math.degrees(a)


def axis_rot(axis, deg):
    """Right-handed rotation matrix about 'x', 'y' or 'z'."""
    t = math.radians(deg)
    c, s = math.cos(t), math.sin(t)
    if axis == 'x':
        return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])
    if axis == 'y':
        return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def smooth(stations, k):
    """Subdivide loft stations k times per interval (Catmull-Rom through every column), so a
    nose or a transition steps in small increments instead of a few big ones."""
    if k <= 1 or len(stations) < 2:
        return list(stations)
    a = np.array(stations, float)
    pad = np.vstack([2 * a[0] - a[1], a, 2 * a[-1] - a[-2]])
    out = []
    for i in range(len(a) - 1):
        p0, p1, p2, p3 = pad[i], pad[i + 1], pad[i + 2], pad[i + 3]
        for j in range(k):
            t = j / k
            v = 0.5 * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t
                       + (-p0 + 3 * p1 - 3 * p2 + p3) * t ** 3)
            if j == 0:
                v = p1.copy()
            v[0] = p1[0] + (p2[0] - p1[0]) * t          # z stays evenly spaced
            for col in (5, 6):                           # chamfers stay between their end values
                v[col] = min(max(v[col], min(p1[col], p2[col])), max(p1[col], p2[col]))
            out.append(tuple(float(x) for x in v))
    out.append(tuple(float(x) for x in a[-1]))
    return out


def face_frame(face, o, s):
    """Corner, s-axis, t-axis and outward normal of a face in unrotated cube space.

    The (s, t) parametrisation is the one Blockbench uses for Bedrock per-face
    UVs with positive uv_size (up/down are flipped by the codec)."""
    ox, oy, oz = o
    sx, sy, sz = s
    if face == 'north':
        return (ox, oy + sy, oz), (sx, 0, 0), (0, -sy, 0), (0, 0, -1)
    if face == 'south':
        return (ox + sx, oy + sy, oz + sz), (-sx, 0, 0), (0, -sy, 0), (0, 0, 1)
    if face == 'east':
        return (ox, oy + sy, oz + sz), (0, 0, -sz), (0, -sy, 0), (-1, 0, 0)
    if face == 'west':
        return (ox + sx, oy + sy, oz), (0, 0, sz), (0, -sy, 0), (1, 0, 0)
    if face == 'up':
        return (ox, oy + sy, oz + sz), (sx, 0, 0), (0, 0, -sz), (0, 1, 0)
    return (ox, oy, oz), (sx, 0, 0), (0, 0, sz), (0, -1, 0)


class Cube:
    def __init__(self, bone, origin, size, mat, pivot=None, rotation=None, skip=(), lod=True, tag=None):
        self.bone = bone
        self.origin = [float(v) for v in origin]
        self.size = [max(float(v), 0.0) for v in size]
        self.mat = mat
        self.pivot = [float(v) for v in pivot] if pivot is not None else None
        self.rotation = [float(v) for v in rotation] if rotation else None
        if self.rotation and not any(abs(a) > 1e-9 for a in self.rotation):
            self.rotation = None
        self.skip = set(skip)
        self.lod = lod
        self.tag = tag
        self.uv = {}

    def faces(self):
        for face in FACES:
            if face in self.skip:
                continue
            corner, sv, tv, n = face_frame(face, self.origin, self.size)
            ls, lt = np.linalg.norm(sv), np.linalg.norm(tv)
            if ls < 1e-6 or lt < 1e-6:
                continue
            yield face, np.array(corner, float), np.array(sv, float), np.array(tv, float), np.array(n, float)

    def world(self, points):
        """Apply the cube rotation (bones are unrotated at rest)."""
        if not self.rotation:
            return points
        m = rot_matrix(self.rotation)
        p = np.array(self.pivot or [0, 0, 0])
        return (points - p) @ m.T + p

    def world_normal(self, n):
        return n if not self.rotation else rot_matrix(self.rotation) @ n


class Bone:
    def __init__(self, name, parent=None, pivot=(0, 0, 0)):
        self.name, self.parent, self.pivot = name, parent, [float(v) for v in pivot]


class Model:
    def __init__(self, identifier):
        self.identifier = identifier
        self.bones = []
        self.cubes = []

    def bone(self, name, parent='body', pivot=(0, 0, 0)):
        self.bones.append(Bone(name, parent, pivot))
        return name

    def cube(self, bone, origin, size, mat, **kw):
        c = Cube(bone, origin, size, mat, **kw)
        self.cubes.append(c)
        return c

    def box(self, bone, x0, x1, y0, y1, z0, z1, mat, **kw):
        """Axis-aligned box from min/max corners (rotation, if any, about pivot)."""
        xa, xb = sorted((x0, x1))
        ya, yb = sorted((y0, y1))
        za, zb = sorted((z0, z1))
        return self.cube(bone, (xa, ya, za), (xb - xa, yb - ya, zb - za), mat, **kw)

    def centered(self, bone, center, size, mat, rotation=None, pivot=None, **kw):
        cx, cy, cz = center
        sx, sy, sz = size
        return self.cube(bone, (cx - sx / 2, cy - sy / 2, cz - sz / 2), size, mat,
                         pivot=pivot if pivot is not None else center, rotation=rotation, **kw)

    def oriented(self, bone, center, size, r, mat, **kw):
        """Cube of `size` centred on `center` whose local axes are the columns of rotation r."""
        rot = euler_from_matrix(r)
        if not any(abs(a) > 1e-7 for a in rot):
            rot = None
        return self.centered(bone, center, size, mat, rotation=rot, pivot=tuple(center), **kw)

    def prism(self, bone, axis, center, radius, length, mat, n=6, lod=True, caps=(True, True), stagger=0.04,
              roll=0.0):
        """Regular 2n-gon prism along 'x', 'y' or 'z' with inradius `radius` (a flat facing down
        for X/Z axes, so a wheel keeps its contact height). It is the union of n bars turned in
        180/n degree steps; each bar shows two facets and its caps, its sides lie inside the
        others. Caps are staggered by `stagger` px per bar so overlapping caps never z-fight."""
        # half-width rounded so the exported (5-decimal) bar stays exactly centred on `center`
        a = round(radius * math.tan(math.pi / (2 * n)), 4)
        out = []
        c0 = np.array(center, float)
        ax = {'x': 0, 'y': 1, 'z': 2}[axis]
        for k in range(n):
            # stagger only the shown caps; a hidden end stays flush with what it is built against
            cut = [k * stagger if caps[0] else 0.0, k * stagger if caps[1] else 0.0]
            ln = length - cut[0] - cut[1]
            c = c0.copy()
            c[ax] += (cut[0] - cut[1]) / 2
            if axis == 'x':
                size, r = (ln, 2 * radius, 2 * a), axis_rot('x', roll + 180.0 * k / n)
                skip = {'north', 'south'} | ({'east'} if not caps[0] else set()) | ({'west'} if not caps[1] else set())
            elif axis == 'z':
                size, r = (2 * a, 2 * radius, ln), axis_rot('z', roll + 180.0 * k / n)
                skip = {'east', 'west'} | ({'north'} if not caps[0] else set()) | ({'south'} if not caps[1] else set())
            else:
                size, r = (2 * radius, ln, 2 * a), axis_rot('y', roll + 180.0 * k / n)
                skip = {'north', 'south'} | ({'down'} if not caps[0] else set()) | ({'up'} if not caps[1] else set())
            out.append(self.oriented(bone, c, size, r, mat, lod=lod, skip=skip))
        return out

    def petals(self, bone, cx, cy, z0, z1, r0, r1, n, thick, mat, lod=True, roll=0.0, overlap=1.06, tag=None):
        """Ring of n plates forming a frustum along Z from radius r0 at z0 to r1 at z1 (outer
        surface): the flaps of a convergent nozzle or an intake lip."""
        dz, dr = z1 - z0, r1 - r0
        ln = math.hypot(dz, dr)
        cos_t, sin_t = dz / ln, dr / ln
        tilt = np.array([[1, 0, 0], [0, cos_t, sin_t], [0, -sin_t, cos_t]], float)   # columns: X, normal, along
        width = 2 * max(r0, r1) * math.tan(math.pi / n) * overlap
        rm = (r0 + r1) / 2 - thick / 2 * cos_t
        out = []
        for k in range(n):
            rr = axis_rot('z', roll + 360.0 * k / n)
            centre = np.array([cx, cy, (z0 + z1) / 2 + thick / 2 * sin_t]) + rr @ np.array([0.0, rm, 0.0])
            out.append(self.oriented(bone, centre, (width, thick, ln), rr @ tilt, mat, lod=lod,
                                     skip={'east', 'west'}, tag=tag))
        return out

    # ----- composite shapes -------------------------------------------------
    def section(self, bone, z0, z1, cx, cy, w, h, ct, cb=None, mat='skin', lod=True, ends=(True, True),
                skip_bottom=False, top_mat=None, round_=False):
        """Octagonal fuselage section along Z: width w, height h centred at (cx, cy),
        top corner chamfer ct and bottom chamfer cb (pixels). round_ replaces each chamfer
        by two facets on a quarter circle through the same end points (a rounder 16-gon)."""
        cb = ct if cb is None else cb
        ct = min(ct, w / 2 - 0.01, h / 2 - 0.01)
        cb = min(cb, w / 2 - 0.01, h / 2 - 0.01)
        skip = set()
        if not ends[0]:
            skip.add('north')
        if not ends[1]:
            skip.add('south')
        y0, y1 = cy - h / 2, cy + h / 2
        out = []
        # Side core between the chamfers, a top block and a bottom block.
        out.append(self.box(bone, cx - w / 2, cx + w / 2, y0 + cb, y1 - ct, z0, z1, mat, skip=skip | {'up', 'down'}, lod=lod))
        out.append(self.box(bone, cx - w / 2 + ct, cx + w / 2 - ct, cy, y1, z0, z1, top_mat or mat,
                            skip=skip | {'down', 'east', 'west'} if ct > 0.01 else skip | {'down'}, lod=lod))
        out.append(self.box(bone, cx - w / 2 + cb, cx + w / 2 - cb, y0, cy, z0, z1, mat,
                            skip=skip | {'up', 'east', 'west'} | ({'down'} if skip_bottom else set()) if cb > 0.01
                            else skip | {'up'}, lod=lod))
        for c, top in ((ct, True), (cb, False)):
            if c <= 0.15:
                continue
            if round_ and c > 0.6 and round_ in (True, 'top' if top else 'bottom'):
                ey = 1 if top else -1
                for side in (-1, 1):
                    o = np.array([cx + side * (w / 2 - c), (y1 - c) if top else (y0 + c)])
                    pts = [o + c * np.array([0, ey]), o + c * np.array([side, ey]) / math.sqrt(2),
                           o + c * np.array([side, 0])]
                    for k, (pa, pb) in enumerate(zip(pts, pts[1:])):
                        u = (pb - pa) / np.linalg.norm(pb - pa)
                        nrm = np.array([-u[1], u[0]])
                        mid = (pa + pb) / 2
                        if nrm @ (mid - o) < 0:
                            u, nrm = -u, -nrm
                        t = c * math.cos(math.pi / 8) + 0.02
                        ctr = mid - nrm * t / 2
                        r = np.array([[u[0], nrm[0], 0], [u[1], nrm[1], 0], [0, 0, 1]])
                        cube = self.oriented(bone, (ctr[0], ctr[1], (z0 + z1) / 2),
                                             (2 * c * math.sin(math.pi / 8) + 0.01, t, z1 - z0), r,
                                             (top_mat or mat) if top else mat, lod=lod,
                                             skip=skip | {'down', 'east', 'west'})
                        cube.tag = 'arc%d' % k
                        out.append(cube)
                continue
            t = c / math.sqrt(2) + 0.02
            length = c * math.sqrt(2)
            for side in (-1, 1):
                # Midpoint of the chamfer edge and its inward normal.
                mx = cx + side * (w / 2 - c / 2)
                my = (y1 - c / 2) if top else (y0 + c / 2)
                ny = -1 if top else 1
                ccx = mx - side * t / 2 / math.sqrt(2)
                ccy = my + ny * t / 2 / math.sqrt(2)
                angle = 45 if (side > 0) == top else -45
                out.append(self.cube(bone, (ccx - length / 2, ccy - t / 2, z0), (length, t, z1 - z0),
                                     (top_mat or mat) if top else mat,
                                     pivot=(ccx, ccy, (z0 + z1) / 2), rotation=(0, 0, angle),
                                     skip=skip | ({'down'} if top else {'up'}), lod=lod))
        # The 3-7 cubes of a section share their end planes; where an end is shown,
        # step the end faces back by a hair (core 0, top/bottom 0.04, chamfers 0.08 px)
        # so the overlapping caps do not z-fight (flicker) in game.
        for k, c in enumerate(out):
            inset = 0.0 if k == 0 else (0.04 if k < 3 else (0.12 if c.tag == 'arc1' else 0.08))
            if round_ and c.tag is None:
                c.tag = 'arc'
            if ends[0]:
                c.origin[2] += inset
                c.size[2] -= inset
            if ends[1]:
                c.size[2] -= inset
        return out

    def loft(self, bone, stations, mat='skin', lod=True, top_mat=None, skip_bottom=False, round_=False):
        """Stations: (z, cx, cy, w, h, ct, cb). One section per interval using the
        mean of its two end stations, end faces kept only where they are exposed."""
        segs = []
        for a, b in zip(stations, stations[1:]):
            z0, z1 = a[0], b[0]
            vals = [(a[i] + b[i]) / 2 for i in range(1, 7)]
            segs.append((z0, z1, *vals))
        def bigger(s, t):
            return s[4] > t[4] + 0.3 or s[5] > t[5] + 0.3

        cubes = []
        for i, s in enumerate(segs):
            prev = segs[i - 1] if i else None
            nxt = segs[i + 1] if i + 1 < len(segs) else None
            front = prev is None or bigger(s, prev)
            back = nxt is None or bigger(s, nxt)
            # A larger neighbour shows its end face and steps that face's outer blocks back by up to
            # 0.12 px (see section); reach into it with this hidden end so no hairline slit opens.
            z0 = s[0] - (LOFT_REACH if prev is not None and not front and bigger(prev, s) else 0.0)
            z1 = s[1] + (LOFT_REACH if nxt is not None and not back and bigger(nxt, s) else 0.0)
            cubes += self.section(bone, z0, z1, s[2], s[3], s[4], s[5], s[6], s[7], mat=mat, lod=lod,
                                  ends=(front, back), top_mat=top_mat, skip_bottom=skip_bottom, round_=round_)
        return cubes

    def slab(self, bone, p0, p1, width, thick, mat, up=(0, 1, 0), lod=True, skip=()):
        """A plate whose centre line runs from p0 to p1 (pitch/yaw only), width
        across X and thickness normal to the plate. Used for sloped panels."""
        p0, p1 = np.array(p0, float), np.array(p1, float)
        d = p1 - p0
        length = float(np.linalg.norm(d))
        c = (p0 + p1) / 2
        horiz = math.hypot(d[0], d[2])
        pitch = math.degrees(math.atan2(d[1], horiz))   # nose-up positive along -Z travel
        yaw = math.degrees(math.atan2(d[0], -d[2])) if horiz > 1e-6 else 0
        # Cube long axis is -Z. Geo rx rotates +Y towards +Z (see rot_matrix), so
        # raising the -Z end needs a negative rx; yaw maps -Z towards +X with -ry.
        return self.cube(bone, (c[0] - width / 2, c[1] - thick / 2, c[2] - length / 2), (width, thick, length), mat,
                         pivot=tuple(c), rotation=(-pitch, -yaw, 0), lod=lod, skip=skip)

    def wedge(self, bone, z0, y0, z1, y1, base, width, mat, cx=0.0, thick=1.6, lod=True):
        """Solid sloped fairing (dorsal ramp, canopy fairing): a slab carries the
        sloped top surface from (z0, y0) to (z1, y1) and stepped boxes fill the
        space under it down to `base`, so no slot opens beneath the slope."""
        self.slab(bone, (cx, y0 - thick / 2, z0), (cx, y1 - thick / 2, z1), width, thick, mat, lod=lod)
        steps = max(1, int(math.ceil(abs(y1 - y0) / (thick * 0.55))))
        for i in range(steps):
            za, zb = z0 + (z1 - z0) * i / steps, z0 + (z1 - z0) * (i + 1) / steps
            ya, yb = y0 + (y1 - y0) * i / steps, y0 + (y1 - y0) * (i + 1) / steps
            top = min(ya, yb) - 0.25
            if top > base:
                self.box(bone, cx - width / 2 + 0.05, cx + width / 2 - 0.05, base, top, za, zb, mat, lod=lod,
                         skip=('up',))

    def panel(self, bone, root, tip, thick, mat, side=1, strips=3, lod=True, thin_tip=None,
              skip_root=True, tip_mat=None, root_in=0.0, le_round=False):
        """Tapered, swept lifting surface (wing, stabiliser) in its own plane (Y=0).

        root/tip: (x_abs, z_le, chord) with x_abs measured outward from the
        centreline (pixels) and z_le the leading-edge Z. side=+1 builds towards
        +X. Per spanwise strip, one box lies along the leading edge and one along
        the trailing edge, so both swept edges are exact; the trailing box is a
        hair (0.04 px a side) thinner so the two tops never z-fight. Both boxes are as deep as
        the strip's smallest chord, so they always meet without a slot; strips
        are added until that holds. root_in buries the root that far inside the
        fuselage, so the slanted ends of the swept boxes leave no gap at the
        joint. Use place_panel to lift it and add dihedral."""
        xr, zr, cr = root
        xt, zt, ct = tip
        le = math.atan2(zt - zr, xt - xr)
        te = math.atan2((zt + ct) - (zr + cr), xt - xr)
        if root_in:
            zr -= root_in * math.tan(le)
            cr += root_in * (math.tan(le) - math.tan(te))
            xr -= root_in
        span = xt - xr

        def chord_at(x):
            return cr + (ct - cr) * (min(max(x, xr), xt) - xr) / span

        def te_depth(xb, cmin):
            # A trailing box swept back (te > 0) leans outboard by d*sin(te)*cos(te)
            # where the chord is shorter: keep its front corner behind the LE there.
            d = cmin
            for _ in range(6):
                shift = max(0.0, math.sin(te) * math.cos(te) * d)
                d = min(cmin, chord_at(xb + shift))
            return d

        def strip_depths(strips):
            out = []
            for k in range(strips):
                xa, xb = xr + span * k / strips, xr + span * (k + 1) / strips
                ca, cb = chord_at(xa), chord_at(xb)
                cmin = min(ca, cb)
                out.append((xa, xb, cmin, te_depth(xb, cmin), max(ca, cb)))
            return out

        while True:
            depths = strip_depths(strips)
            if all(cmin + dte >= cmax + 0.2 for _, _, cmin, dte, cmax in depths) or strips > 16:
                break
            strips += 1
        out = []
        for k, (xa, xb, cmin, d_te, _) in enumerate(depths):
            th = thick   # constant: tapered strips would leave steps on the edges
            if side < 0:
                # Swept root boxes of the two halves overlap inside the fuselage and
                # can cross the centre line; keep their surfaces from being coplanar.
                th += 0.2
            xm = (xa + xb) / 2
            for edge, sweep, t in (('le', le, th), ('te', te, th - 0.08)):
                length = (xb - xa) / math.cos(sweep)
                d = (cmin if edge == 'le' else d_te) * math.cos(sweep)
                xm = (xa + xb) / 2
                if edge == 'te' and k == len(depths) - 1 and sweep > 0:
                    # the outboard lean would carry the last box past the tip: stop it there
                    cut = d * math.sin(sweep)
                    length -= cut / math.cos(sweep)
                    xm -= cut / 2
                if edge == 'le':
                    ze = zr + (xm - xr) * math.tan(sweep)
                    sgn = 1
                else:
                    ze = zr + cr + (xm - xr) * math.tan(sweep)
                    sgn = -1
                skip = set()
                if skip_root and k == 0:
                    skip.add('east' if side > 0 else 'west')
                e = t / 2 if (edge == 'le' and le_round) else 0.0
                if e:
                    # rounded leading edge: a 45-degree 'diamond' whose front vertex is the LE line;
                    # the flat box starts at its widest point, half a thickness behind the LE
                    s = t / math.sqrt(2) - 0.02
                    du, dz = -math.sin(sweep) * e, math.cos(sweep) * e
                    self.cube(bone, (side * (xm + du) - length / 2, -s / 2, ze + dz - s / 2), (length, s, s), mat,
                              pivot=(side * (xm + du), 0, ze + dz), rotation=(45, -side * math.degrees(sweep), 0),
                              lod=lod, skip=set(skip))
                    out.append(self.cubes[-1])
                    skip.add('north')
                u = xm - sgn * math.sin(sweep) * (d + e) / 2
                z = ze + sgn * math.cos(sweep) * (d + e) / 2
                cx = side * u
                cube = self.cube(bone, (cx - length / 2, -t / 2, z - (d - e) / 2), (length, t, d - e),
                                 tip_mat if (tip_mat and k == strips - 1) else mat,
                                 pivot=(cx, 0, z), rotation=(0, -side * math.degrees(sweep), 0),
                                 lod=lod, skip=skip)
                out.append(cube)
        return out


def place_panel(cubes, y_root, dihedral, side, x_root):
    """Lift panel cubes to the root height and apply dihedral about the root line."""
    t = math.tan(math.radians(dihedral))
    for c in cubes:
        cx = c.pivot[0]
        dy = y_root + (abs(cx) - x_root) * t
        c.origin[1] += dy
        c.pivot[1] += dy
        if abs(dihedral) > 1e-6:
            rz = -dihedral * side
            c.rotation = [c.rotation[0], c.rotation[1], rz] if c.rotation else [0, 0, rz]


# ----- texture painting -------------------------------------------------------

def _hash3(ix, iy, iz, seed):
    h = (ix * 374761393 + iy * 668265263 + iz * 2147483647 + seed * 144665) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((h ^ (h >> 16)) & 0xFFFF) / 65535.0


def value_noise(p, scale, seed=0):
    """Smooth 3D value noise in [0, 1] for an (N, 3) array of points."""
    q = p / scale
    i = np.floor(q).astype(np.int64)
    f = q - i
    f = f * f * (3 - 2 * f)
    out = np.zeros(len(p))
    for dx in (0, 1):
        for dy in (0, 1):
            for dz in (0, 1):
                w = (f[:, 0] if dx else 1 - f[:, 0]) * (f[:, 1] if dy else 1 - f[:, 1]) * (f[:, 2] if dz else 1 - f[:, 2])
                out += w * _hash3(i[:, 0] + dx, i[:, 1] + dy, i[:, 2] + dz, seed)
    return out


def fbm(p, scale, seed=0, octaves=3):
    total, amp, norm = 0.0, 1.0, 0.0
    for o in range(octaves):
        total = total + amp * value_noise(p, scale / (2 ** o), seed + o * 17)
        norm += amp
        amp *= 0.5
    return total / norm


def hexrgb(h):
    h = h.lstrip('#')
    return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], float)


class Atlas:
    def __init__(self, size, density):
        self.size = size
        self.density = density
        self.image = np.zeros((size, size, 4), float)

    def pack(self, cubes):
        rects = []
        for c in cubes:
            for face, corner, sv, tv, n in c.faces():
                w = max(1, int(math.ceil(np.linalg.norm(sv) * self.density - 1e-6)))
                h = max(1, int(math.ceil(np.linalg.norm(tv) * self.density - 1e-6)))
                rects.append((h, w, c, face))
        rects.sort(key=lambda r: (-r[0], -r[1]))
        x = y = 0
        shelf = 0
        pad = 1
        for h, w, c, face in rects:
            if x + w + pad > self.size:
                x = 0
                y += shelf + pad
                shelf = 0
            if y + h + pad > self.size:
                raise ValueError('atlas overflow at density %.2f' % self.density)
            c.uv[face] = (x, y, w, h)
            x += w + pad
            shelf = max(shelf, h)
        return y + shelf

    def paint(self, cubes, painter):
        for c in cubes:
            for face, corner, sv, tv, n in c.faces():
                u, v, w, h = c.uv[face]
                ss, tt = np.meshgrid((np.arange(w) + 0.5) / w, (np.arange(h) + 0.5) / h)
                pts = corner[None, :] + ss.reshape(-1, 1) * sv[None, :] + tt.reshape(-1, 1) * tv[None, :]
                wp = c.world(pts)
                wn = c.world_normal(n)
                out = painter(wp, wn, c, face, ss.reshape(-1), tt.reshape(-1), (w, h))
                if out.shape[1] == 3:
                    out = np.concatenate([out, np.full((len(out), 1), 255.0)], 1)
                out = out.reshape(h, w, 4)
                # Gentle outline along every face edge, like hand-painted panel borders.
                edge = np.minimum(np.minimum(ss, 1 - ss) * w, np.minimum(tt, 1 - tt) * h)
                if c.mat != 'glass':
                    # facets of a rounded section are one surface: only a faint seam there
                    k = 0.96 if isinstance(c.tag, str) and c.tag.startswith('arc') else 0.86
                    out[..., :3] *= np.where(edge < 1.0, k, 1.0)[..., None]
                self.image[v:v + h, u:u + w] = out
                # Bleed one texel around the rect to avoid seams under filtering.
                if v > 0:
                    self.image[v - 1, u:u + w] = self.image[v, u:u + w]
                if u > 0:
                    self.image[v:v + h, u - 1] = self.image[v:v + h, u]

    def png(self):
        img = Image.fromarray(np.clip(self.image, 0, 255).astype(np.uint8), 'RGBA')
        buf = io.BytesIO()
        img.save(buf, 'PNG', optimize=True)
        return buf.getvalue()


# ----- export ----------------------------------------------------------------

def r6(v):
    return round(float(v), 5)


def geo_json(model, atlas_size, cubes=None, identifier=None, bounds=(20, 10, 3)):
    cubes = model.cubes if cubes is None else cubes
    bones = []
    for b in model.bones:
        entry = {'name': b.name}
        if b.parent:
            entry['parent'] = b.parent
        entry['pivot'] = [r6(v) for v in b.pivot]
        own = [c for c in cubes if c.bone == b.name]
        out = []
        for c in own:
            uv = {}
            for face, (u, v, w, h) in c.uv.items():
                if face in c.skip:
                    continue
                uv[face] = {'uv': [u, v], 'uv_size': [w, h]}
            if not uv:
                continue
            e = {'origin': [r6(v) for v in c.origin], 'size': [r6(v) for v in c.size]}
            if c.rotation:
                e['pivot'] = [r6(v) for v in c.pivot]
                e['rotation'] = [r6(v) for v in c.rotation]
            e['uv'] = uv
            out.append(e)
        entry['cubes'] = out
        bones.append(entry)
    return {
        'format_version': '1.12.0',
        'minecraft:geometry': [{
            'description': {
                'identifier': identifier or ('geometry.' + model.identifier),
                'texture_width': atlas_size, 'texture_height': atlas_size,
                'visible_bounds_width': bounds[0], 'visible_bounds_height': bounds[1],
                'visible_bounds_offset': [0, bounds[2], 0],
            },
            'bones': bones,
        }],
    }


def bbmodel_from_geo(geo, png_bytes, name):
    """Blockbench project equivalent of a geo file (Blockbench mirrors X)."""
    g = geo['minecraft:geometry'][0]
    tw, th = g['description']['texture_width'], g['description']['texture_height']
    elements, groups, by_name = [], [], {}
    outliner = []
    for b in g['bones']:
        gid = str(uuid.uuid4())
        p = b.get('pivot', [0, 0, 0])
        group = {'uuid': gid, 'name': b['name'], 'origin': [-p[0], p[1], p[2]], 'rotation': [0, 0, 0]}
        groups.append(group)
        node = {'uuid': gid, 'children': []}
        by_name[b['name']] = node
        for c in b.get('cubes', []):
            o, s = c['origin'], c['size']
            eid = str(uuid.uuid4())
            faces = {}
            for face, info in c['uv'].items():
                u, v = info['uv']
                w, h = info['uv_size']
                uv = [u, v, u + w, v + h]
                if face in ('up', 'down'):
                    uv = [uv[2], uv[3], uv[0], uv[1]]
                faces[face] = {'uv': uv, 'texture': 0}
            for face in FACES:
                faces.setdefault(face, {'uv': [0, 0, 0, 0], 'texture': None})
            pv = c.get('pivot', [0, 0, 0])
            r = c.get('rotation', [0, 0, 0])
            elements.append({'uuid': eid, 'name': b['name'], 'type': 'cube', 'box_uv': False,
                             'from': [-(o[0] + s[0]), o[1], o[2]], 'to': [-o[0], o[1] + s[1], o[2] + s[2]],
                             'origin': [-pv[0], pv[1], pv[2]], 'rotation': [-r[0], -r[1], r[2]], 'faces': faces})
            node['children'].append(eid)
        parent = b.get('parent')
        if parent and parent in by_name:
            by_name[parent]['children'].append(node)
        else:
            outliner.append(node)
    src = 'data:image/png;base64,' + base64.b64encode(png_bytes).decode()
    return {
        'meta': {'format_version': '5.0', 'model_format': 'bedrock', 'box_uv': False},
        'name': name, 'model_identifier': name, 'visible_box': [20, 10, 3],
        'resolution': {'width': tw, 'height': th},
        'elements': elements, 'groups': groups, 'outliner': outliner,
        'textures': [{'uuid': str(uuid.uuid4()), 'name': name + '.png', 'source': src, 'path': '',
                      'width': tw, 'height': th, 'uv_width': tw, 'uv_height': th, 'id': '0',
                      'relative_path': '', 'internal': True}],
    }


def dump(path, data):
    with open(path, 'w') as f:
        json.dump(data, f, indent=1)
        f.write('\n')
