#!/usr/bin/env python3
"""Original cube art for the arcade `grad` 122 mm rocket launcher truck.

A BM-21-style 6x6 truck: long-bonnet Ural-type cab with a canvas roof, spare
wheel behind the cab, 40-tube launcher on a rear turntable. Every cube and
every texel is generated here; no native mesh or texture is copied.

Detail level and texture style follow Superb Warfare's own vehicles (about a
thousand cubes, rotated cubes for bevels and round parts, one packed atlas at
roughly one texel per model unit). The authoring kit of the aircraft remakes
(`modules/aviation/art/realistic`: rotation convention, per-face UV frames,
hidden-face removal, z-fight repair) is reused; each face is painted from its
world position, so panel lines, dirt and weathering run across cubes.

The vehicle reuses SBW's PLZ-05 entity/renderer, which animates bones by name,
so the PLZ-05 skeleton (names, parents, pivots, rotations) is read from the
pinned SBW 0.8.9.1 jar and kept. Only these pivots move: turret/barrelBase/
barrel/tube (launcher) and the six used wheel bones (hubs). Without the jar
the skeleton is read back from the previously generated grad.geo.json.

    python3 modules/arcade/art/grad_assets.py [--preview DIR]

Writes only the four files in OUT; --preview writes review renders (including
a same-scale comparison with the native PLZ-05) into DIR.
"""
from __future__ import annotations

import io
import json
import math
import sys
import zipfile
from pathlib import Path

import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(ROOT / "modules/aviation/art/realistic"))
import cull  # noqa: E402
import kit as K  # noqa: E402
import zfight  # noqa: E402
from kit import axis_rot, fbm, value_noise  # noqa: E402

ASSETS = HERE.parent / "src/main/resources/assets/frontier_arcade_vehicles"
OUT = {
    "geo": ASSETS / "models/bedrock/vehicle/grad.geo.json",
    "texture": ASSETS / "textures/bedrock/vehicle/grad.png",
    "container": ASSETS / "textures/vehicle_icon/container/grad.png",
    "icon": ASSETS / "textures/vehicle_icon/grad_icon.png",
}
JAR_GLOB = "superbwarfare-0.8.9.1-hotfix-mc1.20.1-*-all.jar"
JAR_DIRS = ("instance/mods", "play-tablet/mods", "play-minimal/mods")
NATIVE = "assets/superbwarfare/models/bedrock/vehicle/plz_05.geo.json"
NATIVE_TEX = "assets/superbwarfare/textures/bedrock/vehicle/plz_05.png"
DENSITY = 1.0          # texels per model unit, as in SBW's own atlases

# ---------------------------------------------------------------- layout ---
# Model units, 16 = 1 block. Front is -Z, left (L bones) is +X, ground y=0.
WHEEL_R, WHEEL_X, WHEEL_W = 12, 19, 8
AXLES = {0: -56, 3: 18, 6: 46}            # used PLZ wheel bone index -> axle z
SPARE = (0.0, 25.75, -11.0)                # spare wheel lying behind the cab
TURRET = [0, 28, 30]                       # turntable centre on the rear deck
PACK_PIVOT = [0, 35, 47]                   # rear-bottom of the tube pack
PACK_Z = (-21, 49)                         # muzzle face .. rear face
TUBE_PITCH, TUBE_R = 3.4, 1.55             # 8-sided tubes, inradius 1.55
TUBE_X0, TUBE_Y0 = -17.0, 36.5             # tube field corner (10 x 4 tubes)
MOVED = {"turret": TURRET, "barrelBase": PACK_PIVOT, "barrel": PACK_PIVOT,
         "tube": PACK_PIVOT}
for _i, _z in AXLES.items():
    MOVED[f"wheelL{_i}"] = [WHEEL_X, WHEEL_R, _z]
    MOVED[f"wheelR{_i}"] = [-WHEEL_X, WHEEL_R, _z]
DRIVER_SEAT = [10.5, 29.5, -33]            # top of the left (driver) cushion
CREW_SEAT = [-10.5, 29.5, -33]
DRIVER_EYE = [10.2, 43, -35]               # first-person eye: middle of the driver's pane, looking -Z
GUNNER_CAM = [0, 20, -5]                   # barrel-local, relative to PACK_PIVOT
# Steering wheel bone: pivot on the hub, bind rotation tilts local +Z (column axis, from
# the column towards the driver) to (0, sin T, cos T); the rim lies in the local XY plane.
STEER_HUB, STEER_TILT, STEER_R = [10.2, 37.0, -40.0], 55.0, 3.6
# Rear jacks: fixed housing in `body`, telescopic leg + pad in grad_jack_l/r. The legs
# are modelled stowed; translating the bone down by JACK_TRAVEL puts the pads on y=0.
JACK_X, JACK_Z, JACK_TOP, JACK_TRAVEL = 17.25, 65.5, 25.0, 8.0

# Bones added to the PLZ-05 skeleton (name, parent, pivot, insert before) and the one
# allowed change of parent: SBW's wheeled engine steers wheelL0Turn / wheelR0Turn.
EXTRA_BONES = [
    ("wheelL0Turn", "WheelL", [WHEEL_X, WHEEL_R, AXLES[0]], "wheelL0"),
    ("wheelR0Turn", "WheelR", [-WHEEL_X, WHEEL_R, AXLES[0]], "wheelR0"),
    ("grad_jack_l", "body", [JACK_X, JACK_TOP, JACK_Z], None),
    ("grad_jack_r", "body", [-JACK_X, JACK_TOP, JACK_Z], None),
    ("grad_fp_hide", "body", list(DRIVER_EYE), None),
    ("grad_steering_wheel", "body", list(STEER_HUB), None),
]
BIND_ROT = {"grad_steering_wheel": [STEER_TILT, 0, 0]}
REPARENT = {"wheelL0": ("WheelL", "wheelL0Turn"), "wheelR0": ("WheelR", "wheelR0Turn")}

# SBW's GeoVehicleRenderer steers (Y) and spins (X) every wheel?0Turn bone and spins every
# wheel[LR]* bone, so the front wheel cubes live on the Turn bones (as on sodayo/bohdana)
# and wheelL0/wheelR0 stay empty; otherwise the front wheels would spin twice.
def wheel_bone(side, i):
    return f"wheel{side}{i}Turn" if i == 0 else f"wheel{side}{i}"


GEOMETRY_BONES = ({"body", "turret", "barrel", "grad_jack_l", "grad_jack_r", "grad_fp_hide",
                   "grad_steering_wheel"}
                  | {wheel_bone(s, i) for s in "LR" for i in AXLES})

# Frames mapping a part's local X axis onto a world axis (for round parts).
AXIS = {"x": np.eye(3), "y": axis_rot("z", 90), "z": axis_rot("y", -90)}
MIRROR = np.diag([-1.0, 1.0, 1.0])


# -------------------------------------------------------------- skeleton ---
def native_skeleton():
    """PLZ-05 bones as (name, parent, pivot, rotation), in native order."""
    for folder in JAR_DIRS:
        for jar in sorted((ROOT / folder).glob(JAR_GLOB)):
            with zipfile.ZipFile(jar) as z:
                bones = json.loads(z.read(NATIVE))["minecraft:geometry"][0]["bones"]
            return [{k: b[k] for k in ("name", "parent", "pivot", "rotation") if k in b}
                    for b in bones], str(jar.relative_to(ROOT))
    # Moved pivots are re-applied by geo() and checked against MOVED anyway; added
    # bones and the steering re-parenting are undone to recover the native list.
    extra = {e[0] for e in EXTRA_BONES}
    bones = json.loads(OUT["geo"].read_text())["minecraft:geometry"][0]["bones"]
    out = []
    for b in bones:
        if b["name"] in extra:
            continue
        b = {k: b[k] for k in ("name", "parent", "pivot", "rotation") if k in b}
        if b["name"] in REPARENT:
            b["parent"] = REPARENT[b["name"]][0]
        out.append(b)
    return out, "previous grad.geo.json (native jar not found)"


def native_jar():
    for folder in JAR_DIRS:
        for jar in sorted((ROOT / folder).glob(JAR_GLOB)):
            return jar
    return None


# ------------------------------------------------------------ authoring ---
class Truck:
    """Cube collector on top of the aviation kit (kit.Cube keeps UV frames)."""

    def __init__(self):
        self.cubes = []

    def _add(self, c, tag=None, share=None, fmat=None):
        c.tag, c.share, c.fmat = tag, share, fmat or []
        self.cubes.append(c)
        return c

    def box(self, bone, x0, x1, y0, y1, z0, z1, mat, skip=(), **kw):
        xa, xb = sorted((x0, x1))
        ya, yb = sorted((y0, y1))
        za, zb = sorted((z0, z1))
        return self._add(K.Cube(bone, (xa, ya, za), (xb - xa, yb - ya, zb - za), mat,
                                skip=skip), **kw)

    def sbox(self, bone, x0, x1, *rest, **kw):
        """Box on the +X (left) side and its mirror on the -X side."""
        return [self.box(bone, x0, x1, *rest, **kw), self.box(bone, -x0, -x1, *rest, **kw)]

    def ori(self, bone, center, size, r, mat, skip=(), **kw):
        """Cube of `size` centred on `center` whose local axes are the columns of r."""
        rot = K.euler_from_matrix(r)
        if not any(abs(a) > 1e-7 for a in rot):
            rot = None
        c = np.asarray(center, float)
        cube = K.Cube(bone, c - np.asarray(size, float) / 2, size, mat, pivot=tuple(c),
                      rotation=rot, skip=skip)
        return self._add(cube, **kw)

    def sori(self, bone, center, size, r, mat, **kw):
        cx, cy, cz = center
        return [self.ori(bone, center, size, r, mat, **kw),
                self.ori(bone, (-cx, cy, cz), size, MIRROR @ r @ MIRROR, mat, **kw)]

    def bar(self, bone, p0, p1, w, h, mat, up=(0, 1, 0), **kw):
        """Beam from p0 to p1: width w across, thickness h towards `up`."""
        p0, p1 = np.asarray(p0, float), np.asarray(p1, float)
        d = p1 - p0
        ln = float(np.linalg.norm(d))
        z = d / ln
        x = np.cross(up, z)
        if np.linalg.norm(x) < 1e-6:
            x = np.cross((1, 0, 0), z) if abs(z[0]) < .9 else np.cross((0, 0, 1), z)
            x = np.cross(z, x)
        x /= np.linalg.norm(x)
        r = np.column_stack((x, np.cross(z, x), z))
        return self.ori(bone, (p0 + p1) / 2, (w, h, ln), r, mat, **kw)

    def sbar(self, bone, p0, p1, w, h, mat, **kw):
        m = lambda p: (-p[0], p[1], p[2])  # noqa: E731
        return [self.bar(bone, p0, p1, w, h, mat, **kw), self.bar(bone, m(p0), m(p1), w, h, mat, **kw)]

    def prism(self, bone, center, frame, r, length, mat, n=6, stagger=0.04, roll=0.0, **kw):
        """Regular 2n-gon prism along the frame's local X (inradius r, a flat facing down
        for horizontal axes). Union of n bars; caps staggered so they never z-fight."""
        a = round(r * math.tan(math.pi / (2 * n)), 4)
        c0 = np.asarray(center, float)
        out = []
        for k in range(n):
            ln = length - 2 * k * stagger
            rr = frame @ axis_rot("x", roll + 180.0 * k / n)
            out.append(self.ori(bone, c0, (ln, 2 * r, 2 * a), rr, mat,
                                skip={"north", "south"}, **kw))
        return out

    def ring(self, bone, center, frame, radius, t, mat, n=8, **kw):
        """Ring of n bars in the frame's local YZ plane (normal = local X). Alternate bars
        are slightly thinner so their overlapping ends never share a plane."""
        chord = 2 * radius * math.tan(math.pi / n) + t * 0.6
        out = []
        for k in range(n):
            th = 360.0 * k / n
            local = axis_rot("x", th) @ np.array([0.0, radius, 0.0])
            out.append(self.ori(bone, np.asarray(center) + frame @ local, (t * (1 - .15 * (k % 2)), t, chord),
                                frame @ axis_rot("x", th), mat, **kw))
        return out


def inward(c, direction, mat):
    """Faces of c whose normal is `direction` are painted with `mat` (interior sides)."""
    c.fmat.append((np.asarray(direction, float), mat))
    return c


# -------------------------------------------------------------- geometry ---
def wheel(t, bone, center, frame, out_sign, spare=False):
    """Twelve-sided tyre with 24 herringbone tread blocks, dished rim, hub, bolts, drum."""
    c = np.asarray(center, float)
    h = WHEEL_W / 2
    tag = ("wheel", tuple(c), tuple(frame @ [1, 0, 0]))
    t.prism(bone, c, frame, 11.0, WHEEL_W, "tyre", n=6, tag=tag)
    for row, (dx, off) in enumerate(((1.9, 0.0), (-1.9, 15.0))):
        for k in range(12):
            th = 30.0 * k + off
            rk = axis_rot("x", th)
            local = np.array([dx, 0, 0]) + rk @ np.array([0.0, -11.5, 0.0])
            t.ori(bone, c + frame @ local, (3.3, 1.0, 2.7), frame @ rk @ axis_rot("y", 18 if row else -18),
                  "tyre", tag=tag, share="lug")
    side = frame @ np.array([out_sign, 0.0, 0.0])
    rim = c + side * (h - 0.15)
    t.prism(bone, rim, frame, 7.0, 0.9, "rim", n=6, tag=tag)
    t.prism(bone, c + side * (h + 0.75), frame, 2.6, 1.5, "metal", n=4, tag=tag)
    t.prism(bone, c + side * (h + 0.45), frame, 4.0, 0.7, "rim", n=4, tag=tag)
    for k in range(8):
        local = axis_rot("x", 22.5 + 45 * k) @ np.array([0.0, 4.9, 0.0])
        t.ori(bone, c + side * (h + 0.6) + frame @ local, (0.8, 0.9, 0.9), frame, "metal",
              share="bolt")
    if not spare:
        t.prism(bone, c - side * (h + 0.6), frame, 5.0, 1.2, "dark", n=4, tag=tag)


def wheels(t):
    for side, s in (("L", 1), ("R", -1)):
        for i, z in AXLES.items():
            wheel(t, wheel_bone(side, i), (s * WHEEL_X, WHEEL_R, z), AXIS["x"], s)


def frame_and_axles(t):
    B = "body"
    # Narrow ladder frame and inboard springs leave room for the steered front tyres.
    t.sbox(B, 6, 9.5, 14, 20, -79, 70, "dark")
    for z in (-77, -45, -20, 31, 66):
        t.box(B, -6, 6, 15, 19, z, z + 3, "dark")
    for z in AXLES.values():
        reach = 12 if z == AXLES[0] else 15          # front axle stops short of the steered hubs
        t.box(B, -reach, reach, 10.5, 13.5, z - 1.5, z + 1.5, "dark")
        t.prism(B, (0, 11.5, z), AXIS["z"], 3.4, 7, "dark", n=4)
    t.sbox(B, 6.5, 9.5, 12.5, 14, -68, -44, "metal")             # front leaf springs
    t.sbox(B, 6.5, 9.5, 12.5, 14, 6, 58, "metal")                # rear bogie springs
    for x in (11.5, -11.5):
        t.prism(B, (x, 15.5, 32), AXIS["x"], 2.4, 5, "dark", n=4)
    t.sbar(B, (10.6, 13.5, -61), (10.6, 23, -52), 1.2, 1.2, "metal")   # shock absorbers
    t.bar(B, (-7, 12.5, -63), (7, 12.5, -63), 1.0, 1.0, "metal")       # track rod
    t.box(B, 9.6, 10.3, 19.2, 19.9, -40, 28, "cable")                   # launcher cable run
    for z in (-30, -10, 10):
        t.box(B, 9.5, 10.6, 19, 20.1, z, z + 1, "metal")


def bonnet_and_wings(t):
    B = "body"
    # Bumper with bevelled top edge and ends, tow hooks, plate.
    t.box(B, -21, 21, 13, 18, -83, -79, "olive")
    t.ori(B, (0, 17.6, -82.6), (42, 1.6, 1.6), axis_rot("x", 45), "olive")
    t.sori(B, (21.6, 15.5, -80.6), (3.2, 5, 4.4), axis_rot("y", -30), "olive")
    t.sbox(B, 12.8, 15.2, 13.4, 16.6, -86, -83, "metal")
    t.sbox(B, 12.8, 15.2, 16.6, 18.2, -86, -84.8, "metal")
    t.box(B, -5, 5, 13.7, 16.8, -83.3, -83, "plate")
    t.prism(B, (0, 12.4, -80.5), AXIS["x"], 2.1, 15, "dark", n=4)       # winch drum
    # Long bonnet: crowned top, bevelled front edge, louvres, latches.
    t.box(B, -11, 11, 21, 35, -78, -48.5, "olive")
    t.box(B, -7, 7, 16, 21, -76, -50, "dark")                    # engine sump, inside the lock
    t.box(B, -7.5, 7.5, 35, 37, -77, -48.5, "olive")
    t.sori(B, (9.15, 35.55, -62.75), (4.4, 1.8, 28.5), axis_rot("z", -28), "olive")
    t.ori(B, (0, 36.1, -77.6), (15, 1.8, 1.8), axis_rot("x", 45), "olive")
    t.sori(B, (9.4, 35.2, -77.6), (3.8, 1.6, 1.8), axis_rot("z", -28) @ axis_rot("x", 45), "olive")
    t.box(B, -0.6, 0.6, 37, 37.45, -77, -49, "olive_dark")
    for zc in (-73.5, -68.5, -63.5):
        for yc in (24.5, 28, 31.5):
            t.sori(B, (11.1, yc, zc), (0.6, 1.5, 4), axis_rot("z", -25), "olive_dark")
    for zc in (-74, -54):
        t.sbox(B, 11, 11.7, 33, 34.6, zc, zc + 1.6, "metal")
    # Radiator grille: frame ring, dark core, vertical bars.
    t.box(B, -10.5, 10.5, 33, 34.6, -79.2, -78, "olive")
    t.box(B, -10.5, 10.5, 21, 22.6, -79.2, -78, "olive")
    t.sbox(B, 8.6, 10.5, 22.6, 33, -79.2, -78, "olive")
    t.box(B, -8.6, 8.6, 22.6, 33, -78.6, -78, "grille")
    for i in range(8):
        x = -7.35 + i * 2.1
        t.box(B, x - 0.4, x + 0.4, 22.6, 33, -79.0, -78.6, "olive_dark", share="grille_bar")
    # Front wings: flat top up to the bonnet, curved front from three slabs, rolled edge.
    # The wheel well stays open underneath so the steered tyre can swing (~30 deg lock).
    t.sbox(B, 11, 24, 25.5, 27, -66.2, -44.8, "olive")
    prof = [(-66.0, 26.25), (-71.2, 25.4), (-75.0, 23.0), (-76.8, 19.4)]
    for (za, ya), (zb, yb) in zip(prof, prof[1:]):
        d = np.array([0, yb - ya, zb - za])
        e = d / np.linalg.norm(d) * 0.35
        t.sbar(B, (18, ya - e[1], za - e[2]), (18, yb + e[1], zb + e[2]), 12, 1.5, "olive")
    t.sbar(B, (18, 26.4, -45.0), (18, 18.6, -42.6), 12, 1.5, "olive")
    t.sbox(B, 23.4, 24.2, 24.9, 27.2, -66, -45, "olive_dark")
    # Headlights with brackets, lenses and wire guards; side and blackout lamps.
    for s in (1, -1):
        x = s * 16.6
        t.box(B, x - 1.8, x + 1.8, 21.2, 22.8, -77.4, -75.6, "olive_dark")
        t.prism(B, (x, 24.8, -77.6), AXIS["z"], 2.5, 2.6, "olive_dark", n=4)
        t.prism(B, (x, 24.8, -79.0), AXIS["z"], 2.0, 0.4, "lens", n=4, tag=("lens", (x, 24.8)))
        for gx in (-1.2, 1.2):
            t.box(B, x + gx - 0.2, x + gx + 0.2, 22.4, 27.6, -80.2, -79.8, "metal")
        t.box(B, x - 2.4, x + 2.4, 27.2, 27.6, -80.2, -76.8, "metal")
        t.box(B, s * 20, s * 23, 27, 28.8, -64, -61.6, "olive_dark")
        t.box(B, s * 20.4, s * 22.6, 27.3, 28.5, -64.3, -64, "amber")
    t.box(B, 19.6, 22.4, 27, 28.6, -70.5, -68.2, "olive_dark")
    t.box(B, 20.2, 21.8, 27.3, 28.2, -70.8, -70.5, "lens", tag=("lens", (21, 27.75)))


def cab(t):
    B = "body"
    floor = t.box(B, -22, 22, 24, 26, -48, -24.5, "dark")
    inward(floor, (0, 1, 0), "floor")
    inward(t.box(B, -22, 22, 26, 38, -48, -46.5, "olive"), (0, 0, 1), "interior")
    inward(t.box(B, -22, 22, 46, 48, -48, -46.5, "olive"), (0, 0, 1), "interior")
    for c in t.sbox(B, 19.5, 22, 38, 46, -48, -46.5, "olive"):
        inward(c, (0, 0, 1), "interior")
    inward(t.box(B, -0.9, 0.9, 38, 46, -48, -46.5, "olive"), (0, 0, 1), "interior")
    t.sori(B, (21.7, 37, -47.7), (2.2, 22, 2.2), axis_rot("y", 45), "olive")   # rounded corners
    for x0, x1 in ((0.9, 19.5), (-19.5, -0.9)):                                # screen seals
        t.box(B, x0, x1, 45.5, 46, -47.8, -47.1, "rubber")
        t.box(B, x0, x1, 38, 38.5, -47.8, -47.1, "rubber")
    # Parked wipers lie level along the screen base (a slanted bar read as a crooked view).
    t.sbar(B, (3.5, 38.75, -48.3), (11.5, 38.75, -48.3), 0.45, 0.45, "black")
    t.sbox(B, 19.2, 22.2, 30, 35, -48.4, -47.8, "olive_dark")                  # cowl vents
    # Doors (lower), window frames, canvas quarters, handles, hinges.
    for s in (1, -1):
        sl = (s * 20.5, s * 22)
        inward(t.box(B, *sl, 26, 38, -46.5, -26, "olive"), (-s, 0, 0), "interior")
        inward(t.box(B, *sl, 38, 46, -46.5, -45, "olive"), (-s, 0, 0), "interior")
        inward(t.box(B, *sl, 38, 46, -32, -30.5, "olive"), (-s, 0, 0), "interior")
        inward(t.box(B, *sl, 45.2, 46, -45, -32, "olive"), (-s, 0, 0), "interior")
        inward(t.box(B, *sl, 38, 46, -30.5, -26, "canvas"), (-s, 0, 0), "interior")
        inward(t.box(B, s * 20.6, s * 22, 46, 48, -46.5, -26, "canvas"), (-s, 0, 0), "interior")
    t.sbox(B, 22, 22.5, 35.4, 36.4, -34.5, -31.5, "metal")
    t.sbox(B, 22.4, 23.1, 35.2, 36.6, -33.2, -32.2, "metal")
    for y0 in (28, 41):
        t.sbox(B, 22, 22.7, y0, y0 + 2.4, -46.9, -45.6, "metal")
    # Rear wall: metal below, canvas above with a small window opening.
    inward(t.box(B, -22, 22, 26, 38, -26, -24.5, "olive"), (0, 0, -1), "interior")
    for x0, x1, y0, y1 in ((-22, -6, 38, 48), (6, 22, 38, 48), (-6, 6, 38, 41), (-6, 6, 45, 48)):
        inward(t.box(B, x0, x1, y0, y1, -26, -24.5, "canvas"), (0, 0, -1), "interior")
    t.box(B, -6, 6, 40.6, 41, -24.6, -24.2, "rope")
    # Canvas roof: crowned panels, four bows, rope lacing and ties.
    inward(t.box(B, -14, 14, 48, 50.4, -48.6, -24.1, "canvas"), (0, -1, 0), "interior")
    for c in t.sori(B, (18.1, 48.9, -36.35), (8.7, 2.0, 24.5), axis_rot("z", -14), "canvas"):
        inward(c, (0, -1, 0), "interior")
    t.box(B, -22.3, 22.3, 47.3, 48.3, -48.9, -48.1, "canvas")
    for z in (-44.5, -38.5, -32.5, -27.5):
        t.box(B, -14.2, 14.2, 50.2, 50.9, z - 0.5, z + 0.5, "canvas", tag="bow")
        t.sori(B, (18.1, 49.65, z), (8.9, 0.9, 1.0), axis_rot("z", -14), "canvas", tag="bow")
    t.sbox(B, 22.05, 22.35, 46.0, 46.35, -46.4, -26.1, "rope")
    for z in np.arange(-45.0, -26.0, 4.0):
        t.sbox(B, 22.0, 22.4, 45.6, 48.5, z - 0.2, z + 0.2, "rope", share="tie")
    for x in (-18, -10, 10, 18):
        t.box(B, x - 0.2, x + 0.2, 45.6, 48.5, -24.5, -24.1, "rope", share="tie_r")
    # Raised blast shields over the windscreen on hinged arms.
    t.sori(B, (10.2, 49.6, -50.1), (17.4, 0.8, 5.6), axis_rot("x", 20), "olive", tag="shield")
    for x in (3, 17.2):
        t.sbar(B, (x, 47.4, -48.2), (x, 49.1, -51.6), 0.6, 0.6, "metal")
    # Mirrors with arms, steps, grab handle, antenna.
    t.sbar(B, (22, 44.6, -47), (26.6, 44.6, -48.6), 0.5, 0.5, "metal")
    t.sbar(B, (22, 40.2, -47), (26.6, 40.2, -48.6), 0.5, 0.5, "metal")
    t.sbox(B, 26.3, 26.9, 39.8, 45.2, -48.9, -48.3, "metal")
    t.sori(B, (26.7, 42.6, -49.5), (2.8, 4.4, 0.6), axis_rot("y", -18), "black")
    t.sbox(B, 20.5, 24.5, 18, 18.8, -43.5, -35, "metal", tag="tread")
    t.sbox(B, 21.5, 24.2, 22, 22.8, -45.5, -36, "metal", tag="tread")
    t.sbox(B, 23.6, 24.2, 17, 23, -43.5, -42.9, "dark")
    t.sbox(B, 23.6, 24.2, 17, 23, -35.6, -35, "dark")
    t.sbox(B, 22.1, 22.6, 29, 40, -27.4, -26.9, "metal")
    t.box(B, -21.6, -19.4, 50, 51.4, -27.1, -24.9, "metal")
    t.bar(B, (-20.5, 51.4, -26), (-20.5, 66, -26), 0.35, 0.35, "black")


def cab_interior(t):
    B = "body"
    t.box(B, -20.5, 20.5, 33, 38, -46.5, -43, "dash")
    t.box(B, 3.5, 17.5, 37.9, 38.6, -46.5, -44.5, "dark")                  # instrument hood
    # Steering column (fixed) along the wheel axis, from under the dash to the hub.
    hub = np.array(STEER_HUB, float)
    axis = np.array([0, math.sin(math.radians(STEER_TILT)), math.cos(math.radians(STEER_TILT))])
    t.bar(B, hub - axis * 7.0, hub - axis * 0.45, 1.0, 1.0, "black")
    # Wheel in grad_steering_wheel, authored flat in the bone's local XY plane around the
    # pivot; the bind rotation tilts it onto the column. Hub first (centred on the pivot).
    W = "grad_steering_wheel"
    t.prism(W, hub, AXIS["z"], 0.9, 0.9, "black", n=4)
    t.ring(W, hub, AXIS["z"], STEER_R, 0.6, "black")
    for a in (0, 180, 270):          # three-spoke wheel, open at 12 o'clock
        d = np.array([math.cos(math.radians(a)), math.sin(math.radians(a)), 0.0])
        t.bar(W, hub + d * 0.8, hub + d * (STEER_R - 0.2), 0.5, 0.35, "black", up=(0, 0, 1))
    for s in (1, -1):
        t.box(B, s * 4, s * 17, 26, 29.5, -38, -28.5, "seat")
        t.ori(B, (s * 10.5, 35.2, -27.4), (13, 10.5, 2.2), axis_rot("x", 10), "seat")
        # Sun visors sit right above the eye: hidden in the driver's first person.
        t.box("grad_fp_hide", s * 5, s * 16, 46.4, 46.8, -46.4, -43.4, "interior")
    t.bar(B, (2.0, 26, -40), (3.0, 33, -41.5), 0.6, 0.6, "black")
    t.bar(B, (-0.5, 26, -40), (0.2, 31.5, -40.8), 0.6, 0.6, "black")
    for x in (7.5, 10.5, 13.5):
        t.box(B, x - 0.8, x + 0.8, 26, 28.4, -45.2, -44.6, "black", share="pedal")


def spare_and_chassis(t):
    B = "body"
    # Spare wheel lying in its rack behind the cab, clamped through the hub.
    wheel(t, B, SPARE, AXIS["y"], 1, spare=True)
    t.sbox(B, 9.5, 12, 18, 21.9, -23, 1, "dark")
    t.box(B, -12, 12, 20, 21.9, -12, -10, "dark")
    t.box(B, -0.5, 0.5, 21.9, 31.6, -11.5, -10.5, "metal")
    t.box(B, -3.4, 3.4, 31.6, 32.3, -12.4, -9.6, "metal")
    # Left fuel tank on straps; right battery box, tool box, air tank, exhaust.
    t.prism(B, (18, 16.5, -14), AXIS["z"], 4.2, 26, "olive", n=4, tag="tank")
    for z in (-23, -5):
        t.prism(B, (18, 16.5, z), AXIS["z"], 4.45, 0.8, "dark", n=4)
    t.prism(B, (18, 21.0, -21), AXIS["y"], 1.0, 1.0, "metal", n=4)
    for z in (-22, -7):          # tank, box and air-tank brackets to the frame rails
        t.box(B, 9.5, 14.5, 15, 18, z, z + 2, "dark")
        t.box(B, -14, -9.5, 15, 18, z, z + 2, "dark")
    t.box(B, -14.5, -9.5, 15, 17.5, 1, 3, "dark")
    t.box(B, -22, -14, 13, 21, -30, -19, "olive")
    t.box(B, -22.3, -13.7, 21, 21.8, -30.3, -18.7, "olive_dark")
    t.box(B, -22, -14, 13, 21, -18, -8, "olive")
    t.box(B, -22.3, -13.7, 21, 21.8, -18.3, -7.7, "olive_dark")
    for z in (-27, -22, -15, -11):
        t.box(B, -22.6, -22.0, 18.5, 20.5, z - 0.6, z + 0.6, "metal", share="latch")
    t.prism(B, (-17.5, 16.0, 0), AXIS["z"], 3.0, 9, "olive_dark", n=4)
    # Exhaust: pipe along the right rail behind the steered wheel, muffler, side tailpipe.
    t.prism(B, (-16.5, 13.4, -36), AXIS["z"], 2.3, 9, "exhaust", n=4)
    t.bar(B, (-7.5, 15.5, -50), (-10.4, 14.2, -40.6), 1.0, 1.0, "exhaust")
    t.bar(B, (-10.4, 14.2, -40.8), (-16.5, 13.4, -40.3), 1.0, 1.0, "exhaust")
    t.bar(B, (-18.6, 13.2, -34), (-23.2, 12.4, -34.6), 1.0, 1.0, "exhaust")


def deck_and_rear(t):
    B = "body"
    deck = t.box(B, -23, 23, 25, 28, -2, 72, "olive")
    inward(deck, (0, 1, 0), "deck")
    t.sbox(B, 22.6, 23.6, 24.4, 28.4, -2, 72, "olive_dark")
    for z in range(4, 70, 10):
        t.sbox(B, 23.6, 24.3, 25.4, 27.6, z - 0.8, z + 0.8, "olive_dark", share="pocket")
    # Mudguards over the tandem and mud flaps.
    t.sbar(B, (19.25, 24.6, 3.0), (19.25, 16.8, 5.4), 8.6, 0.8, "olive")
    t.sbar(B, (19.25, 24.6, 60.8), (19.25, 15.8, 58.7), 8.6, 0.8, "olive")
    t.sbox(B, 15, 23.5, 4.6, 16.2, 59.2, 59.8, "rubber")
    t.sbox(B, 13, 20.4, 7.5, 17.9, -42.4, -41.8, "rubber")
    # Equipment boxes with lids and latches forward of the turntable.
    t.sbox(B, 17, 22.5, 28, 32, 0, 13.5, "olive")
    t.sbox(B, 16.7, 22.8, 32, 32.6, -0.3, 13.8, "olive_dark")
    for z in (3, 10.5):
        t.sbox(B, 22.5, 23.0, 29.6, 31.4, z - 0.6, z + 0.6, "metal", share="latch")
    t.prism(B, (-19.8, 30.6, -0.6), AXIS["y"], 1.2, 5, "red", n=4)          # extinguisher
    # Rear bumper, pintle hook, lamps with guards, plate.
    t.box(B, -20, 20, 14, 18, 70, 73, "olive_dark")
    t.prism(B, (0, 16, 74.5), AXIS["z"], 1.5, 3.2, "metal", n=4)
    t.box(B, -1.8, 1.8, 14.2, 17.8, 76, 77, "metal")
    for s in (1, -1):
        t.box(B, s * 16, s * 21, 19.5, 24, 71, 73, "olive_dark")
        t.box(B, s * 16.5, s * 18.5, 20.5, 23, 72.9, 73.3, "red_lens")
        t.box(B, s * 18.8, s * 20.6, 20.5, 23, 72.9, 73.3, "amber")
        for gx in (15.6, 21.0):
            t.box(B, s * gx, s * (gx + 0.4), 19, 24.6, 73.4, 73.8, "metal")
        t.box(B, s * 15.6, s * 21.4, 24.2, 24.6, 73.4, 73.8, "metal")
    t.box(B, -5, 5, 19, 23, 72, 72.6, "plate")
    t.box(B, -1, 1, 23, 24, 72, 73, "metal")
    # Rear stabiliser jacks. Fixed in `body`: open-bottomed housing with a guide collar,
    # crank and brace. Moving (grad_jack_l/r): telescopic inner leg, swivel, foot pad,
    # modelled stowed with the pad bottom at y = JACK_TRAVEL.
    x, z = JACK_X, JACK_Z
    t.sbox(B, x - 2.25, x + 2.25, 13, JACK_TOP, z - 2, z + 2, "olive_dark", skip={"down"})
    t.sbox(B, x - 2.25, x + 2.25, 12.4, 13.4, z - 2, z - 1.3, "dark")
    t.sbox(B, x - 2.25, x + 2.25, 12.4, 13.4, z + 1.3, z + 2, "dark")
    t.sbox(B, x - 2.25, x - 1.45, 12.4, 13.4, z - 1.3, z + 1.3, "dark")
    t.sbox(B, x + 1.45, x + 2.25, 12.4, 13.4, z - 1.3, z + 1.3, "dark")
    t.sbox(B, x - 2.6, x + 2.6, 23.4, 24.4, z - 2.35, z + 2.35, "dark")         # top flange
    t.sbar(B, (19.5, 22, z), (21.6, 22, z), 0.5, 0.5, "metal")
    t.sbox(B, 21.3, 21.9, 22, 24.6, z - 0.3, z + 0.3, "black")
    t.sbar(B, (15.2, 23.5, z - 1.5), (9.7, 17, z), 0.8, 0.8, "dark")
    pad = JACK_TRAVEL
    for s, bone in ((1, "grad_jack_l"), (-1, "grad_jack_r")):
        cx = s * x
        t.box(bone, cx - 1.3, cx + 1.3, pad + 2.2, JACK_TOP - 0.4, z - 1.1, z + 1.1, "metal",
              tag="leg")
        t.box(bone, cx - 1.0, cx + 1.0, pad + 1.4, pad + 2.2, z - 1.0, z + 1.0, "dark")
        t.prism(bone, (cx, pad + 0.7, z), AXIS["y"], 3.2, 1.4, "dark", n=4)


def turret(t):
    T = "turret"
    tx, ty, tz = TURRET
    t.prism(T, (0, ty + 1.25, tz), AXIS["y"], 15.0, 2.5, "olive_dark", n=6)
    t.prism(T, (0, ty + 3.25, tz), AXIS["y"], 12.5, 1.5, "olive", n=6)
    for k in range(12):
        a = math.radians(15 + 30 * k)
        t.box(T, 14 * math.cos(a) - 0.4, 14 * math.cos(a) + 0.4, ty + 2.5, ty + 3.1,
              tz + 14 * math.sin(a) - 0.4, tz + 14 * math.sin(a) + 0.4, "metal", share="bolt_t")
    t.box(T, -6, 6, ty + 4, ty + 6, tz - 8, tz + 8, "olive_dark")
    t.prism(T, (-8, ty + 5, tz - 4), AXIS["x"], 1.5, 4, "dark", n=4)        # traverse motor
    px, py, pz = PACK_PIVOT
    t.sbox(T, 12, 21, ty + 2.5, ty + 4, pz - 10, pz + 4, "olive_dark")
    t.sbox(T, 18.8, 21, ty + 4, py + 3.5, pz - 6, pz + 4, "olive")
    t.sbox(T, 18.8, 21, ty + 4, ty + 7, pz - 10, pz - 6, "olive")
    t.sori(T, (19.9, ty + 7.6, pz - 6.4), (2.2, 2.4, 3.6), axis_rot("x", -48), "olive")
    for s in (1, -1):
        t.prism(T, (s * 22, py, pz), AXIS["x"], 2.2, 2.0, "metal", n=4)
    # Gunner's station on the left cheek: sight, panoramic head, hand wheels.
    t.box(T, 21, 23, ty + 4, ty + 10, tz - 2, tz + 1, "olive_dark")
    t.box(T, 22, 25, ty + 10, ty + 13.5, tz - 2.5, tz + 1.5, "olive_dark")
    t.prism(T, (25.9, ty + 12, tz - 0.5), AXIS["x"], 0.8, 1.8, "black", n=4)
    t.prism(T, (23.5, ty + 14.5, tz - 0.5), AXIS["y"], 1.2, 2.0, "olive_dark", n=4)
    t.box(T, 23, 24, ty + 14, ty + 15, tz - 1.9, tz - 1.6, "lens", tag=("lens", (23.5, ty + 14.5)))
    for zc, yc in ((pz - 8, ty + 6.5), (tz + 5, ty + 6)):
        t.ring(T, (22.6, yc, zc), AXIS["x"], 2.4, 0.5, "black")
        t.box(T, 21, 22.6, yc - 0.3, yc + 0.3, zc - 0.3, zc + 0.3, "metal")
        t.box(T, 22.6, 24, yc + 1.9, yc + 2.5, zc - 0.3, zc + 0.3, "black")
    t.bar(T, (8, ty + 4, tz + 8), (10, ty + 6.6, pz - 2), 1.0, 1.0, "cable")


def pack(t):
    P = "barrel"
    z0, z1 = PACK_Z
    px, py, pz = PACK_PIVOT
    zc, ln = (z0 + z1) / 2, z1 - z0
    for j in range(4):
        for i in range(10):
            x = TUBE_X0 + TUBE_PITCH * (i + 0.5)
            y = TUBE_Y0 + TUBE_PITCH * (j + 0.5)
            t.prism(P, (x, y, zc), AXIS["z"], TUBE_R, ln, "tube", n=4,
                    tag=("tube",), share="tube")
    top = TUBE_Y0 + 4 * TUBE_PITCH
    for a, b in ((z0 + 2, z0 + 6), (z0 + 22, z0 + 26), (z0 + 42, z0 + 46), (z1 - 12, z1 - 1)):
        mid, L = (a + b) / 2, b - a
        t.box(P, -17.2, 17.2, top, top + 1.5, a, b, "olive")
        t.box(P, -17.2, 17.2, py, TUBE_Y0, a, b, "olive")
        t.sbox(P, 17.0, 18.5, TUBE_Y0 + 0.7, top - 0.7, a, b, "olive")
        t.sori(P, (17.75, top + 0.2, mid), (2.4, 1.5, L), axis_rot("z", -45), "olive")
        t.sori(P, (17.75, TUBE_Y0 - 0.2, mid), (2.4, 1.5, L), axis_rot("z", 45), "olive")
        for yb in (TUBE_Y0 + 3, top - 3):
            t.sbox(P, 18.5, 19.0, yb - 0.5, yb + 0.5, mid - 0.5, mid + 0.5, "metal", share="bolt_p")
    t.sbox(P, 6, 9, py - 1.2, py, -10, pz + 1, "olive_dark")                # cradle beams
    t.sbox(P, 14, 17.5, py - 1.5, py, pz - 6, pz + 1.5, "metal")            # trunnion lugs
    t.sbox(P, 12.5, 13.5, top + 1.5, top + 2.2, z0 + 4, z1 - 4, "metal")    # lifting rails
    # Firing cable troughs and junction boxes along both sides.
    t.sbox(P, 18.5, 19.5, TUBE_Y0 + 1.0, TUBE_Y0 + 2.4, z0 + 6, z1 - 12, "dark")
    t.sbox(P, 18.6, 19.3, TUBE_Y0 + 2.4, TUBE_Y0 + 3.0, z0 + 6, z1 - 12, "cable")
    for zz in (-6, 12, 30):
        t.sbox(P, 18.5, 20.0, TUBE_Y0 + 4, TUBE_Y0 + 7, zz - 1.6, zz + 1.6, "olive_dark")
    t.sbar(P, (19.2, TUBE_Y0 + 1.7, z1 - 12), (16, py - 1.0, pz - 3), 0.7, 0.7, "cable")
    # Arc of the elevation sector under the pack.
    for k in range(5):
        ph = math.radians(12 + 12 * k)
        c = (16.6, py - 6 * math.sin(ph), pz - 6 * math.cos(ph))
        t.sori(P, c, (1.2, 1.2, 1.6), axis_rot("x", 90 - math.degrees(ph)), "metal")


def build():
    t = Truck()
    wheels(t)
    frame_and_axles(t)
    bonnet_and_wings(t)
    cab(t)
    cab_interior(t)
    spare_and_chassis(t)
    deck_and_rear(t)
    turret(t)
    pack(t)
    cull.FIXED, cull.KEEP_ONE = {"body"}, ()
    hidden = cull.cull(t.cubes)
    left = zfight.resolve([c for c in t.cubes if any(True for _ in c.faces())])
    return t, hidden, len(left)


# --------------------------------------------------------------- texture ---
# Muted Soviet green matched to SBW's own BMP-2/T-90A atlases (dominant ~(56..64, 72..80, 48..52)).
PAL = {
    "olive": (66, 80, 52), "olive_dark": (54, 66, 43), "dark": (44, 49, 38),
    "canvas": (98, 96, 73), "rope": (128, 118, 90), "tyre": (37, 37, 35),
    "rim": (62, 76, 49), "metal": (72, 74, 68), "black": (30, 31, 29),
    "rubber": (35, 35, 33), "cable": (32, 33, 31), "tube": (62, 68, 57),
    "grille": (25, 27, 24), "lens": (190, 188, 162), "amber": (146, 110, 54),
    "red_lens": (118, 46, 40), "red": (112, 52, 44), "plate": (186, 184, 170),
    "interior": (58, 68, 46), "seat": (72, 63, 49), "dash": (42, 44, 39),
    "floor": (44, 44, 41), "deck": (60, 72, 47), "exhaust": (80, 63, 49),
}
PAINTED = {"olive", "olive_dark", "rim", "deck", "dark", "canvas", "interior"}
MUD, DUST = np.array([94, 86, 68.0]), np.array([120, 114, 92.0])
WHITE = np.array([188, 186, 172.0])


def wheel_splash(p):
    """Mud thrown onto wings, flaps and chassis around every road wheel."""
    out = np.zeros(len(p))
    for z in AXLES.values():
        r = np.hypot(p[:, 1] - WHEEL_R, p[:, 2] - z)
        out = np.maximum(out, np.clip(1 - (r - 11) / 7, 0, 1) * (np.abs(p[:, 0]) > 11))
    return out


def markings(p, n, rgb):
    """White tactical rhombus on the doors, hazard stripes on the deck tail."""
    door = (np.abs(n[0]) > .9) & (np.abs(p[:, 0]) > 21.9)
    d = np.abs(p[:, 1] - 32.0) / 3.4 + np.abs(p[:, 2] + 38.5) / 3.4
    rgb[door & (d > 0.62) & (d < 1.0)] = WHITE
    tail = (n[2] > .9) & (p[:, 2] > 71.5) & (p[:, 1] > 24.5) & (np.abs(p[:, 0]) > 12)
    stripe = np.mod(p[:, 0] * np.sign(p[:, 0]) + p[:, 1], 4.0) < 2.0
    rgb[tail & stripe] = WHITE
    rgb[tail & ~stripe] = np.array(PAL["red"], float)
    return rgb


def paint(p, n, c, face, s, tt, wh):
    """Texel colours for one face from world points p (N x 3) and normal n."""
    mat = c.mat
    for direction, alt in c.fmat:
        if n @ direction > 0.9:
            mat = alt
    N = len(p)
    rgb = np.tile(np.array(PAL[mat], float), (N, 1))
    w, h = wh
    edge = np.minimum(np.minimum(s, 1 - s) * w, np.minimum(tt, 1 - tt) * h)
    mott = fbm(p, 9.0, 11)
    rgb *= (0.94 + 0.12 * mott)[:, None]
    if mat in PAINTED:
        # Soft occlusion towards the ground and a lighter, dusty top.
        rgb *= (0.86 + 0.14 * np.clip((p[:, 1] - 4) / 34, 0, 1))[:, None]
        if n[1] > 0.7:
            dust = 0.18 * fbm(p, 6.0, 21)
            rgb = rgb * (1 - dust[:, None]) + DUST * dust[:, None]
        elif abs(n[1]) < 0.3:   # rain streaks down vertical panels
            st = value_noise(p * np.array([1.0, 0.08, 1.0]), 1.4, 31)
            rgb *= (1 - 0.07 * np.clip((st - 0.55) * 3, 0, 1))[:, None]
        if w >= 3 and h >= 3:   # panel seams and chipped edges
            chip = value_noise(p, 1.3, 41) > 0.72
            rgb[edge < 1.0] *= 0.86
            rgb[(edge < 1.0) & chip] = rgb[(edge < 1.0) & chip] * 0.8 + 30
        if mat == "canvas":
            fold = np.cos(p[:, 0] * 0.8 + 3 * fbm(p, 5.0, 51)) * np.cos(p[:, 2] * 0.9)
            rgb *= (0.95 + 0.05 * fold)[:, None]
            if c.tag == "bow":
                rgb *= 1.06
        if mat == "deck":
            dash = (np.mod(np.floor(p[:, 0] / 2) + np.floor(p[:, 2] / 2), 2) == 0) & \
                   (np.mod(p[:, 0], 2) < 1) & (np.mod(p[:, 2], 2) < 1)
            rgb[dash] *= 1.12
        if mat in ("olive", "olive_dark"):
            rgb = markings(p, n, rgb)
            if c.tag == "shield":
                rivet = (np.mod(p[:, 0] + 0.5, 3.0) < 1) & (edge < 1.6) & (edge >= 0.6)
                rgb[rivet] *= 1.15
    elif mat == "tyre":
        ax = np.array(c.tag[1]), np.array(c.tag[2])
        d = p - ax[0]
        r = np.linalg.norm(d - np.outer(d @ ax[1], ax[1]), axis=1)
        if abs(n @ ax[1]) > 0.9:   # sidewall: bead ring and lettering band
            rgb[(r > 8.6) & (r < 9.4)] *= 1.25
        rgb *= (0.9 + 0.2 * value_noise(p, 2.0, 61))[:, None]
    elif mat == "tube":
        rgb *= (0.92 + 0.08 * value_noise(p * np.array([1, 1, .1]), 1.2, 71))[:, None]
        if abs(n[2]) > 0.9:   # muzzle bores and rear contacts
            cx = TUBE_X0 + TUBE_PITCH * (np.floor((p[:, 0] - TUBE_X0) / TUBE_PITCH) + 0.5)
            cy = TUBE_Y0 + TUBE_PITCH * (np.floor((p[:, 1] - TUBE_Y0) / TUBE_PITCH) + 0.5)
            r = np.hypot(p[:, 0] - cx, p[:, 1] - cy)
            if n[2] < 0:
                rgb[r < 1.3] = np.array([56, 58, 52.0])
                rgb[r < 1.0] = np.array([16, 16, 15.0]) + 8 * r[r < 1.0, None]
            else:
                rgb[r < 1.1] = np.array([30, 29, 26.0])
                rgb[r < 0.45] = np.array([112, 94, 60.0])
    elif mat == "lens":
        cx, cy = c.tag[1]
        r = np.hypot(p[:, 0] - cx, p[:, 1] - cy)
        rgb[(p[:, 0] - cx < -0.3) & (p[:, 1] - cy > 0.3)] = np.array([236, 232, 210.0])
        rgb[r > 1.6] *= 0.8
    elif mat == "plate":
        rgb[edge < 0.7] = np.array([30, 30, 28.0])
        digit = (np.mod(np.floor(s * w * 1.0), 2) == 0) & (tt > 0.3) & (tt < 0.75) & (edge >= 0.7)
        rgb[digit] = np.array([40, 40, 38.0])
    elif mat == "dash" and n[2] > 0.9:
        for x in (6.5, 9.5, 12.5, 15.0):
            rgb[np.hypot(p[:, 0] - x, p[:, 1] - 35.7) < 1.0] = np.array([128, 126, 112.0])
    elif mat == "metal":
        if c.tag == "tread":
            rgb[(np.mod(p[:, 0] + p[:, 2], 2.0) < 1)] *= 1.15
        rust = fbm(p, 3.0, 81)
        rgb = rgb * (1 - 0.25 * rust[:, None]) + np.array([96, 70, 50.0]) * 0.25 * rust[:, None]
    elif mat == "exhaust":
        rgb *= (0.8 + 0.3 * fbm(p, 2.0, 91))[:, None]
    # Mud and dust on the lower hull, wheels and everything near the tyres.
    low = np.clip((16 - p[:, 1]) / 14, 0, 1) ** 1.2
    amt = np.maximum(low, 0.6 * wheel_splash(p)) * (0.4 + 0.6 * fbm(p, 5.0, 101))
    if mat == "tyre":       # dusty rubber, caked only near the ground
        amt = 0.12 + 0.5 * low * fbm(p, 3.0, 111)
    elif mat == "rim":
        amt = 0.1 + 0.45 * amt
    if mat in ("lens", "red_lens", "amber", "plate", "dash", "seat", "interior", "floor", "tube"):
        amt = amt * 0.3
    amt = np.clip(amt, 0, 0.65)[:, None]
    rgb = rgb * (1 - amt) + MUD * amt
    return np.concatenate([np.clip(rgb, 0, 255), np.full((N, 1), 255.0)], 1)


def pack_atlas(cubes, size):
    """Shelf-pack every visible face; repeated parts (share key) reuse one rect."""
    rects, shared = [], {}
    for c in cubes:
        for face, corner, sv, tv, n in c.faces():
            w = max(1, int(math.ceil(np.linalg.norm(sv) * DENSITY - 1e-6)))
            h = max(1, int(math.ceil(np.linalg.norm(tv) * DENSITY - 1e-6)))
            key = (c.share, face, w, h) if c.share else None
            if key in shared:
                shared[key].append((c, face))
                continue
            group = [(c, face)]
            if key:
                shared[key] = group
            rects.append((h, w, group))
    rects.sort(key=lambda r: (-r[0], -r[1]))
    x = y = shelf = 0
    for h, w, group in rects:
        if x + w + 1 > size:
            x, y, shelf = 0, y + shelf + 1, 0
        if y + h + 1 > size:
            return None
        for c, face in group:
            c.uv[face] = (x, y, w, h)
        x += w + 1
        shelf = max(shelf, h)
    return [g for _, _, g in rects]


def atlas(cubes):
    for size in (256, 512):
        groups = pack_atlas(cubes, size)
        if groups is not None:
            break
    else:
        raise ValueError("atlas overflow at 512")
    img = np.zeros((size, size, 4))
    for group in groups:
        c, face = group[0]
        u, v, w, h = c.uv[face]
        corner, sv, tv, n = next((cr, a, b, nn) for f, cr, a, b, nn in c.faces() if f == face)
        ss, tt = np.meshgrid((np.arange(w) + 0.5) / w, (np.arange(h) + 0.5) / h)
        pts = corner + ss.reshape(-1, 1) * sv + tt.reshape(-1, 1) * tv
        out = paint(c.world(pts), c.world_normal(n), c, face, ss.reshape(-1), tt.reshape(-1), (w, h))
        img[v:v + h, u:u + w] = out.reshape(h, w, 4)
        if v > 0:
            img[v - 1, u:u + w] = img[v, u:u + w]
        if u > 0:
            img[v:v + h, u - 1] = img[v:v + h, u]
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGBA")


# ---------------------------------------------------------------- export ---
def skeleton_with_extras(skeleton):
    """Native bones (moved pivots applied) plus the added bones and the steering parent."""
    before = {e[3]: e for e in EXTRA_BONES if e[3]}
    out = []
    for nb in skeleton:
        if nb["name"] in before:
            name, parent, pivot, _ = before[nb["name"]]
            out.append({"name": name, "parent": parent, "pivot": list(pivot)})
        b = json.loads(json.dumps(nb))
        if b["name"] in MOVED:
            b["pivot"] = list(MOVED[b["name"]])
        if b["name"] in REPARENT:
            b["parent"] = REPARENT[b["name"]][1]
        out.append(b)
    out += [{"name": n, "parent": p, "pivot": list(v)} for n, p, v, at in EXTRA_BONES if not at]
    for b in out:
        if b["name"] in BIND_ROT:
            b["rotation"] = list(BIND_ROT[b["name"]])
    return out


def geo(skeleton, cubes, size):
    bones = []
    for b in skeleton_with_extras(skeleton):
        out = []
        for c in cubes:
            if c.bone != b["name"]:
                continue
            uv = {f: {"uv": [u, v], "uv_size": [w, h]} for f, (u, v, w, h) in c.uv.items()
                  if f not in c.skip}
            if not uv:
                continue
            e = {"origin": [K.r6(v) for v in c.origin], "size": [K.r6(v) for v in c.size]}
            if c.rotation:
                e["pivot"] = [K.r6(v) for v in c.pivot]
                e["rotation"] = [K.r6(v) for v in c.rotation]
            e["uv"] = uv
            out.append(e)
        if out:
            b["cubes"] = out
        bones.append(b)
    return {"format_version": "1.12.0", "minecraft:geometry": [{
        "description": {"identifier": "geometry.grad", "texture_width": size,
                        "texture_height": size, "visible_bounds_width": 14,
                        "visible_bounds_height": 8, "visible_bounds_offset": [0, 4, 0]},
        "bones": bones}]}


# ------------------------------------------------------------ rendering ---
def geo_quads(data, poses=None, moves=None, hide=()):
    """World quads (4 points) and texel quads of a Bedrock geo with per-face UV.
    poses: bone -> extra rotation matrix; moves: bone -> translation; hide: bones
    (with their children) left out, as a renderer hiding a bone would."""
    poses, moves = poses or {}, moves or {}
    bones = {b["name"]: b for b in data["minecraft:geometry"][0]["bones"]}

    def to_world(name, pts):
        b = bones[name]
        r = K.rot_matrix(b.get("rotation"))
        if name in poses:
            r = poses[name] @ r
        pv = np.array(b.get("pivot", [0, 0, 0]), float)
        pts = (pts - pv) @ r.T + pv + np.asarray(moves.get(name, (0, 0, 0)), float)
        return to_world(b["parent"], pts) if "parent" in b else pts

    def hidden(name):
        while name:
            if name in hide:
                return True
            name = bones[name].get("parent")
        return False

    for b in bones.values():
        if hidden(b["name"]):
            continue
        for c in b.get("cubes", []):
            if not isinstance(c.get("uv"), dict):
                continue
            inf = c.get("inflate", 0)
            o, s = np.array(c["origin"], float) - inf, np.array(c["size"], float) + 2 * inf
            for face, info in c["uv"].items():
                corner, sv, tv, _ = (np.array(v, float) for v in K.face_frame(face, o, s))
                q = np.array([corner, corner + sv, corner + sv + tv, corner + tv])
                if c.get("rotation"):
                    pv = np.array(c["pivot"], float)
                    q = (q - pv) @ K.rot_matrix(c["rotation"]).T + pv
                q = to_world(b["name"], q)
                (u, v), (w, h) = info["uv"], info["uv_size"]
                uvq = np.array([[u, v], [u + w, v], [u + w, v + h], [u, v + h]], float)
                yield q, uvq


def view_basis(yaw, elevation):
    """Camera towards `cam`. Screen right follows the in-game handedness: SBW draws geo X
    mirrored, so +X (the L side) is the vehicle's left when it faces -Z."""
    cam = np.array([math.sin(math.radians(yaw)), elevation, -math.cos(math.radians(yaw))])
    cam /= np.linalg.norm(cam)
    right = np.cross(cam, [0, 1, 0])
    right /= np.linalg.norm(right)
    return cam, right, np.cross(right, cam)


LIGHT = np.array([-.35, 1, -.6]) / np.linalg.norm([-.35, 1, -.6])


def raster(pix, zbuf, texp, scr, key, wq, uv, shade):
    """One triangle: screen xy, depth key (larger = nearer), perspective weight wq
    (1 for orthographic, 1/depth for perspective) and texel coordinates per vertex."""
    h, w = zbuf.shape
    th, tw = texp.shape[:2]
    a, b, c = scr
    x0, x1 = max(0, int(min(a[0], b[0], c[0]))), min(w - 1, math.ceil(max(a[0], b[0], c[0])))
    y0, y1 = max(0, int(min(a[1], b[1], c[1]))), min(h - 1, math.ceil(max(a[1], b[1], c[1])))
    if x1 < x0 or y1 < y0:
        return
    den = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
    if abs(den) < 1e-9:
        return
    xx, yy = np.meshgrid(np.arange(x0, x1 + 1) + .5, np.arange(y0, y1 + 1) + .5)
    l1 = ((b[1] - c[1]) * (xx - c[0]) + (c[0] - b[0]) * (yy - c[1])) / den
    l2 = ((c[1] - a[1]) * (xx - c[0]) + (a[0] - c[0]) * (yy - c[1])) / den
    l3 = 1 - l1 - l2
    z = l1 * key[0] + l2 * key[1] + l3 * key[2]
    q = l1 * wq[0] + l2 * wq[1] + l3 * wq[2]
    q = np.where(np.abs(q) < 1e-12, 1e-12, q)
    tu = (l1 * wq[0] * uv[0, 0] + l2 * wq[1] * uv[1, 0] + l3 * wq[2] * uv[2, 0]) / q
    tv = (l1 * wq[0] * uv[0, 1] + l2 * wq[1] * uv[1, 1] + l3 * wq[2] * uv[2, 1]) / q
    col = texp[np.clip(np.floor(tv), 0, th - 1).astype(int), np.clip(np.floor(tu), 0, tw - 1).astype(int)]
    old = zbuf[y0:y1 + 1, x0:x1 + 1]
    m = (l1 >= -1e-6) & (l2 >= -1e-6) & (l3 >= -1e-6) & (z > old) & (col[..., 3] > 8)
    if not m.any():
        return
    out = col.astype(float)
    out[..., :3] *= shade
    pix[y0:y1 + 1, x0:x1 + 1][m] = out[m].astype(np.uint8)
    old[m] = z[m]


def face_shade(q, toward):
    n = np.cross(q[1] - q[0], q[3] - q[0])
    if np.linalg.norm(n) < 1e-9:
        return None
    n /= np.linalg.norm(n)
    if n @ toward < 0:
        n = -n
    return 0.62 + 0.38 * max(0.0, float(n @ LIGHT))


def render_fp(data, tex, size, eye, yaw=0.0, pitch=-8.0, fov=70.0, hide=(), near=0.4, poses=None):
    """Perspective view from `eye` (model units): yaw 0 looks -Z, positive yaw turns to
    the vehicle's left (+X); fov is vertical, as Minecraft's. Polygons are clipped at
    the near plane, texturing is perspective-correct."""
    w, h = size
    yr, pr = math.radians(yaw), math.radians(pitch)
    f = np.array([math.sin(yr) * math.cos(pr), math.sin(pr), -math.cos(yr) * math.cos(pr)])
    right = np.cross([0, 1, 0], f)
    right /= np.linalg.norm(right)
    up = np.cross(f, right)
    focal = (h / 2) / math.tan(math.radians(fov) / 2)
    e = np.asarray(eye, float)
    texp = np.asarray(tex.convert("RGBA"))
    zbuf = np.full((h, w), -1e30)
    pix = np.zeros((h, w, 4), np.uint8)
    for q, uvq in geo_quads(data, poses, hide=hide):
        shade = face_shade(q, e - q.mean(0))
        if shade is None:
            continue
        cam = np.stack(((q - e) @ right, (q - e) @ up, (q - e) @ f), 1)
        poly = list(zip(cam, uvq))
        clipped = []
        for (pa, ua), (pb, ub) in zip(poly, poly[1:] + poly[:1]):
            if pa[2] >= near:
                clipped.append((pa, ua))
            if (pa[2] >= near) != (pb[2] >= near):
                t = (near - pa[2]) / (pb[2] - pa[2])
                clipped.append((pa + t * (pb - pa), ua + t * (ub - ua)))
        if len(clipped) < 3:
            continue
        P = np.array([p for p, _ in clipped])
        U = np.array([u for _, u in clipped])
        scr = np.stack((w / 2 + P[:, 0] / P[:, 2] * focal, h / 2 - P[:, 1] / P[:, 2] * focal), 1)
        inv = 1 / P[:, 2]
        for k in range(1, len(P) - 1):
            ids = [0, k, k + 1]
            raster(pix, zbuf, texp, scr[ids], inv[ids], inv[ids], U[ids], shade)
    return Image.fromarray(pix, "RGBA")


def render(data, tex, size, yaw=35, elevation=.62, poses=None, scale=None, centre=None, margin=.06,
           moves=None):
    """Orthographic textured render; returns (RGBA image, scale, centre) for reuse."""
    w, h = size
    cam, right, up = view_basis(yaw, elevation)
    quads = list(geo_quads(data, poses, moves))
    pts = np.concatenate([q for q, _ in quads])
    proj = np.stack((pts @ right, pts @ up), 1)
    lo, hi = proj.min(0), proj.max(0)
    if scale is None:
        scale = min(w * (1 - 2 * margin) / (hi[0] - lo[0]), h * (1 - 2 * margin) / (hi[1] - lo[1]))
        centre = (lo + hi) / 2
    texp = np.asarray(tex.convert("RGBA"))
    zbuf = np.full((h, w), -1e30)
    pix = np.zeros((h, w, 4), np.uint8)
    ones = np.ones(3)
    for q, uvq in quads:
        shade = face_shade(q, cam)
        if shade is None:
            continue
        scr = np.stack(((q @ right - centre[0]) * scale + w / 2, h / 2 - (q @ up - centre[1]) * scale), 1)
        depth = q @ cam
        for ids in ([0, 1, 2], [0, 2, 3]):
            raster(pix, zbuf, texp, scr[ids], depth[ids], ones, uvq[ids], shade)
    return Image.fromarray(pix, "RGBA"), scale, centre


# ----------------------------------------------------------------- icons ---
def side_icon(data, tex):
    im, _, _ = render(data, tex, (768, 192), yaw=84, elevation=.1, margin=.02)   # front left
    im = im.resize((256, 64), Image.Resampling.LANCZOS)
    a = np.asarray(im).astype(float)
    lum = (a[..., :3] @ [.299, .587, .114]) / 255
    grey = 255 - 58 * (1 - np.clip((lum - .1) / .4, 0, 1)) ** 1.6
    out = np.zeros_like(a)
    out[..., 0] = out[..., 1] = out[..., 2] = np.round(grey)
    out[..., 3] = a[..., 3]
    return Image.fromarray(out.astype(np.uint8), "RGBA")


def container_icon(data, tex):
    # Front towards the lower right, as SBW's own container icons.
    im, _, _ = render(data, tex, (384, 384), yaw=-35, elevation=.62, margin=.02)
    return im.resize((128, 128), Image.Resampling.LANCZOS)


def previews(data, tex, folder):
    folder.mkdir(parents=True, exist_ok=True)
    bg = (214, 218, 206, 255)
    fire = {"turret": axis_rot("y", 35), "barrel": axis_rot("x", 45)}
    steer = {"wheelL0Turn": axis_rot("y", 30), "wheelR0Turn": axis_rot("y", 30)}
    deploy = {"grad_jack_l": (0, -JACK_TRAVEL, 0), "grad_jack_r": (0, -JACK_TRAVEL, 0)}
    views = [("front34", None, None, 35, .55), ("side", None, None, 90, .05),
             ("rear34", None, None, 150, .55), ("fire_pose", fire, deploy, -60, .5),
             ("top", None, None, 10, 4.0), ("front", None, None, 0, .02),
             ("steer30_top", steer, None, 90, 40.0), ("steer30_front", steer, None, -25, .8),
             ("jacks_stowed", None, None, 205, .12),
             ("jacks_deployed", None, deploy, 205, .12)]
    sheet = Image.new("RGB", (1800, 1200), bg[:3])
    for k, (name, pose, move, yaw, el) in enumerate(views):
        im, _, _ = render(data, tex, (900, 600), yaw=yaw, elevation=el, poses=pose, moves=move)
        canvas = Image.new("RGBA", im.size, bg)
        canvas.alpha_composite(im)
        canvas.save(folder / f"grad-{name}.png")
        if k < 4:
            sheet.paste(canvas.convert("RGB"), (k % 2 * 900, k // 2 * 600))
    sheet.save(folder / "grad-sheet.png")
    for name, (yaw, el) in {"close-front": (30, .35), "close-rear": (215, .3)}.items():
        im, _, _ = render(data, tex, (1400, 900), yaw=yaw, elevation=el, margin=.02)
        canvas = Image.new("RGBA", im.size, bg)
        canvas.alpha_composite(im)
        canvas.save(folder / f"grad-{name}.png")
    tex.resize((tex.width * 2, tex.height * 2), Image.Resampling.NEAREST).save(folder / "grad-atlas-x2.png")
    # Driver first person (grad_fp_hide hidden, FOV 70) and the gunner's camera.
    sky = (176, 196, 214, 255)
    shots = {"fp-forward": (DRIVER_EYE, 0, -8), "fp-left60": (DRIVER_EYE, 60, -8),
             "fp-right60": (DRIVER_EYE, -60, -8), "fp-level": (DRIVER_EYE, 0, 0),
             "gunner": (list(np.add(PACK_PIVOT, GUNNER_CAM)), 0, -6)}
    strip = Image.new("RGB", (1600, 1350), sky[:3])
    for k, (name, (eye, yaw, pitch)) in enumerate(shots.items()):
        im = render_fp(data, tex, (800, 450), eye, yaw=yaw, pitch=pitch, fov=70,
                       hide={"grad_fp_hide"} if name.startswith("fp") else ())
        canvas = Image.new("RGBA", im.size, sky)
        horizon = 225 - 225 * math.tan(math.radians(-pitch)) / math.tan(math.radians(35))
        canvas.alpha_composite(Image.new("RGBA", (800, 450), (120, 116, 96, 255)), (0, int(horizon)))
        canvas.alpha_composite(im)
        canvas.save(folder / f"grad-{name}.png")
        strip.paste(canvas.convert("RGB"), (k % 2 * 800, k // 2 * 450))
    strip.save(folder / "grad-views-fp.png")
    # Right turn: renderer rotateZ(-45 deg) on the wheel bone. SBW mirrors geo X, which flips
    # the sign of Z rotations, so in geo terms that is +45 deg about the bone's local Z.
    bind = K.rot_matrix(BIND_ROT["grad_steering_wheel"])
    spin = {"grad_steering_wheel": bind @ axis_rot("z", 45) @ bind.T}
    for name, pose in (("fp-wheel-straight", None), ("fp-wheel-right45", spin)):
        im = render_fp(data, tex, (800, 450), DRIVER_EYE, 0, -25, 70, hide={"grad_fp_hide"}, poses=pose)
        canvas = Image.new("RGBA", im.size, sky)
        canvas.alpha_composite(im)
        canvas.save(folder / f"grad-{name}.png")
    jar = native_jar()
    if not jar:
        return
    # Same scale, same light and pose: native PLZ-05 (whose renderer the Grad uses)
    # and SBW's Soviet-green BMP-2 / T-90A beside the Grad.
    models = []
    with zipfile.ZipFile(jar) as z:
        for n in ("plz_05", "bmp_2", "t_90a"):
            models.append((json.loads(z.read(f"assets/superbwarfare/models/bedrock/vehicle/{n}.geo.json")),
                           Image.open(io.BytesIO(z.read(f"assets/superbwarfare/textures/bedrock/vehicle/{n}.png")))))
    models.append((data, tex))
    for name, (yaw, el) in {"compare-34": (-35, .55), "compare-side": (90, .05)}.items():
        _, right, up = view_basis(yaw, el)
        proj = [np.stack((p @ right, p @ up), 1) for p in
                (np.concatenate([q for q, _ in geo_quads(d)]) for d, _ in models)]
        scale = min(.9 * 600 / np.ptp(p[:, 0]) for p in proj)
        ymid = (min(p[:, 1].min() for p in proj) + max(p[:, 1].max() for p in proj)) / 2
        out = Image.new("RGBA", (600 * len(models), 520), bg)
        for i, ((d, tx), p) in enumerate(zip(models, proj)):
            im, _, _ = render(d, tx, (600, 520), yaw=yaw, elevation=el, scale=scale,
                              centre=((p[:, 0].min() + p[:, 0].max()) / 2, ymid))
            out.alpha_composite(im, (i * 600, 0))
        out.convert("RGB").save(folder / f"grad-{name}.png")


# ----------------------------------------------------------- validation ---
def subtree(bones, root):
    out, todo = set(), [root]
    while todo:
        n = todo.pop()
        out.add(n)
        todo += [b["name"] for b in bones if b.get("parent") == n]
    return out


def validate(data, skeleton, tex):
    g = data["minecraft:geometry"][0]
    bones = g["bones"]
    by = {b["name"]: b for b in bones}
    names = [b["name"] for b in bones]
    assert len(names) == len(set(names)), "duplicate bone names"
    assert names == [b["name"] for b in skeleton_with_extras(skeleton)], "bone order and set"
    for nb in skeleton:
        b = by[nb["name"]]
        parent = REPARENT[nb["name"]][1] if nb["name"] in REPARENT else nb.get("parent")
        assert nb.get("parent") == REPARENT.get(nb["name"], (nb.get("parent"),))[0]
        assert b.get("parent") == parent, ("parent", nb["name"])
        assert b.get("rotation") == nb.get("rotation"), ("rotation", nb["name"])
        expect = MOVED.get(nb["name"], nb["pivot"])
        assert b["pivot"] == list(expect), ("pivot", nb["name"])
    for name, parent, pivot, _ in EXTRA_BONES:
        assert by[name].get("parent") == parent and by[name]["pivot"] == list(pivot)
        assert by[name].get("rotation") == BIND_ROT.get(name)
    # Steering wheel: hub centred on the pivot; with the bind rotation local +Z points up
    # and back along the column towards the driver.
    sw = by["grad_steering_wheel"]
    hub0 = np.add(sw["cubes"][0]["origin"], np.array(sw["cubes"][0]["size"]) / 2)
    assert np.allclose(hub0, sw["pivot"], atol=0.05), hub0
    assert sw["name"] not in subtree(bones, "grad_fp_hide")
    zax = K.rot_matrix(sw["rotation"]) @ [0, 0, 1]
    assert zax[1] > 0.5 and zax[2] > 0.3 and abs(zax[0]) < 1e-9, zax
    assert by["wheelL0Turn"]["pivot"] == by["wheelL0"]["pivot"]
    assert not by["wheelL0"].get("cubes") and not by["wheelR0"].get("cubes"), "front wheels spin twice"
    assert by["wheelR0Turn"]["pivot"] == by["wheelR0"]["pivot"]
    corners = np.array([[i, j, k] for i in (0, 1) for j in (0, 1) for k in (0, 1)], float)
    for jack in ("grad_jack_l", "grad_jack_r"):   # stowed pad clear, travel lands it on y=0
        low = min(((corners * c["size"] + c["origin"] - c.get("pivot", 0))
                   @ K.rot_matrix(c.get("rotation")).T + c.get("pivot", 0))[:, 1].min()
                  for c in by[jack]["cubes"])
        assert abs(low - JACK_TRAVEL) < 0.07 and JACK_TRAVEL > 4, (jack, low)
    for b in bones:
        if b.get("cubes"):
            assert b["name"] in GEOMETRY_BONES, ("geometry on unexpected bone", b["name"])
    for n in subtree(bones, "tube") | {"barrelBase"}:
        assert not by[n].get("cubes"), ("tube/barrelBase must stay empty", n)
    tw, th = g["description"]["texture_width"], g["description"]["texture_height"]
    assert (tw, th) == tex.size and tw & (tw - 1) == 0
    alpha = np.asarray(tex)[..., 3]
    cubes = [c for b in bones for c in b.get("cubes", [])]
    for c in cubes:
        assert all(math.isfinite(v) for v in c["origin"] + c["size"] + c.get("rotation", []))
        for f in c["uv"].values():
            (u, v), (w, h) = f["uv"], f["uv_size"]
            assert 0 <= u and u + w <= tw and 0 <= v and v + h <= th, f
            assert alpha[v:v + h, u:u + w].min() == 255, ("UV on empty texels", f)
    # Wheels turn about their bone pivots: the tyre (first cube) is centred on the hub.
    for side in "LR":
        for i in AXLES:
            b = by[wheel_bone(side, i)]
            first = b["cubes"][0]
            centre = np.add(first["origin"], np.array(first["size"]) / 2)
            assert np.allclose(centre, b["pivot"], atol=0.05), (b["name"], centre)
    pts = np.concatenate([q for q, _ in geo_quads(data)])
    lo, hi = pts.min(0), pts.max(0)
    return {"cubes": len(cubes), "rotated_cubes": sum("rotation" in c for c in cubes),
            "faces": sum(len(c["uv"]) for c in cubes), "texture": [tw, th],
            "bounds_units": [lo.round(2).tolist(), hi.round(2).tolist()],
            "bounds_blocks": ((hi - lo) / 16).round(3).tolist()}


def steering_clearance(t, limit=45):
    """Largest front-wheel lock (degrees, each way) before a steered front tyre or its
    hub parts first enter a fixed body cube."""
    body = [c for c in t.cubes if c.bone == "body" and min(c.size) > 0.05 and any(True for _ in c.faces())]
    out = {}
    for side, s in (("L", 1), ("R", -1)):
        pts = []
        for c in t.cubes:
            if c.bone != wheel_bone(side, 0):
                continue
            for face, corner, sv, tv, n in c.faces():
                g = np.linspace(0.02, 0.98, 6)
                ss, tt = np.meshgrid(g, g)
                pts.append(c.world(corner + ss.reshape(-1, 1) * sv + tt.reshape(-1, 1) * tv))
        pts = np.concatenate(pts)
        hub = np.array([s * WHEEL_X, WHEEL_R, AXLES[0]])
        for sign in (1, -1):
            clear = 0
            for deg in range(1, limit + 1):
                p = (pts - hub) @ axis_rot("y", sign * deg).T + hub
                lo, hi = p.min(0), p.max(0)
                hit = False
                for c in body:
                    a, b = cull._aabb(c)
                    if np.any(a > hi) or np.any(b < lo):
                        continue
                    if cull._inside(c, p, tol=0.05).any():
                        hit = True
                        break
                if hit:
                    break
                clear = deg
            out[f"{side}{'+' if sign > 0 else '-'}"] = clear
    return out


def main():
    skeleton, source = native_skeleton()
    t, hidden, zleft = build()
    steer = steering_clearance(t)
    tex = atlas([c for c in t.cubes if any(True for _ in c.faces())])
    data = geo(skeleton, t.cubes, tex.width)
    metrics = validate(data, skeleton, tex)
    OUT["geo"].parent.mkdir(parents=True, exist_ok=True)
    OUT["geo"].write_text(json.dumps(data, separators=(",", ":")) + "\n")
    for key, im in (("texture", tex), ("container", container_icon(data, tex)),
                    ("icon", side_icon(data, tex))):
        OUT[key].parent.mkdir(parents=True, exist_ok=True)
        im.save(OUT[key], optimize=True)
    if "--preview" in sys.argv:
        previews(data, tex, Path(sys.argv[sys.argv.index("--preview") + 1]))
    metrics.update(skeleton_source=source, authored_cubes=len(t.cubes), hidden_faces=hidden,
                   coplanar_pairs_left=zleft, moved_pivots=MOVED,
                   muzzle_face_centre=[0, TUBE_Y0 + 2 * TUBE_PITCH, PACK_Z[0]],
                   tube_field_wh=[10 * TUBE_PITCH, 4 * TUBE_PITCH],
                   driver_seat=DRIVER_SEAT, crew_seat=CREW_SEAT, driver_eye=DRIVER_EYE,
                   gunner_cam_barrel_local=GUNNER_CAM,
                   gunner_cam_model=list(np.add(PACK_PIVOT, GUNNER_CAM).tolist()),
                   jack_travel_down=JACK_TRAVEL, steering_clear_deg=steer,
                   extra_bones=[dict({"name": n, "parent": p, "pivot": v},
                                     **({"rotation": BIND_ROT[n]} if n in BIND_ROT else {}))
                                for n, p, v, _ in EXTRA_BONES])
    print(json.dumps(metrics, indent=1))


if __name__ == "__main__":
    main()
