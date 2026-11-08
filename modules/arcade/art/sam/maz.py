"""S-300PS (5P85S style) and S-400 (5P85SM2 style) launchers on the MAZ-543M 8x8 chassis.

Built on the aviation kit (modules/aviation/art/realistic: kit.py geometry, paint.py helpers) at
the detail of the Su-25/F-16/J-10 remakes; driven by modules/arcade/art/sam_assets.py.

Units 16 per block (1 m), front -Z, left +X, ground y = 0. Proportions of the MAZ-543M: length
11.7 m, width 3.05 m, wheel base 2.2 + 3.3 + 2.2 m, track 2.375 m, 1500x600-635 tyres; two
separate forward cabs (left driver, right crew) with the engine and radiator between them.

Bones (the contract numbers of SamLayout.java are kept exactly):
  root > base                       hull, cabs, chassis, every static part
  root > Wheel > wheelL0Turn/wheelR0Turn (front axle, steers), wheelL1..3/wheelR1..3 (spin)
  base > move_pack (0, 42, 70)      the four canisters; rotates about X, 0..+90 raises them
  move_pack > move_cover_<i>        front (launch-end) cover of tube i (x = -21, -7, 7, 21)
  base > move_ram_<k> > _s1 > _s2 > _s3   telescopic lift rams (k 0 = +X, 1 = -X)
  base > move_jack_<j>              outrigger legs + pads (0 FL, 1 FR, 2 RL, 3 RR), slide down
  base > move_door_l / move_door_r  outer cab doors, hinged on their front edge
  base > move_antenna               crew-cab whip antenna, folded back along the roof
Inside move_* bones every cube is axis-aligned or turned about Z only (SBW's baker flips X/Y).
"""
from __future__ import annotations

import math

import numpy as np

import kit as K
from kit import Model, fbm, hexrgb, value_noise
from paint import Decal, text_mask

# ---------------------------------------------------------------- contract ---
PIVOT = (0.0, 42.0, 70.0)
PACK_LENGTH = 128.0
TUBES = (-21.0, -7.0, 7.0, 21.0)
CAN_R = 5.0                                 # canister radius: outer bands end at x 26.4 (3.3 blocks)
FRONT_END = PIVOT[2] - PACK_LENGTH          # -58: launch end of the stowed canisters

# ------------------------------------------------------------------ layout ---
AXLES = (-62.0, -27.0, 26.0, 61.0)          # 2.2 + 3.3 + 2.2 m
WHEEL_X, WHEEL_Y, WHEEL_R = 18.6, 12.0, 12.0
TYRE_W = 9.0
HW = 24.4                                   # half width of the hull (3.05 m)
DECK_B, DECK_T = 26.4, 28.4
DRIVER_EYE = (16.6, 40.0, -84.0)
CREW_EYE = (-16.6, 40.0, -84.0)
# lift rams: base mount A and pack lug B (stowed), both at y 31
RAM_X, RAM_Y, RAM_A, RAM_B = 14.0, 31.0, 10.0, 40.0
JACKS = ((22.6, -44.4), (-22.6, -44.4), (22.6, 81.5), (-22.6, 81.5))
JACK_TRAVEL = 8.0
DOOR_HINGE_Z = -91.0

FIXED = {"root", "base"}
DENSITY = 1.5


def ram_pose(phi):
    """Ram rotation about X (degrees, move_pack sign convention) and total extension for pack
    angle phi (degrees, 0 stowed .. 90 vertical)."""
    f = math.radians(phi)
    dy = RAM_Y - PIVOT[1]
    dz = RAM_B - PIVOT[2]
    by = PIVOT[1] + dy * math.cos(f) - dz * math.sin(f)
    bz = PIVOT[2] + dy * math.sin(f) + dz * math.cos(f)
    vy, vz = by - RAM_Y, bz - RAM_A
    return -math.degrees(math.atan2(vy, vz)), math.hypot(vy, vz) - (RAM_B - RAM_A)


# ----------------------------------------------------------------- builder ---
class V:
    def __init__(self, ident):
        self.m = Model(ident)

    def bone(self, name, parent, pivot=(0, 0, 0)):
        return self.m.bone(name, parent, pivot)

    def box(self, bone, x0, x1, y0, y1, z0, z1, mat, tag=None, skip=()):
        c = self.m.box(bone, x0, x1, y0, y1, z0, z1, mat, skip=set(skip))
        c.tag = tag
        return c

    def sbox(self, bone, x0, x1, y0, y1, z0, z1, mat, tag=None):
        return [self.box(bone, x0, x1, y0, y1, z0, z1, mat, tag), self.box(bone, -x0, -x1, y0, y1, z0, z1, mat, tag)]

    def prism(self, bone, axis, c, r, ln, mat, n=6, tag=None, caps=(True, True), roll=0.0):
        out = self.m.prism(bone, axis, c, r, ln, mat, n=n, caps=caps, roll=roll)
        for cb in out:
            cb.tag = tag
        return out

    def sprism(self, bone, axis, c, r, ln, mat, **kw):
        x, y, z = c
        return self.prism(bone, axis, (x, y, z), r, ln, mat, **kw) + self.prism(bone, axis, (-x, y, z), r, ln, mat, **kw)

    def oriented(self, bone, c, size, r, mat, tag=None):
        cb = self.m.oriented(bone, tuple(float(v) for v in c), size, r, mat)
        cb.tag = tag
        return cb

    def plate(self, bone, axis, a, b, lo, hi, thick, mat, inside, tag=None):
        """Plate whose outer face is the segment a-b (2D, in the plane normal to `axis`), running
        lo..hi along `axis` and thick inward (towards `inside`). Turned about `axis` only."""
        ax = "xyz".index(axis)
        plane = [i for i in range(3) if i != ax]

        def lift(q):
            v = np.zeros(3)
            v[plane[0]], v[plane[1]] = q
            return v
        A, Bp, I = lift(a), lift(b), lift(inside)
        u = Bp - A
        L = float(np.linalg.norm(u))
        u /= L
        e = np.zeros(3)
        e[ax] = 1.0
        nrm = np.cross(u, e)
        if nrm @ (I - (A + Bp) / 2) > 0:
            nrm = -nrm
        if axis == "z":
            cols, size, flip = [u, nrm, e], (L, thick, hi - lo), 0
        elif axis == "x":
            cols, size, flip = [e, u, nrm], (hi - lo, L, thick), 1
        else:
            cols, size, flip = [u, e, nrm], (L, hi - lo, thick), 0
        r = np.column_stack(cols)
        if np.linalg.det(r) < 0:
            r[:, flip] *= -1
        centre = (A + Bp) / 2 + e * (lo + hi) / 2 - nrm * thick / 2
        return self.oriented(bone, centre, size, r, mat, tag)

    def section(self, bone, z0, z1, cx, cy, w, h, ct, cb, mat, round_=True, ends=(True, True)):
        return self.m.section(bone, z0, z1, cx, cy, w, h, ct, cb, mat=mat, round_=round_, ends=ends)


# ------------------------------------------------------------------ wheels ---
def wheel(v, bone, cx, cz, sx):
    c = np.array([cx, WHEEL_Y, cz])
    tag = ("wheel", tuple(c))
    v.prism(bone, "x", c, 11.25, TYRE_W, "tire", n=8, tag=tag)                       # tread band
    v.prism(bone, "x", c, 10.3, TYRE_W + 0.9, "tire", n=8, tag=tag, roll=11.25)      # sidewall shoulders
    for row, dx in enumerate((-2.2, 2.2)):                                          # herringbone lugs
        for k in range(16):
            th = 22.5 * k + (11.25 if row else 0.0)
            rk = K.axis_rot("x", th)
            centre = c + rk @ np.array([dx, -11.45, 0.0])
            yaw = 24.0 if row else -24.0
            v.oriented(bone, centre, (4.2, 1.1, 2.5), rk @ K.axis_rot("y", yaw), "tire", ("wheel", tuple(c), "lug"))
    o = sx
    side = TYRE_W / 2 + 0.45
    v.prism(bone, "x", c + [o * (side + 0.05), 0, 0], 7.0, 0.7, "rim", n=8, tag=tag)          # rim flange
    v.prism(bone, "x", c + [o * (side + 0.5), 0, 0], 5.4, 0.4, "rim", n=8, tag=tag, roll=11.25)  # dished disc
    v.prism(bone, "x", c + [o * (side + 1.15), 0, 0], 3.2, 1.3, "hub", n=6, tag=tag)          # planetary hub
    v.prism(bone, "x", c + [o * (side + 2.0), 0, 0], 1.6, 0.6, "metal", n=4, tag=tag)         # inflation joint
    for k in range(10):                                                                     # wheel nuts
        local = K.axis_rot("x", 18.0 + 36.0 * k) @ np.array([0.0, 4.4, 0.0])
        v.oriented(bone, c + [o * (side + 0.85), 0, 0] + local, (0.6, 0.75, 0.75), np.eye(3), "metal")
    # tyre-inflation hose from the hub to the rim
    v.oriented(bone, c + [o * (side + 0.9), 2.8, 1.2], (0.5, 0.5, 2.6), K.axis_rot("x", 35.0), "rubber")
    v.prism(bone, "x", c - [o * (side + 0.5), 0, 0], 6.0, 1.0, "chassis", n=6, tag=tag)       # brake drum


def wheels(v):
    v.bone("Wheel", "root")
    for side, sx in (("L", 1), ("R", -1)):
        for i, z in enumerate(AXLES):
            name = f"wheel{side}{i}Turn" if i == 0 else f"wheel{side}{i}"
            v.bone(name, "Wheel", (sx * WHEEL_X, WHEEL_Y, z))
            wheel(v, name, sx * WHEEL_X, z, sx)


# ----------------------------------------------------------------- chassis ---
def frame(v):
    B = "base"
    v.sbox(B, 6.0, 8.4, 14.0, 21.0, -93.0, 87.0, "chassis")                          # side members
    for z in (-82.0, -45.0, -10.0, 9.0, 44.0, 78.0):
        v.box(B, -6.0, 6.0, 15.0, 20.0, z - 1.5, z + 1.5, "chassis")
    for z in AXLES:
        v.box(B, -12.6, 12.6, 10.6, 13.4, z - 1.6, z + 1.6, "chassis")               # axle beam
        v.prism(B, "z", (0, 12.0, z), 3.6, 7.0, "chassis", n=6)                       # differential
        v.sprism(B, "x", (11.4, 12.0, z), 2.8, 2.6, "chassis", n=6)                   # wheel-end gearbox
        v.sbox(B, 9.4, 11.8, 12.0, 15.6, z - 9.0, z - 1.0, "chassis")                  # torsion-bar lever
        v.sprism(B, "z", (8.2, 15.4, z - 14.0), 0.9, 12.0, "metal", n=4)               # torsion bar
    for a, b in zip(AXLES, AXLES[1:]):
        v.box(B, -0.8, 0.8, 12.2, 13.8, a + 3.6, b - 3.6, "chassis")                  # propeller shafts
    v.box(B, -9.4, 9.4, 19.0, DECK_B, -62.0, -48.0, "skin2")                            # body over the front axle
    v.box(B, -13.4, 13.4, 19.0, DECK_B, -48.0, 74.6, "skin2")                           # central body


def deck(v):
    B = "base"
    v.box(B, -HW, HW, DECK_B, DECK_T, -62.0, 88.0, "skin2", "tread")
    v.sbox(B, HW, HW + 0.3, 26.9, 27.9, -61.0, 87.0, "skin")                           # rubbing strip
    for z0, z1 in ((-62.0, -47.6), (-41.2, -13.8), (12.6, 77.0)):                     # fender skirts
        v.sbox(B, 23.9, HW + 0.2, 21.6, DECK_B, z0, z1, "skin")
    v.sbox(B, 14.0, 23.6, 4.6, 21.6, 77.0, 77.6, "rubber")                             # mud flaps
    v.sbox(B, 14.0, 23.6, 4.6, 12.6, -14.4, -13.8, "rubber")
    for z in AXLES[1:]:
        v.sbox(B, 13.4, 23.9, 25.4, DECK_B, z - 13.0, z + 13.0, "dark")               # arch liners
    # side lockers between the axle pairs
    v.section(B, -13.0, 1.4, 20.9, 19.2, 6.6, 12.4, 2.4, 2.4, "skin")                  # fuel tank (left)
    for z in (-9.4, -3.4):
        v.box(B, 17.4, 24.45, 12.8, 25.6, z, z + 0.8, "chassis")
    v.prism(B, "y", (21.4, 26.0, -10.8), 0.9, 0.8, "metal", n=4)
    v.box(B, 17.6, 24.2, 13.0, 25.6, 2.0, 12.2, "skin")                                # battery box
    v.box(B, 24.2, 24.45, 13.8, 24.8, 2.8, 11.4, "skin", "door")
    v.box(B, -24.2, -17.6, 13.0, 25.6, -13.0, 12.2, "skin")                            # tool locker (right)
    for z0, z1 in ((-12.2, -0.8), (0.2, 11.4)):
        v.box(B, -24.45, -24.2, 13.8, 24.8, z0, z1, "skin", "door")
    for x in (1, -1):
        for z in (4.2, 9.6, -10.0, -3.0) if x < 0 else (4.2, 9.6):
            v.box(B, x * 24.45, x * 24.75, 23.2, 24.0, z, z + 1.1, "metal")
    v.sbox(B, 17.8, 24.2, 12.6, 21.6, 39.4, 47.8, "skin")                              # lockers, rear pair
    v.sbox(B, 24.2, 24.45, 13.4, 20.8, 40.2, 47.0, "skin", "door")
    v.sbox(B, 24.45, 24.75, 19.4, 20.2, 43.0, 44.2, "metal")
    # rear overhang: body, beam, lamps, bumper, hitch, plate, ladder, rails
    v.box(B, -21.0, 21.0, 15.0, DECK_B, 77.6, 88.0, "skin")
    v.box(B, -HW, HW, 22.0, DECK_B, 86.0, 88.0, "skin")
    v.sbox(B, 15.0, 19.6, 18.4, 21.6, 88.0, 88.4, "red", ("lamp",))
    v.sbox(B, 19.6, 21.0, 18.4, 21.6, 88.0, 88.4, "amber", ("lamp",))
    v.box(B, -22.0, 22.0, 13.0, 17.6, 88.0, 90.6, "chassis", "hazard")
    v.box(B, -2.0, 2.0, 13.6, 16.6, 90.6, 92.6, "metal")
    v.prism(B, "y", (0, 15.1, 91.8), 0.6, 4.0, "metal", n=4)
    v.box(B, -4.6, 4.6, 18.2, 21.2, 88.0, 88.15, "plate")
    for x in (-12.0, -7.6):
        v.box(B, x, x + 0.6, 4.0, DECK_T, 88.4, 89.0, "metal")
    for y in (6.0, 10.4, 14.8, 19.2, 23.6):
        v.box(B, -11.4, -7.6, y, y + 0.6, 88.5, 88.9, "metal")
    for z in (76.0, 87.0):
        v.sbox(B, 23.4, 24.0, DECK_T, 33.4, z, z + 0.6, "metal")
    v.sbox(B, 23.4, 24.0, 32.8, 33.4, 76.0, 87.6, "metal")


def jacks(v):
    B = "base"
    for j, (x, z) in enumerate(JACKS):
        s = 1 if x > 0 else -1
        v.prism(B, "y", (x, 19.6, z), 2.2, 13.6, "skin2", n=6)                         # cylinder housing
        v.prism(B, "y", (x, 13.0, z), 2.6, 1.0, "chassis", n=6)                        # gland collar
        v.prism(B, "y", (x, 25.2, z), 2.6, 1.2, "chassis", n=6)
        v.box(B, s * 16.0, x, 21.4, DECK_B, z - 1.6, z + 1.6, "skin2")                 # bracket
        v.box(B, x - 0.3, x + 0.3, 14.0, 25.0, z + 2.1, z + 2.6, "rubber")             # hydraulic line
        name = f"move_jack_{j}"
        v.bone(name, "base", (x, 13.0, z))
        v.box(name, x - 1.3, x + 1.3, 9.0, 22.0, z - 1.3, z + 1.3, "chrome")
        v.box(name, x - 3.0, x + 3.0, 8.0, 9.0, z - 3.0, z + 3.0, "chassis", "pad")
        v.box(name, x - 1.8, x + 1.8, 9.0, 9.7, z - 1.8, z + 1.8, "chassis")


# --------------------------------------------------------------------- cabs ---
def cab(v, sx, crew):
    B = "base"

    def X(x):
        return sx * x
    xi, xo = 8.8, HW
    cxm = X((xi + xo) / 2)
    # lower front (ahead of the front wheel) with the bevelled outer corner
    v.box(B, X(xi), X(xo - 1.6), 15.0, 30.6, -95.6, -94.0, "skin")
    v.box(B, X(xi), X(xo), 15.0, 30.6, -94.0, -76.0, "skin")
    v.plate(B, "y", (X(xo), -94.0), (X(xo - 1.6), -95.6), 15.0, 30.6, 1.4, "skin", inside=(X(16), -88))
    v.plate(B, "x", (16.8, -95.6), (15.0, -93.8), *sorted((X(xi), X(xo - 1.6))), 1.2, "skin", inside=(22, -90))
    v.box(B, X(xi), X(xo), 26.4, 30.6, -76.0, -62.0, "skin")                           # over the wheel
    # glazed upper cab with the rounded roof and the bevelled front corner
    v.section(B, -93.4, -62.0, cxm, 38.6, xo - xi, 16.0, 3.6, 0.0, "skin", round_="top")
    v.section(B, -95.0, -93.4, X(xi + 7.0), 38.6, 14.0, 16.0, 3.6, 0.0, "skin", round_="top", ends=(True, False))
    v.plate(B, "y", (X(xo), -93.4), (X(xo - 1.6), -95.0), 30.6, 43.0, 1.4, "skin", inside=(X(16), -88))
    v.box(B, X(xi + 0.6), X(xo - 2.0), 42.8, 43.4, -96.6, -95.0, "skin")                # sun visor
    v.box(B, X(xi), X(xo - 1.6), 30.4, 30.9, -95.9, -95.0, "rubber")                   # sill seal
    # windscreen panes, wipers
    for a, b in ((xi + 1.0, xi + 7.2), (xi + 8.0, xo - 2.4)):
        v.box(B, X(a), X(b), 31.6, 42.4, -95.3, -95.0, "window")
        p0 = (X(a + 1.0), 32.0)
        p1 = (X(a + 3.8), 40.4)
        v.plate(B, "z", p0, p1, -95.75, -95.35, 0.35, "black", inside=(X(a + 4.0), 32.0))
        v.box(B, X(a + 0.6), X(a + 1.4), 31.4, 32.2, -95.6, -95.3, "metal")
    v.box(B, X(xi + 7.2), X(xi + 8.0), 31.6, 42.4, -95.2, -95.0, "skin")                # centre pillar strip
    # lamps on the lower front
    v.prism(B, "z", (X(20.2), 23.4, -95.9), 2.5, 0.6, "chassis", n=6)
    v.prism(B, "z", (X(20.2), 23.4, -96.3), 1.9, 0.6, "lens", n=6, tag=("lens", X(20.2), 23.4))
    v.prism(B, "z", (X(12.8), 23.4, -95.85), 1.8, 0.5, "chassis", n=6)
    v.prism(B, "z", (X(12.8), 23.4, -96.2), 1.3, 0.5, "lens", n=6, tag=("lens", X(12.8), 23.4))
    v.box(B, X(21.2), X(22.8), 27.4, 29.0, -95.9, -95.6, "amber", ("lamp",))
    v.box(B, X(10.6), X(15.4), 26.4, 28.6, -95.8, -95.6, "grille", "louver")
    v.box(B, X(16.0), X(18.4), 17.4, 19.4, -95.85, -95.6, "red", ("lamp",))           # reflector
    # door (moving) and the doorway behind it
    door = "move_door_" + ("l" if sx > 0 else "r")
    v.bone(door, "base", (X(xo), 30.0, DOOR_HINGE_Z))
    v.box(door, X(xo), X(xo + 0.3), 16.4, 43.0, DOOR_HINGE_Z, -77.0, "skin", "door")
    v.box(door, X(xo + 0.3), X(xo + 0.5), 32.4, 41.6, -89.6, -78.4, "window")
    v.box(door, X(xo + 0.3), X(xo + 0.55), 31.6, 32.4, -89.8, -78.2, "rubber")
    v.box(door, X(xo + 0.3), X(xo + 0.8), 29.4, 30.0, -80.4, -78.6, "metal")
    for y in (19.0, 38.0):
        v.box(door, X(xo + 0.3), X(xo + 0.6), y, y + 2.0, -90.8, -89.8, "metal")
    # windows: quarter, inner side, rear
    v.box(B, X(xo), X(xo + 0.25), 32.4, 41.6, -74.6, -65.0, "window")
    v.box(B, X(xi - 0.25), X(xi), 35.4, 41.6, -90.0, -72.0, "window")
    v.box(B, X(xi + 3.0), X(xo - 3.0), 37.0, 42.0, -62.0, -61.75, "window")
    # arch lips, steps, grab rail, mirror
    v.box(B, X(xo - 0.4), X(xo + 0.3), 15.0, 26.4, -76.6, -75.8, "skin")
    v.box(B, X(xo - 0.4), X(xo + 0.3), 25.6, 26.4, -76.0, -62.0, "skin")
    for z in (-90.6, -80.0):
        v.box(B, X(23.6), X(24.2), 5.2, 15.0, z, z + 0.6, "metal")
    for y in (5.2, 10.2):
        v.box(B, X(20.6), X(24.2), y, y + 0.8, -90.6, -79.4, "metal", "tread")
    v.box(B, X(24.7), X(25.2), 18.0, 41.0, -75.4, -74.9, "metal")
    for y in (18.0, 40.0):
        v.box(B, X(xo), X(24.7), y, y + 1.0, -75.4, -74.9, "metal")
    v.box(B, X(xo), X(xo + 1.3), 42.4, 43.0, -92.6, -92.0, "metal")
    v.box(B, X(xo + 1.0), X(xo + 1.3), 34.6, 43.0, -92.6, -92.0, "metal")
    v.box(B, X(xo + 0.5), X(xo + 1.6), 35.4, 41.4, -93.4, -92.6, "mirror")
    # rain gutters along the roof edges
    for a, b in ((xo, xo + 0.35), (xi - 0.35, xi)):
        v.box(B, X(a), X(b), 42.6, 43.2, -93.4, -62.0, "skin")
    # roof: hatch, lamps, antenna
    v.box(B, X(13.4), X(20.0), 46.6, 47.0, -86.0, -78.0, "skin", "door")
    v.box(B, X(14.2), X(15.4), 47.0, 47.4, -80.0, -79.0, "metal")
    if not crew:
        v.box(B, X(19.6), X(20.4), 46.6, 48.4, -90.6, -89.8, "metal")                  # searchlight
        v.prism(B, "z", (X(20.0), 49.6, -90.2), 1.3, 2.4, "chassis", n=6)
        v.prism(B, "z", (X(20.0), 49.6, -91.5), 1.0, 0.3, "lens", n=6, tag=("lens", X(20.0), 49.6))
    else:
        v.box(B, X(12.8), X(15.2), 46.6, 47.6, -89.2, -86.8, "chassis")                # antenna base
        v.bone("move_antenna", "base", (X(14.0), 47.8, -88.0))
        v.box("move_antenna", X(13.4), X(14.6), 47.2, 48.4, -88.8, -86.6, "metal")
        v.box("move_antenna", X(13.8), X(14.2), 47.6, 48.0, -86.6, -64.0, "black")
        v.box("move_antenna", X(13.65), X(14.35), 47.45, 48.15, -86.6, -84.6, "metal")
    for z in (-93.0, -64.0):                                                         # roof marker lamps
        v.box(B, X(xo - 4.0), X(xo - 2.6), 46.6, 47.2, z, z + 0.8, "amber", ("lamp",))


def engine_bay(v):
    B = "base"
    v.box(B, -8.8, 8.8, 15.0, 33.5, -94.4, -60.0, "skin")
    v.box(B, -7.6, 7.6, 17.0, 32.4, -94.8, -94.4, "grille", "grille")
    v.box(B, -8.8, 8.8, 32.4, 33.5, -95.2, -94.4, "skin")
    v.sbox(B, 7.6, 8.8, 15.0, 32.4, -95.2, -94.4, "skin")
    v.box(B, -7.6, 7.6, 15.0, 17.0, -95.0, -94.4, "skin")
    v.box(B, -8.4, 8.4, 33.5, 33.9, -93.6, -92.6, "metal")                            # hood hinge
    v.sbox(B, 1.4, 7.2, 33.5, 33.8, -88.0, -72.0, "grille", "louver")
    v.box(B, -0.6, 0.6, 33.5, 33.9, -90.0, -66.0, "skin")                             # hood centre seam
    v.prism(B, "y", (4.5, 37.0, -64.6), 1.3, 7.0, "exhaust", n=6)                      # exhaust stack
    v.box(B, 2.8, 6.2, 40.5, 40.9, -66.4, -62.8, "exhaust")
    v.prism(B, "y", (-4.4, 36.0, -66.0), 2.4, 5.0, "skin", n=6)                        # air intake
    v.prism(B, "y", (-4.4, 39.1, -66.0), 2.9, 1.2, "skin", n=6)
    v.box(B, -24.0, 24.0, 11.5, 16.2, -98.0, -95.6, "chassis")                         # front bumper
    v.sbox(B, 9.6, 11.6, 12.4, 15.2, -99.6, -98.0, "metal")                            # tow hooks
    v.sprism(B, "x", (10.6, 13.8, -99.0), 0.5, 2.6, "metal", n=4)
    v.box(B, -4.6, 4.6, 12.4, 15.4, -98.15, -98.0, "plate")
    v.sbox(B, 22.0, 24.0, 11.0, 16.6, -98.4, -95.6, "rubber")                          # bumper ends


# ----------------------------------------------------------------- launcher ---
def launcher_base(v, s400):
    B = "base"
    v.box(B, -HW, HW, DECK_T, 35.8, -62.0, -55.2, "skin")                              # forward compartment
    v.sbox(B, HW, HW + 0.25, 29.2, 35.0, -61.2, -56.0, "skin", "door")
    v.box(B, -HW + 1.0, HW - 1.0, 35.8, 36.2, -61.0, -56.2, "skin", "door")
    v.box(B, -24.8, 24.8, 33.4, 34.5, -53.6, -46.4, "chassis")                         # front saddle
    for x in (4.2, 20.2):
        v.sbox(B, x - 1.6, x + 1.6, DECK_T, 33.4, -52.4, -47.6, "chassis")
    for x in TUBES:
        v.box(B, x - 3.0, x + 3.0, 34.5, 34.56, -53.2, -46.8, "rubber")
    # generator set (left), cable reels and hydraulic tank (right)
    v.box(B, 3.0, HW, DECK_T, 34.4, -45.6, -22.0, "skin")
    v.box(B, HW, HW + 0.2, 29.4, 33.4, -43.0, -31.0, "grille", "louver")
    v.box(B, HW, HW + 0.25, 29.2, 33.8, -29.6, -23.4, "skin", "door")
    v.box(B, 6.0, 10.0, 34.4, 34.9, -30.0, -26.0, "grille", "grille")
    v.box(B, 13.0, 17.0, 34.4, 34.9, -40.0, -36.0, "grille", "grille")
    for zc in (-41.0, -32.0):
        for x in (-23.6, -17.8):
            v.prism(B, "x", (x, 31.5, zc), 3.0, 0.6, "skin2", n=6)
        v.prism(B, "x", (-20.7, 31.5, zc), 2.3, 5.2, "cable", n=6)
        v.prism(B, "x", (-20.7, 31.5, zc), 0.6, 7.8, "metal", n=4)
        v.box(B, -24.2, -17.2, DECK_T, 29.0, zc - 3.2, zc + 3.2, "chassis")
    v.box(B, -15.4, -4.0, DECK_T, 33.8, -45.6, -27.0, "skin")
    v.prism(B, "y", (-9.6, 34.2, -40.0), 1.0, 0.8, "metal", n=4)
    v.box(B, -4.0, -2.6, 29.0, 30.0, -44.0, -12.0, "metal")                            # hydraulic lines
    v.box(B, -2.6, -1.8, 29.0, 29.8, -44.0, 60.0, "rubber")
    # cabinets along the deck edges, control and valve blocks in the middle
    v.sbox(B, 16.6, HW, DECK_T, 34.4, -19.0, 19.0, "skin")
    for z0, z1 in ((-18.2, -0.6), (0.6, 18.2)):
        v.sbox(B, HW, HW + 0.25, 29.2, 33.6, z0, z1, "skin", "door")
        v.sbox(B, HW + 0.25, HW + 0.5, 31.0, 31.8, z1 - 2.0, z1 - 0.8, "metal")
    v.box(B, -8.0, 8.0, DECK_T, 32.0, -20.0, -10.0, "skin")
    v.box(B, -8.0, 8.0, DECK_T, 32.2, 46.0, 60.0, "skin")
    v.box(B, -6.0, 6.0, 32.2, 32.6, 48.0, 58.0, "metal")
    # fire extinguishers and a spare-parts box near the rear
    v.sprism(B, "y", (9.6, 31.2, 64.0), 1.2, 5.6, "red", n=6)
    v.box(B, -21.0, -16.4, DECK_T, 33.6, 40.0, 58.0, "skin")
    v.box(B, -21.25, -21.0, 29.0, 33.0, 41.0, 57.0, "skin", "door")
    v.box(B, 16.4, 21.0, DECK_T, 33.6, 40.0, 58.0, "skin")
    v.box(B, 21.0, 21.25, 29.0, 33.0, 41.0, 57.0, "skin", "door")
    # ram mounts on the deck (clevis at A)
    v.sbox(B, RAM_X - 1.6, RAM_X + 1.6, DECK_T, RAM_Y + 1.0, RAM_A - 2.4, RAM_A + 2.4, "chassis")
    v.sprism(B, "x", (RAM_X, RAM_Y, RAM_A), 0.8, 4.6, "metal", n=4)
    # pivot posts at the rear (between the canisters), pins and braces
    px, py, pz = PIVOT
    v.sbox(B, 14.0, 15.6, DECK_T, py, pz - 5.4, pz + 5.4, "skin")
    v.sprism(B, "x", (14.8, py, pz), 5.4, 1.6, "skin", n=6)
    v.sprism(B, "x", (14.0, py, pz), 1.4, 3.4, "metal", n=6)
    for s in (1, -1):
        v.plate(B, "x", (py - 2.0, pz + 5.4), (DECK_T, pz + 15.0), *sorted((s * 14.0, s * 15.6)), 1.4, "skin",
                inside=(DECK_T, pz))
        v.box(B, s * 13.6, s * 16.0, DECK_T, DECK_T + 1.0, pz - 6.4, pz + 16.0, "chassis")
    if s400:   # extra antenna mast stub and an air conditioner on the forward compartment
        v.box(B, -20.0, -12.0, 36.2, 38.4, -61.0, -56.6, "skin")
        v.box(B, -19.4, -12.6, 37.0, 38.0, -56.6, -56.4, "grille", "louver")


def rams(v):
    """Telescopic lift rams: barrel turns about X at the deck mount A, three stages slide out
    along the barrel to the pack lug B (move_pack)."""
    stages = ((2.3, RAM_A, 27.0, "skin2"), (1.85, 11.0, 29.0, "chrome"), (1.45, 12.0, 31.0, "chrome"),
              (1.1, 13.0, RAM_B, "chrome"))
    for k, s in enumerate((1, -1)):
        x = s * RAM_X
        parent = "base"
        for i, (r, z0, z1, mat) in enumerate(stages):
            name = f"move_ram_{k}" + (f"_s{i}" if i else "")
            v.bone(name, parent, (x, RAM_Y, RAM_A))
            parent = name
            v.prism(name, "z", (x, RAM_Y, (z0 + z1) / 2), r, z1 - z0, mat, n=6)
            if i == 0:
                v.prism(name, "z", (x, RAM_Y, z1 - 0.3), r + 0.35, 1.2, "skin2", n=6)    # gland
                v.box(name, x - 1.4, x + 1.4, RAM_Y - 1.4, RAM_Y + 1.4, RAM_A - 1.4, RAM_A + 1.4, "chassis")
                v.box(name, x - 0.25, x + 0.25, RAM_Y + 2.2, RAM_Y + 2.7, RAM_A + 2.0, z1 - 1.6, "rubber")
            elif i == 3:
                v.box(name, x - 1.2, x + 1.2, RAM_Y - 1.4, RAM_Y + 1.4, RAM_B - 1.6, RAM_B + 1.2, "metal")   # rod eye


# --------------------------------------------------------------------- pack ---
def pack_frame(v):
    P = "move_pack"
    px, py, pz = PIVOT
    v.sbox(P, 12.6, 15.4, 34.6, 38.2, -53.0, 60.0, "frame")                            # longitudinal beams
    v.box(P, -1.4, 1.4, 34.6, 38.4, -53.0, 60.0, "frame")
    for z, heavy in ((-50.0, False), (-6.0, False), (34.0, False), (61.8, True)):
        h = 1.8 if heavy else 1.5
        v.box(P, -24.6, 24.6, 33.2 if heavy else 34.6, 36.7, z - h, z + h, "frame")
        v.box(P, -0.8, 0.8, 36.7, 47.9, z - 1.2, z + 1.2, "frame")
        v.sbox(P, 13.2, 14.8, 36.7, 47.9, z - 1.2, z + 1.2, "frame")
        v.box(P, -24.6, 24.6, 47.9, 49.3, z - 1.2, z + 1.2, "frame")
        v.sbox(P, 24.0, 25.6, 34.2, 37.4, z - h - 0.2, z + h + 0.2, "frame")           # band anchors
    v.sbox(P, 12.4, 13.9, 36.7, 44.4, 63.6, 72.0, "metal")                             # pivot lugs
    v.sbox(P, 15.5, 16.3, 29.8, 34.6, RAM_B - 1.4, RAM_B + 1.4, "metal")                # ram lugs (B)
    v.sbox(P, 11.7, 12.5, 29.8, 34.6, RAM_B - 1.4, RAM_B + 1.4, "metal")


def bands(v, x, r, stations):
    for z in stations:
        v.prism("move_pack", "z", (x, PIVOT[1], z), r, 2.4, "band", n=8)


def can_5v55(v, i, x):
    """5V55-type canister: smooth tube, reinforcing ribs, a cable conduit on top, domed front cover."""
    P = "move_pack"
    y = PIVOT[1]
    r = CAN_R
    v.prism(P, "z", (x, y, (FRONT_END + PIVOT[2]) / 2), r, PACK_LENGTH, "can", n=8, caps=(False, False))
    for z in (-40.0, -24.0, 4.0, 20.0, 48.0):
        v.prism(P, "z", (x, y, z), r + 0.25, 1.0, "can", n=8, tag="rib")
    v.prism(P, "z", (x, y, FRONT_END + 1.1), r + 0.32, 2.2, "can", n=8, tag="rib")
    v.prism(P, "z", (x, y, PIVOT[2] - 1.4), r + 0.38, 2.8, "can", n=8, tag="rib")
    v.prism(P, "z", (x, y, PIVOT[2] + 0.25), 4.2, 0.5, "dark", n=6)
    v.box(P, x - 1.2, x + 1.2, y - 3.4, y - 1.2, PIVOT[2] + 0.4, PIVOT[2] + 1.0, "metal")   # umbilical plug
    v.box(P, x - 0.75, x + 0.75, y + r - 0.2, y + r + 0.8, FRONT_END + 4.0, PIVOT[2] - 6.0, "can")
    v.box(P, x - 1.4, x + 1.4, y + r - 0.4, y + r + 1.2, 54.0, 58.0, "can", "door")
    for z in (-30.0, 38.0):                                                            # lifting lugs
        v.box(P, x + 2.2, x + 3.2, y + r - 0.6, y + r + 0.9, z - 0.8, z + 0.8, "metal")
    bands(v, x, r + 0.4, (-50.0, -6.0, 34.0, 61.8))
    cover = f"move_cover_{i}"
    v.bone(cover, P, (x, y + 4.6, FRONT_END - 0.7))
    v.prism(cover, "z", (x, y, FRONT_END - 0.35), 4.5, 0.7, "cover", n=8)
    v.prism(cover, "z", (x, y, FRONT_END - 0.9), 3.0, 0.5, "cover", n=8, roll=11.25)
    v.prism(cover, "z", (x, y, FRONT_END - 1.3), 1.2, 0.4, "metal", n=4)
    v.box(cover, x - 1.4, x + 1.4, y + 3.8, y + 4.8, FRONT_END - 0.9, FRONT_END + 0.0, "metal")   # hinge


def can_48n6(v, i, x):
    """48N6-type canister: ribbed tube, heavy front ring with a flat cover and cross ribs, a
    large rear end block with the gas-generator flange."""
    P = "move_pack"
    y = PIVOT[1]
    r = CAN_R
    v.prism(P, "z", (x, y, (FRONT_END + PIVOT[2]) / 2), r, PACK_LENGTH, "can", n=8, caps=(False, False))
    for z in (-46.0, -30.0, -14.0, 2.0, 18.0, 46.0):
        v.prism(P, "z", (x, y, z), r + 0.22, 0.8, "can", n=8, tag="rib")
    v.prism(P, "z", (x, y, FRONT_END + 1.6), r + 0.4, 3.2, "can", n=8, tag="rib")
    v.prism(P, "z", (x, y, PIVOT[2] - 2.2), r + 0.32, 4.4, "can", n=8, tag="rib")
    v.prism(P, "z", (x, y, PIVOT[2] + 0.25), 3.6, 0.5, "dark", n=6)
    for k in range(4):
        q = K.axis_rot("z", 45 + 90 * k) @ np.array([0.0, 4.4, 0.0])
        v.box(P, x + q[0] - 0.4, x + q[0] + 0.4, y + q[1] - 0.4, y + q[1] + 0.4, PIVOT[2], PIVOT[2] + 0.5, "metal")
    sx = 1 if x > 0 else -1
    v.box(P, x - 0.8, x + 0.8, y + r - 0.2, y + r + 0.9, FRONT_END + 4.0, PIVOT[2] - 6.0, "can")
    v.box(P, x + sx * (r - 0.2), x + sx * (r + 0.38), y - 0.8, y + 0.8, FRONT_END + 5.0, PIVOT[2] - 6.0, "can")
    v.box(P, x - 1.6, x + 1.6, y + r - 0.4, y + r + 1.4, 52.0, 57.0, "can", "door")
    bands(v, x, r + 0.4, (-50.0, -6.0, 34.0, 61.8))
    cover = f"move_cover_{i}"
    v.bone(cover, P, (x, y + 4.7, FRONT_END - 0.6))
    v.prism(cover, "z", (x, y, FRONT_END - 0.3), 4.7, 0.6, "cover", n=8)
    v.box(cover, x - 4.2, x + 4.2, y - 0.5, y + 0.5, FRONT_END - 1.0, FRONT_END - 0.6, "cover")
    v.box(cover, x - 0.5, x + 0.5, y - 4.2, y + 4.2, FRONT_END - 1.0, FRONT_END - 0.6, "cover")
    v.prism(cover, "z", (x, y, FRONT_END - 1.2), 1.5, 0.6, "metal", n=6)
    v.box(cover, x - 1.4, x + 1.4, y + 3.9, y + 4.9, FRONT_END - 0.8, FRONT_END + 0.0, "metal")


def can_quad(v, i, x):
    """Quad-pack canister for four 9M96: rounded square tube, square ribs and frames, an end
    plate with four round cell covers (launch end) and four gas-generator caps (rear)."""
    P = "move_pack"
    y = PIVOT[1]
    a = 2 * CAN_R
    v.section(P, FRONT_END + 1.0, PIVOT[2] - 0.4, x, y, a, a, 1.9, 1.9, "can")
    for z in (-42.0, -20.0, 2.0, 24.0, 46.0):
        v.section(P, z - 0.5, z + 0.5, x, y, a + 0.6, a + 0.6, 2.1, 2.1, "can")
    v.section(P, FRONT_END, FRONT_END + 1.6, x, y, a + 0.8, a + 0.8, 2.2, 2.2, "can")
    v.section(P, PIVOT[2] - 2.4, PIVOT[2], x, y, a + 0.8, a + 0.8, 2.2, 2.2, "can")
    for dx in (-2.5, 2.5):
        for dy in (-2.5, 2.5):
            v.prism(P, "z", (x + dx, y + dy, PIVOT[2] + 0.2), 1.6, 0.4, "dark", n=6)
    v.box(P, x - 0.75, x + 0.75, y + a / 2 - 0.2, y + a / 2 + 0.8, FRONT_END + 4.0, PIVOT[2] - 6.0, "can")
    v.box(P, x - 1.6, x + 1.6, y + a / 2 - 0.3, y + a / 2 + 1.2, 52.0, 57.0, "can", "door")
    for z in (-50.0, -6.0, 34.0, 61.8):
        v.section(P, z - 1.2, z + 1.2, x, y, a + 1.4, a + 1.4, 2.4, 2.4, "band")
    cover = f"move_cover_{i}"
    v.bone(cover, P, (x, y + 4.7, FRONT_END - 0.6))
    v.box(cover, x - 4.6, x + 4.6, y - 4.6, y + 4.6, FRONT_END - 0.6, FRONT_END, "cover")
    for dx in (-2.5, 2.5):
        for dy in (-2.5, 2.5):
            v.prism(cover, "z", (x + dx, y + dy, FRONT_END - 0.85), 1.9, 0.5, "cover2", n=6)
            v.prism(cover, "z", (x + dx, y + dy, FRONT_END - 1.2), 0.7, 0.3, "metal", n=4)
    v.box(cover, x - 1.4, x + 1.4, y + 4.0, y + 5.0, FRONT_END - 0.8, FRONT_END + 0.0, "metal")


# ------------------------------------------------------------------- build ---
def build(s400):
    v = V("s400_triumf" if s400 else "s300ps")
    v.bone("root", None)
    v.bone("base", "root")
    wheels(v)
    v.bone("move_pack", "base", PIVOT)
    frame(v)
    deck(v)
    jacks(v)
    cab(v, 1, crew=False)
    cab(v, -1, crew=True)
    engine_bay(v)
    launcher_base(v, s400)
    rams(v)
    pack_frame(v)
    for i, x in enumerate(TUBES):
        if not s400:
            can_5v55(v, i, x)
        elif i in (0, 3):
            can_48n6(v, i, x)
        else:
            can_quad(v, i, x)
    return v.m


def build_s300():
    return build(False)


def build_s400():
    return build(True)


# ------------------------------------------------------------------- paint ---
MUD = hexrgb("#5b5140")
DRY_MUD = hexrgb("#7d7259")
DUST = hexrgb("#8e8670")
WHITE = np.array([214.0, 212.0, 198.0])
PAINTED = {"skin", "skin2", "can", "frame", "band", "rim", "hub", "cover", "cover2"}
SOLID = {
    "chassis": "#2f3427", "dark": "#1b1d18", "metal": "#5e605a", "chrome": "#b4b8b1", "rubber": "#1e1e1c",
    "black": "#161715", "cable": "#222320", "exhaust": "#4b3b2d", "red": "#8f2a22", "amber": "#b5802e",
    "lens": "#d6d4c2", "grille": "#1e211b", "mirror": "#2b2f27", "plate": "#141518", "tire": "#232322",
}
SCHEMES = {
    "s300": {"skin": "#4f5b38", "skin2": "#465130", "can": "#56613c", "frame": "#434c2f", "band": "#394029",
             "rim": "#4b5535", "hub": "#424b2f", "cover": "#3d4430", "cover2": "#4c533c", "number": "231",
             "stencils": ("5V55",) * 4},
    "s400": {"skin": None, "skin2": None, "can": None, "frame": None, "band": "#2f3226", "rim": "#4b5535",
             "hub": "#424b2f", "cover": "#3a3f2f", "cover2": "#8d8261", "number": "412",
             "stencils": ("48N6", "9M96", "9M96", "48N6")},
}
CAMO = ["#56653a", "#a99a6b", "#3b3428"]
SHADE = {"skin": 1.0, "skin2": 0.88, "can": 1.0, "frame": 0.84}


def _decals(number, stencils):
    out = []
    num = text_mask(number)
    for sx in (1, -1):
        # tactical number on the cab doors (and the locker below the deck at the rear)
        out.append(Decal((sx * (HW + 0.3), 24.2, -84.0), (sx, 0, 0), (0, 0, sx), (0, -1, 0), (10.5, 4.3),
                         [(num, WHITE)], max_depth=1.0))
        out.append(Decal((sx * (HW + 0.3), 31.4, -9.4), (sx, 0, 0), (0, 0, sx), (0, -1, 0), (10.4, 4.2),
                         [(num, WHITE)], max_depth=1.0))
    # canister stencils on top (read from the left side) and tube numbers at the launch end
    for k, x in enumerate(TUBES):
        out.append(Decal((x, PIVOT[1] + 5.6, -32.0), (0, 1, 0), (0, 0, 1), (1, 0, 0), (17.0, 4.6),
                         [(text_mask(stencils[k]), WHITE)], max_depth=1.4))
        out.append(Decal((x, PIVOT[1] + 5.6, 27.0), (0, 1, 0), (0, 0, 1), (1, 0, 0), (3.0, 4.2),
                         [(text_mask(str(k + 1)), WHITE)], max_depth=1.4))
    return out


def camo3(p, seed=7):
    """Three-tone disruptive scheme: green ground, sand and dark-brown patches with hard edges."""
    q = p.copy()
    q[:, 0] += (value_noise(p, 23.0, seed + 90) - 0.5) * 16.0
    q[:, 2] += (value_noise(p, 21.0, seed + 91) - 0.5) * 16.0
    q[:, 1] *= 0.7
    a = fbm(q, 30.0, seed, 2)
    b = fbm(q + 400.0, 22.0, seed + 40, 2)
    rgb = np.tile(hexrgb(CAMO[0]), (len(p), 1))
    rgb[a > 0.56] = hexrgb(CAMO[1])
    rgb[(b > 0.60) & (a <= 0.62)] = hexrgb(CAMO[2])
    return rgb


def make_painter(scheme):
    pal = SCHEMES[scheme]
    decals = _decals(pal["number"], pal["stencils"])
    camo = scheme == "s400"
    rng_seed = 7 if camo else 3

    def body(p, n, mat):
        N = len(p)
        if camo and pal.get(mat) is None:
            rgb = camo3(p) * SHADE.get(mat, 1.0)
        else:
            rgb = np.tile(hexrgb(pal[mat]), (N, 1))
        return rgb * (0.94 + 0.12 * fbm(p, 14.0, 5, 2))[:, None]

    def weather(p, n, rgb, edge, mat):
        g = fbm(p, 20.0, 300, 3)
        rgb = rgb * (1 - 0.14 * np.clip((g - 0.45) * 2.4, 0, 1))[:, None]
        if abs(n[1]) < 0.4:   # rain streaks down the sides
            st = value_noise(p * np.array([1.0, 0.07, 1.0]), 1.5, 310)
            rgb = rgb * (1 - 0.11 * np.clip((st - 0.5) * 3, 0, 1))[:, None]
        if n[1] > 0.6:        # faded, dusty tops
            d = 0.10 + 0.22 * fbm(p, 7.0, 21, 2)
            rgb = rgb * 1.05 * (1 - d[:, None]) + DUST * d[:, None]
        if n[1] < -0.6:       # undersides in shadow and grime
            rgb = rgb * 0.62
        chip = (edge < 1.0) & (value_noise(p, 1.2, 41) > 0.7)
        rgb[chip] = rgb[chip] * 0.6 + np.array([70.0, 68.0, 60.0]) * 0.4
        return rgb

    def lines(p, n, cube, rgb, edge, s, t, wh):
        tag = cube.tag if isinstance(cube.tag, str) else None
        w, h = wh
        x, y, z = p[:, 0], p[:, 1], p[:, 2]
        ax = np.abs(x)
        if tag == "door":
            rgb[edge < 1.0] *= 0.55
            inner = (edge > 2.2) & (edge < 2.8)
            rgb[inner] *= 0.9
        elif tag == "louver":
            q = np.mod(y, 1.1) if abs(n[1]) < 0.5 else np.mod(z, 1.2)
            rgb[(q < 0.5) & (edge > 0.8)] *= 0.35
        elif tag == "tread" and n[1] > 0.5:
            a, b = (x + z) * 0.7, (x - z) * 0.7
            dash = ((np.mod(a, 2.2) < 0.45) & (np.mod(np.floor(b / 2.2), 2) == 0)) | \
                   ((np.mod(b, 2.2) < 0.45) & (np.mod(np.floor(a / 2.2), 2) == 1))
            rgb[dash] = rgb[dash] * 1.18 + 6
            rgb[np.mod(z + 62.0, 24.0) < 0.35] *= 0.6
        elif tag == "hazard":
            st = np.mod(x + y, 4.0) < 2.0
            rgb[st] = WHITE * 0.92
            rgb[~st] = np.array([150.0, 38.0, 30.0])
        elif tag == "rib":
            rgb *= 0.92
        if cube.mat in ("skin", "skin2") and cube.bone == "base":
            side = np.abs(n[0]) > 0.9
            # doorway dark behind the moving door
            dw = side & (ax > HW - 0.05) & (ax < HW + 0.05) & (z > DOOR_HINGE_Z) & (z < -77.0) & (y > 16.4) & (y < 43.0)
            rgb[dw] = np.array([30.0, 33.0, 26.0])
            # cab panel seams and the cab's lower front panel joint
            cabz = z < -61.5
            m = cabz & side & ((np.abs(z + 76.0) < 0.3) | (np.abs(z + 65.6) < 0.25) & (y > 30.6)) & (y > 15)
            m |= cabz & (n[2] < -0.9) & (np.abs(y - 21.0) < 0.25) & (ax > 8.8)
            m |= cabz & (n[1] > 0.9) & (np.abs(z + 70.0) < 0.25)
            # deck-side and skirt rivets
            riv = side & (ax > 23.8) & (np.mod(z, 2.2) < 0.7) & ((np.abs(y - 22.4) < 0.35) | (np.abs(y - 25.6) < 0.35))
            riv |= side & (ax > HW - 0.1) & (y > DECK_B) & (y < DECK_T) & (np.mod(z, 2.4) < 0.8)
            rgb[m] *= 0.6
            rgb[riv] = rgb[riv] * 1.2 + 8
        if cube.mat in ("frame", "band") and min(w, h) > 5:
            rv = (edge > 1.1) & (edge < 2.0) & (np.mod(s * w + t * h, 3.2) < 1.1)
            rgb[rv] = rgb[rv] * 1.22 + 6
        if cube.mat == "can":
            m = (np.mod(z + 58.0, 32.0) < 0.3) & (z > -57) & (z < 69)
            rgb[m] *= 0.75
            # red-white warning band near the launch end
            band = (z > FRONT_END + 3.2) & (z < FRONT_END + 5.0) & (cube.tag != "rib")
            rgb[band] = np.array([150.0, 40.0, 30.0])
        return rgb

    def tyre(p, n, cube, edge):
        N = len(p)
        c = cube.tag[1]
        r = np.hypot(p[:, 1] - c[1], p[:, 2] - c[2])
        rgb = np.tile(hexrgb(SOLID["tire"]), (N, 1)) * (0.88 + 0.22 * value_noise(p, 1.6, 15))[:, None]
        if abs(n[0]) > 0.7:
            rgb[np.abs(r - 9.4) < 0.35] *= 1.45
            rgb[(r > 7.5) & (r < 8.6) & (np.mod(np.degrees(np.arctan2(p[:, 1] - c[1], p[:, 2] - c[2])), 15.0) < 6)] *= 1.25
        elif len(cube.tag) > 2:
            rgb *= 1.25          # worn lug faces
        else:
            rgb *= 0.7           # grooves between the lugs
        return rgb

    def paint(p, n, cube, face, s, t, wh):
        N = len(p)
        mat = cube.mat
        w, h = wh
        edge = np.minimum(np.minimum(s, 1 - s) * w, np.minimum(t, 1 - t) * h)
        alpha = np.full(N, 255.0)
        if mat in PAINTED:
            rgb = body(p, n, mat)
            if mat in ("rim", "hub") and isinstance(cube.tag, tuple):
                c = cube.tag[1]
                r = np.hypot(p[:, 1] - c[1], p[:, 2] - c[2])
                ang = np.degrees(np.arctan2(p[:, 1] - c[1], p[:, 2] - c[2]))
                holes = (np.abs(r - 6.0) < 0.55) & (np.mod(ang, 45.0) < 9.0)
                rgb[holes & (abs(n[0]) > 0.7)] = np.array([24.0, 25.0, 22.0])
                rgb[(np.abs(r - 3.3) < 0.25) & (abs(n[0]) > 0.7)] *= 0.6
            rgb = lines(p, n, cube, rgb, edge, s, t, wh)
            rgba = np.concatenate([rgb, alpha[:, None]], 1)
            for d in decals:
                rgba = d.apply(p, n, rgba)
            rgb = weather(p, n, rgba[:, :3], edge, mat)
        elif mat == "tire":
            rgb = tyre(p, n, cube, edge)
        elif mat == "window":
            rgb = np.tile(hexrgb("#18232a"), (N, 1))
            k = 1.0 + 0.55 * (1 - t) ** 2 if abs(n[1]) < 0.5 else np.ones(N)
            rgb = rgb * k[:, None] * (0.92 + 0.16 * value_noise(p, 6.0, 41))[:, None]
            g = np.mod(p[:, 0] * 0.6 + p[:, 1] * 1.1 + p[:, 2] * 0.6, 15.0)
            glint = (g < 1.0) | ((g > 2.2) & (g < 2.7))
            rgb[glint] = rgb[glint] * 0.6 + 70
            rgb[edge < 0.7] = np.array([12.0, 12.0, 11.0])
            low = np.clip((-0.15 + t) * 1.2, 0, 1) if abs(n[1]) < 0.5 else np.zeros(N)
            rgb = rgb * (1 - 0.35 * low[:, None]) + DUST * 0.35 * low[:, None]
        elif mat == "lens":
            rgb = np.tile(hexrgb(SOLID["lens"]), (N, 1))
            if isinstance(cube.tag, tuple):
                cx, cy = cube.tag[1], cube.tag[2]
                d = np.hypot(p[:, 0] - cx, p[:, 1] - cy)
                rgb[d > 1.3] *= 0.75
                rgb[(p[:, 1] - cy > 0.3) & (np.abs(p[:, 0] - cx) < 0.7)] = np.array([250.0, 248.0, 236.0])
        elif mat == "mirror":
            rgb = np.tile(hexrgb(SOLID["mirror"]), (N, 1))
            if n[2] > 0.5:
                rgb = np.tile(hexrgb("#9fb1b8"), (N, 1)) * (0.8 + 0.3 * (1 - t))[:, None]
                rgb[edge < 0.8] = np.array([30.0, 32.0, 28.0])
        elif mat == "plate":
            rgb = np.tile(hexrgb(SOLID["plate"]), (N, 1))
            glyph = (edge > 0.9) & (np.mod(s * w, 1.6) < 0.9) & (t > 0.25) & (t < 0.75)
            rgb[glyph | ((edge > 0.3) & (edge < 0.7))] = WHITE * 0.85
        elif mat == "grille":
            rgb = np.tile(hexrgb(SOLID["grille"]), (N, 1))
            if isinstance(cube.tag, str) and cube.tag == "grille":
                bars = np.mod(p[:, 0], 1.3) < 0.5 if abs(n[0]) < 0.5 else np.mod(p[:, 2], 1.3) < 0.5
                rgb[bars] = np.array([62.0, 68.0, 52.0])
                rgb[np.mod(p[:, 1], 5.0) < 0.5] = np.array([62.0, 68.0, 52.0])
            elif isinstance(cube.tag, str) and cube.tag == "louver":
                q = np.mod(p[:, 1], 1.1) if abs(n[1]) < 0.5 else np.mod(p[:, 2], 1.2)
                rgb[q > 0.5] = np.array([66.0, 74.0, 52.0]) if pal.get("skin") else np.array([90.0, 86.0, 62.0])
        elif mat in ("red", "amber") and isinstance(cube.tag, tuple):
            rgb = np.tile(hexrgb(SOLID[mat]), (N, 1)) * (0.85 + 0.35 * np.clip(edge / 3.0, 0, 1))[:, None]
        elif mat == "cable":
            rgb = np.tile(hexrgb(SOLID["cable"]), (N, 1))
            rgb[np.mod(p[:, 0], 0.9) < 0.2] *= 0.6
        elif mat == "chrome":
            rgb = np.tile(hexrgb(SOLID["chrome"]), (N, 1)) * (0.85 + 0.2 * value_noise(p * np.array([3, 0.2, 3]), 1.0, 77))[:, None]
        elif mat in ("metal", "exhaust", "chassis"):
            rgb = np.tile(hexrgb(SOLID[mat]), (N, 1)) * (0.9 + 0.2 * fbm(p, 5.0, 61, 2))[:, None]
            rust = np.clip(fbm(p, 3.0, 81, 2) - 0.45, 0, 1) * (1.2 if mat == "exhaust" else 0.7)
            rgb = rgb * (1 - rust[:, None]) + np.array([98.0, 66.0, 44.0]) * rust[:, None]
            if cube.tag == "tread" and n[1] > 0.5:
                rgb[np.mod(np.floor(p[:, 0] / 0.8) + np.floor(p[:, 2] / 0.8), 2) == 0] *= 1.2
            if cube.tag == "pad":
                st = np.mod(p[:, 0] + p[:, 2], 2.4) < 1.2
                if abs(n[1]) < 0.5:
                    rgb[st] = np.array([170.0, 50.0, 38.0])
                    rgb[~st] = WHITE * 0.8
            if n[1] < -0.6:
                rgb *= 0.7
        elif mat in SOLID:
            rgb = np.tile(hexrgb(SOLID[mat]), (N, 1)) * (0.92 + 0.12 * value_noise(p, 2.0, 91))[:, None]
        else:
            rgb = np.tile(np.array([255.0, 0, 255.0]), (N, 1))
        # mud: thrown around every wheel, caked low on the hull, on tyres near the ground
        if mat != "window":
            low = np.clip((15.0 - p[:, 1]) / 13.0, 0, 1) ** 1.3
            splash = np.zeros(N)
            for zz in AXLES:
                rr = np.hypot(p[:, 1] - WHEEL_Y, p[:, 2] - zz)
                splash = np.maximum(splash, np.clip(1 - (rr - 11.0) / 9.0, 0, 1) * (np.abs(p[:, 0]) > 12.0))
            amt = np.maximum(low, 0.7 * splash) * (0.3 + 0.7 * fbm(p, 4.5, 101, 3))
            if mat == "tire":
                amt = 0.12 + 0.45 * np.clip((8.0 - p[:, 1]) / 8.0, 0, 1) * fbm(p, 3.0, 111, 2) + 0.15 * splash
            elif mat in ("rim", "hub"):
                amt = 0.15 + 0.5 * amt
            elif mat in ("can", "cover", "cover2", "frame", "band", "lens", "plate"):
                amt = amt * 0.25
            amt = np.clip(amt, 0, 0.72)
            wet = fbm(p, 2.5, 121, 2) > 0.55
            col = np.where(wet[:, None], MUD[None, :], DRY_MUD[None, :])
            rgb = rgb * (1 - amt[:, None]) + col * amt[:, None]
        return np.concatenate([np.clip(rgb, 0, 255), alpha[:, None]], 1)

    return paint


def painter_s300():
    return make_painter("s300")


def painter_s400():
    return make_painter("s400")


MODELS = {"s300ps": (build_s300, painter_s300), "s400_triumf": (build_s400, painter_s400)}

_RAISED = K.axis_rot("x", 90.0)
_ram_angle, _ram_ext = ram_pose(90.0)
_POSE = {"move_pack": _RAISED, "move_ram_0": K.axis_rot("x", _ram_angle), "move_ram_1": K.axis_rot("x", _ram_angle),
         "move_door_l": K.axis_rot("y", 65.0), "move_door_r": K.axis_rot("y", -65.0),
         "move_antenna": K.axis_rot("x", -90.0)}
for _i in range(4):
    _POSE[f"move_cover_{_i}"] = K.axis_rot("x", 100.0)
PREVIEW_POSES = {"s300ps": dict(_POSE), "s400_triumf": dict(_POSE)}
