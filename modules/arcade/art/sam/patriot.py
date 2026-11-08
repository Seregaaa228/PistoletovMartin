"""MIM-104 Patriot launching station: M901-style launcher on an M860-style semitrailer towed by a
HEMTT-style 6x6 tractor.

Built on the aviation kit (modules/aviation/art/realistic: kit.py geometry, paint.py helpers) at
the detail of the Su-25/F-16/J-10 remakes; driven by modules/arcade/art/sam_assets.py.

Units 16 per block (1 m), front -Z, left +X, ground y = 0. The real tractor-trailer is about
16 m long; tractor and trailer are shortened to ~13 m (tractor 6.4 m, trailer 7.6 m from the
king pin) and kept at the real width (2.44 m), height (4.1 m stowed) and canister size
(5.8 x 1.0 x 1.0 m). The game treats the rig as one rigid vehicle: the king pin does not
articulate.

The launcher carries four canisters in a 2 x 2 stack: the lower pair are single-round PAC-2
canisters, the upper pair PAC-3 four-round canisters. Stowed, the canisters lie along the
trailer with their muzzles (launch ends) to the FRONT (-Z); the elevation hinge is at the rear
of the stack and the launcher raises the front end to a fixed 38 degrees to fire.

Bones:
  root > base                       tractor + trailer (one rigid body), every static part
  root > Wheel > wheelL0Turn/wheelR0Turn (tractor front axle, steers), wheel[LR]1..4 (spin)
  base > move_turret                launcher train, yaw about Y
         move_turret > move_launcher   elevation about X, 0 stowed .. +38 raised (front end up)
                       move_launcher > move_cover_<i>   muzzle covers, hinge on the top edge
         move_turret > move_ram_<k> > move_ram_<k>_s1  elevation rams (barrel turns, rod slides)
  base > move_jack_<j>              levelling jacks (0 FL, 1 FR, 2 RL, 3 RR), slide down
  base > move_door_l / move_door_r  cab doors, hinge on the front edge
  base > move_antenna               data-link mast, folded back along the electronics shelter
Inside move_* bones every cube is axis-aligned or turned about Z only (SBW's baker flips X/Y).
"""
from __future__ import annotations

import math

import numpy as np

import kit as K
from kit import Model, axis_rot, fbm, hexrgb, value_noise
from paint import Decal, dilate, hatch, rivet_rows, stations_mask, text_mask

DENSITY = 1.5
FIXED = {"root", "base"}

L, R = 1, -1

# ------------------------------------------------------------------ layout ---
WR, WW, WX = 10.6, 7.4, 15.6                 # tyre radius (16.00R20), tread width, wheel centre |x|
AXLES = (-80.0, -30.0, -10.0, 60.0, 80.0)    # tractor front, tractor tandem, trailer tandem
HW = 19.6                                    # half width (2.44 m)
CAB_Z0, CAB_Z1 = -101.0, -66.0               # tractor cab
CAB_B, CAB_T = 22.0, 47.0
DOOR_HINGE_Z = -88.0
GOOSE_Z0, GOOSE_Z1 = -42.0, -4.0             # trailer gooseneck
GN_B, GN_T = 23.4, 32.0
DECK_B, DECK_T = 22.0, 24.0                  # trailer main deck
TRAILER_REAR = 100.0
KINGPIN_Z = -20.0

TURRET = (0.0, 25.0, 70.0)                   # train axis (vertical through this point)
PIVOT = (0.0, 30.0, 95.0)                    # elevation hinge (axis along X)
FIRE_ELEV = 38.0
CW = 16.4                                    # canister section (outer, square)
COLS = (8.6, -8.6)
ROWS = (39.8, 57.0)
Z_MUZZLE, Z_REAR = 1.0, 93.6                 # canister launch end / aft end, stowed
CANISTERS = ((COLS[0], ROWS[0], "pac2"), (COLS[1], ROWS[0], "pac2"),
             (COLS[0], ROWS[1], "pac3"), (COLS[1], ROWS[1], "pac3"))
FRAMES = (6.0, 30.0, 54.0, 78.0)            # clamp frames round the stack
RAM_X, RAM_Y, RAM_A, RAM_B = 12.0, 27.8, 40.0, 74.0
JACKS = ((17.0, 8.0), (-17.0, 8.0), (17.0, 95.0), (-17.0, 95.0))
JACK_TRAVEL = 6.0
ANTENNA = (-10.0, 48.1, -38.0)
DRIVER_EYE = (9.5, 38.5, -86.0)
CREW_EYE = (-9.5, 38.5, -86.0)


def ram_pose(phi):
    """Ram rotation about X (degrees, right-handed, as K.axis_rot) and rod extension (model
    units) for launcher elevation phi (degrees, 0 stowed .. 38 raised)."""
    f = math.radians(phi)
    dy, dz = RAM_Y - PIVOT[1], RAM_B - PIVOT[2]
    by = PIVOT[1] + dy * math.cos(f) - dz * math.sin(f)
    bz = PIVOT[2] + dy * math.sin(f) + dz * math.cos(f)
    vy, vz = by - RAM_Y, bz - RAM_A
    return -math.degrees(math.atan2(vy, vz)), math.hypot(vy, vz) - (RAM_B - RAM_A)


def wheel_bone(side, i):
    s = "L" if side > 0 else "R"
    return f"wheel{s}0Turn" if i == 0 else f"wheel{s}{i}"


# ----------------------------------------------------------------- builder ---
class Build:
    def __init__(self, m):
        self.m = m

    def box(self, bone, x0, x1, y0, y1, z0, z1, mat, tag=None, skip=()):
        return self.m.box(bone, x0, x1, y0, y1, z0, z1, mat, tag=tag, skip=set(skip))

    def sbox(self, bone, x0, x1, y0, y1, z0, z1, mat, tag=None, skip=()):
        return [self.box(bone, x0, x1, y0, y1, z0, z1, mat, tag, skip),
                self.box(bone, -x0, -x1, y0, y1, z0, z1, mat, tag, skip)]

    def prism(self, bone, axis, c, r, ln, mat, n=6, tag=None, caps=(True, True), roll=0.0):
        out = self.m.prism(bone, axis, tuple(float(v) for v in c), r, ln, mat, n=n, caps=caps, roll=roll)
        for cb in out:
            cb.tag = tag
        return out

    def sprism(self, bone, axis, c, r, ln, mat, **kw):
        x, y, z = c
        return self.prism(bone, axis, (x, y, z), r, ln, mat, **kw) + self.prism(bone, axis, (-x, y, z), r, ln, mat, **kw)

    def ori(self, bone, c, size, r, mat, tag=None):
        cb = self.m.oriented(bone, tuple(float(v) for v in c), tuple(size), r, mat)
        cb.tag = tag
        return cb

    def bar(self, bone, p0, p1, w, h, mat, up=(0, 1, 0), tag=None):
        p0, p1 = np.asarray(p0, float), np.asarray(p1, float)
        d = p1 - p0
        ln = float(np.linalg.norm(d))
        z = d / ln
        x = np.cross(up, z)
        if np.linalg.norm(x) < 1e-6:
            x = np.cross((1, 0, 0), z) if abs(z[0]) < .9 else np.cross((0, 0, 1), z)
        x /= np.linalg.norm(x)
        r = np.column_stack((x, np.cross(z, x), z))
        return self.ori(bone, (p0 + p1) / 2, (w, h, ln), r, mat, tag)

    def sbar(self, bone, p0, p1, w, h, mat, **kw):
        f = lambda p: (-p[0], p[1], p[2])  # noqa: E731
        return [self.bar(bone, p0, p1, w, h, mat, **kw), self.bar(bone, f(p0), f(p1), w, h, mat, **kw)]

    def arc(self, bone, x0, x1, cy, cz, r, a0, a1, n, t, mat):
        """Curved plate round the X axis (wheel arches): angle 0 = top, positive = forward."""
        out = []
        da = math.radians(a1 - a0) / n
        chord = 2 * (r + t) * math.tan(abs(da) / 2) + 0.12
        for k in range(n):
            a = math.radians(a0) + da * (k + 0.5)
            rad = np.array([0.0, math.cos(a), -math.sin(a)])
            tan = np.array([0.0, -rad[2], rad[1]])
            c = np.array([(x0 + x1) / 2, cy, cz]) + rad * (r + t / 2)
            fr = np.column_stack(((1.0, 0, 0), rad, tan))
            inset = 0.06 * (k % 2)
            out.append(self.ori(bone, c, (abs(x1 - x0) - 2 * inset, t, chord), fr, mat))
        return out

    def section(self, bone, z0, z1, cx, cy, w, h, ct, cb, mat, round_=True, ends=(True, True), tag=None):
        out = self.m.section(bone, z0, z1, cx, cy, w, h, ct, cb, mat=mat, round_=round_, ends=ends)
        if tag is not None:
            for c in out:
                c.tag = tag
        return out

    def xcyl(self, bone, x0, x1, cy, cz, r, mat, tag=None):
        """Round bar along X for move_* bones (no X rotations allowed there): three stepped
        axis-aligned boxes give a 12-sided outline; their ends are staggered against flicker."""
        a = r * 0.42
        b = r * 0.78
        xa, xb = sorted((x0, x1))
        out = []
        for k, (hy, hz) in enumerate(((a, r), (r, a), (b, b))):
            e = 0.04 * k
            out.append(self.box(bone, xa + e, xb - e, cy - hy, cy + hy, cz - hz, cz + hz, mat, tag))
        return out


# ------------------------------------------------------------------ wheels ---
def wheel(b, bone, cx, cz, side, cy=WR):
    """16-sided cross-country tyre with 28 directional lugs, dished rim, hub, nuts, CTIS hose."""
    c = np.array([cx, cy, cz])
    tag = ("wheel", (cx, cy, cz))
    b.prism(bone, "x", c, WR - 0.7, WW, "tyre", n=8, tag=tag)
    b.prism(bone, "x", c, WR - 0.25, WW - 1.8, "tyre", n=8, tag=tag, roll=11.25)
    for row, dx in ((0, 1.9), (1, -1.9)):
        for k in range(14):
            th = 360.0 / 14 * k + (360.0 / 28 if row else 0.0)
            rk = axis_rot("x", th)
            centre = c + np.array([dx, 0, 0]) + rk @ np.array([0.0, -(WR - 0.55), 0.0])
            b.ori(bone, centre, (3.4, 1.1, 2.6), rk @ axis_rot("y", 24 if row else -24), "tyre",
                  ("wheel", (cx, cy, cz), "lug"))
    o = side
    h = WW / 2
    b.prism(bone, "x", (cx + o * (h + 0.15), cy, cz), 6.6, 0.5, "rim", n=8, tag=tag)
    b.prism(bone, "x", (cx + o * (h + 0.55), cy, cz), 4.8, 0.5, "rim", n=8, tag=tag, roll=11.25)
    b.prism(bone, "x", (cx + o * (h + 1.1), cy, cz), 2.6, 1.1, "hub", n=6, tag=tag)
    b.prism(bone, "x", (cx + o * (h + 1.9), cy, cz), 1.2, 0.6, "metal", n=4, tag=tag)
    for k in range(10):
        a = math.radians(18.0 + 36 * k)
        b.box(bone, cx + o * (h + 0.75), cx + o * (h + 1.4), cy + 3.6 * math.cos(a) - 0.36,
              cy + 3.6 * math.cos(a) + 0.36, cz + 3.6 * math.sin(a) - 0.36, cz + 3.6 * math.sin(a) + 0.36, "metal")
    b.bar(bone, (cx + o * (h + 1.95), cy + 0.6, cz), (cx + o * (h + 0.6), cy + 5.6, cz - 1.4), 0.45, 0.45, "rubber")
    b.prism(bone, "x", (cx - o * (h + 0.7), cy, cz), 5.4, 1.4, "dark", n=6, tag=tag)


def wheels(b):
    for i, z in enumerate(AXLES):
        for s in (L, R):
            wheel(b, wheel_bone(s, i), s * WX, z, s)


# ----------------------------------------------------------------- tractor ---
def tractor_cab(b):
    B = "base"
    m = b.m
    z0, z1 = CAB_Z0, CAB_Z1
    cy = (CAB_B + CAB_T) / 2
    # body with a rounded roof, a bevelled front edge and a crowned roof cap
    b.section(B, z0 + 1.6, z1, 0.0, cy, 2 * HW - 0.8, CAB_T - CAB_B, 4.0, 0.6, "skin", round_="top")
    m.loft(B, [(z0, 0.0, cy - 0.4, 2 * HW - 2.8, CAB_T - CAB_B - 2.8, 3.0, 0.4),
               (z0 + 0.8, 0.0, cy - 0.2, 2 * HW - 1.8, CAB_T - CAB_B - 1.6, 3.4, 0.5),
               (z0 + 1.6, 0.0, cy - 0.1, 2 * HW - 1.2, CAB_T - CAB_B - 0.8, 3.6, 0.55)], round_="top")
    b.box(B, -15.0, 15.0, CAB_T, CAB_T + 0.5, z0 + 6.0, z1 - 3.0, "skin")         # roof stiffener pan
    # windscreen: two flat panes in a rubber frame, centre post, wipers, sun visor
    for s in (L, R):
        b.box(B, s * 1.4, s * 17.0, 31.0, 43.4, z0 - 0.3, z0, "rubber")
        b.box(B, s * 1.9, s * 16.5, 31.5, 42.9, z0 - 0.45, z0 - 0.3, "window")
        b.bar(B, (s * 4.0, 32.0, z0 - 0.7), (s * 11.5, 40.8, z0 - 0.7), 0.4, 0.35, "black")
        b.box(B, s * 3.4, s * 4.6, 31.2, 32.4, z0 - 0.8, z0 - 0.45, "dark")
    b.box(B, -1.4, 1.4, 30.6, 43.8, z0 - 0.5, z0, "skin")
    b.box(B, -18.0, 18.0, 43.8, 44.4, z0 - 2.2, z0, "skin")                       # visor
    for x in (-16.0, 0.0, 16.0):
        b.box(B, x - 0.4, x + 0.4, 43.0, 43.8, z0 - 1.6, z0, "dark")
    for x in (-6.0, 0.0, 6.0):                                                     # roof markers
        b.box(B, x - 1.2, x + 1.2, CAB_T - 0.8, CAB_T + 0.6, z0 + 0.4, z0 + 2.0, "amber", ("lamp",))
    # grille panel under the windscreen: frame, slats, brush guard
    b.box(B, -14.0, 14.0, 22.6, 30.2, z0 - 0.5, z0, "skin")
    b.box(B, -12.6, 12.6, 23.4, 29.4, z0 - 0.7, z0 - 0.5, "grille", "grille")
    for x in (-13.4, -6.6, 0.0, 6.6, 13.4):
        b.box(B, x - 0.45, x + 0.45, 22.2, 30.6, z0 - 1.9, z0 - 1.1, "dark")
    b.box(B, -14.0, 14.0, 29.8, 30.6, z0 - 1.9, z0 - 1.1, "dark")
    b.box(B, -14.0, 14.0, 22.2, 23.0, z0 - 1.9, z0 - 1.1, "dark")
    for x in (-13.4, 13.4):
        b.box(B, x - 0.45, x + 0.45, 22.2, 23.0, z0 - 1.1, z0, "dark")
    # front fenders with the headlight pods
    for s in (L, R):
        b.box(B, s * 14.0, s * HW, 16.0, 22.2, z0 - 0.6, z0 + 6.0, "skin")
        b.box(B, s * 14.5, s * (HW - 0.5), 22.0, 22.6, z0 - 0.4, z0 + 6.0, "skin")
        b.prism(B, "z", (s * 16.6, 19.2, z0 - 0.75), 2.3, 0.4, "dark", n=6)
        b.prism(B, "z", (s * 16.6, 19.2, z0 - 1.05), 1.8, 0.4, "lens", n=6, tag=("lens", (s * 16.6, 19.2)))
        b.box(B, s * 14.6, s * 15.4, 17.2, 21.2, z0 - 1.4, z0 - 0.6, "dark")       # lamp guard bars
        b.box(B, s * 17.8, s * 18.6, 17.2, 21.2, z0 - 1.4, z0 - 0.6, "dark")
        b.box(B, s * 14.6, s * 18.6, 21.2, 21.8, z0 - 1.4, z0 - 0.6, "dark")
        b.box(B, s * 12.0, s * 13.6, 18.4, 20.4, z0 - 0.5, z0 - 0.1, "amber", ("lamp",))
        b.box(B, s * 18.6, s * 19.4, 23.6, 25.2, z0 - 0.3, z0 + 1.2, "amber", ("lamp",))
        # arch round the steered wheel, liner, mud flap behind it
        b.arc(B, s * 12.4, s * 19.8, WR, AXLES[0], 11.8, -78.0, 80.0, 10, 0.8, "skin")
        b.box(B, s * 12.4, s * 19.4, 4.0, 15.6, -67.2, -66.6, "rubber")
    # bumper with winch, tow shackles, step and plate
    b.box(B, -HW, HW, 11.0, 16.6, -104.0, z0, "skin")
    b.box(B, -HW + 0.6, HW - 0.6, 16.6, 17.2, -103.4, z0, "skin")
    b.box(B, -6.0, 6.0, 11.8, 16.0, -104.3, -104.0, "dark")
    b.prism(B, "x", (0, 13.9, -103.0), 1.8, 9.0, "cable", n=5)                      # winch drum
    b.box(B, -1.2, 1.2, 12.4, 13.6, -105.4, -104.3, "metal")                        # winch hook
    for s in (L, R):
        b.box(B, s * 9.4, s * 11.4, 11.6, 13.2, -105.6, -104.0, "metal")
        b.prism(B, "x", (s * 10.4, 12.4, -105.4), 0.7, 3.2, "red", n=4)
        b.box(B, s * 16.0, s * 19.0, 11.4, 12.2, -104.6, -104.0, "rubber")
    b.box(B, 7.6, 9.4, 13.4, 15.6, -104.15, -104.0, "plate")
    # doors (own bones) and the rest of the side: window, steps, handles, mirrors
    for s, name in ((L, "move_door_l"), (R, "move_door_r")):
        x = s * (HW - 0.4)
        b.box(name, x, x + s * 0.4, 23.0, 45.2, DOOR_HINGE_Z, -68.6, "skin", "door")
        b.box(name, x + s * 0.4, x + s * 0.55, 32.6, 43.2, -86.6, -71.2, "rubber")
        b.box(name, x + s * 0.55, x + s * 0.7, 33.0, 42.8, -86.2, -71.6, "window")
        b.box(name, x + s * 0.4, x + s * 1.1, 30.0, 30.9, -72.8, -70.2, "dark")       # handle
        b.box(name, x + s * 0.4, x + s * 0.8, 27.6, 28.8, -72.0, -70.6, "metal")       # lock
        for y in (26.0, 40.0):
            b.box(name, x + s * 0.4, x + s * 1.0, y, y + 2.2, DOOR_HINGE_Z - 0.4, DOOR_HINGE_Z + 1.0, "dark")
    for s in (L, R):
        b.box(B, s * HW, s * (HW + 0.2), 33.0, 42.8, -99.0, -89.4, "window")           # front quarter
        b.box(B, s * (HW - 0.1), s * (HW + 0.25), 32.6, 43.2, -99.4, -89.0, "rubber")
        for z in (-70.0, -63.2):                                                       # step ladder
            b.box(B, s * 18.4, s * 19.0, 4.0, 22.0, z, z + 0.6, "dark")
        for y in (6.0, 11.6, 17.2):
            b.box(B, s * 13.4, s * 19.0, y, y + 0.8, -70.0, -62.6, "metal", "tread")
        b.box(B, s * (HW + 0.6), s * (HW + 1.1), 24.0, 42.0, -66.2, -65.7, "metal")     # grab rail
        for y in (24.0, 41.4):
            b.box(B, s * HW, s * (HW + 0.6), y, y + 0.6, -66.2, -65.7, "metal")
        # mirror on two arms
        b.bar(B, (s * HW, 44.0, -99.4), (s * (HW + 3.6), 44.6, -100.6), 0.6, 0.6, "dark")
        b.bar(B, (s * HW, 33.0, -99.4), (s * (HW + 3.6), 34.0, -100.6), 0.6, 0.6, "dark")
        b.box(B, s * (HW + 3.0), s * (HW + 4.8), 32.6, 44.8, -101.8, -100.4, "dark")
        b.box(B, s * (HW + 3.2), s * (HW + 4.6), 33.2, 44.2, -102.0, -101.8, "mirror")
        # gutters and rear-wall window
        b.box(B, s * (HW - 0.6), s * (HW + 0.2), 44.6, 45.1, z0 + 2.0, z1, "skin")
    b.box(B, -12.0, 12.0, 35.0, 42.0, z1, z1 + 0.25, "window")
    # roof: observation hatch ring (right), antenna bases, air horn, light bar
    b.prism(B, "y", (-8.0, CAB_T + 0.7, -80.0), 5.4, 1.2, "skin", n=8)
    b.prism(B, "y", (-8.0, CAB_T + 1.5, -80.0), 4.6, 0.6, "skin", n=8)
    b.box(B, -11.6, -4.4, CAB_T + 1.8, CAB_T + 2.1, -83.0, -77.0, "dark")
    b.box(B, 12.0, 15.0, CAB_T + 0.5, CAB_T + 1.6, -72.0, -69.0, "dark")
    b.prism(B, "y", (13.5, CAB_T + 10.0, -70.5), 0.3, 17.0, "antenna", n=2)
    b.prism(B, "y", (13.5, CAB_T + 2.0, -70.5), 0.8, 1.0, "dark", n=4)
    b.prism(B, "z", (6.0, CAB_T + 1.4, -92.0), 0.9, 5.0, "metal", n=4)
    b.box(B, 5.0, 7.0, CAB_T + 0.5, CAB_T + 1.0, -91.0, -89.0, "dark")


def tractor_body(b):
    B = "base"
    m = b.m
    # engine compartment behind the cab (rounded top), side louvres, access doors
    m.loft(B, [(-66.0, 0, 30.0, 28.0, 24.0, 3.6, 0.2), (-47.0, 0, 30.0, 28.0, 24.0, 3.6, 0.2)], round_="top")
    for s in (L, R):
        b.box(B, s * 14.0, s * 14.2, 25.0, 37.0, -63.0, -54.0, "grille", "louver")
        b.box(B, s * 14.0, s * 14.25, 21.0, 39.0, -53.0, -48.4, "skin", "door")
    b.box(B, -8.0, 8.0, 42.0, 42.3, -62.0, -50.0, "grille", "grille")
    # exhaust stack with heat shield (right), air cleaner (left)
    b.prism(B, "y", (-15.6, 36.0, -60.0), 1.3, 32.0, "exhaust", n=6)
    b.prism(B, "z", (-15.6, 52.4, -59.2), 1.2, 2.2, "exhaust", n=5)
    b.box(B, -17.6, -17.1, 30.0, 46.0, -62.0, -58.0, "grille", "grille")
    for y in (31.0, 45.0):
        b.box(B, -17.1, -14.0, y, y + 0.6, -60.6, -59.4, "dark")
    b.prism(B, "y", (16.0, 34.0, -60.0), 2.8, 18.0, "skin", n=8)
    b.prism(B, "y", (16.0, 43.6, -60.0), 3.2, 1.4, "skin", n=8)
    b.prism(B, "y", (16.0, 44.6, -60.0), 2.2, 0.8, "dark", n=6)
    for y in (28.0, 39.0):
        b.prism(B, "y", (16.0, y, -60.0), 3.0, 0.7, "dark", n=6)
    b.box(B, 14.0, 15.0, 30.0, 32.0, -61.0, -59.0, "dark")
    # frame rails and cross-members
    b.sbox(B, 5.4, 8.2, 13.0, 20.0, -103.0, -1.0, "frame")
    for z in (-96.0, -64.0, -44.0, -20.0, -2.4):
        b.box(B, -5.4, 5.4, 13.6, 19.4, z, z + 1.6, "frame")
    # front axle (beam, leaf springs, steering box, tie rod)
    z = AXLES[0]
    b.box(B, -12.0, 12.0, 8.8, 12.2, z - 1.4, z + 1.4, "frame")
    b.prism(B, "z", (0, 10.6, z), 3.4, 6.0, "frame", n=6)
    b.sbox(B, 6.0, 8.6, 12.6, 13.6, z - 13.0, z + 13.0, "metal")
    b.sbox(B, 6.0, 8.6, 11.8, 12.6, z - 9.0, z + 9.0, "metal")
    b.sbox(B, 5.8, 8.8, 11.0, 13.8, z - 2.0, z + 2.0, "dark")
    b.bar(B, (-11.0, 8.6, z - 4.0), (11.0, 8.6, z - 4.0), 0.8, 0.8, "metal")
    b.box(B, 8.2, 11.6, 14.0, 19.0, -98.0, -93.0, "dark")
    b.sbar(B, (10.6, 11.0, z + 3.0), (9.4, 18.0, z + 6.0), 1.0, 1.0, "chrome")
    # engine / transmission under the cab, sump, transfer case
    b.box(B, -5.2, 5.2, 9.2, 21.6, -76.0, -48.0, "dark")
    b.box(B, -4.0, 4.0, 7.6, 9.2, -72.0, -60.0, "dark")
    b.box(B, -4.2, 4.2, 8.6, 15.6, -46.0, -38.0, "frame")
    # tandem: walking beams, trunnion, axles, diffs, drive line
    zc = (AXLES[1] + AXLES[2]) / 2
    b.sbox(B, 8.4, 10.6, 9.6, 12.6, AXLES[1] - 3.0, AXLES[2] + 3.0, "frame")
    b.sprism(B, "x", (9.5, 13.6, zc), 2.6, 2.6, "dark", n=6)
    b.box(B, -8.4, 8.4, 12.8, 14.4, zc - 1.4, zc + 1.4, "frame")
    for z in AXLES[1:3]:
        b.box(B, -12.0, 12.0, 9.0, 12.2, z - 1.4, z + 1.4, "frame")
        b.prism(B, "z", (0, 10.6, z), 3.4, 6.4, "frame", n=6)
        b.prism(B, "z", (0, 10.6, z - 3.6), 1.6, 1.2, "dark", n=4)
        b.sbar(B, (5.0, 10.0, z - 2.0), (5.0, 15.0, zc - 2.0 if z < zc else zc + 2.0), 0.9, 0.9, "metal")
    for za, zb in ((-46.0, -33.6), (-26.4, -13.6)):
        b.prism(B, "z", (0, 11.0, (za + zb) / 2), 0.9, zb - za, "metal", n=4)
    # fuel tank (left) and battery / tool box (right) between the front axle and the tandem
    b.section(B, -64.0, -46.0, 14.8, 14.6, 8.0, 9.6, 2.0, 2.0, "skin", round_=True)
    for z in (-61.0, -49.0):
        b.box(B, 10.6, 19.0, 9.6, 19.6, z, z + 0.8, "dark")
    b.prism(B, "y", (15.6, 19.6, -55.0), 1.0, 0.9, "metal", n=4)
    b.box(B, -18.8, -10.6, 10.4, 20.0, -64.0, -46.0, "skin")
    b.box(B, -19.05, -18.8, 11.2, 19.2, -63.2, -55.4, "skin", "door")
    b.box(B, -19.05, -18.8, 11.2, 19.2, -54.6, -46.8, "skin", "door")
    for z in (-58.0, -50.0):
        b.box(B, -19.4, -19.05, 16.6, 17.6, z, z + 1.4, "metal")
    # air tanks under the frame, catwalk plates behind the engine
    b.sprism(B, "z", (10.4, 15.6, -42.0), 1.9, 7.0, "skin", n=6)
    b.box(B, -13.0, 13.0, 20.0, 21.0, -47.0, -42.0, "metal", "tread")
    # tandem mudguards with flaps, rear cross-member, lamps
    for s in (L, R):
        b.box(B, s * 11.4, s * 19.8, 21.6, 22.4, -43.0, 2.2, "skin")
        b.box(B, s * (19.0), s * 19.8, 18.6, 21.6, -43.0, 2.2, "skin")
        b.m.slab(B, (s * 15.6, 20.4, -44.2), (s * 15.6, 15.6, -46.8), 8.4, 0.8, "skin")
        b.box(B, s * 12.0, s * 19.4, 4.0, 21.6, 1.6, 2.2, "rubber")
        b.box(B, s * 9.0, s * 12.4, 14.0, 16.4, -1.0, -0.4, "red", ("lamp",))
        b.box(B, s * 9.0, s * 12.4, 16.4, 17.6, -1.0, -0.4, "amber", ("lamp",))
    b.box(B, -1.6, 1.6, 13.0, 16.0, -2.4, 0.6, "frame")                            # pintle hook
    b.prism(B, "z", (0, 14.0, 1.4), 1.3, 1.6, "metal", n=4)
    # fifth wheel and its ramps
    b.box(B, -12.0, 12.0, 20.0, 21.6, -31.0, -10.0, "frame")
    b.box(B, -11.0, 11.0, 21.6, GN_B, -29.0, -11.0, "metal", "tread")
    b.sbox(B, 2.0, 11.0, 21.6, 22.6, -10.0, -4.0, "frame")
    b.sbar(B, (6.4, 22.4, -4.2), (6.4, 20.4, -0.4), 4.0, 0.8, "frame")


# ----------------------------------------------------------------- trailer ---
def trailer(b):
    B = "base"
    z0, z1 = GOOSE_Z0, GOOSE_Z1
    # gooseneck: deck block, bevelled front, front wall details
    b.box(B, -HW, HW, GN_B + 1.6, GN_T, z0 + 1.6, z1, "skin2")
    b.box(B, -HW + 1.0, HW - 1.0, GN_B, GN_B + 1.6, z0 + 3.0, z1, "skin2")
    b.m.slab(B, (0, GN_B + 0.8, z0 + 3.2), (0, GN_B + 2.4, z0 + 1.6), 2 * HW - 2.0, 1.4, "skin2")
    b.box(B, -HW, HW, GN_B + 1.6, GN_T, z0, z0 + 1.6, "skin2")
    b.box(B, -HW - 0.2, HW + 0.2, GN_T - 0.6, GN_T, z0 - 0.2, z1, "skin2")          # deck edge rail
    for s in (L, R):
        b.box(B, s * 4.0, s * 7.0, GN_B + 1.6, GN_B + 4.6, z0 - 0.4, z0, "dark")     # gladhands, receptacle
        b.prism(B, "z", (s * 5.5, GN_B + 3.1, z0 - 0.7), 0.7, 0.6, "red" if s > 0 else "blue", n=4)
        b.box(B, s * 16.0, s * 18.8, GN_T - 3.6, GN_T - 1.6, z0 - 0.3, z0, "amber", ("lamp",))
        for z in range(int(z0) + 6, int(z1), 8):                                     # stake pockets
            b.box(B, s * HW, s * (HW + 0.5), GN_T - 4.0, GN_T - 0.6, z, z + 1.6, "skin2")
    b.box(B, -1.4, 1.4, GN_B + 1.6, GN_B + 4.0, z0 - 0.5, z0, "dark")
    b.prism(B, "y", (0, GN_B - 1.0, KINGPIN_Z), 1.2, 2.0, "metal", n=4)              # king pin
    b.box(B, -14.0, 14.0, GN_B - 0.2, GN_B, -32.0, -8.0, "metal")                   # upper coupler plate
    # step down from the gooseneck to the main deck
    b.box(B, -HW, HW, DECK_T, GN_T, z1, z1 + 1.2, "skin2")
    for s in (L, R):
        b.m.slab(B, (s * 12.0, GN_B - 0.6, z1 - 4.0), (s * 12.0, DECK_B - 0.6, z1 + 3.0), 3.0, 1.4, "frame")
    # main deck, side rails with stake pockets, rub rail
    b.box(B, -HW, HW, DECK_B, DECK_T, z1, TRAILER_REAR, "skin2", "tread")
    b.sbox(B, HW - 0.8, HW, 17.8, DECK_B, z1 + 1.2, TRAILER_REAR, "skin2")
    for s in (L, R):
        for z in range(int(z1) + 10, int(TRAILER_REAR) - 2, 12):
            b.box(B, s * HW, s * (HW + 0.5), 18.4, DECK_T - 0.2, z, z + 1.6, "skin2")
    for s in (L, R):                                                                  # side marker lamps
        for z in (12.0, 52.0, 96.0):
            b.box(B, s * (HW + 0.5), s * (HW + 0.8), 20.2, 21.4, z - 0.8, z + 0.8, "amber" if z < 90 else "red", ("lamp",))
    # main I-beams, cross-members, axles, springs, equaliser, brake chambers
    b.sbox(B, 5.2, 8.4, 13.0, DECK_B, z1 + 1.2, TRAILER_REAR - 2.0, "frame")
    b.sbox(B, 4.6, 9.0, 13.0, 13.8, z1 + 1.2, TRAILER_REAR - 2.0, "frame")
    for z in range(int(z1) + 6, int(TRAILER_REAR) - 2, 11):
        b.box(B, -18.8, 18.8, 18.6, DECK_B, z, z + 1.2, "frame")
    for z in AXLES[3:]:
        b.box(B, -12.0, 12.0, 9.0, 12.0, z - 1.3, z + 1.3, "frame")
        b.sbox(B, 7.6, 10.4, 13.0, 14.0, z - 9.0, z + 9.0, "metal")
        b.sbox(B, 7.6, 10.4, 12.2, 13.0, z - 6.0, z + 6.0, "metal")
        b.sbox(B, 7.4, 10.6, 11.6, 14.4, z - 1.8, z + 1.8, "dark")
        b.sprism(B, "z", (7.4, 8.0, z - 3.6), 1.6, 2.2, "dark", n=5)
    zc = (AXLES[3] + AXLES[4]) / 2
    b.sbox(B, 7.8, 10.2, 13.4, 17.0, zc - 2.0, zc + 2.0, "frame")                  # equaliser hanger
    for z in (AXLES[3] - 10.0, AXLES[4] + 10.0):
        b.sbox(B, 8.0, 10.0, 13.4, 18.6, z - 1.2, z + 1.2, "frame")
    for s in (L, R):
        b.box(B, s * 12.0, s * 19.4, 3.6, DECK_B - 0.4, 92.0, 92.6, "rubber")       # mud flaps
        b.box(B, s * 11.0, s * 19.8, 17.8, 18.4, AXLES[3] - 12.0, AXLES[4] + 12.0, "skin2")
    # stowage boxes under the deck between the front jacks and the axles
    for s in (L, R):
        b.box(B, s * 9.6, s * 18.6, 11.4, DECK_B - 0.2, 14.0, 44.0, "skin")
        b.box(B, s * 18.6, s * 18.85, 12.2, 20.8, 14.8, 28.6, "skin", "door")
        b.box(B, s * 18.6, s * 18.85, 12.2, 20.8, 29.4, 43.2, "skin", "door")
        for z in (21.0, 36.0):
            b.box(B, s * 18.85, s * 19.2, 18.6, 19.6, z, z + 1.6, "metal")
    b.prism(B, "z", (-13.0, 9.0, 12.0), 2.2, 2.0, "dark", n=6)                     # spare-wheel winch
    # rear: bumper, under-ride bar, light clusters, reflectors, plate, steps
    b.box(B, -HW, HW, 15.4, DECK_T, TRAILER_REAR, TRAILER_REAR + 0.8, "skin2")
    b.box(B, -15.0, 15.0, 7.0, 9.6, TRAILER_REAR - 2.2, TRAILER_REAR + 0.4, "frame", "hazard")
    b.sbox(B, 10.0, 12.0, 9.6, 15.4, TRAILER_REAR - 2.0, TRAILER_REAR - 0.4, "frame")
    for s in (L, R):
        b.box(B, s * 13.0, s * 17.4, 16.4, 19.6, TRAILER_REAR + 0.8, TRAILER_REAR + 1.2, "red", ("lamp",))
        b.box(B, s * 17.4, s * 19.0, 16.4, 19.6, TRAILER_REAR + 0.8, TRAILER_REAR + 1.2, "amber", ("lamp",))
        b.box(B, s * 13.0, s * 14.6, 20.6, 22.6, TRAILER_REAR + 0.8, TRAILER_REAR + 1.1, "red", ("lamp",))
        b.box(B, s * 10.0, s * 12.0, 21.0, 22.6, TRAILER_REAR + 0.8, TRAILER_REAR + 1.0, "lens")
    b.box(B, -4.4, 4.4, 17.0, 19.6, TRAILER_REAR + 0.8, TRAILER_REAR + 1.0, "plate")
    for y in (12.0, 16.0):
        b.box(B, 5.0, 9.6, y, y + 0.7, TRAILER_REAR + 0.8, TRAILER_REAR + 1.6, "metal", "tread")


def jacks(b):
    for j, (x, z) in enumerate(JACKS):
        s = 1 if x > 0 else -1
        B = "base"
        b.prism(B, "y", (x, 17.0, z), 2.0, 8.8, "skin2", n=6)
        b.prism(B, "y", (x, 12.4, z), 2.4, 0.9, "frame", n=6)
        b.prism(B, "y", (x, DECK_B - 0.5, z), 2.6, 1.0, "frame", n=6)
        b.box(B, s * 8.4, x, 18.6, DECK_B, z - 1.4, z + 1.4, "frame")
        b.box(B, x - 0.25, x + 0.25, 13.0, 21.0, z + 2.2, z + 2.7, "rubber")
        b.box(B, x - s * 2.6, x - s * 3.2, 14.0, 19.0, z - 0.6, z + 0.6, "dark")    # crank gearbox
        name = f"move_jack_{j}"
        b.m.bone(name, "base", (x, 12.0, z))
        b.box(name, x - 1.3, x + 1.3, 5.0, 12.4, z - 1.3, z + 1.3, "chrome")
        b.box(name, x - 3.2, x + 3.2, 3.6, 4.6, z - 3.2, z + 3.2, "frame", "pad")
        b.box(name, x - 2.0, x + 2.0, 4.6, 5.4, z - 2.0, z + 2.0, "frame")


# ---------------------------------------------------- generator, electronics ---
def equipment(b):
    B = "base"
    m = b.m
    y0 = GN_T
    # 15 kW generator set on the gooseneck (left): rounded housing, louvres, doors, muffler
    m.section(B, -40.4, -24.6, 10.0, y0 + 8.0, 17.6, 16.0, 2.4, 0.2, mat="skin", round_="top")
    b.box(B, 18.8, 19.05, y0 + 3.0, y0 + 13.0, -39.0, -32.6, "grille", "louver")
    b.box(B, 18.8, 19.05, y0 + 1.6, y0 + 14.0, -31.8, -26.0, "skin", "door")
    b.box(B, 19.05, 19.5, y0 + 7.0, y0 + 8.4, -27.6, -26.8, "metal")
    b.box(B, 3.0, 17.0, y0 + 2.0, y0 + 13.0, -40.65, -40.4, "skin", "door")
    b.box(B, 5.0, 10.0, y0 + 8.0, y0 + 12.0, -40.85, -40.65, "dark")               # control panel
    b.box(B, 12.0, 16.0, y0 + 3.0, y0 + 7.0, -40.85, -40.65, "grille", "louver")
    b.box(B, 4.0, 14.0, y0 + 16.0, y0 + 16.3, -38.0, -32.0, "grille", "grille")
    b.prism(B, "z", (6.0, y0 + 17.6, -29.0), 1.6, 6.0, "exhaust", n=6)             # muffler
    b.prism(B, "y", (6.0, y0 + 19.4, -26.4), 0.7, 3.4, "exhaust", n=4)
    b.box(B, 5.2, 6.8, y0 + 21.1, y0 + 21.4, -27.4, -25.4, "exhaust")              # rain cap
    for x in (2.4, 17.6):
        for z in (-39.6, -25.4):
            b.box(B, x - 0.6, x + 0.6, y0 + 16.0, y0 + 17.2, z - 0.6, z + 0.6, "metal")
    b.prism(B, "y", (15.0, y0 + 16.4, -26.4), 0.9, 0.8, "metal", n=4)                # fuel filler
    # launcher electronics shelter (right): box, AC unit, doors, connector panel
    b.section(B, -40.4, -24.6, -10.0, y0 + 7.6, 17.6, 15.2, 1.6, 0.2, "skin", round_="top")
    b.box(B, -17.0, -3.0, y0 + 2.0, y0 + 13.0, -40.65, -40.4, "skin", "door")
    b.box(B, -16.0, -12.0, y0 + 9.0, y0 + 12.0, -40.85, -40.65, "plate")
    b.box(B, -18.8, -19.05, y0 + 1.6, y0 + 13.6, -39.4, -26.4, "skin", "door")
    b.box(B, -19.05, -19.5, y0 + 7.0, y0 + 8.4, -29.0, -28.2, "metal")
    b.box(B, -16.0, -4.0, y0 + 2.0, y0 + 9.0, -24.6, -24.0, "skin")                # connector panel
    for k, x in enumerate((-14.0, -11.0, -8.0, -5.0)):
        b.prism(B, "z", (x, y0 + 5.5, -23.6), 1.0, 0.8, "dark", n=4)
        b.prism(B, "z", (x, y0 + 5.5, -23.1), 0.6, 0.2, "metal", n=4)
    b.box(B, -7.0, -1.6, y0 + 15.2, y0 + 16.8, -36.0, -28.0, "skin")               # AC unit
    b.box(B, -6.6, -2.0, y0 + 16.8, y0 + 17.0, -35.4, -28.6, "grille", "grille")
    # antenna mast cradle and the folded mast (own bone)
    ax, ay, az = ANTENNA
    b.box(B, ax - 1.6, ax + 1.6, y0 + 15.2, ay - 1.3, az - 1.6, az + 1.6, "dark")
    b.box(B, ax - 0.6, ax + 0.6, y0, ay - 0.8, -18.0, -17.0, "frame")              # rest post
    b.box(B, ax - 1.2, ax + 1.2, ay - 1.2, ay - 0.8, -18.4, -16.6, "rubber")       # rest saddle
    b.m.bone("move_antenna", "base", ANTENNA)
    A = "move_antenna"
    b.box(A, ax - 1.3, ax + 1.3, ay - 1.3, ay + 1.3, az - 1.3, az + 1.3, "metal")
    b.prism(A, "z", (ax, ay, az + 9.0), 0.8, 16.0, "skin2", n=6)                 # telescopic mast
    b.prism(A, "z", (ax, ay, az + 20.6), 0.55, 8.0, "skin2", n=6)
    b.prism(A, "z", (ax, ay, az + 8.0), 1.0, 1.2, "frame", n=6)                   # section clamps
    b.prism(A, "z", (ax, ay, az + 17.0), 0.75, 1.0, "frame", n=6)
    b.box(A, ax - 3.2, ax + 3.2, ay - 0.2, ay + 0.2, az + 24.2, az + 24.6, "antenna")  # UHF elements
    b.box(A, ax - 2.4, ax + 2.4, ay - 0.2, ay + 0.2, az + 22.6, az + 23.0, "antenna")
    b.box(A, ax - 0.6, ax + 0.6, ay - 0.6, ay + 0.6, az + 24.6, az + 25.4, "dark")
    # cable reels between the shelters and the step (axis X), on A-frame stands
    for s in (L, R):
        xc = s * 12.0
        c = (xc, y0 + 6.2, -15.0)
        b.prism(B, "x", c, 5.6, 0.6, "skin", n=8, tag=("reel", c))
        b.prism(B, "x", (xc + s * 6.8, c[1], c[2]), 5.6, 0.6, "skin", n=8, tag=("reel", c))
        b.prism(B, "x", (xc + s * 3.4, c[1], c[2]), 4.4, 6.2, "cable", n=8)
        b.prism(B, "x", (xc + s * 3.4, c[1], c[2]), 0.8, 10.0, "metal", n=4)
        for dx in (-1.4, 8.2):
            b.bar(B, (xc + s * dx, y0, -20.0), (xc + s * dx, c[1], -15.0), 0.8, 0.8, "frame")
            b.bar(B, (xc + s * dx, y0, -10.0), (xc + s * dx, c[1], -15.0), 0.8, 0.8, "frame")
        b.box(B, xc + s * 1.6, xc + s * 5.2, y0, y0 + 1.2, -21.0, -9.0, "frame")
    # low stowage boxes on the deck ahead of the turntable, under the stowed canisters
    for s in (L, R):
        b.box(B, s * 11.0, s * 19.0, DECK_T, 28.6, 4.0, 30.0, "skin")
        b.box(B, s * 11.6, s * 18.4, 28.6, 28.9, 4.6, 16.6, "skin", "door")
        b.box(B, s * 11.6, s * 18.4, 28.6, 28.9, 17.4, 29.4, "skin", "door")
        b.prism(B, "y", (s * 5.0, 26.6, 10.0), 1.2, 5.2, "red", n=6)                # extinguisher
    # turntable: static race on the deck and the train drive gearbox
    _, ty, tz = TURRET
    b.prism(B, "y", (0, DECK_T + 0.5, tz), 15.2, 1.0, "frame", n=8)
    b.prism(B, "y", (0, DECK_T + 0.15, tz), 16.4, 0.3, "skin2", n=8)
    b.box(B, -4.0, 4.0, DECK_T, DECK_T + 0.8, tz - 18.6, tz - 15.4, "dark")


# ------------------------------------------------------------------ turret ---
def turret(b):
    T = "move_turret"
    _, ty, tz = TURRET
    y0, y1 = ty, ty + 1.2
    for k, (hx, hz) in enumerate(((14.6, 6.0), (13.4, 9.2), (11.2, 12.0), (8.0, 13.8), (4.0, 14.6))):
        e = 0.03 * k
        b.box(T, -hx, hx, y0 + e, y1 - e, tz - hz, tz + hz, "skin")
    b.sbox(T, 8.8, 15.4, y0, y1, 36.0, tz - 5.0, "skin")                            # forward ram arms
    b.box(T, -15.4, 15.4, y0 + 0.1, y1 - 0.1, 36.0, 38.4, "skin")
    b.sbox(T, 10.0, 15.6, y0, y1, tz + 5.0, 99.0, "skin")                           # rear hinge arms
    b.box(T, -15.6, 15.6, y0 + 0.1, y1 - 0.1, 96.6, 99.0, "skin")
    # ram clevis on the forward arms
    for s in (L, R):
        x = s * RAM_X
        b.box(T, x - 2.6, x - 1.6, y1, RAM_Y + 1.2, RAM_A - 2.2, RAM_A + 2.2, "frame")
        b.box(T, x + 1.6, x + 2.6, y1, RAM_Y + 1.2, RAM_A - 2.2, RAM_A + 2.2, "frame")
    # hinge brackets (gusseted plates) carrying the elevation pins
    px, py, pz = PIVOT
    for s in (L, R):
        b.box(T, s * 14.4, s * 16.2, y1, py + 2.6, pz - 5.0, pz + 3.0, "skin")
        b.box(T, s * 14.4, s * 16.2, y1, py - 1.0, pz - 9.0, pz - 5.0, "skin")
        b.box(T, s * 16.2, s * 16.6, py - 1.4, py + 1.4, pz - 1.4, pz + 1.4, "metal")
    # traverse drive cover, hydraulic manifold, travel lock post
    b.box(T, -6.0, 6.0, y1, y1 + 1.2, tz + 6.0, tz + 12.0, "skin")
    b.box(T, -4.0, 4.0, y1 + 1.2, y1 + 1.4, tz + 7.0, tz + 11.0, "grille", "louver")
    b.box(T, -3.0, 3.0, y1, y1 + 1.4, tz - 10.0, tz - 4.0, "dark")
    b.box(T, -0.6, 0.6, y1, 27.8, 40.0, 41.2, "metal")


def rams(b):
    """Elevation rams: barrel turns about X at the turret mount A, the rod slides out along it
    to the launcher lug B."""
    for k, s in enumerate((L, R)):
        x = s * RAM_X
        bar_ = f"move_ram_{k}"
        rod = bar_ + "_s1"
        b.m.bone(bar_, "move_turret", (x, RAM_Y, RAM_A))
        b.m.bone(rod, bar_, (x, RAM_Y, RAM_A))
        b.prism(bar_, "z", (x, RAM_Y, (RAM_A + 62.0) / 2), 1.45, 62.0 - RAM_A, "skin2", n=6)
        b.prism(bar_, "z", (x, RAM_Y, 61.6), 1.75, 1.2, "frame", n=6)
        b.box(bar_, x - 1.5, x + 1.5, RAM_Y - 1.5, RAM_Y + 1.5, RAM_A - 1.5, RAM_A + 1.5, "frame")
        b.box(bar_, x - 0.25, x + 0.25, RAM_Y + 1.4, RAM_Y + 1.9, RAM_A + 2.0, 60.0, "rubber")
        b.prism(rod, "z", (x, RAM_Y, (52.0 + RAM_B) / 2), 0.95, RAM_B - 52.0, "chrome", n=6)
        b.box(rod, x - 1.1, x + 1.1, RAM_Y - 1.2, RAM_Y + 1.2, RAM_B - 1.4, RAM_B + 1.2, "metal")


# ---------------------------------------------------------------- launcher ---
def launcher_frame(b):
    Lb = "move_launcher"
    px, py, pz = PIVOT
    yb = ROWS[0] - CW / 2           # 31.6: bottom of the stack
    yt = ROWS[1] + CW / 2           # 65.2: top of the stack
    xo = COLS[0] + CW / 2           # 16.8: outer face of a column
    # cradle: longitudinal beams under the stack, rear cross box, ram lugs
    b.sbox(Lb, 9.6, 14.4, yb - 2.2, yb - 0.2, 3.0, pz - 1.0, "frame")
    b.sbox(Lb, 1.0, 4.6, 28.0, yb - 0.2, 3.0, pz - 9.0, "frame")                      # centre box beam
    b.sbox(Lb, 4.6, 5.0, 28.6, yb - 0.6, 8.0, pz - 12.0, "rubber")                  # hydraulic lines
    for s in (L, R):
        x = s * RAM_X
        b.box(Lb, x - 2.6, x - 1.6, RAM_Y - 1.0, yb - 2.2, RAM_B - 2.0, RAM_B + 2.0, "metal")
        b.box(Lb, x + 1.6, x + 2.6, RAM_Y - 1.0, yb - 2.2, RAM_B - 2.0, RAM_B + 2.0, "metal")
    # clamp frames round the 2 x 2 stack (bottom, sides, middle tie, top with lifting eyes)
    for z in FRAMES:
        z0, z1 = z, z + 2.2
        b.box(Lb, -xo - 1.6, xo + 1.6, yb - 2.2, yb - 0.2, z0, z1, "frame")
        b.sbox(Lb, xo + 0.2, xo + 1.6, yb - 2.2, yt + 1.6, z0, z1, "frame")
        b.box(Lb, -xo - 1.6, xo + 1.6, yt + 0.2, yt + 1.6, z0, z1, "frame")
        b.sbox(Lb, xo + 1.6, xo + 2.2, ROWS[0] + CW / 2 - 1.6, ROWS[0] + CW / 2 + 2.2, z0 + 0.4, z1 - 0.4, "metal")
        b.sbox(Lb, 2.0, 3.2, yt + 1.6, yt + 2.6, z0 + 0.4, z1 - 0.4, "metal")
        for s in (L, R):                                                      # clamp bolts
            b.box(Lb, s * (xo + 1.6), s * (xo + 2.0), yb + 3.0, yb + 4.0, z0 + 0.6, z1 - 0.6, "metal")
            b.box(Lb, s * (xo + 1.6), s * (xo + 2.0), yt - 4.0, yt - 3.0, z0 + 0.6, z1 - 0.6, "metal")
    # rear frame: heavy box section, hinge lugs down to the pins, umbilical junction boxes
    zr0, zr1 = Z_REAR + 0.2, pz + 1.4
    b.box(Lb, -xo - 1.6, xo + 1.6, yb - 2.4, yb + 0.4, zr0, zr1, "frame")
    b.sbox(Lb, xo + 0.2, xo + 1.8, yb - 2.4, yt + 1.8, zr0, zr1, "frame")
    b.box(Lb, -xo - 1.6, xo + 1.6, yt + 0.2, yt + 1.8, zr0, zr1, "frame")
    b.box(Lb, -xo, xo, ROWS[0] + CW / 2 - 0.4, ROWS[0] + CW / 2 + 0.4, zr0, zr1 - 0.6, "frame")
    b.box(Lb, -0.6, 0.6, yb + 0.4, yt + 0.2, zr0, zr1 - 0.6, "frame")
    for s in (L, R):
        b.box(Lb, s * 12.6, s * 14.4, py - 2.2, yb - 2.4, pz - 4.0, pz + 2.2, "frame")
        b.xcyl(Lb, s * 12.4, s * 14.6, py, pz, 2.0, "metal")
        b.box(Lb, s * 3.0, s * 9.0, yb + 2.0, yb + 7.0, zr1, zr1 + 1.6, "dark")       # junction boxes
        b.box(Lb, s * 3.6, s * 8.4, yb + 2.6, yb + 6.4, zr1 + 1.6, zr1 + 1.8, "metal")
        b.box(Lb, s * 4.0, s * 7.0, yt - 6.0, yt - 2.0, zr1, zr1 + 1.0, "dark")
    b.box(Lb, -12.0, 12.0, yb - 1.0, yb, zr1, zr1 + 0.8, "cable")                     # harness
    # walkway rail along the top, data plates
    b.sbox(Lb, 5.0, 6.0, yt + 1.6, yt + 2.2, FRAMES[0], FRAMES[-1] + 2.2, "frame")


def can_pac2(b, i, x, y):
    """Single-round PAC-2 canister: chamfered square body, stiffener rings, end flanges,
    lifting lugs; front cover with a domed frangible disc (own bone)."""
    Lb = "move_launcher"
    zf, zr = Z_MUZZLE, Z_REAR
    w = CW - 0.6
    b.section(Lb, zf + 2.0, zr - 2.4, x, y, w, w, 2.2, 2.2, "can", round_=False)
    for z in (16.0, 42.0, 66.0, 88.0):
        b.section(Lb, z - 0.6, z + 0.6, x, y, CW, CW, 2.5, 2.5, "can", round_=False, tag="rib")
    b.section(Lb, zf, zf + 2.0, x, y, CW, CW, 2.5, 2.5, "can", round_=False, tag="rib")
    b.section(Lb, zr - 2.4, zr, x, y, CW, CW, 2.5, 2.5, "can", round_=False, tag="rib")
    side = 1 if x > 0 else -1
    b.box(Lb, x + side * (w / 2 - 0.1), x + side * (w / 2 + 0.3), y - 3.0, y + 3.0, 50.0, 60.0, "can", "door")
    b.box(Lb, x - 3.0, x + 3.0, y - w / 2 - 0.3, y - w / 2 + 0.1, 48.0, 58.0, "can", "door")
    b.box(Lb, x + side * (w / 2), x + side * (w / 2 + 0.6), y + 3.4, y + 4.6, 24.0, 26.0, "metal")
    b.box(Lb, x + side * (w / 2), x + side * (w / 2 + 0.6), y + 3.4, y + 4.6, 76.0, 78.0, "metal")
    # aft end: closure with the umbilical connector and the blow-out disc
    b.box(Lb, x - 6.6, x + 6.6, y - 6.6, y + 6.6, zr, zr + 0.2, "dark")
    b.prism(Lb, "z", (x, y, zr + 0.3), 4.6, 0.2, "can", n=8, tag=("aft", (x, y)))
    cover = f"move_cover_{i}"
    b.m.bone(cover, Lb, (x, y + CW / 2 - 0.6, zf - 0.3))
    b.box(cover, x - 7.2, x + 7.2, y - 7.2, y + 7.2, zf - 0.6, zf, "cover")
    b.prism(cover, "z", (x, y, zf - 0.95), 5.8, 0.7, "cover", n=8, tag=("disc", (x, y)))
    b.prism(cover, "z", (x, y, zf - 1.5), 3.8, 0.5, "cover", n=8, roll=11.25, tag=("disc", (x, y)))
    b.prism(cover, "z", (x, y, zf - 1.9), 1.4, 0.4, "metal", n=4)
    b.box(cover, x - 2.0, x + 2.0, y + 6.0, y + 7.6, zf - 1.1, zf - 0.6, "metal")          # hinge
    for dx in (-6.0, 6.0):
        b.box(cover, x + dx - 0.7, x + dx + 0.7, y - 7.0, y - 5.8, zf - 1.0, zf - 0.6, "metal")  # latches


def can_pac3(b, i, x, y):
    """Four-round PAC-3 canister: lattice body (corner longerons, dense frames), a square
    front frame showing four cells; cover with four round frangible caps (own bone)."""
    Lb = "move_launcher"
    zf, zr = Z_MUZZLE, Z_REAR
    w = CW - 1.0
    b.section(Lb, zf + 2.4, zr - 2.4, x, y, w, w, 1.2, 1.2, "can3", round_=False)
    for sx in (-1, 1):                                                          # corner longerons
        for sy in (-1, 1):
            cx, cy = x + sx * (CW / 2 - 1.1), y + sy * (CW / 2 - 1.1)
            b.box(Lb, cx - 1.1, cx + 1.1, cy - 1.1, cy + 1.1, zf + 2.4, zr - 2.4, "can3")
    for k in range(11):                                                         # lattice frames
        z = zf + 9.0 + k * 8.0
        b.section(Lb, z - 0.5, z + 0.5, x, y, CW - 0.2, CW - 0.2, 1.4, 1.4, "can3", round_=False, tag="rib")
    for z0, z1 in ((zf, zf + 2.4), (zr - 2.4, zr)):
        b.section(Lb, z0, z1, x, y, CW, CW, 1.6, 1.6, "can3", round_=False, tag="rib")
    side = 1 if x > 0 else -1
    b.box(Lb, x + side * (CW / 2 - 0.2), x + side * (CW / 2 + 0.3), y - 2.6, y + 2.6, 52.0, 62.0, "can3", "door")
    # aft end: four gas-management caps
    b.box(Lb, x - 6.8, x + 6.8, y - 6.8, y + 6.8, zr, zr + 0.2, "dark")
    for dx in (-3.8, 3.8):
        for dy in (-3.8, 3.8):
            b.prism(Lb, "z", (x + dx, y + dy, zr + 0.35), 2.6, 0.3, "can3", n=6, tag=("aft", (x + dx, y + dy)))
    cover = f"move_cover_{i}"
    b.m.bone(cover, Lb, (x, y + CW / 2 - 0.6, zf - 0.3))
    b.box(cover, x - 7.4, x + 7.4, y - 7.4, y + 7.4, zf - 0.5, zf, "cover")
    b.box(cover, x - 7.4, x + 7.4, y - 0.4, y + 0.4, zf - 1.1, zf - 0.5, "cover")    # cell divider cross
    b.box(cover, x - 0.4, x + 0.4, y - 7.4, y + 7.4, zf - 1.1, zf - 0.5, "cover")
    for dx in (-3.8, 3.8):
        for dy in (-3.8, 3.8):
            b.prism(cover, "z", (x + dx, y + dy, zf - 0.85), 3.0, 0.7, "cover2", n=6, tag=("cell", (x + dx, y + dy)))
            b.prism(cover, "z", (x + dx, y + dy, zf - 1.35), 0.8, 0.3, "metal", n=4)
    b.box(cover, x - 2.0, x + 2.0, y + 6.2, y + 7.8, zf - 1.1, zf - 0.5, "metal")
    for dx in (-6.4, 6.4):
        b.box(cover, x + dx - 0.6, x + dx + 0.6, y - 7.2, y - 6.0, zf - 1.0, zf - 0.5, "metal")


# ------------------------------------------------------------------- build ---
def build():
    m = Model("patriot_m901")
    m.bone("root", None)
    m.bone("base", "root")
    m.bone("Wheel", "root")
    for i, z in enumerate(AXLES):
        for s in (L, R):
            m.bone(wheel_bone(s, i), "Wheel", (s * WX, WR, z))
    m.bone("move_turret", "base", TURRET)
    m.bone("move_launcher", "move_turret", PIVOT)
    m.bone("move_door_l", "base", (HW, 34.0, DOOR_HINGE_Z))
    m.bone("move_door_r", "base", (-HW, 34.0, DOOR_HINGE_Z))
    b = Build(m)
    wheels(b)
    tractor_cab(b)
    tractor_body(b)
    trailer(b)
    jacks(b)
    equipment(b)
    turret(b)
    rams(b)
    launcher_frame(b)
    for i, (x, y, kind) in enumerate(CANISTERS):
        (can_pac2 if kind == "pac2" else can_pac3)(b, i, x, y)
    return m


_ram_angle, _ram_ext = ram_pose(FIRE_ELEV)
_POSE = {"move_turret": axis_rot("y", 40.0), "move_launcher": axis_rot("x", FIRE_ELEV),
         "move_ram_0": axis_rot("x", _ram_angle), "move_ram_1": axis_rot("x", _ram_angle),
         "move_door_l": axis_rot("y", 65.0), "move_antenna": axis_rot("x", -90.0)}
for _i in range(4):
    _POSE[f"move_cover_{_i}"] = axis_rot("x", 100.0)
PREVIEW_POSES = {"patriot_m901": _POSE}


# ------------------------------------------------------------------- paint ---
TAN = hexrgb("#b39d72")
TAN2 = hexrgb("#a38d64")
CAN_TAN = hexrgb("#b8a47a")
CAN3_TAN = hexrgb("#ad9a72")
DUST = hexrgb("#cbb894")
GRIME = hexrgb("#6e5f45")
STENCIL = np.array([38.0, 36.0, 30.0])
WHITE = np.array([226.0, 222.0, 206.0])
RED = np.array([168.0, 40.0, 32.0])
YELLOW = np.array([214.0, 168.0, 40.0])
PAL = {
    "frame": "#7d6c4c", "dark": "#3a352b", "rubber": "#1d1d1b", "metal": "#77705f", "chrome": "#b8bbb4",
    "exhaust": "#5a4636", "amber": "#d08a2a", "red": "#9e231c", "blue": "#2a4a8a", "lens": "#d8d8cc",
    "mirror": "#93a5aa", "plate": "#dcd8c8", "antenna": "#2e2c26", "grille": "#2a2720", "cable": "#22211d",
    "black": "#161614", "rim": "#a8925f", "hub": "#97835a", "cover": "#a8956c", "cover2": "#5a564a",
    "window": "#141b1e", "tyre": "#232220",
}
PALC = {k: hexrgb(v) for k, v in PAL.items()}


def tan_side(x, y, z, size, layers, depth=0.8):
    """Same marking on both sides, reading front to back (SBW draws geo X mirrored)."""
    return [Decal((sx * x, y, z), (sx, 0, 0), (0, 0, sx), (0, -1, 0), size, layers, max_depth=depth)
            for sx in (1, -1)]


def make_decals():
    d = []
    # tractor door: unit bumper code and vehicle number; trailer side: US ARMY, registration
    d += tan_side(HW + 0.1, 26.6, -78.4, (16.0, 3.2), [(text_mask("2-43 ADA"), STENCIL)], depth=0.9)
    d += tan_side(18.9, 16.6, 21.7, (9.0, 3.2), [(text_mask("A-12"), STENCIL)], depth=0.5)
    d += tan_side(HW + 0.3, 20.0, 66.0, (22.0, 3.0), [(text_mask("U S ARMY"), STENCIL)], depth=0.6)
    d += tan_side(19.1, GN_T + 4.6, -29.0, (10.0, 2.4), [(text_mask("DANGER"), RED)], depth=0.6)
    # canister sides: type, PATRIOT, DANGER band text
    for (x, y, kind) in CANISTERS:
        sx = 1 if x > 0 else -1
        xo = x + sx * CW / 2
        name = "PAC-2" if kind == "pac2" else "PAC-3"
        d.append(Decal((xo, y + 2.8, 29.0), (sx, 0, 0), (0, 0, sx), (0, -1, 0), (19.0, 4.6),
                       [(text_mask(name), STENCIL)], max_depth=1.0))
        d.append(Decal((xo, y - 3.2, 29.0), (sx, 0, 0), (0, 0, sx), (0, -1, 0), (24.0, 4.0),
                       [(text_mask("PATRIOT"), STENCIL)], max_depth=1.0))
        d.append(Decal((xo, y + 0.0, 67.0), (sx, 0, 0), (0, 0, sx), (0, -1, 0), (21.0, 4.2),
                       [(text_mask("DANGER"), RED)], max_depth=1.0))
        # top surface: NO STEP, reading from the side
        if y > 50:
            d.append(Decal((x, y + CW / 2, 64.0), (0, 1, 0), (0, 0, 1), (1, 0, 0), (15.0, 2.6),
                           [(text_mask("NO STEP"), STENCIL)], max_depth=0.6))
    # tractor bumper markings
    for z, nz in ((-104.0, -1),):
        d.append(Decal((-12.0, 14.4, z), (0, 0, nz), (-nz * 1.0, 0, 0), (0, -1, 0), (11.0, 2.4),
                       [(text_mask("2-43ADA"), STENCIL)], max_depth=0.4))
        d.append(Decal((13.6, 14.4, z), (0, 0, nz), (-nz * 1.0, 0, 0), (0, -1, 0), (5.0, 1.8),
                       [(text_mask("A-12"), STENCIL)], max_depth=0.4))
    d.append(Decal((-11.0, GN_B + 5.6, GOOSE_Z0), (0, 0, -1), (1, 0, 0), (0, -1, 0), (9.0, 2.6),
                   [(text_mask("M901"), STENCIL)], max_depth=0.4))
    d.append(Decal((0, 18.3, TRAILER_REAR + 1.0), (0, 0, 1), (-1, 0, 0), (0, -1, 0), (7.0, 1.6),
                   [(np.pad(text_mask("HB 4417"), 1), (24, 24, 22))], max_depth=0.4))
    return d


def mud(p, n, rgb, heavy=0.0, scale=1.0):
    y = p[:, 1]
    lo = np.clip((22.0 - y) / 16.0, 0, 1) ** 1.3
    g = fbm(p, 7.0, 77, 3)
    amt = lo * np.clip(0.2 + 1.2 * (g - 0.38), 0, 1)
    splash = np.zeros(len(p))
    for zz in AXLES:
        rr = np.hypot(y - WR, p[:, 2] - zz)
        splash = np.maximum(splash, np.clip(1 - (rr - 11.5) / 8.0, 0, 1) * (np.abs(p[:, 0]) > 10.0))
    spat = (value_noise(p, 0.6, 78) > 0.72) & (splash > 0.05)
    amt = np.maximum(amt, 0.55 * splash * fbm(p, 3.0, 79, 2))
    amt = np.where(spat, np.maximum(amt, 0.6), amt)
    amt = np.clip((amt + heavy * (0.4 + 0.5 * g)) * scale, 0, 0.9)
    dry = value_noise(p, 3.0, 80)[:, None]
    col = hexrgb("#5e4e38") * (1 - dry) + hexrgb("#8f7c5c") * dry
    return rgb * (1 - amt[:, None]) + col * amt[:, None]


def seams(p, n, cube):
    """Panel seams and rivet rows from world position (rest pose)."""
    x, y, z = p[:, 0], p[:, 1], p[:, 2]
    ax_, ay_, az_ = abs(n[0]) > 0.5, n[1] > 0.5, abs(n[2]) > 0.5
    N = len(p)
    lines = np.zeros(N, bool)
    riv = np.zeros(N, bool)
    bone = cube.bone
    if bone == "base":
        cab = (z < CAB_Z1 + 0.1) & (y > CAB_B - 0.5)
        if ax_:
            lines |= cab & stations_mask(z, [-90.0], 0.2) & (y > 23.0) & (y < 45.0)
            lines |= cab & stations_mask(y, [31.6], 0.18)
            riv |= cab & rivet_rows(y, [31.6], z, offset=0.6, pitch=1.4, width=0.2)
            eng = (z > -66) & (z < -47) & (y > 18)
            lines |= eng & stations_mask(z, [-56.0], 0.2)
            gn = (z > GOOSE_Z0) & (z < GOOSE_Z1 + 1.3) & (y > GN_B) & (y < GN_T + 0.1)
            lines |= gn & stations_mask(y, [GN_T - 4.2], 0.18)
            riv |= gn & rivet_rows(y, [GN_T - 4.2], z, offset=0.7, pitch=1.6, width=0.22)
            sh = (y > GN_T) & (z < -24.0) & (z > -41.0)
            lines |= sh & stations_mask(y, [GN_T + 1.6], 0.2)
            lines |= sh & stations_mask(z, [-32.6], 0.18)
            riv |= sh & rivet_rows(z, [-40.0, -25.0], y, offset=0.6, pitch=1.4, width=0.2)
            rail = (np.abs(x) > HW - 0.9) & (y > 17.5) & (y < DECK_T)
            riv |= rail & (np.abs(y - 20.0) < 0.3) & (np.mod(z, 2.4) < 0.8)
        if ay_:
            lines |= cab & stations_mask(z, [-90.0, -76.0], 0.2) & (y > CAB_T - 0.2)
            lines |= (y > GN_T - 0.1) & (y < GN_T + 0.2) & stations_mask(z, [-30.0, -18.0, -6.0], 0.18)
            lines |= (y > GN_T + 14) & (z < -24) & stations_mask(np.abs(x), [10.0], 0.18)
        if az_:
            lines |= (z < CAB_Z0 + 0.1) & stations_mask(y, [44.0], 0.15) & (y > 22)
            lines |= (y > GN_T) & (z < -40.3) & stations_mask(np.abs(x), [10.0], 0.2)
    elif bone == "move_turret":
        if ay_:
            lines |= stations_mask(np.hypot(x, z - TURRET[2]), [9.0], 0.2)
            riv |= (np.abs(np.hypot(x, z - TURRET[2]) - 12.0) < 0.3) & (np.mod(np.degrees(np.arctan2(x, z - TURRET[2])), 15.0) < 5)
    elif bone == "move_launcher":
        if ax_ or ay_:
            riv |= (cube.mat == "frame") & (np.mod(z, 2.2) < 0.6) & (
                (np.abs(np.abs(x) - 17.6) < 0.3) if ay_ else (np.abs(y - 30.4) < 0.3) | (np.abs(y - 66.0) < 0.3))
    return lines, riv


def painter():
    decals = make_decals()

    def paint(p, n, cube, face, s, t, wh):
        N = len(p)
        mat = cube.mat
        w, h = wh
        x, y, z = p[:, 0], p[:, 1], p[:, 2]
        tag = cube.tag if isinstance(cube.tag, tuple) else None
        stag = cube.tag if isinstance(cube.tag, str) else None
        edge = np.minimum(np.minimum(s, 1 - s) * w, np.minimum(t, 1 - t) * h)
        lowmud = True
        if mat in ("skin", "skin2", "can", "can3", "cover", "rim", "hub", "frame"):
            base = {"skin": TAN, "skin2": TAN2, "can": CAN_TAN, "can3": CAN3_TAN}.get(mat, PALC.get(mat))
            rgb = np.tile(base, (N, 1)) * (0.95 + 0.10 * fbm(p, 14.0, 5, 2))[:, None]
            # CARC mottling: slightly different batches of paint between panels
            rgb = rgb * (1 + (value_noise(p, 9.0, 3) - 0.5) * 0.08)[:, None]
            if stag == "door":
                rgb[edge < 0.8] *= 0.55
                rgb[(edge > 1.8) & (edge < 2.2)] *= 0.88
            elif stag == "louver":
                q = np.mod(y, 1.1) if abs(n[1]) < 0.5 else np.mod(z, 1.2)
                rgb[(q < 0.5) & (edge > 0.6)] *= 0.4
            elif stag == "tread" and n[1] > 0.5:
                a, bb = (x + z) * 0.7, (x - z) * 0.7
                dash = ((np.mod(a, 2.2) < 0.45) & (np.mod(np.floor(bb / 2.2), 2) == 0)) | \
                       ((np.mod(bb, 2.2) < 0.45) & (np.mod(np.floor(a / 2.2), 2) == 1))
                rgb[dash] = rgb[dash] * 1.15 + 6
                rgb[np.mod(z + 4.0, 24.0) < 0.3] *= 0.6
            elif stag == "hazard":
                st = np.mod(x + y, 4.0) < 2.0
                rgb[st] = WHITE * 0.92
                rgb[~st] = RED
            elif stag == "rib":
                rgb *= 0.93
            if mat in ("rim", "hub") and tag:
                c = tag[1]
                r = np.hypot(y - c[1], z - c[2])
                if abs(n[0]) > 0.7:
                    ang = np.arctan2(y - c[1], z - c[2])
                    for k in range(8):
                        a = math.radians(22.5 + 45 * k)
                        hole = np.hypot(y - (c[1] + 5.6 * math.sin(a)), z - (c[2] + 5.6 * math.cos(a))) < 0.8
                        rgb[hole] = hexrgb("#141310")
                    rgb[np.abs(r - 6.3) < 0.22] *= 0.7
                    rgb[np.abs(r - 4.4) < 0.2] *= 0.75
                    rust = (value_noise(p, 0.8, 22) > 0.72) & (np.mod(ang * 4.0, 1.0) < 0.5)
                    rgb[rust] = rgb[rust] * 0.7 + hexrgb("#5a3a22") * 0.3
                rgb = mud(p, n, rgb, heavy=0.10)
                lowmud = False
            if mat in ("can", "can3"):
                # red/yellow band near the launch end, stencilled lot box near the aft end
                band = (z > Z_MUZZLE + 3.0) & (z < Z_MUZZLE + 4.6) & (stag != "rib")
                rgb[band] = YELLOW if mat == "can3" else RED
                if tag and tag[0] == "aft" and n[2] > 0.5:
                    r = np.hypot(x - tag[1][0], y - tag[1][1])
                    rgb[np.abs(r - (3.6 if mat == "can" else 1.8)) < 0.25] *= 0.6
                    rgb[r < 0.8] = PALC["metal"]
                if abs(n[0]) > 0.5:
                    box = (np.abs(z - 80.0) < 4.0) & (np.abs(y - (ROWS[0] if mat == "can" else ROWS[1])) < 2.0)
                    rgb[box & ((np.abs(np.abs(z - 80.0) - 3.8) < 0.25) | (np.abs(np.abs(y - (ROWS[0] if mat == "can" else ROWS[1])) - 1.8) < 0.25))] = STENCIL
            if mat == "cover":
                if tag and tag[0] == "disc" and n[2] < -0.5:
                    r = np.hypot(x - tag[1][0], y - tag[1][1])
                    ang = np.degrees(np.arctan2(y - tag[1][1], x - tag[1][0]))
                    rgb[(np.mod(ang, 45.0) < 3.0) & (r < 5.2) & (r > 1.6)] *= 0.62     # score lines
                    rgb[np.abs(r - 5.3) < 0.2] *= 0.7
                if n[2] < -0.5 and not tag:
                    rgb[(edge > 0.6) & (edge < 1.1)] = RED
            if cube.bone == "base" and abs(n[0]) > 0.5 and np.all(np.abs(np.abs(x) - HW) < 0.25):
                inside = (z > DOOR_HINGE_Z + 0.2) & (z < -68.8) & (y > 23.2) & (y < 45.0)
                rgb[inside] = hexrgb("#2f2b24")
            lines, riv = seams(p, n, cube)
            rgb = rgb * np.where(riv & ~lines, 1.12, 1.0)[:, None]
            rgb = rgb * np.where(lines, 0.62, 1.0)[:, None]
            if mat in ("frame",) and min(w, h) > 4:
                rv = (edge > 0.8) & (edge < 1.5) & (np.mod(s * w + t * h, 2.6) < 0.9)
                rgb[rv] = rgb[rv] * 1.15 + 5
            rgba = np.concatenate([rgb, np.full((N, 1), 255.0)], 1)
            if mat in ("skin", "skin2", "can", "can3", "cover"):
                for dcl in decals:
                    rgba = dcl.apply(p, n, rgba)
            rgb = rgba[:, :3]
            # grime, rain streaks, dust on top faces, worn edges, oil spots
            g = fbm(p, 18.0, 300, 3)
            k = 1 - 0.15 * np.clip((g - 0.42) * 2.4, 0, 1)
            if abs(n[1]) < 0.5:
                st = value_noise(p * np.array([1.0, 0.07, 1.0]), 1.4, 310)
                k *= 1 - 0.14 * np.clip((st - 0.55) * 3.0, 0, 1)
            rgb = rgb * k[:, None]
            if n[1] > 0.5:
                dust = 0.12 + 0.22 * value_noise(p, 6.0, 312)
                rgb = rgb * (1 - dust[:, None]) + DUST * dust[:, None]
            if n[1] < -0.5:
                rgb = rgb * 0.68
            chip = (edge < 1.0) & (value_noise(p, 0.9, 320) > 0.62)
            rgb[chip] = rgb[chip] * 0.72 + GRIME * 0.28
            spot = value_noise(p, 0.55, 321) > 0.93
            rgb[spot] = rgb[spot] * 0.86
            if mat in ("can", "can3", "cover"):
                lowmud = False
        elif mat == "tyre":
            rgb = np.tile(PALC["tyre"], (N, 1)) * (0.88 + 0.22 * value_noise(p, 1.1, 15))[:, None]
            if tag and abs(n[0]) > 0.7:
                c = tag[1]
                r = np.hypot(y - c[1], z - c[2])
                rgb[np.abs(r - 8.4) < 0.3] = hexrgb("#2d2c29")
                rgb[np.abs(r - 9.2) < 0.18] = hexrgb("#151514")
                ang = np.degrees(np.arctan2(y - c[1], z - c[2]))
                letters = (np.abs(r - 7.7) < 0.45) & (np.mod(ang, 30.0) < 9.0) & (np.mod(ang * 3.1, 7.0) < 4.0)
                rgb[letters] = hexrgb("#33322e")
            elif tag and len(tag) > 2:
                rgb *= 1.2
            else:
                rgb[np.mod(x + 0.4, 2.3) < 0.35] *= 0.7
            rgb = mud(p, n, rgb, heavy=0.04, scale=0.55) * 0.9 + DUST * 0.1 * (y < 6)[:, None]
            lowmud = False
        elif mat == "window":
            rgb = np.tile(PALC["window"], (N, 1)) * (0.9 + 0.2 * value_noise(p, 7.0, 41))[:, None]
            grad = np.clip((y - 31.0) / 13.0, 0, 1)
            rgb = rgb + np.array([40.0, 52, 58])[None, :] * grad[:, None] * 0.5
            g2 = np.mod(z * 0.5 + y * 0.9 + x * 0.35, 26.0)
            glint = np.clip(1 - np.abs(g2 - 6.0) / 2.2, 0, 1) * 0.35 + np.clip(1 - np.abs(g2 - 10.0) / 0.6, 0, 1) * 0.2
            rgb = rgb * (1 - glint[:, None]) + 120 * glint[:, None]
            low = np.clip((34.0 - y) / 3.0, 0, 1)
            rgb = rgb * (1 - 0.4 * low[:, None]) + DUST * 0.4 * low[:, None]
            lowmud = False
        elif mat == "cover2":
            rgb = np.tile(PALC["cover2"], (N, 1)) * (0.9 + 0.15 * value_noise(p, 2.0, 81))[:, None]
            if tag and n[2] < -0.5:
                cx, cy = tag[1]
                dx, dy = x - cx, y - cy
                r = np.hypot(dx, dy)
                rgb[((np.abs(dx) < 0.12) | (np.abs(dy) < 0.12)) & (r < 2.6)] = hexrgb("#2a2822")
                rgb[np.abs(r - 2.75) < 0.16] = hexrgb("#2a2822")
                rgb[np.abs(r - 1.6) < 0.12] *= 0.8
            lowmud = False
        elif mat == "grille":
            rgb = np.tile(PALC["grille"], (N, 1))
            if stag == "grille":
                bars = (np.mod(x, 1.3) < 0.5) if abs(n[0]) < 0.5 else (np.mod(z, 1.3) < 0.5)
                rgb[bars] = TAN * 0.55
                rgb[np.mod(y, 3.0) < 0.5] = TAN * 0.5
            else:
                q = np.mod(y, 1.1) if abs(n[1]) < 0.5 else np.mod(z, 1.2)
                rgb[q > 0.5] = TAN * 0.6
            rgb = rgb * (0.9 + 0.2 * value_noise(p, 3.0, 51))[:, None]
        elif mat == "lens":
            rgb = np.tile(PALC["lens"], (N, 1))
            if tag and tag[0] == "lens":
                r = np.hypot(x - tag[1][0], y - tag[1][1])
                rgb[np.abs(r - 1.3) < 0.2] *= 0.75
                rgb[r < 0.5] = hexrgb("#f4f2e4")
            lowmud = False
        elif mat in ("amber", "red", "blue", "mirror", "plate"):
            rgb = np.tile(PALC[mat], (N, 1)) * (0.92 + 0.12 * value_noise(p, 1.0, 97))[:, None]
            if mat in ("amber", "red") and isinstance(cube.tag, tuple):
                rgb = rgb * (0.8 + 0.35 * np.clip(edge / 2.0, 0, 1))[:, None]
            if mat == "mirror" and n[2] < -0.5:
                rgb = np.tile(hexrgb("#9fb1b8"), (N, 1)) * (0.8 + 0.3 * (1 - t))[:, None]
                rgb[edge < 0.6] = np.array([30.0, 32.0, 28.0])
            if mat == "plate":
                lowmud = True
            else:
                lowmud = False
        elif mat == "exhaust":
            rgb = np.tile(PALC["exhaust"], (N, 1)) * (0.8 + 0.4 * value_noise(p, 1.2, 99))[:, None]
            top = np.clip((y - 40) / 14, 0, 1)
            rgb = rgb * (1 - 0.55 * top[:, None])
        elif mat == "cable":
            rgb = np.tile(PALC["cable"], (N, 1)) * (0.9 + 0.2 * value_noise(p, 2.0, 55))[:, None]
            rgb[np.mod(x, 0.9) < 0.25] *= 0.6
        elif mat == "chrome":
            rgb = np.tile(PALC["chrome"], (N, 1)) * (0.85 + 0.2 * value_noise(p * np.array([3, 0.2, 3]), 1.0, 77))[:, None]
            lowmud = False
        elif mat in PALC:
            rgb = np.tile(PALC[mat], (N, 1)) * (0.92 + 0.14 * value_noise(p, 2.5, 103))[:, None]
            if mat in ("dark", "metal"):
                rust = np.clip(fbm(p, 3.0, 81, 2) - 0.5, 0, 1) * 0.8
                rgb = rgb * (1 - rust[:, None]) + hexrgb("#6b4a30") * rust[:, None]
            if stag == "tread" and n[1] > 0.5:
                rgb[np.mod(np.floor(x / 0.8) + np.floor(z / 0.8), 2) == 0] *= 1.2
            if stag == "pad" and abs(n[1]) < 0.5:
                st = np.mod(x + z, 2.4) < 1.2
                rgb[st] = RED
                rgb[~st] = WHITE * 0.85
            if n[1] < -0.6:
                rgb *= 0.72
        else:
            rgb = np.tile(hexrgb("#ff00ff"), (N, 1))
        if lowmud:
            rgb = mud(p, n, rgb)
        return np.concatenate([np.clip(rgb, 0, 255), np.full((N, 1), 255.0)], 1)

    return paint


MODELS = {"patriot_m901": (build, painter)}
