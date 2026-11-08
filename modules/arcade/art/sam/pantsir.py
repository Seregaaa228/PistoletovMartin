"""Pantsir-S1 (96K6) air-defence gun/missile system on the KamAZ-6560 8x8 chassis.

Built on the aviation kit (modules/aviation/art/realistic: kit.Model lofts, prisms, slabs;
paint helpers) at the aircraft remakes' detail, driven by modules/arcade/art/sam_assets.py.

Units: 16 per block (1 block = 1 m), front -Z, left +X, ground y = 0.

Proportions (KamAZ-6560 / Pantsir-S1 published data, rounded to the model grid):
  length ~10.6 m, width 2.56 m (hull), wheel track 2.06 m, tyres 425/85R21 (r 0.63 m),
  axle spacing 1.75 + 3.25 + 1.38 m, forward cab 2.0 m long, roof 3.1 m.

Bones (contract with SamLayout.java / SamSiteRenderer.java, numbers kept):
  root > base                    hull, cab, chassis, power unit, module base (static)
  root > Wheel > wheelL0Turn / wheelR0Turn (front axle, steer + spin), wheel[LR]1..3 (spin)
  base > move_turret      (0, 50, 42)   combat module yaw
         move_turret > move_launcher (0, 58, 42)  elevation: both six-round packs, both guns
                       move_launcher > move_gun_l / move_gun_r  barrel group, recoils along +Z
                       move_launcher > move_caps_l / move_caps_r frangible front caps of a pack
         move_turret > move_radar    (0, 70, 48)  search radar sweep about Y
         move_turret > move_tracking (0, 60.6, 24.6) tracking radar elevation about X
         move_turret > move_eo       electro-optical head, yaw about Y / pitch about X
  base > move_door_l / move_door_r   cab doors, vertical hinge on the front edge
  base > move_door_module           module crew door (left side), hinge on its front edge
Inside every move_* bone cubes are axis-aligned or turned about Z only (SBW's baker flips
X/Y rotations in dynamic bones): tubes, barrels, discs are prisms along 'z', everything else
boxes and Z-axis lofts.
"""
from __future__ import annotations

import math

import numpy as np

import kit as K
from kit import Model, axis_rot, fbm, hexrgb, value_noise
from paint import Decal, dilate, hatch, rivet_rows, stations_mask

DENSITY = 1.5
FIXED = {"root", "base"}

# ------------------------------------------------------------------ layout ---
WR = 10.0                 # wheel radius (tyre 425/85R21)
WX = 16.5                 # wheel centre |x|
WW = 7.0                  # tyre width
AXLES = (-64.0, -36.0, 16.0, 38.0)
TURRET = (0.0, 50.0, 42.0)
LAUNCHER = (0.0, 58.0, 42.0)
RADAR = (0.0, 70.0, 48.0)
PACK_X = 24.0
TUBE_DX, TUBE_R = 2.7, 2.4
TUBE_ROWS = (60.2, 65.2, 70.2)
PACK_Z = (4.0, 52.0)       # mouth .. rear end at zero elevation
GUN_Y = 53.4               # barrel axis height
GUN_DX = 1.45              # half spacing of the twin barrels
TRACK = (0.0, 60.6, 24.6)  # tracking radar elevation axis (behind the antenna)
EO = (-6.0, 72.6, 29.5)    # electro-optical head pivot (bottom centre)
DOOR_HINGE_Z = -79.4
MODULE_DOOR = (20.5, 35.0, -9.0)
DRIVER_EYE = (9.5, 42.5, -71.0)
CREW_EYE = (-9.5, 42.5, -71.0)

L, R = 1, -1


def wheel_bone(side, i):
    s = "L" if side > 0 else "R"
    return f"wheel{s}0Turn" if i == 0 else f"wheel{s}{i}"


# --------------------------------------------------------------- helpers ---
class Build:
    """Thin layer over kit.Model: mirrored boxes, oriented plates, bars, arcs, wheels."""

    def __init__(self, m):
        self.m = m

    def box(self, bone, x0, x1, y0, y1, z0, z1, mat, **kw):
        return self.m.box(bone, x0, x1, y0, y1, z0, z1, mat, **kw)

    def sbox(self, bone, x0, x1, y0, y1, z0, z1, mat, **kw):
        return [self.m.box(bone, x0, x1, y0, y1, z0, z1, mat, **kw),
                self.m.box(bone, -x0, -x1, y0, y1, z0, z1, mat, **kw)]

    def ori(self, bone, center, size, mat, rx=0.0, ry=0.0, rz=0.0, **kw):
        r = axis_rot("y", ry) @ axis_rot("x", rx) @ axis_rot("z", rz)
        return self.m.oriented(bone, tuple(center), tuple(size), r, mat, **kw)

    def sori(self, bone, center, size, mat, rx=0.0, ry=0.0, rz=0.0, **kw):
        cx, cy, cz = center
        return [self.ori(bone, (cx, cy, cz), size, mat, rx, ry, rz, **kw),
                self.ori(bone, (-cx, cy, cz), size, mat, rx, -ry, -rz, **kw)]

    def bar(self, bone, p0, p1, w, h, mat, up=(0, 1, 0), **kw):
        p0, p1 = np.asarray(p0, float), np.asarray(p1, float)
        d = p1 - p0
        ln = float(np.linalg.norm(d))
        z = d / ln
        x = np.cross(up, z)
        if np.linalg.norm(x) < 1e-6:
            x = np.cross((1, 0, 0), z) if abs(z[0]) < .9 else np.cross((0, 0, 1), z)
        x /= np.linalg.norm(x)
        r = np.column_stack((x, np.cross(z, x), z))
        return self.m.oriented(bone, tuple((p0 + p1) / 2), (w, h, ln), r, mat, **kw)

    def sbar(self, bone, p0, p1, w, h, mat, **kw):
        f = lambda p: (-p[0], p[1], p[2])  # noqa: E731
        return [self.bar(bone, p0, p1, w, h, mat, **kw), self.bar(bone, f(p0), f(p1), w, h, mat, **kw)]

    def arc(self, bone, x0, x1, cy, cz, r, a0, a1, n, t, mat, **kw):
        """Curved plate round the X axis (wheel arches): angle 0 = top, positive = forward (-Z)."""
        out = []
        da = math.radians(a1 - a0) / n
        chord = 2 * (r + t) * math.tan(abs(da) / 2) + 0.12
        for k in range(n):
            a = math.radians(a0) + da * (k + 0.5)
            rad = np.array([0.0, math.cos(a), -math.sin(a)])
            tan = np.array([0.0, -rad[2], rad[1]])
            c = np.array([(x0 + x1) / 2, cy, cz]) + rad * (r + t / 2)
            fr = np.column_stack(((1.0, 0, 0), rad, tan))
            inset = 0.06 * (k % 2)          # neighbouring pieces overlap: keep their ends apart
            out.append(self.m.oriented(bone, tuple(c), (abs(x1 - x0) - 2 * inset, t, chord), fr, mat, **kw))
        return out

    def sarc(self, bone, x0, x1, *rest, **kw):
        return self.arc(bone, x0, x1, *rest, **kw) + self.arc(bone, -x0, -x1, *rest, **kw)

    def prism(self, bone, axis, center, r, length, mat, n=6, tag=None, **kw):
        cubes = self.m.prism(bone, axis, tuple(center), r, length, mat, n=n, **kw)
        if tag is not None:
            for c in cubes:
                c.tag = tag
        return cubes


# ---------------------------------------------------------------- wheels ---
def wheel(b, bone, cx, cz, side, cy=WR):
    """16-sided military tyre with 32 chevron lugs, dished rim with holes, hub, nuts, CTIS."""
    c = np.array([cx, cy, cz])
    tag = ("wheel", (cx, cy, cz))
    b.prism(bone, "x", c, WR - 0.75, WW, "tyre", n=8, tag=tag)
    b.prism(bone, "x", c, WR - 0.45, WW - 1.6, "tyre", n=8, tag=tag)
    for row, dx in ((0, 1.72), (1, -1.72)):
        for k in range(16):
            th = 22.5 * k + (11.25 if row else 0.0)
            rk = axis_rot("x", th)
            centre = c + np.array([dx, 0, 0]) + rk @ np.array([0.0, -(WR - 0.62), 0.0])
            r = rk @ axis_rot("y", 22 if row else -22)
            b.m.oriented(bone, tuple(centre), (3.0, 1.0, 2.25), r, "tyre").tag = tag
    o = side
    h = WW / 2
    b.prism(bone, "x", (cx + o * (h + 0.15), cy, cz), 6.3, 0.5, "rim", n=8, tag=tag)
    b.prism(bone, "x", (cx + o * (h + 0.55), cy, cz), 4.4, 0.5, "rim", n=6, tag=tag)
    b.prism(bone, "x", (cx + o * (h + 1.05), cy, cz), 2.3, 1.1, "hub", n=5, tag=tag)
    b.prism(bone, "x", (cx + o * (h + 1.85), cy, cz), 1.0, 0.6, "metal", n=3, tag=tag)
    for k in range(8):
        a = math.radians(22.5 + 45 * k)
        b.box(bone, cx + o * (h + 0.75), cx + o * (h + 1.45), cy + 3.3 * math.cos(a) - 0.38,
              cy + 3.3 * math.cos(a) + 0.38, cz + 3.3 * math.sin(a) - 0.38, cz + 3.3 * math.sin(a) + 0.38, "metal")
    # tyre inflation (CTIS) hose from the hub cap to the rim
    b.bar(bone, (cx + o * (h + 1.9), cy + 0.6, cz), (cx + o * (h + 0.55), cy + 5.4, cz - 1.2), 0.45, 0.45, "dark")
    b.prism(bone, "x", (cx - o * (h + 0.7), cy, cz), 5.0, 1.4, "dark", n=5, tag=tag)


# ------------------------------------------------------------------- cab ---
def cab(b):
    B = "base"
    m = b.m
    # lower body (to the windscreen sill), plan-rounded front corners
    m.loft(B, [(-85.0, 0, 27.75, 37.0, 12.5, 0.1, 0.1), (-84.5, 0, 27.75, 38.6, 12.5, 0.1, 0.1),
               (-83.8, 0, 27.75, 39.6, 12.5, 0.1, 0.1), (-83.0, 0, 27.75, 40.0, 12.5, 0.1, 0.1),
               (-53.0, 0, 27.75, 40.0, 12.5, 0.1, 0.1)])
    # upper body behind the sloped windscreen, rounded roof edges
    m.loft(B, [(-82.9, 0, 42.0, 37.6, 16.0, 3.6, 0.1), (-82.3, 0, 42.0, 39.0, 16.0, 4.0, 0.1),
               (-81.4, 0, 42.0, 39.8, 16.0, 4.3, 0.1), (-80.6, 0, 42.0, 40.0, 16.0, 4.4, 0.1),
               (-53.0, 0, 42.0, 40.0, 16.0, 4.4, 0.1)], round_="top")

    def zg(y):                       # windscreen front plane
        return -85.0 + (y - 34.0) * (2.1 / 13.4)

    # fill behind the opaque windscreen
    ys = (34.0, 37.4, 40.8, 44.2, 47.4)
    for ya, yb in zip(ys, ys[1:]):
        b.box(B, -18.6, 18.6, ya, yb, zg(yb) + 0.45, -82.6, "dark")
    m.slab(B, (0, 34.3, zg(34.3) + 0.3), (0, 47.2, zg(47.2) + 0.3), 36.6, 0.55, "tint")
    for s in (L, R):           # A-pillars, rubber seal, corner turn indicators
        m.slab(B, (s * 18.95, 33.6, zg(33.6) + 0.9), (s * 18.95, 48.6, zg(48.6) + 1.0), 2.2, 2.4, "skin")
    m.slab(B, (0, 34.0, zg(34.0) + 0.15), (0, 47.6, zg(47.6) + 0.15), 1.1, 0.9, "rubber")
    b.box(B, -18.8, 18.8, 33.3, 34.3, -85.5, -84.2, "rubber")
    b.box(B, -19.4, 19.4, 47.0, 48.6, -83.3, -81.0, "skin")                        # windscreen header
    for s in (L, R):                                                                  # wipers
        b.bar(B, (s * 3.0, 34.9, zg(34.9) - 0.35), (s * 14.5, 36.2, zg(36.2) - 0.35), 0.5, 0.35, "dark")
        b.box(B, s * 2.4, s * 3.6, 34.3, 35.3, -85.6, -85.0, "dark")
    # sun visor with brackets and three amber roof markers
    m.slab(B, (0, 49.0, -87.3), (0, 50.4, -82.2), 38.8, 0.6, "skin")
    for x in (-17.0, 0.0, 17.0):
        b.box(B, x - 0.6, x + 0.6, 48.2, 49.9, -84.6, -82.4, "dark")
    for x in (-5.0, 0.0, 5.0):
        b.box(B, x - 1.3, x + 1.3, 49.6, 50.9, -85.9, -84.3, "amber")
    # front panel: black grille, badge, corner indicators
    b.box(B, -13.6, 13.6, 23.6, 32.6, -85.35, -84.9, "grille")
    b.box(B, -2.4, 2.4, 31.0, 32.2, -85.6, -85.3, "chrome")
    for s in (L, R):
        b.box(B, s * 15.0, s * 18.0, 29.6, 31.8, -85.3, -84.8, "amber")
        b.box(B, s * 14.4, s * 18.6, 24.0, 28.2, -85.2, -84.9, "skin")              # lamp-guard plate
    # bumper with wrap-round ends, headlights, fog lights, tow hooks, plate
    b.box(B, -20.3, 20.3, 11.0, 20.6, -88.6, -85.0, "skin")
    m.slab(B, (0, 20.6, -88.4), (0, 21.6, -85.2), 40.2, 0.6, "skin")
    b.box(B, -19.0, 19.0, 9.4, 11.0, -87.8, -85.2, "dark")
    for s in (L, R):
        b.box(B, s * 19.2, s * 20.3, 11.0, 20.6, -88.6, -82.5, "skin")
        b.prism(B, "z", (s * 15.3, 16.0, -88.75), 2.75, 0.4, "metal", n=6)
        b.prism(B, "z", (s * 15.3, 16.0, -89.0), 2.25, 0.5, "lens", n=6, tag=("lens", (s * 15.3, 16.0)))
        b.box(B, s * 8.6, s * 11.6, 15.0, 17.6, -88.9, -88.5, "amber")
        b.box(B, s * 5.0, s * 7.2, 11.4, 13.6, -89.6, -88.5, "dark")
        b.box(B, s * 5.4, s * 6.8, 10.6, 11.4, -89.6, -88.9, "dark")
    b.box(B, -4.2, 4.2, 13.6, 16.2, -88.85, -88.55, "plate")
    # roof: ribs, hatch, aerial
    b.sbox(B, 6.0, 7.2, 49.7, 50.5, -78.0, -56.0, "skin")
    b.box(B, -14.0, -4.0, 49.8, 50.9, -74.0, -64.0, "skin")
    b.box(B, -13.4, -4.6, 50.9, 51.3, -73.4, -64.6, "skin")
    b.box(B, -9.8, -8.2, 50.6, 51.6, -63.8, -62.6, "dark")
    b.box(B, 16.0, 18.0, 49.6, 51.0, -56.6, -54.6, "dark")
    b.prism(B, "y", (17.0, 58.0, -55.6), 0.35, 14.0, "antenna", n=2)
    # cab rear wall details
    b.box(B, -12, 12, 30, 46, -53.0, -52.6, "skin")
    for s in (L, R):
        # step plates and brackets ahead of the front wheel, grab rail behind the door
        for y in (8.4, 15.4):
            b.box(B, s * 15.8, s * 20.2, y, y + 1.0, -84.4, -77.6, "metal")
        for z in (-84.4, -78.2):
            b.box(B, s * 19.8, s * 20.3, 7.8, 21.6, z, z + 0.6, "dark")
        b.box(B, s * 20.4, s * 21.0, 28.0, 46.0, -54.7, -54.1, "dark")
        for y in (28.5, 45.0):
            b.box(B, s * 20.0, s * 20.5, y - 0.4, y + 0.4, -54.6, -54.2, "dark")
        # mirrors on two arms, wide-angle mirror below
        b.bar(B, (s * 19.8, 45.6, -80.2), (s * 22.9, 46.6, -82.8), 0.6, 0.6, "dark")
        b.bar(B, (s * 19.8, 37.4, -80.2), (s * 22.9, 38.0, -82.8), 0.6, 0.6, "dark")
        b.box(B, s * 22.4, s * 24.0, 37.4, 47.6, -83.8, -82.2, "dark")
        b.box(B, s * 22.6, s * 23.8, 38.0, 47.0, -82.2, -82.0, "mirror")
        b.box(B, s * 22.4, s * 23.8, 32.6, 36.8, -83.6, -82.3, "dark")
        b.box(B, s * 22.6, s * 23.6, 33.0, 36.4, -82.3, -82.1, "mirror")
        # front wheel arch (concentric with the tyre: steering stays clear), mud flap
        b.arc(B, s * 14.0, s * 20.4, WR, AXLES[0], 11.0, -82.0, 86.0, 10, 0.8, "skin")
        b.box(B, s * 13.6, s * 20.0, 3.6, 12.6, -52.7, -52.1, "rubber")
    # cab doors: separate bones, hinge on the front edge
    for s, name in ((L, "move_door_l"), (R, "move_door_r")):
        z0, z1 = DOOR_HINGE_Z, -57.6
        b.box(name, s * 19.95, s * 20.45, 22.0, 45.4, z0, z1, "skin")
        b.box(name, s * 20.45, s * 20.6, 34.6, 45.0, z0 + 1.0, -64.0, "rubber")
        b.box(name, s * 20.6, s * 20.75, 35.2, 44.6, z0 + 1.6, -64.6, "tint")
        b.box(name, s * 20.45, s * 21.1, 32.4, 33.4, -62.6, -59.6, "dark")         # handle
        b.box(name, s * 20.45, s * 20.9, 28.0, 29.0, -62.0, -59.0, "dark")         # lock plate
        for y in (26.0, 41.0):
            b.box(name, s * 20.45, s * 21.0, y, y + 2.0, z0 - 0.4, z0 + 0.8, "dark")
    for s in (L, R):           # vent (quarter) window ahead of the door
        b.box(B, s * 19.95, s * 20.25, 36.0, 45.0, -81.2, -79.6, "tint")


# ------------------------------------------------------------ chassis ---
def chassis(b):
    B = "base"
    b.sbox(B, 6.2, 8.6, 13.0, 19.5, -84.0, 78.0, "frame")
    for z in (-82.0, -50.0, -20.0, 6.0, 27.0, 50.0, 76.0):
        b.box(B, -6.2, 6.2, 13.6, 18.8, z, z + 2.0, "frame")
    b.sbox(B, 8.6, 12.2, 19.0, 22.2, -21.0, 78.0, "frame")                        # module subframe
    b.box(B, -6.0, 6.0, 9.6, 21.4, -82.0, -55.0, "dark")                           # engine / gearbox
    b.box(B, -5.0, 5.0, 7.6, 9.6, -78.0, -60.0, "dark")                            # sump
    for i, z in enumerate(AXLES):
        b.box(B, -13.0, 13.0, 8.6, 11.4, z - 1.3, z + 1.3, "frame")
        b.prism(B, "z", (0, 10.0, z), 3.1, 5.6, "frame", n=6)
        b.prism(B, "z", (0, 10.0, z - 3.3), 1.6, 1.4, "dark", n=4)
        if i < 2:          # leaf springs, U-bolts, knuckles, shock absorbers, tie rods
            b.sbox(B, 6.4, 8.4, 12.2, 13.0, z - 12.0, z + 12.0, "metal")
            b.sbox(B, 6.4, 8.4, 11.4, 12.2, z - 8.5, z + 8.5, "metal")
            b.sbox(B, 6.2, 8.6, 10.6, 13.4, z - 2.0, z + 2.0, "dark")
            b.sbox(B, 11.4, 13.2, 6.8, 13.2, z - 1.6, z + 1.6, "frame")
            b.sbar(B, (10.2, 11.5, z + 3.0), (9.4, 18.5, z + 6.0), 1.0, 1.0, "metal")
            b.bar(B, (-11.5, 8.0, z - 4.0), (11.5, 8.0, z - 4.0), 0.8, 0.8, "metal")
    for zc in ((AXLES[2] + AXLES[3]) / 2,):            # rear balancer suspension
        b.sbox(B, 6.4, 8.4, 12.2, 13.0, zc - 17.0, zc + 17.0, "metal")
        b.sbox(B, 6.4, 8.4, 11.4, 12.2, zc - 13.0, zc + 13.0, "metal")
        b.sbox(B, 6.4, 8.4, 10.6, 11.4, zc - 8.0, zc + 8.0, "metal")
        b.prism(B, "x", (0, 14.6, zc), 1.8, 22.0, "frame", n=5)
        b.sbox(B, 6.0, 9.0, 12.8, 17.0, zc - 3.0, zc + 3.0, "frame")
        for dz in (-1, 1):
            b.sbar(B, (5.0, 9.4, zc + dz * 3.0), (5.0, 9.4, zc + dz * 19.0), 1.0, 1.0, "frame")
    # drive line
    for z0, z1 in ((-58.0, -38.6), (-33.4, -14.0), (-2.0, 13.4), (18.6, 35.4)):
        b.prism(B, "z", (0, 10.4, (z0 + z1) / 2), 0.9, z1 - z0, "metal", n=4)
    b.box(B, -5.0, 5.0, 8.2, 15.6, -14.0, -2.0, "frame")                           # transfer case
    b.prism(B, "x", (0, 16.0, -26.0), 2.2, 10.0, "dark", n=6)                      # air tanks
    b.prism(B, "x", (0, 16.0, 62.0), 2.2, 10.0, "dark", n=6)


def fenders(b):
    B = "base"
    for s in (L, R):
        # second axle under the power unit: arch and flap
        b.arc(B, s * 13.0, s * 19.6, WR, AXLES[1], 11.0, -72.0, 72.0, 8, 0.8, "skin")
        b.box(B, s * 13.2, s * 19.6, 3.4, 14.0, -24.0, -23.4, "rubber")
        # rear tandem: flat mudguard with sloped ends, flap behind
        b.box(B, s * 12.6, s * 20.6, 21.0, 21.9, 5.2, 49.0, "skin")
        b.m.slab(B, (s * 16.6, 21.4, 5.6), (s * 16.6, 15.6, 2.4), 8.0, 0.8, "skin")
        b.m.slab(B, (s * 16.6, 21.4, 48.6), (s * 16.6, 15.6, 52.2), 8.0, 0.8, "skin")
        b.box(B, s * 13.2, s * 20.0, 3.0, 15.8, 52.6, 53.2, "rubber")
        # side under-run rail between the tandem wheels
        b.box(B, s * 19.6, s * 20.4, 14.0, 15.4, 26.6, 27.4, "dark")


def side_equipment(b):
    B = "base"
    m = b.m
    # fuel tank (left), straps, filler, brackets
    m.section(B, -19.6, 2.0, 16.4, 13.5, 6.6, 9.4, 1.8, 1.8, mat="skin", round_=True)
    for z in (-15.4, -3.0):
        b.box(B, 12.9, 19.9, 8.6, 18.4, z, z + 0.9, "dark")
        b.box(B, 8.6, 13.2, 15.0, 17.0, z - 0.2, z + 1.1, "frame")
    b.prism(B, "y", (17.4, 18.9, -9.0), 1.05, 1.0, "metal", n=4)
    b.box(B, 16.6, 18.2, 19.3, 19.7, -9.8, -8.2, "dark")
    # spare wheel (right side, vertical, in a carrier)
    wheel(b, B, -16.4, -6.6, R, cy=10.8)
    b.box(B, -12.8, -8.6, 9.6, 12.0, -8.0, -5.2, "frame")
    b.box(B, -13.4, -12.6, 0.6, 21.8, -18.2, -17.2, "frame")
    b.box(B, -13.4, -12.6, 0.6, 21.8, 4.0, 5.0, "frame")
    b.box(B, -13.4, -8.6, 0.6, 1.6, -18.2, 5.0, "frame")
    # toolboxes under the rear overhang (lid lines are painted), battery box
    for s in (L, R):
        b.box(B, s * 13.2, s * 20.0, 11.6, 21.0, 55.0, 73.0, "skin")
        b.box(B, s * 20.0, s * 20.5, 19.0, 20.4, 57.0, 71.0, "dark")
        for z in (59.0, 69.0):
            b.box(B, s * 20.0, s * 20.6, 16.4, 18.4, z - 0.7, z + 0.7, "dark")
    b.box(B, 12.6, 19.8, 10.6, 18.6, 3.4, 8.6, "skin")
    b.box(B, 13.0, 19.4, 18.6, 19.2, 3.8, 8.2, "dark")


# ------------------------------------------------------- power unit, module ---
def hull(b):
    B = "base"
    m = b.m
    # exhaust stack (left) and air intake (right) behind the cab
    b.prism(B, "y", (14.4, 33.0, -50.0), 1.3, 38.0, "exhaust", n=6)
    b.prism(B, "z", (14.4, 52.0, -49.0), 1.3, 2.4, "exhaust", n=6)
    b.box(B, 15.7, 17.0, 34.0, 46.0, -51.4, -48.6, "grille")                      # heat guard
    b.prism(B, "y", (-14.4, 38.0, -50.0), 2.2, 32.0, "skin", n=6)
    for y in (30.0, 46.0):
        b.prism(B, "y", (-14.4, y, -50.0), 2.45, 0.9, "dark", n=6)
    b.prism(B, "y", (-14.4, 55.0, -50.0), 2.9, 2.2, "skin", n=6)
    b.prism(B, "y", (-14.4, 56.5, -50.0), 2.5, 0.8, "dark", n=6)
    # power unit (gas-turbine generator) housing
    m.loft(B, [(-47.0, 0, 32.5, 35.6, 22.4, 2.4, 0.1), (-46.2, 0, 32.5, 37.0, 23.0, 2.6, 0.1),
               (-22.3, 0, 32.5, 37.0, 23.0, 2.6, 0.1), (-21.5, 0, 32.5, 35.6, 22.4, 2.4, 0.1)], round_="top")
    for s in (L, R):
        for z0, z1 in ((-45.0, -36.6), (-33.6, -25.2)):
            b.box(B, s * 18.5, s * 18.85, 26.0, 40.0, z0, z1, "skin")
            b.box(B, s * 18.85, s * 19.05, 27.0, 39.0, z0 + 0.8, z1 - 0.8, "grille")
            b.box(B, s * 18.85, s * 19.3, 32.4, 33.6, z1 - 1.8, z1 - 0.9, "dark")
    b.box(B, -7.0, 7.0, 44.0, 44.4, -42.0, -28.0, "grille")
    b.box(B, -7.6, 7.6, 43.8, 44.1, -42.6, -27.4, "skin")
    b.box(B, 6.0, 12.0, 44.0, 46.0, -26.0, -22.6, "dark")
    b.box(B, 6.4, 11.6, 46.0, 46.2, -25.6, -23.0, "grille")
    # module base: lower front block, sloped front, body with bevelled top edges
    b.box(B, -20.5, 20.5, 22.0, 38.0, -21.0, -14.0, "skin")
    m.wedge(B, -21.0, 38.0, -14.0, 48.0, 37.6, 41.0, "skin", thick=1.6)
    m.loft(B, [(-14.0, 0, 35.0, 41.0, 26.0, 1.6, 0.1), (78.0, 0, 35.0, 41.0, 26.0, 1.6, 0.1)], round_="top")
    # turret ring (static race) and roof furniture below the gun sweep (y < 49.4)
    b.prism(B, "y", (0, 48.6, TURRET[2]), 16.6, 1.4, "dark", n=8)
    b.prism(B, "y", (0, 48.2, TURRET[2]), 17.4, 0.8, "skin", n=8)
    b.box(B, -9.0, 1.0, 48.0, 48.8, -4.0, 8.0, "skin")                              # roof hatch
    b.box(B, -8.4, 0.4, 48.8, 49.1, -3.4, 7.4, "skin")
    b.box(B, 0.4, 1.4, 48.0, 49.2, -2.0, 0.0, "dark")
    b.box(B, 0.4, 1.4, 48.0, 49.2, 4.0, 6.0, "dark")
    for x0 in (6.0, 12.0):
        b.box(B, x0, x0 + 5.0, 48.0, 49.0, 64.0, 72.0, "skin")
        b.box(B, x0 + 0.4, x0 + 4.6, 49.0, 49.2, 64.4, 71.6, "grille")
    for s in (L, R):
        for z in (-12.0, 75.0):
            b.box(B, s * 18.6, s * 19.6, 48.0, 49.2, z - 1.0, z + 1.0, "dark")      # lifting eyes
        # aerial masts on the front corners, clear of the guns' sweep
        b.box(B, s * 17.4, s * 19.8, 38.4, 44.0, -19.6, -16.6, "skin")
        b.prism(B, "y", (s * 18.6, 55.0, -18.1), 0.65, 22.0, "antenna", n=4)
        b.prism(B, "y", (s * 18.6, 76.0, -18.1), 0.3, 20.0, "antenna", n=2)
        b.prism(B, "y", (s * 18.6, 66.2, -18.1), 0.9, 0.6, "dark", n=4)
        # side marker lamps
        for z in (-12.0, 8.0, 46.0, 74.0):
            b.box(B, s * 20.5, s * 20.9, 22.6, 23.6, z - 0.8, z + 0.8, "amber")
        # ventilation louvres and rear equipment bay doors
        b.box(B, s * 20.5, s * 20.85, 26.0, 45.0, 55.0, 75.0, "skin")
        b.box(B, s * 20.85, s * 21.05, 33.0, 43.0, 57.0, 66.0, "grille")
        for z in (68.5, 73.0):
            b.box(B, s * 20.85, s * 21.4, 34.0, 36.0, z - 0.6, z + 0.6, "dark")
        for y in (27.6, 42.0):
            b.box(B, s * 20.85, s * 21.3, y, y + 1.6, 54.6, 55.6, "dark")
        # cable boxes and fire extinguisher between the tandem arches and the door
        b.box(B, s * 20.5, s * 21.2, 24.0, 30.0, 18.0, 24.0, "skin")
    b.box(B, 20.5, 20.8, 24.4, 26.0, 30.0, 31.2, "dark")                             # shovel and crowbar
    b.box(B, 20.5, 20.8, 24.4, 26.0, 47.0, 48.2, "dark")
    b.box(B, 20.8, 21.3, 24.6, 25.4, 33.0, 46.0, "wood")
    b.box(B, 20.8, 21.2, 23.6, 26.4, 45.6, 50.4, "metal")
    b.box(B, 20.8, 21.3, 26.6, 27.2, 29.0, 49.0, "dark")
    b.box(B, -20.85, -20.5, 25.0, 46.0, -11.0, 6.0, "skin")                        # right service door
    b.box(B, -21.3, -20.85, 32.0, 33.2, 2.0, 4.6, "dark")
    b.box(B, -21.2, -20.85, 38.0, 44.0, -10.0, -8.0, "red")                         # extinguisher
    # module crew door (left), ladder
    dname = "move_door_module"
    x0 = MODULE_DOOR[0]
    b.box(dname, x0, x0 + 0.45, 23.4, 46.6, -9.0, 5.0, "skin")
    b.box(dname, x0 + 0.45, x0 + 0.55, 38.4, 45.0, -6.6, 2.6, "rubber")
    b.box(dname, x0 + 0.55, x0 + 0.7, 39.0, 44.4, -6.0, 2.0, "tint")
    b.box(dname, x0 + 0.45, x0 + 1.1, 31.6, 32.6, 1.0, 3.6, "dark")
    for y in (26.0, 42.0):
        b.box(dname, x0 + 0.45, x0 + 1.0, y, y + 2.0, -9.4, -8.2, "dark")
    for z in (-7.4, 3.0):
        b.box(B, 20.6, 21.2, 3.0, 23.2, z, z + 0.6, "metal")
    for y in (5.5, 10.0, 14.5, 19.0):
        b.box(B, 20.6, 21.3, y - 0.35, y + 0.35, -6.8, 3.0, "metal")
    # rear: bumper, lamps, pintle, plate, ladder, air conditioner
    b.box(B, -19.0, 19.0, 9.6, 17.0, 78.0, 80.4, "skin")
    for s in (L, R):
        b.box(B, s * 13.0, s * 17.4, 13.0, 16.0, 80.4, 80.8, "red")
        b.box(B, s * 9.6, s * 12.6, 13.0, 16.0, 80.4, 80.8, "amber")
        b.box(B, s * 13.0, s * 15.0, 10.6, 12.4, 80.4, 80.8, "lens")
    b.box(B, -1.8, 1.8, 10.2, 13.6, 80.4, 82.0, "dark")
    b.prism(B, "y", (0, 11.9, 81.4), 1.0, 3.8, "metal", n=4)
    b.box(B, -4.2, 4.2, 13.8, 16.4, 80.4, 80.6, "plate")
    for x in (-17.8, -12.6):
        b.box(B, x - 0.3, x + 0.3, 17.0, 48.6, 78.0, 79.6, "metal")
    for y in range(19, 48, 5):
        b.box(B, -17.6, -12.8, y - 0.3, y + 0.3, 78.6, 79.2, "metal")
    b.box(B, 2.0, 14.0, 29.0, 42.0, 78.0, 80.0, "skin")
    b.box(B, 3.0, 13.0, 30.0, 41.0, 80.0, 80.2, "grille")
    b.box(B, -8.0, -1.0, 26.0, 32.0, 78.0, 79.0, "dark")                           # cable connector box


# ------------------------------------------------------------- turret ---
def turret(b):
    T = "move_turret"
    m = b.m
    px, py, pz = TURRET
    # rotating platform (stepped disc in plan: axis-aligned boxes only)
    for hx, hz in ((15.8, 8.0), (13.8, 12.0), (10.6, 14.4), (6.0, 15.8)):
        b.box(T, -hx, hx, 49.4, 50.6, pz - hz, pz + hz, "skin")
    # housing: loft along Z, sloped (stepped) front, bevelled sides, rear bustle
    st = [(24.0, 0, 59.6, 23.0, 15.6, 3.6, 1.0), (25.0, 0, 60.0, 25.4, 18.6, 4.4, 1.0),
          (26.0, 0, 60.4, 27.0, 20.2, 5.0, 1.0), (27.2, 0, 60.6, 28.0, 21.0, 5.2, 1.0),
          (58.0, 0, 60.6, 28.0, 21.0, 5.2, 1.0), (59.0, 0, 60.4, 27.2, 20.4, 5.0, 1.0),
          (60.0, 0, 60.0, 26.0, 19.6, 4.6, 1.0)]
    m.loft(T, K.smooth(st[:4], 2) + st[4:])
    m.loft(T, [(60.0, 0, 59.0, 22.0, 15.0, 3.4, 0.8), (64.4, 0, 59.0, 22.0, 15.0, 3.4, 0.8),
               (65.4, 0, 59.0, 20.4, 13.6, 3.0, 0.8)])
    b.box(T, -8.0, 8.0, 54.0, 64.0, 65.4, 65.7, "grille")                            # cooling outlet
    for s in (L, R):
        # trunnion housings, side access panels, latches, ammunition chutes
        b.box(T, s * 13.6, s * 16.2, 54.4, 61.6, 38.4, 45.6, "skin")
        b.prism(T, "z", (s * 15.2, 58.0, 42.0), 2.2, 9.0, "skin", n=6)
        b.box(T, s * 14.0, s * 14.4, 52.0, 64.4, 28.6, 37.0, "skin")
        b.box(T, s * 14.0, s * 14.4, 52.0, 64.4, 47.0, 57.4, "skin")
        for z in (30.0, 35.6, 48.4, 56.0):
            b.box(T, s * 14.4, s * 14.8, 62.4, 63.4, z - 0.5, z + 0.5, "dark")
        b.box(T, s * 13.8, s * 16.6, 51.2, 54.0, 33.6, 44.0, "canvas")
        # roof hand rails on posts
        b.box(T, s * 8.0, s * 8.6, 72.2, 72.7, 34.0, 41.0, "metal")
        for z in (34.0, 41.0):
            b.box(T, s * 8.0, s * 8.6, 71.0, 72.2, z - 0.3, z + 0.3, "metal")
    # roof: radar well, IFF / direction-finder head, GPS pucks, air vents
    b.box(T, -4.4, 4.4, 71.0, 71.5, 43.6, 52.4, "dark")
    b.box(T, 3.0, 9.0, 71.0, 72.4, 26.6, 30.4, "skin")
    b.box(T, 4.0, 8.0, 72.4, 75.6, 27.2, 28.4, "radome")
    b.box(T, 4.2, 7.8, 75.6, 76.0, 27.0, 28.6, "dark")
    for x in (-3.0, 1.5):
        b.prism(T, "z", (x, 71.6, 58.4), 0.9, 1.2, "radome", n=4)
    b.box(T, -7.0, 7.0, 71.0, 71.8, 56.0, 59.4, "grille")
    # tracking radar: elevating phased-array disc on the front (own bone)
    tr = "move_tracking"
    tx, ty, tz = TRACK
    b.prism(tr, "z", (tx, ty, tz - 1.2), 9.4, 1.6, "skin", n=10)
    b.prism(tr, "z", (tx, ty, tz - 2.9), 9.0, 1.8, "radome", n=10, tag=("radome", (tx, ty)))
    b.prism(tr, "z", (tx, ty, tz - 4.0), 1.6, 0.6, "dark", n=6)
    b.box(tr, -10.4, -9.0, ty - 2.0, ty + 2.0, tz - 2.6, tz + 0.2, "dark")
    b.box(tr, 9.0, 10.4, ty - 2.0, ty + 2.0, tz - 2.6, tz + 0.2, "dark")
    b.box(tr, -3.0, 3.0, ty - 10.6, ty - 9.0, tz - 2.4, tz + 0.0, "skin")
    # electro-optical station (own bone): base, head, windows, sun hood
    eo = "move_eo"
    ex, ey, ez = EO
    b.box(T, ex - 3.6, ex + 3.6, 71.0, 72.6, ez - 3.6, ez + 3.6, "skin")
    b.prism(T, "z", (ex, 72.0, ez), 2.6, 5.4, "dark", n=6)
    b.box(eo, ex - 3.0, ex + 3.0, ey, ey + 0.8, ez - 3.0, ez + 3.0, "dark")
    m.section(eo, ez - 3.0, ez + 3.2, ex, ey + 3.6, 6.6, 5.6, 1.6, 0.8, mat="skin")
    b.box(eo, ex - 2.8, ex - 0.2, ey + 2.2, ey + 5.0, ez - 3.2, ez - 3.0, "eo")
    b.box(eo, ex + 0.4, ex + 2.6, ey + 3.0, ey + 5.0, ez - 3.2, ez - 3.0, "eo2")
    b.box(eo, ex + 0.8, ex + 2.2, ey + 1.4, ey + 2.4, ez - 3.2, ez - 3.0, "lens")
    b.box(eo, ex - 3.4, ex + 3.4, ey + 6.4, ey + 6.8, ez - 4.6, ez - 2.8, "skin")
    b.box(eo, ex - 3.6, ex - 3.3, ey + 1.0, ey + 6.8, ez - 4.6, ez + 1.0, "skin")
    b.box(eo, ex + 3.3, ex + 3.6, ey + 1.0, ey + 6.8, ez - 4.6, ez + 1.0, "skin")


def search_radar(b):
    Rd = "move_radar"
    rx, ry, rz = RADAR
    b.prism(Rd, "z", (0, 72.6, rz), 2.4, 5.0, "metal", n=6)                          # rotary joint
    b.box(Rd, -6.0, 6.0, 74.4, 75.4, rz - 1.4, rz + 3.0, "skin")
    b.box(Rd, -1.6, 1.6, 71.6, 74.6, rz - 1.6, rz + 1.6, "dark")
    # face rows stepped back towards the top: a back-tilted phased array
    for j in range(7):
        y0 = 75.0 + j * 1.8
        b.box(Rd, -12.6, 12.6, y0, y0 + 1.8, rz - 2.2 + j * 0.28, rz + 1.2, "array")
    b.box(Rd, -13.4, 13.4, 74.6, 88.0, rz + 1.2, rz + 2.4, "skin")
    b.sbox(Rd, 12.6, 13.4, 74.6, 88.0, rz - 2.6, rz + 1.2, "skin")
    b.box(Rd, -13.4, 13.4, 87.6, 88.4, rz - 0.8, rz + 2.4, "skin")
    b.box(Rd, -13.4, 13.4, 74.6, 75.0, rz - 2.6, rz + 1.2, "skin")
    b.box(Rd, -9.0, 9.0, 88.4, 89.6, rz - 0.4, rz + 1.6, "radome")                  # IFF array
    b.box(Rd, -7.0, 7.0, 76.0, 84.0, rz + 2.4, rz + 5.2, "skin")                    # electronics
    b.box(Rd, -6.0, 6.0, 77.0, 83.0, rz + 5.2, rz + 5.4, "grille")
    for s in (L, R):
        b.box(Rd, s * 7.0, s * 12.0, 80.0, 81.0, rz + 2.4, rz + 3.4, "skin")       # stiffeners


# ----------------------------------------------------------- launcher ---
def launcher(b):
    Lb = "move_launcher"
    m = b.m
    _, ly, lz = LAUNCHER
    z0, z1 = PACK_Z
    for s, side in ((L, "l"), (R, "r")):
        xc = s * PACK_X
        # trunnion, cradle plate, gun bracket
        b.prism(Lb, "z", (s * 17.6, ly, lz), 1.8, 7.0, "metal", n=5)
        b.box(Lb, s * 16.2, s * 18.4, ly - 1.4, ly + 1.4, lz - 1.4, lz + 1.4, "metal")
        b.box(Lb, s * 17.6, s * 18.5, 50.6, 73.0, 30.0, 50.0, "skin")
        b.box(Lb, s * 18.5, s * 20.6, 51.6, 55.4, 35.0, 47.0, "skin")
        # pack frame: inner mounting plate and bottom tray, four clamp bands (top rail with
        # lifting lugs), front and rear grids; the round tubes stay visible between the bands
        xi = xc - s * 5.6                                     # inboard face of the tube bundle
        b.box(Lb, xi, xi - s * 0.6, 56.8, 73.0, z0 + 2.4, z1 - 0.6, "skin")
        b.box(Lb, xc - 5.0, xc + 5.0, 56.9, 57.7, z0 + 2.4, z1 - 0.6, "skin")
        for zb in (9.0, 20.0, 34.0, 45.4):
            b.box(Lb, xc - 5.8, xc + 5.8, 56.3, 57.1, zb, zb + 1.8, "skin")
            b.box(Lb, xc - 5.8, xc + 5.8, 72.7, 73.5, zb, zb + 1.8, "skin")
            for x in (xc - 5.8, xc + 5.2):
                b.box(Lb, x, x + 0.6, 57.1, 72.7, zb, zb + 1.8, "skin")
            for y in (62.7, 67.7):
                b.box(Lb, xc - 5.2, xc + 5.2, y - 0.3, y + 0.3, zb + 0.2, zb + 1.6, "dark")
            b.box(Lb, xc - 0.3, xc + 0.3, 57.1, 72.7, zb + 0.2, zb + 1.6, "dark")
        b.box(Lb, xc - 1.0, xc + 1.0, 73.5, 74.3, 9.0, 47.2, "skin")
        for zl in (14.0, 40.0):
            b.box(Lb, xc - 0.5, xc + 0.5, 74.3, 75.4, zl - 0.8, zl + 0.8, "dark")
        for zf in (z0 + 0.9, z1 - 1.4):
            b.box(Lb, xc - 5.6, xc + 5.6, 56.8, 57.7, zf, zf + 1.4, "skin")
            b.box(Lb, xc - 5.6, xc + 5.6, 72.7, 73.6, zf, zf + 1.4, "skin")
            for x in (xc - 5.6, xc + 5.1):
                b.box(Lb, x, x + 0.5, 57.7, 72.7, zf, zf + 1.4, "skin")
            b.box(Lb, xc - 0.4, xc + 0.4, 57.7, 72.7, zf, zf + 1.4, "skin")
            for y in (62.7, 67.7):
                b.box(Lb, xc - 5.1, xc + 5.1, y - 0.4, y + 0.4, zf, zf + 1.4, "skin")
        b.box(Lb, xc - 3.0, xc + 3.0, 73.5, 74.1, z1 - 4.4, z1 - 1.6, "dark")      # connector box
        # six 57E6 launch tubes, collars with mouths, rear caps
        for ty in TUBE_ROWS:
            for dx in (-TUBE_DX, TUBE_DX):
                tx = xc + dx
                b.prism(Lb, "z", (tx, ty, (z0 + 1.6 + z1) / 2), TUBE_R, z1 - z0 - 1.6, "tube", n=8)
                b.prism(Lb, "z", (tx, ty, z0 + 1.1), TUBE_R + 0.22, 2.2, "tube", n=8,
                        tag=("mouth", (tx, ty)))
                b.prism(Lb, "z", (tx, ty, z1 + 0.5), TUBE_R - 0.2, 1.0, "tube", n=8,
                        tag=("rear", (tx, ty)))
                b.prism("move_caps_" + side, "z", (tx, ty, z0 - 0.15), 1.75, 0.3, "cap", n=8,
                        tag=("cap", (tx, ty)))
        # 2A38M gun: fixed receiver in the launcher, recoiling barrels in move_gun_*
        gx = xc
        m.section(Lb, 29.4, 50.0, gx, GUN_Y, 7.0, 5.6, 1.4, 1.2, mat="skin")
        b.box(Lb, gx - 3.0, gx + 3.0, 56.0, 56.8, 31.0, 48.0, "skin")
        b.box(Lb, gx - 3.8, gx + 3.8, GUN_Y - 3.0, GUN_Y + 3.0, 28.8, 29.4, "metal")
        b.box(Lb, gx - 2.2, gx + 2.2, GUN_Y - 3.6, GUN_Y - 2.8, 34.0, 46.0, "dark")
        b.box(Lb, gx + s * 3.5, gx + s * 4.2, GUN_Y - 1.8, GUN_Y + 1.8, 34.0, 44.0, "dark")
        g = "move_gun_" + side
        for d in (-GUN_DX, GUN_DX):
            bx = gx + d
            b.prism(g, "z", (bx, GUN_Y, 25.2), 1.3, 7.2, "gunmetal", n=5)         # cooling jacket
            b.prism(g, "z", (bx, GUN_Y, 7.2), 0.78, 29.0, "gunmetal", n=5)         # barrel
            b.prism(g, "z", (bx, GUN_Y, -7.2), 1.08, 2.8, "gunmetal", n=5, tag=("muzzle", (bx, GUN_Y)))
            b.prism(g, "z", (bx, GUN_Y, -5.5), 0.9, 0.6, "dark", n=4)
            b.prism(g, "z", (bx, GUN_Y, 21.4), 1.5, 0.8, "metal", n=5)
        b.box(g, gx - 2.6, gx + 2.6, GUN_Y - 1.1, GUN_Y + 1.1, 9.0, 10.4, "gunmetal")  # barrel clamp
        b.box(g, gx - 0.4, gx + 0.4, GUN_Y - 0.5, GUN_Y + 0.5, 10.4, 21.0, "gunmetal")


# ---------------------------------------------------------------- build ---
def build():
    m = Model("pantsir_s1")
    m.bone("root", None)
    m.bone("base", "root")
    m.bone("Wheel", "root")
    for i, z in enumerate(AXLES):
        for s in (L, R):
            m.bone(wheel_bone(s, i), "Wheel", (s * WX, WR, z))
    m.bone("move_turret", "base", TURRET)
    m.bone("move_launcher", "move_turret", LAUNCHER)
    m.bone("move_radar", "move_turret", RADAR)
    m.bone("move_tracking", "move_turret", TRACK)
    m.bone("move_eo", "move_turret", EO)
    for s, side in ((L, "l"), (R, "r")):
        m.bone("move_gun_" + side, "move_launcher", (s * PACK_X, GUN_Y, 29.4))
        m.bone("move_caps_" + side, "move_launcher", (s * PACK_X, 65.2, PACK_Z[0]))
    m.bone("move_door_l", "base", (20.2, 35.0, DOOR_HINGE_Z))
    m.bone("move_door_r", "base", (-20.2, 35.0, DOOR_HINGE_Z))
    m.bone("move_door_module", "base", MODULE_DOOR)
    b = Build(m)
    for i, z in enumerate(AXLES):
        for s in (L, R):
            wheel(b, wheel_bone(s, i), s * WX, z, s)
    cab(b)
    chassis(b)
    fenders(b)
    side_equipment(b)
    hull(b)
    turret(b)
    search_radar(b)
    launcher(b)
    return m


PREVIEW_POSES = {"pantsir_s1": {"move_turret": axis_rot("y", 40), "move_launcher": axis_rot("x", 30),
                                "move_door_r": axis_rot("y", -60)}}


# ---------------------------------------------------------------- paint ---
GREEN, SAND, BLACK = hexrgb("#55633a"), hexrgb("#a39069"), hexrgb("#2a2b25")
WHITE = np.array([226, 224, 212.0])
PAL = {
    "frame": "#2b2c27", "dark": "#30322c", "rubber": "#1c1c1a", "metal": "#6c6d64", "chrome": "#b8bbb4",
    "gunmetal": "#3a3c37", "exhaust": "#5e4b3a", "amber": "#d58a26", "red": "#9e231c", "lens": "#d6dbd2",
    "mirror": "#93a5aa", "plate": "#dedcd0", "antenna": "#2c2e29", "canvas": "#56583c", "grille": "#22231f",
    "olive": "#4c5833", "rim": "#4a5632", "hub": "#3d4729", "array": "#454b3a", "radome": "#59614a",
    "cap": "#3e4530", "wood": "#7a5c3a", "eo": "#1a2a33", "eo2": "#1f2a2a", "tint": "#141b1e", "interior": "#2a2c28",
}
PALC = {k: hexrgb(v) for k, v in PAL.items()}

FONT = {
    '0': ['01110', '10001', '10011', '10101', '11001', '10001', '01110'],
    '1': ['00100', '01100', '00100', '00100', '00100', '00100', '01110'],
    '2': ['01110', '10001', '00001', '00010', '00100', '01000', '11111'],
    '3': ['11110', '00001', '00001', '01110', '00001', '00001', '11110'],
    '4': ['00010', '00110', '01010', '10010', '11111', '00010', '00010'],
    '5': ['11111', '10000', '11110', '00001', '00001', '10001', '01110'],
    '7': ['11111', '00001', '00010', '00100', '01000', '01000', '01000'],
    '8': ['01110', '10001', '10001', '01110', '10001', '10001', '01110'],
    '.': ['00000', '00000', '00000', '00000', '00000', '01100', '01100'],
    ' ': ['00000'] * 7,
    'A': ['01110', '10001', '10001', '11111', '10001', '10001', '10001'],
    'B': ['11110', '10001', '10001', '11110', '10001', '10001', '11110'],
    'E': ['11111', '10000', '10000', '11110', '10000', '10000', '11111'],
    'H': ['10001', '10001', '10001', '11111', '10001', '10001', '10001'],
    'K': ['10001', '10010', '10100', '11000', '10100', '10010', '10001'],
    'M': ['10001', '11011', '10101', '10101', '10001', '10001', '10001'],
    'O': ['01110', '10001', '10001', '10001', '10001', '10001', '01110'],
    'P': ['11110', '10001', '10001', '11110', '10000', '10000', '10000'],
    'C': ['01110', '10001', '10000', '10000', '10000', '10001', '01110'],
    'T': ['11111', '00100', '00100', '00100', '00100', '00100', '00100'],
    'X': ['10001', '10001', '01010', '00100', '01010', '10001', '10001'],
    'Д': ['00110', '01010', '01010', '01010', '01010', '11111', '10001'],
    'П': ['11111', '10001', '10001', '10001', '10001', '10001', '10001'],
    'У': ['10001', '10001', '10001', '01111', '00001', '00001', '01110'],
    'Ь': ['10000', '10000', '10000', '11110', '10001', '10001', '11110'],
    'Л': ['00111', '01001', '01001', '01001', '01001', '01001', '11001'],
    'Ж': ['10101', '10101', '01110', '00100', '01110', '10101', '10101'],
}
for _cyr, _lat in zip("АВЕНКМОРСТХ", "ABEHKMOPCTX"):
    FONT[_cyr] = FONT[_lat]


def text_mask(text):
    rows = []
    for r in range(7):
        rows.append([int(ch) for c in text for ch in (FONT[c][r] + '0')][:-1])
    return np.array(rows, bool)


def side_decal(x, y, z, size, layers, depth=1.5):
    """Same marking on both sides, reading front to back (SBW draws geo X mirrored)."""
    return [Decal((sx * x, y, z), (sx, 0, 0), (0, 0, sx), (0, -1, 0), size, layers, max_depth=depth)
            for sx in (1, -1)]


def camo(p):
    q = p.copy()
    q[:, 0] += (value_noise(p, 23.0, 401) - 0.5) * 14
    q[:, 2] += (value_noise(p, 21.0, 402) - 0.5) * 14
    q[:, 1] *= 0.8
    v = fbm(q, 30.0, 411, 3)
    w = fbm(q + np.array([131.0, 7.0, 57.0]), 24.0, 433, 3)
    rgb = np.tile(GREEN, (len(p), 1))
    sand = v > 0.565
    black = (w > 0.585) & (v < 0.53)
    rgb[sand] = SAND
    rgb[black] = BLACK
    return rgb


def mud(p, n, rgb, heavy=0.0):
    y = p[:, 1]
    lo = np.clip((25.0 - y) / 17.0, 0, 1) ** 1.25
    g = fbm(p, 7.0, 77, 3)
    amt = lo * np.clip(0.25 + 1.2 * (g - 0.38), 0, 1)
    spat = (value_noise(p, 0.65, 78) > 0.74) & (y < 33) & (y > 3)
    amt = np.where(spat, np.maximum(amt, 0.55 * np.clip((34 - y) / 20, 0, 1)), amt)
    amt = np.clip(amt + heavy * (0.45 + 0.5 * g), 0, 0.92)
    if n[1] > 0.5:
        amt = np.clip(amt * 1.3 + 0.1 * lo, 0, 0.95)
    dry = value_noise(p, 3.0, 79)[:, None]
    col = hexrgb("#4a3c2b") * (1 - dry) + hexrgb("#7f6d50") * dry
    return rgb * (1 - amt[:, None]) + col * amt[:, None]


def seams(p, n, cube):
    """Panel seams (recessed lines) and rivet rows, from world position."""
    x, y, z = p[:, 0], p[:, 1], p[:, 2]
    ax, ay = abs(n[0]) > 0.5, n[1] > 0.5
    az = abs(n[2]) > 0.5
    N = len(p)
    lines = np.zeros(N, bool)
    riv = np.zeros(N, bool)
    bone = cube.bone
    if bone == "base":
        cabz = z < -52.6
        if ax:
            lines |= cabz & stations_mask(y, [33.6], 0.2) & (z > -79.2)
            lines |= (z > -47.5) & (z < -21) & (hatch(p, n, 'x', -45.6, -35.9, 23.4, 41.6, 0.22)
                                                 | hatch(p, n, 'x', -34.2, -24.6, 23.4, 41.6, 0.22))
            md = (z > -21.2) & (y > 22.3) & (y < 47.6)
            lines |= md & stations_mask(z, [-14.0, 9.0, 31.0, 53.0], 0.2)
            lines |= md & stations_mask(y, [36.0], 0.18) & ((z < -11.5) | (z > 6))
            riv |= md & rivet_rows(z, [9.0, 31.0, 53.0], y, offset=0.7, pitch=1.6, width=0.22)
            riv |= md & rivet_rows(y, [36.0], z, offset=0.7, pitch=1.6, width=0.22) & ((z < -11.5) | (z > 6))
            lines |= hatch(p, n, 'x', 58.0, 72.0, 12.4, 20.0, 0.2) & (abs(n[0]) > 0.5)
        if ay:
            lines |= (z > -14) & stations_mask(z, [10.0, 24.0, 61.0], 0.2) & (np.abs(x) < 19.6)
            lines |= cabz & stations_mask(z, [-66.0], 0.2)
            lines |= (z > -21) & (z < -14) & stations_mask(np.abs(x), [10.0], 0.2)
        if az:
            lines |= (z > 77.5) & stations_mask(np.abs(x), [9.0], 0.18) & (y > 22)
            lines |= (z < -84.5) & stations_mask(y, [21.2], 0.15)
    elif bone in ("move_turret",):
        if ax:
            lines |= stations_mask(z, [37.8, 46.4], 0.2) & (y > 51) & (y < 66)
            lines |= stations_mask(y, [52.0], 0.18)
            riv |= rivet_rows(z, [37.8, 46.4], y, offset=0.65, pitch=1.4, width=0.2) & (y > 51) & (y < 66)
        if ay:
            lines |= stations_mask(z, [33.0, 53.0], 0.2)
            lines |= hatch(p, n, 'y', 46.0, 55.0, 10.0, 12.6, 0.2)
    elif bone == "move_launcher":
        if ax or ay:
            lines |= stations_mask(z, [20.0, 36.0], 0.16) & (np.abs(np.abs(x) - PACK_X) < 6.2)
    elif bone.startswith("move_door"):
        pass
    return lines, riv


def make_decals():
    d = []
    num = text_mask("214")
    d += side_decal(20.45, 28.4, -68.8, (12.6, 5.2), [(num, WHITE)], depth=0.2)
    d += side_decal(20.5, 30.0, 20.0, (16.0, 6.6), [(num, WHITE)], depth=0.8)
    d.append(Decal((19.7, 13.4, -14.4), (1, 0, 0), (0, 0, 1), (0, -1, 0), (5.6, 3.6),
                   [(text_mask("ДТ"), WHITE)], max_depth=0.3))
    stencil = (206, 204, 190)
    d += side_decal(20.5, 45.4, 40.0, (16.0, 2.4), [(text_mask("HE CTУПATЬ"), stencil)], depth=0.3)
    d += side_decal(19.0, 41.6, -37.0, (11.0, 1.6), [(text_mask("OПACHO"), (190, 40, 30))], depth=0.3)
    d.append(Decal((-8.0, 37.0, 80.2), (0, 0, 1), (-1, 0, 0), (0, -1, 0), (8.0, 1.6),
                   [(text_mask("220B 50"), stencil)], max_depth=0.3))
    plate = np.pad(text_mask("2147AB"), 1)
    for z, nz in ((-88.85, -1), (80.6, 1)):
        d.append(Decal((0, 14.9 if nz < 0 else 15.1, z), (0, 0, nz), (-nz * 1.0, 0, 0), (0, -1, 0), (7.6, 2.0),
                       [(plate, (30, 30, 28))], max_depth=0.5))
    return d


def painter():
    decals = make_decals()

    def paint(p, n, cube, face, s, t, wh):
        N = len(p)
        mat = cube.mat
        w, h = wh
        x, y, z = p[:, 0], p[:, 1], p[:, 2]
        tag = cube.tag if isinstance(cube.tag, tuple) else None
        alpha = None
        lowmud = True
        if mat in ("skin", "tube"):
            rgb = camo(p)
            if mat == "tube":
                rgb = rgb * 0.55 + PALC["olive"] * 0.45
                band = stations_mask(z, [14.0, 40.0], 0.35)
                rgb[band] *= 0.72
                if tag and tag[0] == "mouth" and n[2] < -0.5:
                    r = np.hypot(x - tag[1][0], y - tag[1][1])
                    rgb[r < 2.15] = PALC["metal"] * 0.85
                    rgb[r < 1.85] = hexrgb("#16150f")
                if tag and tag[0] == "rear" and n[2] > 0.5:
                    r = np.hypot(x - tag[1][0], y - tag[1][1])
                    rgb[r < 1.9] = hexrgb("#24231e")
                    rgb[r < 1.1] = hexrgb("#4a4a44")
            # interior colour of the cab body under the opening doors
            if cube.bone == "base" and abs(n[0]) > 0.5 and np.all(np.abs(np.abs(x) - 20.0) < 0.3):
                inside = (z > DOOR_HINGE_Z + 0.2) & (z < -57.8) & (y > 22.2) & (y < 45.2)
                rgb[inside] = PALC["interior"]
            if cube.bone == "base" and abs(n[0]) > 0.5 and np.all(np.abs(np.abs(x) - 20.5) < 0.3):
                inside = (x > 0) & (z > -8.8) & (z < 4.8) & (y > 23.6) & (y < 46.4)
                rgb[inside] = PALC["interior"]
            lines, riv = seams(p, n, cube)
            rgb = rgb * np.where(riv & ~lines, 0.84, 1.0)[:, None]
            rgb = rgb * np.where(lines, 0.66, 1.0)[:, None]
            rgb = rgb * (1 + (value_noise(p, 5.0, 3) - 0.5) * 0.12)[:, None]
            # grime, rain streaks (vertical), dust on top faces, worn edges
            g = fbm(p, 18.0, 300, 3)
            k = 1 - 0.16 * np.clip((g - 0.42) * 2.4, 0, 1)
            st = value_noise(p * np.array([1.0, 0.07, 1.0]), 1.4, 310)
            k *= 1 - 0.13 * np.clip((st - 0.55) * 3.0, 0, 1)
            rgb = rgb * k[:, None]
            if n[1] > 0.5:
                dust = 0.10 + 0.18 * value_noise(p, 6.0, 312)
                rgb = rgb * (1 - dust[:, None]) + hexrgb("#8c7f63") * dust[:, None]
            edge = np.minimum(np.minimum(s, 1 - s) * w, np.minimum(t, 1 - t) * h)
            chip = (edge < 1.3) & (value_noise(p, 0.9, 320) > 0.6)
            rgb[chip] = rgb[chip] * 0.85 + 22
            spot = value_noise(p, 0.55, 321) > 0.93
            rgb[spot] = rgb[spot] * 0.85
        elif mat == "tyre":
            rgb = np.tile(hexrgb("#1f1f1d"), (N, 1)) * (0.88 + 0.22 * value_noise(p, 1.1, 15))[:, None]
            if tag and abs(n[0]) > 0.7:
                c = tag[1]
                r = np.hypot(y - c[1], z - c[2])
                rgb[np.abs(r - 7.9) < 0.3] = hexrgb("#2b2b28")
                rgb[(np.abs(r - 8.6) < 0.18)] = hexrgb("#151514")
                ang = np.degrees(np.arctan2(y - c[1], z - c[2]))
                lettering = (np.abs(r - 7.2) < 0.45) & (np.mod(ang, 30.0) < 9.0) & (np.mod(ang * 3.1, 7.0) < 4.0)
                rgb[lettering] = hexrgb("#2f2f2c")
                rgb[r < 6.6] = hexrgb("#141412")
            else:
                groove = np.mod(x + 0.4, 2.3) < 0.35
                rgb[groove] *= 0.7
            rgb = mud(p, n, rgb, heavy=0.16)
            lowmud = False
        elif mat in ("rim", "hub"):
            rgb = np.tile(PALC[mat], (N, 1)) * (0.92 + 0.16 * value_noise(p, 2.0, 21))[:, None]
            if tag and abs(n[0]) > 0.7 and mat == "rim":
                c = tag[1]
                r = np.hypot(y - c[1], z - c[2])
                ang = np.arctan2(y - c[1], z - c[2])
                for k in range(6):
                    a = math.radians(30 + 60 * k)
                    hole = np.hypot(y - (c[1] + 5.1 * math.sin(a)), z - (c[2] + 5.1 * math.cos(a))) < 0.85
                    rgb[hole] = hexrgb("#11110f")
                rgb[np.abs(r - 6.0) < 0.22] *= 0.7
                rgb[np.abs(r - 4.2) < 0.2] *= 0.75
                rust = (value_noise(p, 0.8, 22) > 0.7) & (np.mod(ang * 4.0, 1.0) < 0.5)
                rgb[rust] = rgb[rust] * 0.7 + hexrgb("#5a3a22") * 0.3
            rgb = mud(p, n, rgb, heavy=0.12)
            lowmud = False
        elif mat == "tint":
            base = PALC["tint"]
            rgb = np.tile(base, (N, 1)) * (0.9 + 0.2 * value_noise(p, 7.0, 41))[:, None]
            grad = np.clip((y - 34.0) / 14.0, 0, 1)
            rgb = rgb + (np.array([40.0, 52, 58]) * grad[:, None]) * 0.5
            g2 = np.mod(z * 0.5 + y * 0.9 + x * 0.35, 26.0)
            glint = np.clip(1 - np.abs(g2 - 6.0) / 2.2, 0, 1) * 0.35 + np.clip(1 - np.abs(g2 - 10.0) / 0.6, 0, 1) * 0.2
            rgb = rgb * (1 - glint[:, None]) + 120 * glint[:, None]
            alpha = np.full(N, 255.0)
            lowmud = False
        elif mat == "grille":
            rgb = np.tile(PALC["grille"], (N, 1))
            coord = y if abs(n[1]) < 0.5 else z
            slat = np.mod(coord, 0.9) < 0.4
            rgb[slat] = rgb[slat] * 1.9 + 8
            rgb = rgb * (0.9 + 0.2 * value_noise(p, 3.0, 51))[:, None]
        elif mat == "array":
            rgb = np.tile(PALC["array"], (N, 1)) * (0.92 + 0.14 * value_noise(p, 4.0, 61))[:, None]
            if n[2] < -0.5:
                cell = (np.mod(x + 0.3, 1.5) < 0.95) & (np.mod(y - 75.0, 1.8) > 0.35) & (np.mod(y - 75.0, 1.8) < 1.45)
                rgb[cell] = hexrgb("#5d6450")
                dot = (np.abs(np.mod(x + 0.3, 1.5) - 0.47) < 0.2) & (np.abs(np.mod(y - 75.0, 1.8) - 0.9) < 0.2)
                rgb[dot] = hexrgb("#2b2f26")
        elif mat == "radome":
            rgb = np.tile(PALC["radome"], (N, 1)) * (0.92 + 0.14 * value_noise(p, 3.0, 71))[:, None]
            if tag and tag[0] == "radome" and n[2] < -0.5:
                r = np.hypot(x - tag[1][0], y - tag[1][1])
                grid = (np.mod(x + 0.25, 1.3) < 0.2) | (np.mod(y + 0.25, 1.3) < 0.2)
                rgb[grid & (r < 8.2)] *= 0.86
                rgb[np.abs(r - 8.5) < 0.3] = hexrgb("#4d5442")
                rgb[np.abs(r - 4.0) < 0.15] *= 0.8
            rgb = rgb * (1 - 0.12 * np.clip((fbm(p, 12.0, 72, 2) - 0.45) * 2.5, 0, 1))[:, None]
        elif mat == "cap":
            rgb = np.tile(PALC["cap"], (N, 1)) * (0.9 + 0.15 * value_noise(p, 2.0, 81))[:, None]
            if tag and n[2] < -0.5:
                cx, cy = tag[1]
                dx, dy = x - cx, y - cy
                r = np.hypot(dx, dy)
                score = ((np.abs(dx) < 0.12) | (np.abs(dy) < 0.12)) & (r < 1.5)
                rgb[score] = hexrgb("#262a1e")
                rgb[np.abs(r - 1.55) < 0.14] = hexrgb("#262a1e")
                rgb[r < 0.3] = hexrgb("#8a8f7a")
        elif mat == "gunmetal":
            rgb = np.tile(PALC["gunmetal"], (N, 1)) * (0.9 + 0.2 * value_noise(p, 1.5, 91))[:, None]
            if tag and tag[0] == "muzzle":
                soot = np.clip((-5.0 - z) / 4.0, 0, 1)
                rgb = rgb * (1 - 0.5 * soot[:, None])
                slot = np.mod(z, 0.9) < 0.35
                rgb[slot & (abs(n[2]) < 0.5)] *= 0.55
                if n[2] < -0.5:
                    r = np.hypot(x - tag[1][0], y - tag[1][1])
                    rgb[r < 0.55] = hexrgb("#0c0c0b")
            else:
                hi = (np.minimum(s, 1 - s) > 0.3) & (abs(n[2]) < 0.5)
                rgb[hi] = rgb[hi] * 1.15 + 6
        elif mat == "lens":
            rgb = np.tile(PALC["lens"], (N, 1))
            if tag and tag[0] == "lens":
                r = np.hypot(x - tag[1][0], y - tag[1][1])
                rgb[np.abs(r - 1.3) < 0.2] *= 0.75
                rgb[r < 0.5] = hexrgb("#f4f2e4")
            lowmud = False
        elif mat in ("eo", "eo2"):
            rgb = np.tile(PALC[mat], (N, 1)) * (0.85 + 0.3 * value_noise(p, 2.0, 95))[:, None]
            g2 = np.mod(x * 0.9 + y * 1.3, 4.0) < 0.6
            rgb[g2] = rgb[g2] * 0.5 + 90
            lowmud = False
        elif mat in ("amber", "red", "mirror", "plate"):
            rgb = np.tile(PALC[mat], (N, 1)) * (0.92 + 0.12 * value_noise(p, 1.0, 97))[:, None]
            if mat in ("amber", "red"):
                ring = np.minimum(np.minimum(s, 1 - s) * w, np.minimum(t, 1 - t) * h) < 1.0
                rgb[ring] *= 0.7
            lowmud = mat == "plate"
        elif mat == "exhaust":
            rgb = np.tile(PALC["exhaust"], (N, 1)) * (0.8 + 0.4 * value_noise(p, 1.2, 99))[:, None]
            top = np.clip((y - 40) / 14, 0, 1)
            rgb = rgb * (1 - 0.5 * top[:, None])
        elif mat == "canvas":
            rgb = np.tile(PALC["canvas"], (N, 1)) * (0.88 + 0.2 * value_noise(p * np.array([1, 3, 1]), 0.6, 101))[:, None]
            fold = np.mod(z, 1.2) < 0.3
            rgb[fold] *= 0.8
        elif mat in PALC:
            rgb = np.tile(PALC[mat], (N, 1)) * (0.92 + 0.14 * value_noise(p, 2.5, 103))[:, None]
            if mat in ("frame", "dark", "metal"):
                rust = value_noise(p, 1.4, 105) > 0.72
                rgb[rust] = rgb[rust] * 0.75 + hexrgb("#4f3523") * 0.25
        else:
            rgb = np.tile(hexrgb("#ff00ff"), (N, 1))
        if lowmud:
            rgb = mud(p, n, rgb)
        rgba = np.concatenate([rgb, np.full((N, 1), 255.0) if alpha is None else alpha[:, None]], 1)
        if mat in ("skin", "tube", "plate"):
            for d in decals:
                rgba = d.apply(p, n, rgba)
        return rgba

    return paint


MODELS = {"pantsir_s1": (build, painter)}
