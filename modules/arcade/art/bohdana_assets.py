#!/usr/bin/env python3
"""Original low-poly cube art for the wheeled 6x6 155 mm howitzer `bohdana`.

Python 3 + Pillow + NumPy. Deterministic; run from any directory:

    python3 modules/arcade/art/bohdana_assets.py [--preview DIR] [--jar SBW_JAR]

Writes the Bedrock geometry, its texture atlas and two vehicle icons under
src/main/resources/assets/frontier_arcade_vehicles. No source mesh or texture is
imported: every cube and every pixel is produced here. (With --preview the SBW
PLZ-05 is read from the jar only to render a scale comparison; nothing of it is
written to resources.)

The entity reuses Superb Warfare's PLZ-05 renderer and animations, which drive
bones by name. The full PLZ-05 skeleton (names, parents, pivots, rotations) is
embedded below and kept unchanged, except for the pivots the art contract lets
move: turret, barrelBase/barrel/tube and the six used wheel bones. All visible
cubes hang under the bone that must move them. Track bones, unused wheel bones
and the PLZ travel-lock bones (paojia, zhuzi, paojia2, shensuo) stay empty:
their pivots sit at the front bumper, so geometry hung on them would swing
through the vehicle when the lock animation plays.

Geometry is in Bedrock file coordinates: 16 units = 1 block, +Y up, front -Z.
In game (Java) the X axis is mirrored, so the "L" bones (+X) end up on the
vehicle's left when looking forward.
"""
from __future__ import annotations

import argparse
import io
import json
import math
from pathlib import Path
import sys
import zipfile

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
sys.path.insert(0, str(REPO / "modules/style/art"))
from generate_assets import FACES, FACE_CORNERS, font  # noqa: E402

ASSETS = HERE.parent / "src/main/resources/assets/frontier_arcade_vehicles"
GEO_PATH = ASSETS / "models/bedrock/vehicle/bohdana.geo.json"
TEX_PATH = ASSETS / "textures/bedrock/vehicle/bohdana.png"
ICON_CONTAINER = ASSETS / "textures/vehicle_icon/container/bohdana.png"
ICON_SIDE = ASSETS / "textures/vehicle_icon/bohdana_icon.png"
SBW_JAR = REPO / "play-tablet/mods/superbwarfare-0.8.9.1-hotfix-mc1.20.1-993063bed-all.jar"
PLZ_GEO = "assets/superbwarfare/models/bedrock/vehicle/plz_05.geo.json"
PLZ_TEX = "assets/superbwarfare/textures/bedrock/vehicle/plz_05.png"

# --------------------------------------------------------------------------
# Layout (model units). Everything below is derived from these numbers.
TURRET_PIVOT = [0, 28, 41]       # turntable centre over the rear tandem
GUN_PIVOT = [0, 63, 38]          # trunnions; the tube clears the cab roof
MUZZLE_Z = -114.0
HUB_Y = 10.6                     # tread lugs touch y = 0
TYRE_R, TYRE_X, TYRE_W = 9.9, 22.5, 8.0
AXLES = {"0": -62.0, "3": 30.0, "6": 53.0}   # wheel bone index -> hub z
MOVABLE = {"turret", "barrelBase", "barrel", "tube"} | {
    f"wheel{s}{i}" for s in "LR" for i in AXLES}
STEER = {"wheelL0Turn", "wheelR0Turn"}         # new steering bones (SBW name rule)
WHEEL_GEO_BONE = {f"wheel{s}{i}": f"wheel{s}{i}Turn" if i == "0" else f"wheel{s}{i}"
                  for s in "LR" for i in AXLES}
SPADE_HINGE = [12, 14, 87]       # bh_spade_l pivot; bh_spade_r mirrors x
WINDSCREEN_LOCAL = (2.5, 12.0)   # opening along the sloped frame, units above its hinge
SIDE_WINDOW_LOCAL = (3.5, 10.5)
DRIVER_EYE = [12, 46, -73]       # recommended first-person eye (model coordinates)
GUNNER_CAM = [7, 7, -30]         # recommended gunner camera, relative to GUN_PIVOT
# Bone-name patterns SBW treats specially (wheel spin, tracks, flare, dog tag).
RESERVED_PREFIXES = ("wheel", "w_", "shell", "track", "flare", "laser")
TEX_LIMIT = 512

# --------------------------------------------------------------------------
# PLZ-05 skeleton from Superb Warfare 0.8.9.1 (names, parents, pivots, rotation).
SKELETON = [
    ("root", None, [0, 0, 0]),
    ("Wheel", "root", [-0.7488, 14.04, -15.7248]),
    ("WheelL", "Wheel", [30.9816, 16.2864, -50.9184]),
    ("wheelL0", "WheelL", [28.9224, 14.508, -77.8752]),
    ("wheelL01", "wheelL0", [32.1048, 14.508, -77.8752]),
    ("wheelL02", "wheelL0", [25.9272, 14.508, -77.8752]),
    ("wheelL1", "WheelL", [28.9224, 7.3008, -54.288]),
    ("wheelL11", "wheelL1", [30.9816, 7.3008, -54.288]),
    ("wheelL12", "wheelL1", [24.804, 7.3008, -54.288]),
    ("wheelL2", "WheelL", [28.9224, 7.3008, -34.632]),
    ("wheelL21", "wheelL2", [30.9816, 7.3008, -34.632]),
    ("wheelL22", "wheelL2", [19.188, 7.3008, -34.632]),
    ("wheelL3", "WheelL", [28.9224, 7.3008, -17.784]),
    ("wheelL31", "wheelL3", [30.9816, 7.3008, -17.784]),
    ("wheelL32", "wheelL3", [24.804, 7.3008, -17.784]),
    ("wheelL4", "WheelL", [28.9224, 7.3008, -0.936]),
    ("wheelL41", "wheelL4", [30.9816, 7.3008, -0.936]),
    ("wheelL42", "wheelL4", [24.804, 7.3008, -0.936]),
    ("wheelL5", "WheelL", [28.9224, 7.3008, 14.976]),
    ("wheelL51", "wheelL5", [30.9816, 7.3008, 14.976]),
    ("wheelL52", "wheelL5", [24.804, 7.3008, 14.976]),
    ("wheelL6", "WheelL", [28.9224, 7.3008, 36.504]),
    ("wheelL61", "wheelL6", [30.9816, 7.3008, 36.504]),
    ("wheelL62", "wheelL6", [24.804, 7.3008, 36.504]),
    ("wheelL7", "WheelL", [28.9224, 13.66195, 50.9184]),
    ("wheelL71", "wheelL7", [32.19121, 13.66193, 50.9184]),
    ("wheelL72", "wheelL7", [26.01361, 13.66193, 50.9184]),
    ("smallwhlL", "Wheel", [0, -0.936, 0]),
    ("smallwhlL1", "smallwhlL", [27.4248, 17.5968, -40.248]),
    ("smallwhlL2", "smallwhlL", [27.4248, 17.5968, -8.424]),
    ("smallwhlL3", "smallwhlL", [27.4248, 17.5968, 24.336]),
    ("WheelR", "Wheel", [-30.9816, 16.2864, -50.9184]),
    ("wheelR0", "WheelR", [-28.9224, 14.508, -77.8752]),
    ("wheelR01", "wheelR0", [-32.1048, 14.508, -77.8752]),
    ("wheelR02", "wheelR0", [-25.9272, 14.508, -77.8752]),
    ("wheelR1", "WheelR", [-28.9224, 7.3008, -54.288]),
    ("wheelR11", "wheelR1", [-30.9816, 7.3008, -54.288]),
    ("wheelR12", "wheelR1", [-24.804, 7.3008, -54.288]),
    ("wheelR2", "WheelR", [-28.9224, 7.3008, -34.632]),
    ("wheelR21", "wheelR2", [-30.9816, 7.3008, -34.632]),
    ("wheelR22", "wheelR2", [-19.188, 7.3008, -34.632]),
    ("wheelR3", "WheelR", [-28.9224, 7.3008, -17.784]),
    ("wheelR31", "wheelR3", [-30.9816, 7.3008, -17.784]),
    ("wheelR32", "wheelR3", [-24.804, 7.3008, -17.784]),
    ("wheelR4", "WheelR", [-28.9224, 7.3008, -0.936]),
    ("wheelR41", "wheelR4", [-30.9816, 7.3008, -0.936]),
    ("wheelR42", "wheelR4", [-24.804, 7.3008, -0.936]),
    ("wheelR5", "WheelR", [-28.9224, 7.3008, 14.976]),
    ("wheelR51", "wheelR5", [-30.9816, 7.3008, 14.976]),
    ("wheelR52", "wheelR5", [-24.804, 7.3008, 14.976]),
    ("wheelR6", "WheelR", [-28.9224, 7.3008, 36.504]),
    ("wheelR61", "wheelR6", [-30.9816, 7.3008, 36.504]),
    ("wheelR62", "wheelR6", [-24.804, 7.3008, 36.504]),
    ("wheelR7", "WheelR", [-28.9224, 13.66195, 50.9184]),
    ("wheelR71", "wheelR7", [-32.19119, 13.66193, 50.9184]),
    ("wheelR72", "wheelR7", [-26.01359, 13.66193, 50.9184]),
    ("smallwhlR", "Wheel", [0, -0.936, 0]),
    ("smallwhlR1", "smallwhlR", [-27.4248, 17.5968, -40.248]),
    ("smallwhlR2", "smallwhlR", [-27.4248, 17.5968, -8.424]),
    ("smallwhlR3", "smallwhlR", [-27.4248, 17.5968, 24.336]),
    ("TrackL", "root", [31.824, 22.39958, 51.40027]),
    ("bone21", "TrackL", [31.824, 18.34122, -56.42234]),
    ("#tracks", 'L', [29.016, 20.2706, -80.5345]),
    ("TrackR", "root", [-31.824, 22.39958, 51.40027]),
    ("bone2", "TrackR", [-31.824, 18.34122, -56.42234]),
    ("#tracks", 'R', [-29.016, 20.2706, -80.5345]),
    ("base", "root", [0, 0, 0]),
    ("turret", "base", [0, 30.888, 14.04]),
    ("barrelBase", "turret", [0, 44.928, -1.404]),
    ("barrel", "barrelBase", [0, 44.928, -1.404]),
    ("xroll1", "barrel", [0, 44.928, -1.404]),
    ("xroll2", "barrel", [7.488, 44.928, -1.404]),
    ("xroll3", "barrel", [-7.488, 44.928, -1.404]),
    ("bone5", "barrel", [0, 44.928, -40.248]),
    ("bone6", "barrel", [0, 44.928, -40.71319]),
    ("bone7", "barrel", [0, 44.928, -43.05319]),
    ("tube", "barrel", [0, 44.928, -1.404]),
    ("bone17", "tube", [0, 44.928, -57.56119]),
    ("bone8", "tube", [0, 44.928, -138.99319]),
    ("bone9", "tube", [0, 44.928, -97.34119]),
    ("bone10", "tube", [0, 44.928, -157.24519]),
    ("bone11", "tube", [0, 44.928, -156.30919]),
    ("bone12", "tube", [0, 44.928, -162.38945]),
    ("bone14", "bone12", [0, 44.928, -166.608]),
    ("bone13", "bone12", [0, 44.928, -167.076]),
    ("bone15", "bone12", [0, 44.928, -166.608]),
    ("bone16", "bone12", [0, 44.928, -166.608]),
    ("bone19", "barrel", [0, -0.936, 0]),
    ("bone20", "bone19", [-2.9952, 50.4504, -42.49159]),
    ("bone23", "bone19", [-2.9952, 50.4504, -47.17159]),
    ("bone22", "bone19", [2.9952, 50.4504, -43.42759]),
    ("bone24", "bone19", [3.3696, 56.0664, -36.4104]),
    ("yroll", "turret", [0, 30.888, 14.04]),
    ("trtbody", "turret", [21.762, 36.97793, -12.62685]),
    ("trtitems", "turret", [0, 30.888, 14.04]),
    ("tifront", "trtitems", [-18.486, 35.05947, -17.08822]),
    ("tifrontdoor", "tifront", [-18.486, 35.05947, -17.08822]),
    ("smoke", "tifront", [-24.336, 48.8814, -19.3425]),
    ("bone36", "tifront", [18.486, 49.7838, -13.4175]),
    ("tifchuang", "tifront", [-18.486, 35.05947, -17.08822]),
    ("tileft", "trtitems", [29.75271, 42.07919, 21.06]),
    ("menchuang", "tileft", [3.0888, 55.5048, 50.076]),
    ("tidoorL1", "menchuang", [30.3387, 48.05926, -11.29842]),
    ("tidoorL2", "menchuang", [29.96428, 50.77366, 20.00324]),
    ("tidoorL3", "menchuang", [30.0909, 44.95267, 29.18963]),
    ("tiright", "trtitems", [29.75271, 42.07919, 21.06]),
    ("menchuang2", "tiright", [3.0888, 55.5048, 50.076]),
    ("tidoorR1", "menchuang2", [30.3387, 48.05926, -11.29842]),
    ("tidoorR2", "menchuang2", [29.96428, 50.77366, 20.00324]),
    ("tiback", "trtitems", [0, 30.888, 14.04]),
    ("huojia", "tiback", [0, 54.1008, 59.67]),
    ("bone26", "huojia", [0, 54.1944, 59.67]),
    ("bone28", "bone26", [6.786, 52.0884, 60.138]),
    ("bone29", "bone26", [-6.786, 52.0884, 60.138]),
    ("bone30", "bone26", [0, 54.1944, 59.67]),
    ("bone31", "bone26", [0, 54.1944, 59.67]),
    ("tiback1", "tiback", [25.9272, 44.93081, 56.0664]),
    ("tiback2", "tiback", [0, 30.888, 14.04]),
    ("bone25", "tiback2", [-27.4248, 42.07528, 57.6576]),
    ("bone27", "tiback2", [-27.4248, 56.11528, 57.6576]),
    ("tibackdoor", "tiback", [0, 30.888, 14.04]),
    ("bone32", "tibackdoor", [0, 30.888, 14.04]),
    ("bone33", "tibackdoor", [-9.594, 30.888, 14.04]),
    ("bone34", "tibackdoor", [0, 30.888, 14.04]),
    ("bone35", "tibackdoor", [9.594, 30.888, 14.04]),
    ("titop", "trtitems", [0, -4.212, -15.912]),
    ("move_titop1", "titop", [0, -0.936, 0]),
    ("bone37", "move_titop1", [73.476, 89.81128, -77.9688]),
    ("bone39", "move_titop1", [18.4392, 58.22201, -4.9608]),
    ("bone38", "move_titop1", [73.476, 104.03848, -77.9688]),
    ("titop2", "titop", [0.1872, -4.212, -16.848]),
    ("bone41", "titop2", [-73.2888, 89.81128, -78.9048]),
    ("bone40", "titop2", [-18.252, 58.69001, -5.8968]),
    ("titop3", "titop", [0, -4.212, -18.72]),
    ("bone43", "titop3", [-15.912, 57.56681, 11.8872]),
    ("bone44", "titop3", [-15.912, 57.80081, 11.8872]),
    ("bone45", "titop3", [-15.912, 58.73681, 11.8872]),
    ("titop4", "titop", [-19.188, 57.0024, 23.03711]),
    ("titop5", "titop", [18.4392, 59.85636, 28.314]),
    ("bone46", "titop5", [18.4392, 57.19241, 22.1832]),
    ("bone47", "titop5", [18.4392, 59.06441, 22.1832]),
    ("titop6", "titop", [30.9816, -4.212, -21.06]),
    ("bone48", "titop6", [15.0696, 57.56681, 9.5472]),
    ("bone49", "titop6", [15.0696, 57.80081, 9.5472]),
    ("bone50", "titop6", [15.0696, 58.73681, 9.5472]),
    ("titop7", "titop", [0.0936, 57.33281, 51.6672]),
    ("titoplight", "titop", [-4.68, 59.904, 52.416]),
    ("titop8", "titop", [-14.73867, 63.6948, 51.9948]),
    ("body_left_dogTag_10x10", "turret", [31.18667, 42.60435, -4.72611], [0, -90, -7]),
    ("body_right_dogTag_10x10", "turret", [-31.18667, 42.60435, -4.72611], [0, 90, 7]),
    ("body", "base", [0, 12.12891, -82.43603]),
    ("paojia", "body", [0, 20.87326, -85.1823]),
    ("zhuzi", "paojia", [0.00334, 32.31391, -89.11153]),
    ("shensuo", "zhuzi", [0.00334, 32.12987, -87.71359], [84.6815, 5.2957, 44.7539]),
    ("paojia2", "body", [0, 27.79862, -86.38562]),
    ("main", "body", [0, 12.12891, -82.43603]),
    ("bottom", "body", [0, 12.12891, -82.43603]),
    ("hang1", "bottom", [23.0256, 11.0448, -59.904]),
    ("hang2", "bottom", [23.0256, 11.0448, -40.248]),
    ("hang3", "bottom", [23.0256, 11.0448, -23.4]),
    ("hang4", "bottom", [23.0256, 11.0448, -6.552]),
    ("hang5", "bottom", [23.0256, 11.0448, 9.36]),
    ("hang6", "bottom", [23.0256, 11.0448, 30.888]),
    ("frontrL", "body", [20.9664, 13.572, -78.8112]),
    ("frontrR", "body", [-20.9664, 13.572, -78.8112]),
    ("sidearmorL", "body", [35.334, 16.43425, -80.45635]),
    ("sidearmorR", "body", [-35.334, 16.43425, -80.45635]),
    ("items", "body", [0, 12.12891, -82.43603]),
    ("engine", "items", [-20.124, 32.994, -40.248]),
    ("ctd2", "items", [13.53688, 32.8536, -32.02861]),
    ("bone3", "ctd2", [14.976, 27.7056, -47.06929]),
    ("bone18", "bone3", [11.2788, 31.68141, -48.03052]),
    ("bone4", "bone3", [15.0696, 27.7056, -47.06929]),
    ("mifront1", "items", [-11.7, 31.54045, -49.12785]),
    ("mifront2", "items", [0, 12.12891, -82.43603]),
    ("flightL", "items", [0, -1.872, 0]),
    ("flightR", "items", [0, -1.872, 0]),
    ("mdoorL", "items", [33.9008, 21.46007, -30.42]),
    ("mdoorB", "items", [-0.936, 12.12891, -82.43603]),
    ("miback1", "items", [-7.254, 23.4, 54.52481]),
    ("blightL", "items", [0, 12.12891, 56.09197]),
    ("blightR", "items", [0, 12.12891, 56.09197]),
    ("backup", "items", [-15.444, 6.552, 134.316]),
    ("miback", "items", [-19.59314, 25.58022, 54.99]),
]
DOG_TAG_CUBES = {   # zero-size placeholder cubes the native dog-tag code reads
    "body_left_dogTag_10x10": {"origin": [26.18667, 37.60435, -4.72611], "size": [10, 10, 0],
                               "uv": {"north": {"uv": [0, 0], "uv_size": [0, 0]}}},
    "body_right_dogTag_10x10": {"origin": [-36.18667, 37.60435, -4.72611], "size": [10, 10, 0],
                                "uv": {"north": {"uv": [0, 0], "uv_size": [0, 0]}}},
}


def skeleton_rows():
    for row in SKELETON:
        if row[0] == "#tracks":
            _, side, pivot = row
            for i in range(50):
                yield (f"trackMov{side}{i}", f"Track{side}", list(pivot))
                yield (f"trackRot{side}{i}", f"trackMov{side}{i}", list(pivot))
        else:
            yield row


# --------------------------------------------------------------------------
# Rotation convention. Bedrock file angles (rx, ry, rz) act as
# Rz(-rz) . Ry(ry) . Rx(-rx) (right-handed, file coordinates): GeckoLib and
# Blockbench negate X and Y and mirror X. Checked against the PLZ-05 glacis
# (rx = +12 lowers its front edge) and its dog-tag planes.
def rot_matrix(rot):
    rx, ry, rz = (math.radians(a) for a in rot)
    ax, ay, az = -rx, ry, -rz
    Rx = np.array([[1, 0, 0], [0, math.cos(ax), -math.sin(ax)], [0, math.sin(ax), math.cos(ax)]])
    Ry = np.array([[math.cos(ay), 0, math.sin(ay)], [0, 1, 0], [-math.sin(ay), 0, math.cos(ay)]])
    Rz = np.array([[math.cos(az), -math.sin(az), 0], [math.sin(az), math.cos(az), 0], [0, 0, 1]])
    return Rz @ Ry @ Rx


def R(axis, deg):
    """File rotation for a right-handed turn of `deg` about +axis."""
    return {"x": [-deg, 0, 0], "y": [0, deg, 0], "z": [0, 0, -deg]}[axis]


SWAP = {"east": "west", "west": "east"}


# --------------------------------------------------------------------------
class Geo:
    def __init__(self):
        self.bones, self.by = [], {}
        self.groups = 0
        for row in skeleton_rows():
            name, parent, pivot = row[0], row[1], list(row[2])
            bone = {"name": name}
            if parent:
                bone["parent"] = parent
            bone["pivot"] = pivot
            if len(row) > 3:
                bone["rotation"] = list(row[3])
            self.bones.append(bone)
            self.by[name] = bone
        for name, cube in DOG_TAG_CUBES.items():
            self.by[name]["cubes"] = [json.loads(json.dumps(cube))]
        self.by["turret"]["pivot"] = list(TURRET_PIVOT)
        for name in ("barrelBase", "barrel", "tube"):
            self.by[name]["pivot"] = list(GUN_PIVOT)
        for side, sign in (("L", 1), ("R", -1)):
            for idx, z in AXLES.items():
                self.by[f"wheel{side}{idx}"]["pivot"] = [sign * TYRE_X, HUB_Y, z]
        # Steering: SBW yaws (rudder) and spins bones named wheel?0Turn. The front
        # wheel geometry sits on them; wheel?0 becomes an empty child (it would
        # spin a second time), keeping its pivot. Inserted before wheel?0 so that
        # parents precede children in the bone list.
        for side in "LR":
            child = self.by[f"wheel{side}0"]
            turn = {"name": f"wheel{side}0Turn", "parent": f"Wheel{side}",
                    "pivot": list(child["pivot"])}
            self.bones.insert(self.bones.index(child), turn)
            self.by[turn["name"]] = turn
            child["parent"] = turn["name"]

    def bone(self, name, parent, pivot=None):
        assert name not in self.by and not name.startswith(RESERVED_PREFIXES), name
        bone = {"name": name, "parent": parent,
                "pivot": list(pivot if pivot is not None else self.by[parent]["pivot"])}
        self.bones.append(bone)
        self.by[name] = bone
        return name

    def cube(self, bone, origin, size, mat="camo", faces=None, rot=None, pivot=None,
             key=None, mirror=False):
        mats = {f: mat for f in FACES}
        if faces:
            mats.update(faces)
        c = {"origin": [round(float(v), 4) for v in origin],
             "size": [round(float(v), 4) for v in size], "_mat": mats,
             "_key": key, "_mirror": mirror}
        if rot is not None and any(rot):
            c["pivot"] = [round(float(v), 4) for v in pivot]
            c["rotation"] = [round(float(v), 4) for v in rot]
        self.by[bone].setdefault("cubes", []).append(c)
        return c

    @staticmethod
    def mirrored(origin, size, faces, rot, pivot):
        mo = [-(origin[0] + size[0]), origin[1], origin[2]]
        mf = {SWAP.get(k, k): v for k, v in faces.items()} if faces else None
        mr = mp = None
        if rot is not None:
            mr, mp = [rot[0], -rot[1], -rot[2]], [-pivot[0], pivot[1], pivot[2]]
        return mo, mf, mr, mp

    def sym(self, bone, origin, size, mat="camo", faces=None, rot=None, pivot=None, bone2=None):
        """Cube on the +X side and its mirror (optionally on another bone);
        both share one texture region."""
        self.groups += 1
        key = ("sym", self.groups)
        self.cube(bone, origin, size, mat, faces, rot, pivot, key)
        mo, mf, mr, mp = self.mirrored(origin, size, faces, rot, pivot)
        self.cube(bone2 or bone, mo, size, mat, mf, mr, mp, key, mirror=True)

    def ngon(self, bone, center, axis, radius, length, mat, n=8, ends=None, sym=False):
        """Regular n-gon prism (n = 8 or 12) from n/2 overlapping bands.

        Overlapping coplanar end faces are inset by a hair and painted from
        model position only, so they cannot z-fight."""
        step = 360 / n
        ra = radius * math.cos(math.radians(step / 2))
        e = 2 * ra * math.tan(math.radians(step / 2))
        add = self.sym if sym else self.cube
        for k in range(n // 2):
            ln = length - 0.04 * k
            if axis == "x":
                size, faces = [ln, 2 * ra, e], ({"east": ends, "west": ends} if ends else None)
            elif axis == "y":
                size, faces = [e, ln, 2 * ra], ({"up": ends, "down": ends} if ends else None)
            else:
                size, faces = [2 * ra, e, ln], ({"north": ends, "south": ends} if ends else None)
            origin = [c - s / 2 for c, s in zip(center, size)]
            add(bone, origin, size, mat, faces, R(axis, k * step) if k else None, center)

    def cubes(self):
        for bone in self.bones:
            for c in bone.get("cubes", []):
                yield bone, c


# --------------------------------------------------------------------------
def wheel(g, bone, sign, z):
    """One road wheel: 12-gon tyre, 32 staggered tread lugs, rim, bolts, cap.

    Cubes are centred on the wheel bone's pivot. Every wheel shares one set of
    texture regions (right wheels use them mirrored)."""
    hx = sign * TYRE_X
    hub = (hx, HUB_Y, z)
    cubes = []

    def add(origin, size, mat, faces=None, rot=None):
        cubes.append((origin, size, mat, faces, rot))

    step = 30
    ra = TYRE_R * math.cos(math.radians(15))
    e = 2 * ra * math.tan(math.radians(15))
    x0 = TYRE_X - TYRE_W / 2
    for k in range(6):
        ln = TYRE_W - 0.04 * k
        add([TYRE_X - ln / 2, HUB_Y - ra, z - e / 2], [ln, 2 * ra, e], ("tread", "core"),
            {"east": "sidewall", "west": "sidewall"}, R("x", k * step) if k else None)
    for k in range(16):           # chevron tread: two staggered lug rows
        for xa, phase in ((x0 + 0.25, 0.0), (TYRE_X + 0.35, 11.25)):
            ang = k * 22.5 + phase
            add([xa, HUB_Y + ra - 0.4, z - 1.3], [3.4, 1.45, 2.6], ("tread", "lug"), None,
                R("x", ang) if ang else None)
    ro = TYRE_X + TYRE_W / 2
    rr = 6.2 * math.cos(math.radians(15))
    re = 2 * rr * math.tan(math.radians(15))
    for k in range(6):            # rim proud of the sidewall
        add([ro - 0.5, HUB_Y - rr, z - re / 2], [0.9 - 0.02 * k, 2 * rr, re], "hub",
            None, R("x", k * 30) if k else None)
    hr = 3.0 * math.cos(math.radians(22.5))
    he = 2 * hr * math.tan(math.radians(22.5))
    for k in range(4):
        add([ro + 0.4, HUB_Y - hr, z - he / 2], [0.9 - 0.02 * k, 2 * hr, he], "metal_dark_flat",
            None, R("x", k * 45) if k else None)
    for k in range(8):
        add([ro + 0.4, HUB_Y + 4.0, z - 0.45], [0.5, 0.9, 0.9], "metal", None,
            R("x", k * 45 + 22.5))
    add([ro + 1.3, HUB_Y - 0.8, z - 0.8], [0.5, 1.6, 1.6], "metal")
    for i, (origin, size, mat, faces, rot) in enumerate(cubes):
        key = ("wheel", i)
        if sign > 0:
            g.cube(bone, origin, size, mat, faces, rot, hub, key)
        else:
            mo, mf, mr, mp = Geo.mirrored(origin, size, faces, rot, (TYRE_X, HUB_Y, z))
            g.cube(bone, mo, size, mat, mf, mr, mp, key, mirror=True)


def fender_arc(g, bone, z, lo=-70, hi=70, step=20):
    """Wheel-arch mudguard as a fan of plates around the hub (both sides)."""
    for ang in range(lo, hi + 1, step):
        g.sym(bone, [TYRE_X - 4.3, HUB_Y + 11.2, z - 2.2], [8.6, 0.8, 4.4], "camo",
              rot=R("x", ang) if ang else None, pivot=[TYRE_X, HUB_Y, z])


def build():
    g = Geo()
    chassis = g.bone("bh_chassis", "body")
    cab = g.bone("bh_cab", "body")
    mid = g.bone("bh_midbody", "body")
    deck = g.bone("bh_platform", "body")
    spades = g.bone("bh_spades", "body")
    spade_l = g.bone("bh_spade_l", "body", SPADE_HINGE)
    spade_r = g.bone("bh_spade_r", "body", [-SPADE_HINGE[0], SPADE_HINGE[1], SPADE_HINGE[2]])
    fp_hide = g.bone("bh_fp_hide", "body")
    rest = g.bone("bh_travel_rest", "body")
    mount = g.bone("bh_mount", "turret")
    cradle = g.bone("bh_cradle", "barrel")
    tube = g.bone("bh_tube", "tube")
    brake = g.bone("bh_muzzle_brake", "tube")

    # --- wheels ------------------------------------------------------------------
    for side, sign in (("L", 1), ("R", -1)):
        for idx, z in AXLES.items():
            wheel(g, WHEEL_GEO_BONE[f"wheel{side}{idx}"], sign, z)

    # --- chassis, axles, bumper, lights --------------------------------------------
    g.sym(chassis, [6, 12, -86], [5, 8, 170], "camo_dark")
    for z in (-80, -40, 0, 40, 78):
        g.cube(chassis, [-6, 13, z], [12, 5, 3], "camo_dark")
    for z in AXLES.values():
        g.cube(chassis, [-18.5, HUB_Y - 2, z - 2], [37, 4, 4], "metal_dark")
        g.cube(chassis, [-4, HUB_Y - 4.5, z - 3.5], [8, 8, 7], "metal_dark")
    g.sym(chassis, [13.5, 12, 27], [4, 3, 29], "metal_dark")             # tandem beams
    g.sym(chassis, [13.5, 13, -72], [4, 2.5, 20], "metal_dark")          # front springs
    g.cube(chassis, [-14, 12, -85.8], [28, 9.8, 12.8], "camo_dark")           # engine sump
    g.cube(chassis, [-22, 14, -91], [44, 8, 6], "camo")                  # bumper
    g.sym(chassis, [21.5, 14, -90.5], [5, 8, 5], "camo", rot=R("y", 45), pivot=[24, 18, -88])
    g.sym(chassis, [11, 15, -93], [3, 3, 2], "metal_dark")               # tow hooks
    g.sym(chassis, [11.5, 13.5, -94], [2, 2, 1.5], "metal_dark")
    g.ngon(chassis, (0, 18, -92), "x", 2.2, 12, "metal_dark_flat")       # winch drum
    g.cube(chassis, [-5, 19.5, -91.6], [10, 3, 0.6], "plate")
    g.sym(chassis, [17, 18.5, -92], [6, 4, 1.5], "metal_dark")           # headlights
    g.sym(chassis, [17.6, 19.1, -92.6], [4.8, 2.8, 0.6], "lamp")
    for x in (17.3, 19.75, 22.2):
        g.sym(chassis, [x, 18, -93.6], [0.5, 5, 0.5], "metal_dark")      # lamp guards
    g.sym(chassis, [17, 20.25, -93.75], [6, 0.5, 0.5], "metal_dark")
    g.sym(chassis, [13.5, 19, -91.6], [2, 2, 0.6], "amber")
    fender_arc(g, chassis, AXLES["0"])
    for z in (AXLES["3"], AXLES["6"]):
        fender_arc(g, chassis, z)
    g.sym(chassis, [18.5, 4, -50.6], [8, 14, 0.6], "rubber")             # mudflaps
    g.sym(chassis, [18.5, 5, 64.4], [8, 15, 0.6], "rubber")
    for y in (8, 14):                                                    # cab steps
        g.sym(chassis, [22, y, -48], [4.5, 1, 5], "metal")
    g.sym(chassis, [26.05, 7, -46], [0.6, 8, 0.6], "metal_dark")
    g.ngon(chassis, (-17, 17, -52), "z", 3, 9, "metal_dark_flat")        # muffler

    # --- armoured cab -------------------------------------------------------------------
    g.cube(cab, [-26, 22, -82], [52, 18, 36], "camo")
    g.cube(cab, [-24, 22, -84], [48, 18, 2], "camo")
    s2 = 2 * math.sqrt(2)
    g.sym(cab, [24 - s2 / 2, 22.05, -82 - s2 / 2], [s2, 17.9, s2], "camo", rot=R("y", 45),
          pivot=[24, 31, -82])                                           # front corner bevels
    g.cube(cab, [-13, 24.5, -84.6], [26, 13, 0.6], "black")              # grille
    for k in range(6):
        g.cube(cab, [-12.5, 25.6 + k * 1.95, -85.4], [25, 0.8, 0.8], "camo_dark")
    for y in (24, 37):
        g.cube(cab, [-13.5, y, -85.4], [27, 1, 1], "camo_dark")
    g.sym(cab, [13, 24.05, -85.3], [1, 13.9, 0.9], "camo_dark")
    # Upper cab is a hollow armoured shell so the driver can look out: a sloped
    # windscreen frame with two openings, side plates with window openings, roof
    # and rear wall. Glass and wipers live in bh_fp_hide (hidden in first person).
    slope = math.degrees(math.atan2(6, 13))
    hinge, srot = [0, 40, -84], R("x", slope)
    plen = math.hypot(6, 13) + 0.3                 # local height of the sloped frame
    w0, w1 = WINDSCREEN_LOCAL                      # opening, local y above the hinge
    g.cube(cab, [-24, 40, -84], [48, w0, 2], "camo", rot=srot, pivot=hinge)          # sill
    g.cube(cab, [-24, 40 + w1, -84], [48, plen - w1, 2], "camo", rot=srot, pivot=hinge)
    g.sym(cab, [20, 40 + w0, -84], [4, w1 - w0, 2], "camo", rot=srot, pivot=hinge)  # posts
    g.cube(cab, [-3, 40 + w0, -84], [6, w1 - w0, 2], "camo", rot=srot, pivot=hinge)
    g.sym(fp_hide, [3, 40 + w0, -83.4], [17, w1 - w0, 0.6], "glass", rot=srot, pivot=hinge)
    for o, s in (([3, 40 + w1, -84.6], [17, 0.6, 0.6]), ([3, 40 + w0 - 0.6, -84.6], [17, 0.6, 0.6]),
                 ([2.4, 40 + w0 - 0.6, -84.6], [0.6, w1 - w0 + 1.2, 0.6]),
                 ([20, 40 + w0 - 0.6, -84.6], [0.6, w1 - w0 + 1.2, 0.6])):
        g.sym(cab, o, s, "camo_dark", rot=srot, pivot=hinge)            # window trims
    g.sym(fp_hide, [6, 40 + w0 + 0.3, -84.9], [7, 0.4, 0.4], "black", rot=srot, pivot=hinge)
    g.cube(cab, [-22, 40, -82], [44, 2, 5], "camo_dark")                 # dashboard
    g.cube(cab, [-24, 40, -48], [48, 13, 2], "camo")                     # rear wall
    for x0 in (8, -16):                                                  # seats
        g.cube(cab, [x0, 40, -66], [8, 2, 7], "black")
        g.cube(cab, [x0, 42, -60], [8, 9, 1.5], "black")
    side_rot, side_piv = R("z", 8), [24, 40, -63]
    s0, s1 = SIDE_WINDOW_LOCAL
    for o, s in (([24, 40, -83], [1.6, s0, 36]), ([24, 40 + s1, -83], [1.6, 13.2 - s1, 36]),
                 ([24, 40 + s0, -83], [1.6, s1 - s0, 7]), ([24, 40 + s0, -58], [1.6, s1 - s0, 11])):
        g.sym(cab, o, s, "camo", rot=side_rot, pivot=side_piv)          # side plates
    g.sym(fp_hide, [24.5, 40 + s0, -76], [0.6, s1 - s0, 18], "glass", rot=side_rot, pivot=side_piv)
    for o, s in (([25.5, 40 + s1, -76.6], [0.7, 0.6, 19.2]), ([25.5, 40 + s0 - 0.6, -76.6], [0.7, 0.6, 19.2]),
                 ([25.5, 40 + s0 - 0.6, -76.6], [0.7, s1 - s0 + 1.2, 0.6]),
                 ([25.5, 40 + s0 - 0.6, -58], [0.7, s1 - s0 + 1.2, 0.6])):
        g.sym(cab, o, s, "camo_dark", rot=side_rot, pivot=side_piv)
    g.cube(cab, [-22.5, 53, -77], [45, 1.5, 30], "camo")                 # roof + bevels
    g.sym(cab, [21.4, 51.9, -77], [2.2, 2.2, 30], "camo", rot=R("z", 45), pivot=[22.5, 53, -62])
    g.cube(cab, [-22.5, 51.9, -78.1], [45, 2.2, 2.2], "camo", rot=R("x", 45), pivot=[0, 53, -77])
    g.ngon(cab, (-10, 55.3, -62), "y", 4.5, 1.6, "camo_flat")             # hatch
    g.cube(cab, [-14.8, 54.5, -64], [1.2, 1.2, 4], "metal")
    g.cube(cab, [-8, 56.0, -62.5], [4, 0.6, 1], "metal")
    g.sym(cab, [6, 54.5, -76], [3, 1.6, 2], "metal_dark", {"north": "glass"})  # periscopes
    g.cube(cab, [-22, 54.5, -53], [8, 2.5, 5], "camo", {"up": "grille"})  # filter box
    g.cube(cab, [19.2, 54.5, -49.8], [1.6, 1.5, 1.6], "metal_dark")
    g.cube(cab, [19.7, 56, -49.3], [0.6, 16, 0.6], "black")               # antenna
    g.cube(cab, [-18.5, 54.5, -74.5], [1, 2, 1], "metal")                 # spotlight
    g.cube(cab, [-19.5, 56.5, -75.5], [3, 2.5, 2], "metal_dark", {"north": "lamp"})
    for z in (-72, -56):
        g.sym(cab, [20.5, 54.5, z], [0.6, 1.4, 0.6], "metal")             # roof grab rails
    g.sym(cab, [20.5, 55.9, -72], [0.6, 0.6, 16.6], "metal")
    g.sym(cab, [26, 23, -70], [0.4, 16, 14], "camo")                       # doors
    for y in (25, 35):
        g.sym(cab, [26.2, y, -70.6], [0.6, 2.5, 1.2], "metal_dark")
    g.sym(cab, [26.4, 32, -60], [0.6, 0.8, 3], "metal")
    g.sym(cab, [26.2, 26, -57.2], [0.6, 10, 0.6], "metal")
    g.sym(cab, [24.4, 48, -79.3], [3.5, 0.6, 0.6], "metal")               # mirrors
    g.sym(cab, [27.6, 44, -80.4], [1.0, 5.5, 3.2], "metal_dark", {"south": "glass"})
    g.ngon(cab, (-23, 43, -44.2), "y", 1.1, 28, "metal_dark_flat")        # exhaust
    g.cube(cab, [-24.6, 36, -46], [3.2, 12, 0.5], "grille")
    g.cube(cab, [-23.8, 57, -45], [1.6, 1.2, 2.5], "black")

    # --- mid body: crew/ammunition box, lockers, tanks, travel rest -----------------------
    g.cube(mid, [-19, 22, -46], [38, 23, 39], "camo")
    g.cube(mid, [-16, 45, -42], [32, 0.6, 30], "camo")
    g.sym(mid, [17.8, 43.8, -46], [2.4, 2.4, 39], "camo", rot=R("z", 45), pivot=[19, 45, -26.5])
    for z0 in (-45, -26):
        g.sym(mid, [19, 24, z0], [6.5, 15, 17], "camo")
        g.sym(mid, [18.8, 39, z0 - 0.3], [7, 1.2, 17.6], "camo")
        for dz in (3, 12.4):
            g.sym(mid, [25.5, 35.5, z0 + dz], [0.6, 2.2, 1.6], "metal_dark")
            g.sym(mid, [24.5, 40.2, z0 + dz], [1.2, 0.6, 1.6], "metal_dark")
    g.ngon(mid, (17, 17.5, -36), "z", 4.8, 18, "camo_dark_flat")          # fuel tank
    for z in (-42, -30):
        g.ngon(mid, (17, 17.5, z), "z", 5.1, 1.0, "metal_dark_flat")
    g.cube(mid, [16.2, 22.2, -29], [1.6, 0.8, 1.6], "metal")
    g.ngon(mid, (-16, 16, -38), "z", 3, 14, "camo_dark_flat")             # air tank
    g.cube(mid, [-22, 13, -25], [8, 9, 12], "camo_dark")                  # batteries
    g.cube(mid, [-22.3, 21.6, -25.3], [8.6, 0.8, 12.6], "camo")
    g.sym(rest, [2.5, 45, -26], [1.5, 12, 2], "metal_dark")
    g.cube(rest, [-5, 56, -27], [10, 1.5, 4], "metal_dark")
    g.sym(rest, [4, 57.5, -27], [1.5, 2, 4], "metal_dark")
    g.cube(rest, [-3, 57.5, -26.5], [6, 0.4, 3], "rubber")
    g.sym(rest, [2.6, 46, -30.5], [1.2, 9, 1.2], "metal_dark", rot=R("x", 28),
          pivot=[3.2, 46, -29.9])

    # --- open rear platform -------------------------------------------------------------
    g.cube(deck, [-20, 20, -7], [40, 6, 91], "camo_dark")
    g.cube(deck, [-26.5, 26, -7], [53, 2, 87], "camo")
    g.sym(deck, [10, 28, -4], [14, 0.3, 20], "treadplate")
    g.cube(deck, [-6, 28, 62], [12, 0.3, 16], "treadplate")
    g.sym(deck, [18, 22.6, 17], [9, 3.4, 49], "camo")                     # mudguard tops
    g.sym(deck, [19, 15, -4], [7, 11, 18], "camo")                       # side lockers
    g.sym(deck, [25.8, 22, 1], [0.6, 2, 2], "metal_dark")
    g.sym(deck, [25.8, 22, 10], [0.6, 2, 2], "metal_dark")
    g.sym(deck, [13, 16, 66], [11, 10, 17.8], "camo_dark")
    g.cube(deck, [-20, 14, 82], [40, 5, 3], "camo_dark")                 # rear bumper
    g.sym(deck, [18.5, 21.5, 84], [5, 4, 1], "metal_dark")               # tail lights
    g.sym(deck, [19, 22, 84.6], [2, 3, 0.6], "tail")
    g.sym(deck, [21.3, 22, 84.6], [1.8, 3, 0.6], "lamp")
    g.sym(deck, [18.5, 25.6, 84], [5, 0.5, 2], "metal_dark")
    g.sym(deck, [25.5, 28, -5], [1, 3, 17], "camo")                      # side boards
    g.sym(deck, [25.5, 28, 66], [1, 3, 14], "camo")
    for z in (-5.4, 11.4, 65.6, 79.4):
        g.sym(deck, [25.3, 28, z], [1.4, 4, 1], "metal")
    for z in (66, 78):
        g.sym(deck, [24, 28, z], [1, 10, 1], "metal")                    # rear handrails
    g.sym(deck, [24.1, 37, 65.9], [0.8, 0.8, 13.2], "metal")
    for z in (0, 20, 60):
        g.sym(deck, [25.8, 26.6, z], [0.8, 1.2, 1.6], "metal_dark")     # tie-down rings
    g.sym(deck, [16, 28, 70], [8, 6, 8], "camo")                         # ammunition bins
    g.sym(deck, [15.7, 34, 69.7], [8.6, 1, 8.6], "camo")
    g.sym(deck, [19, 32, 69.4], [2, 1.5, 0.6], "metal_dark")
    g.sym(deck, [17, 35, 73], [4, 0.6, 0.6], "metal")
    g.sym(deck, [2, 8, 84.5], [0.8, 18, 0.8], "metal")                   # rear ladder
    for k in range(3):
        g.cube(deck, [-2.6, 10 + k * 5, 84.6], [5.2, 0.6, 0.6], "metal")

    # --- rear spades (stowed up) with hydraulic rams ----------------------------------------
    # Static hinge brackets on the rear bumper; each spade (arms, blade, teeth,
    # ribs and its ram) folds about its own hinge bone. Modelled stowed (up).
    hx, hy, hz = SPADE_HINGE
    for x0 in (hx - 7, hx + 5.5):
        g.sym(spades, [x0, hy - 3, hz - 3], [1.5, 6, 5.5], "metal_dark")
    g.sym(spades, [hx - 7.5, hy - 3.5, hz - 3], [15, 1.5, 2.5], "camo_dark")  # bracket base
    pair = dict(bone2=spade_r)
    g.sym(spade_l, [hx - 5.5, hy - 1, hz - 1], [11, 2, 2], "metal", **pair)    # hinge tube
    for x0 in (hx - 5.4, hx + 4):
        g.sym(spade_l, [x0, hy, hz - 0.8], [1.4, 21, 1.6], "camo_dark", **pair)  # arms
    g.sym(spade_l, [hx - 5.4, hy + 10, hz - 0.6], [10.8, 1.4, 1.2], "camo_dark", **pair)
    g.sym(spade_l, [hx - 7, hy + 19, hz - 1.8], [14, 9, 2.2], "camo_dark", **pair)  # blade
    for k in range(4):
        g.sym(spade_l, [hx - 6.5 + k * 3.5, hy + 28, hz - 1.4], [2.4, 2.5, 1.4], "metal_dark",
              **pair)                                                    # teeth
    for x0 in (hx - 3.5, hx + 2.3):
        g.sym(spade_l, [x0, hy + 19.5, hz + 0.4], [1.2, 8, 1], "camo_dark", **pair)  # ribs
    g.sym(spade_l, [hx - 0.8, hy + 1.5, hz + 1.2], [1.6, 11, 1.6], "metal_dark", **pair)  # ram
    g.sym(spade_l, [hx - 0.4, hy + 12.5, hz + 1.6], [0.8, 7, 0.8], "metal", **pair)
    g.sym(spade_l, [hx - 1, hy + 19.5, hz + 0.4], [2, 2, 1.6], "metal_dark", **pair)

    # --- gun mount (yaws with turret) --------------------------------------------------
    tx, ty, tz = TURRET_PIVOT
    g.ngon(mount, (tx, ty + 1, tz), "y", 16, 2, "metal_dark_flat", n=12)
    g.ngon(mount, (tx, ty + 2.5, tz), "y", 12, 1, "camo_flat", n=12)
    g.sym(mount, [6.5, 30, 24], [5.5, 7, 36], "camo")
    g.cube(mount, [-6.5, 30, 24], [13, 6, 6], "camo")
    g.sym(mount, [7, 37, 28], [4.5, 23, 20], "camo")                     # trunnion cheeks
    g.sym(mount, [7, 60, 31], [4.5, 7, 14], "camo")
    g.sym(mount, [11.5, 40, 32], [0.4, 8, 12], "camo")
    g.sym(mount, [7, 56.5, 26.3], [4.5, 5, 3], "camo", rot=R("x", 35), pivot=[9, 59, 28])
    g.ngon(mount, (12.4, GUN_PIVOT[1], GUN_PIVOT[2]), "x", 3.6, 2, "metal_flat", sym=True)
    g.sym(mount, [13.4, 62, 37], [0.6, 2, 2], "metal_dark")
    g.cube(mount, [12, 37, 30], [4, 8, 8], "camo")                       # layer's station
    g.cube(mount, [12.5, 45, 31], [3, 4, 4], "metal_dark")
    g.cube(mount, [12.8, 46, 30.4], [2.4, 2, 0.6], "glass")
    g.cube(mount, [13.5, 46.5, 35], [1, 1, 2], "black")
    g.ngon(mount, (16.4, 41, 36), "x", 2, 0.5, "metal_flat")
    g.ngon(mount, (14, 41.5, 38.4), "z", 2, 0.5, "metal_flat")
    g.cube(mount, [13, 34, 40], [4, 1.5, 5], "black")
    g.cube(mount, [13, 35.5, 44.5], [4, 4, 0.8], "black")
    g.cube(mount, [14.5, 30.5, 42], [1, 3.5, 1], "metal_dark")
    g.cube(mount, [16.2, 32, 26], [1, 15, 16], "camo")                  # layer's shield
    g.cube(mount, [16, 46.6, 25.9], [1.4, 0.6, 16.2], "metal_dark")
    g.cube(mount, [-12, 30, 60], [24, 2, 8], "camo")                     # loader step
    g.sym(mount, [11, 32, 66], [1, 10, 1], "metal")
    g.cube(mount, [-12.1, 41, 66.1], [24.2, 0.8, 0.8], "metal")
    g.cube(mount, [-11, 36.5, 66.1], [22, 0.7, 0.8], "metal")
    g.cube(mount, [-16, 36, 46], [4, 1, 16], "metal_dark")               # loading tray
    g.cube(mount, [-16.3, 37, 46], [0.6, 2, 16], "metal")
    g.cube(mount, [-12.3, 37, 46], [0.6, 2, 16], "metal")
    g.ngon(mount, (-14, 40.5, 52), "z", 1.6, 12, "metal_flat")
    g.cube(mount, [-14.5, 30.5, 52], [1, 6, 1], "metal_dark")
    g.cube(mount, [-9, 37, 25], [4, 4, 3], "metal_dark")

    # --- cradle (elevates, does not recoil) ----------------------------------------------
    gx, gy, gz = GUN_PIVOT
    g.cube(cradle, [-6, gy - 5, 14], [12, 10, 34], "camo")
    g.cube(cradle, [-5, gy + 5, 16], [10, 0.6, 30], "camo")
    g.sym(cradle, [6, gy - 3.5, 16], [0.8, 3, 30], "metal_dark")
    for k in range(4):
        g.sym(cradle, [6.8, gy - 2, 18 + k * 7], [0.4, 1, 1], "metal")
    g.ngon(cradle, (0, gy, 13), "z", 5.5, 3, "camo_flat", n=12)
    g.ngon(cradle, (3.2, gy + 6.6, 28), "z", 2.2, 36, "camo_flat", sym=True)
    g.sym(cradle, [1.9, gy + 5.3, 9.5], [2.6, 2.6, 0.6], "metal_dark")
    g.sym(cradle, [1.8, gy + 4.5, 44], [2.8, 3, 2.2], "camo")
    g.ngon(cradle, (0, gy - 6.5, 30), "z", 2.0, 30, "camo_dark_flat")

    # --- recoiling tube, breech, fume extractor, multi-baffle brake ---------------------------
    g.cube(tube, [-5, gy - 5, 48], [10, 10, 10], "metal_dark")
    g.cube(tube, [-3.5, gy - 3.5, 57.5], [7, 7, 1.2], "metal")
    g.cube(tube, [5, gy - 2, 54], [1, 4, 3], "metal")
    g.cube(tube, [5.3, gy + 1.5, 50], [0.5, 0.6, 6], "metal")
    g.sym(tube, [4.2, gy + 4.2, 48], [1.6, 1.6, 10], "metal_dark", rot=R("z", 45),
          pivot=[5, gy + 5, 53])
    g.ngon(tube, (0, gy, 19), "z", 3.4, 58, "camo_flat", n=12)            # jacket
    g.ngon(tube, (0, gy, -55), "z", 2.8, 90, "camo_flat")                 # chase
    g.ngon(tube, (0, gy, -10), "z", 3.8, 1.6, "metal_dark_flat")
    g.ngon(tube, (0, gy, -49), "z", 4.0, 16, "camo_flat", n=12)           # fume extractor
    for zc in (-58, -40):
        g.ngon(tube, (0, gy, zc), "z", 3.5, 2, "camo_flat")
    g.ngon(brake, (0, gy, -99), "z", 3.6, 2, "metal_dark_flat")
    core_front = MUZZLE_Z + 0.1
    g.ngon(brake, (0, gy, (core_front - 100) / 2), "z", 3.2, -100 - core_front,
           "metal_dark_flat", ends=("bore", (0, gy)))
    for k in range(5):
        z0 = MUZZLE_Z + k * 2.8
        g.cube(brake, [-4.6, gy - 4.4, z0], [9.2, 8.8, 1.2], "metal_dark",
               {"north": ("bore", (0, gy))} if k == 0 else None)
    for y in (gy + 4.3, gy - 5.0):
        g.cube(brake, [-2.2, y, MUZZLE_Z + 0.3], [4.4, 0.7, 13], "metal_dark")
    return g


# --------------------------------------------------------------------------
# Texture. Large camouflaged faces get their own region (painted from model
# position, so the pattern runs across cube edges); mirrored halves, the six
# wheels, undersides and small parts share regions. Muted colours, soft
# per-texel variation, panel lines, plate seams and dirt on the lower hull.
PAL = {"green": (108, 116, 81), "dark": (78, 86, 61), "khaki": (148, 141, 107),
       "brown": (104, 92, 71), "black": (64, 65, 57)}
DIRT = np.array((112, 100, 79), float)
UNIFORM = {"metal", "metal_flat", "metal_dark", "metal_dark_flat", "black", "rubber", "glass",
           "lamp", "tail", "amber", "plate", "grille", "treadplate"}


def _lattice(seed, n=32):
    return np.random.default_rng(seed).random((n, n, n))


LAT = [_lattice(s) for s in (1301, 1307, 1319, 1321)]


def vnoise(p, lat):
    n = lat.shape[0]
    f = np.floor(p)
    t = p - f
    t = t * t * (3 - 2 * t)
    i = f.astype(int) % n
    j = (i + 1) % n
    out = 0
    for dx in (0, 1):
        for dy in (0, 1):
            for dz in (0, 1):
                ix = j[..., 0] if dx else i[..., 0]
                iy = j[..., 1] if dy else i[..., 1]
                iz = j[..., 2] if dz else i[..., 2]
                w = ((t[..., 0] if dx else 1 - t[..., 0]) * (t[..., 1] if dy else 1 - t[..., 1])
                     * (t[..., 2] if dz else 1 - t[..., 2]))
                out = out + w * lat[ix, iy, iz]
    return out


def hash01(P):
    q = np.floor(P * 1.01)
    h = np.sin(q[..., 0] * 12.9898 + q[..., 1] * 78.233 + q[..., 2] * 37.719) * 43758.5453
    return h - np.floor(h)


def camo(P):
    q = P / 15.0
    a = vnoise(q + 3.1, LAT[0]) * 0.7 + vnoise(q * 2.3 + 7.7, LAT[0]) * 0.3
    b = vnoise(q * 1.1 + 11.3, LAT[1]) * 0.7 + vnoise(q * 2.6 + 1.9, LAT[1]) * 0.3
    c = vnoise(q * 1.3 + 5.5, LAT[2]) * 0.75 + vnoise(q * 3.0 + 2.2, LAT[2]) * 0.25
    out = np.empty(P.shape[:-1] + (3,), float)
    out[...] = PAL["green"]
    out[a > 0.60] = PAL["khaki"]
    out[b > 0.60] = PAL["brown"]
    out[(c > 0.65) & (b <= 0.60)] = PAL["dark"]
    out[b > 0.73] = PAL["black"]
    return out


def weathering(img, P, strength=1.0):
    """Soft grain, slight darkening low down and dust on the lower hull."""
    grain = (hash01(P) - 0.5) * 7
    img = img + grain[..., None]
    y = P[..., 1]
    img = img * (0.93 + 0.07 * np.clip(y / 55, 0, 1))[..., None]
    mott = vnoise(P / 4.0 + 17.0, LAT[3])
    dirt = np.clip((25 - y) / 22, 0, 1) ** 1.3 * (0.35 + 0.5 * mott) * strength
    return img * (1 - dirt[..., None]) + DIRT * dirt[..., None]


def edges(img, su, sv, line=0.76, soft=0.92):
    h, w = img.shape[:2]          # padded by one texel on every side
    if su >= 5 and sv >= 5:
        img[1:h - 1, 2] *= soft; img[1:h - 1, w - 3] *= soft
        img[2, 1:w - 1] *= soft; img[h - 3, 1:w - 1] *= soft
    if su >= 3 and sv >= 3:
        f = line if (su >= 5 and sv >= 5) else (1 + line) / 2
        img[1:h - 1, 1] *= f; img[1:h - 1, w - 2] *= f
        img[1, 1:w - 1] *= f; img[h - 2, 1:w - 1] *= f
    return img


def flat(shape, rgb):
    out = np.empty(shape + (3,), float)
    out[...] = rgb
    return out


def paint(mat, P, fu, fv, su, sv, seams):
    name, param = (mat if isinstance(mat, tuple) else (mat, None))
    shp = fu.shape
    if name in ("camo", "camo_flat", "camo_dark", "camo_dark_flat", "under"):
        img = camo(P)
        if name in ("camo_dark", "camo_dark_flat"):
            img = img * 0.7
        if name == "under":
            img = img * 0.55
        if seams:
            for axis in (0, 2):
                fr = P[..., axis] / 24.0
                img[np.abs(fr - np.round(fr)) < 0.022] *= 0.84
        img = weathering(img, P, 1.3 if name == "under" else 1.0)
        return img if name.endswith("flat") else edges(img, su, sv)
    if name in ("metal", "metal_flat"):
        img = weathering(flat(shp, (121, 123, 116)), P, 0.6)
        return img if name == "metal_flat" else edges(img, su, sv, 0.8, 0.95)
    if name in ("metal_dark", "metal_dark_flat"):
        img = weathering(flat(shp, (78, 80, 74)), P, 0.7)
        return img if name == "metal_dark_flat" else edges(img, su, sv, 0.8, 0.95)
    if name == "black":
        return flat(shp, (44, 45, 41)) + ((hash01(P) - 0.5) * 4)[..., None]
    if name == "rubber":
        return weathering(flat(shp, (48, 47, 43)), P, 0.8)
    if name == "glass":
        img = flat(shp, (54, 68, 74))
        d = fu + (1 - fv)
        img[(d > 0.4) & (d < 0.62)] = (92, 110, 114)
        return edges(img, su, sv, 0.7, 1.0)
    if name == "lamp":
        img = flat(shp, (204, 199, 168))
        img[(fu + fv) < 0.7] = (222, 218, 192)
        return edges(img, su, sv, 0.6, 1.0)
    if name == "tail":
        return edges(flat(shp, (126, 60, 50)), su, sv, 0.65, 1.0)
    if name == "amber":
        return edges(flat(shp, (168, 132, 78)), su, sv, 0.7, 1.0)
    if name == "plate":
        img = flat(shp, (176, 174, 158))
        img[(np.abs(fv - 0.5) < 0.17) & (np.abs(fu - 0.5) < 0.4)] = (70, 70, 64)
        return edges(img, su, sv, 0.6, 1.0)
    if name == "grille":
        img = flat(shp, (52, 56, 46))
        img[(np.floor(fv * sv) % 2) == 0] = (78, 84, 64)
        return edges(img, su, sv, 0.8, 1.0)
    if name == "treadplate":
        img = weathering(flat(shp, (92, 96, 78)), P, 0.5)
        i, j = np.floor(fu * su), np.floor(fv * sv)
        img[((i + j) % 4 == 0) | ((i - j) % 4 == 0)] *= 1.12
        return edges(img, su, sv, 0.8, 1.0)
    if name == "tread":
        base = flat(shp, (58, 56, 51) if param == "lug" else (46, 45, 41))
        mott = vnoise(P / 3.0 + 5.0, LAT[3])
        img = base * (1 - 0.25 * mott[..., None]) + DIRT * 0.25 * mott[..., None]
        return img + ((hash01(P) - 0.5) * 5)[..., None]
    if name == "sidewall":
        return img_sidewall(P)
    if name == "hub":
        return img_hub(P)
    if name == "bore":
        cx, cy = param
        r = np.hypot(P[..., 0] - cx, P[..., 1] - cy)
        img = flat(shp, (78, 80, 74))
        img[r < 2.6] = (52, 53, 49)
        img[r < 1.9] = (16, 17, 16)
        return img
    raise ValueError(name)


def _hub_polar(P):
    """Polar coordinates about the representative (left front) wheel hub."""
    dy, dz = P[..., 1] - HUB_Y, P[..., 2] - AXLES["0"]
    return np.hypot(dy, dz), np.arctan2(dy, dz)


def img_sidewall(P):
    r, _ = _hub_polar(P)
    img = flat(r.shape, (50, 49, 45))
    img[(r > 7.6) & (r < 8.3)] = (63, 62, 57)
    img = img * (1 - 0.2 * np.clip((r - 6) / 4, 0, 1))[..., None]
    mott = vnoise(P / 3.0, LAT[3])
    return img * (1 - 0.3 * mott[..., None]) + DIRT * 0.3 * mott[..., None]


def img_hub(P):
    r, a = _hub_polar(P)
    img = flat(r.shape, (88, 95, 68))
    img[r > 5.2] = (70, 76, 54)
    img[(r > 3.3) & (r < 3.9)] = (66, 72, 51)
    bolt = (a / (2 * math.pi) * 10) % 1.0
    img[(np.abs(r - 4.6) < 0.45) & (bolt < 0.35)] = (128, 130, 118)
    return weathering(img, P, 0.6)


def face_frame(cube, face):
    """World corner points (c0..c3) of a face at rest, cube rotation applied."""
    inf = float(cube.get("inflate", 0))
    o = np.array(cube["origin"], float) - inf
    s = np.array(cube["size"], float) + 2 * inf
    pts = np.array(FACE_CORNERS[face], float) * s + o
    if "rotation" in cube:
        pv = np.array(cube["pivot"], float)
        pts = (pts - pv) @ rot_matrix(cube["rotation"]).T + pv
    return pts


def face_dims(cube, face):
    s = np.array(cube["size"], float)
    c = np.array(FACE_CORNERS[face], float)
    return float(np.abs((c[2] - c[3]) * s).sum()), float(np.abs((c[0] - c[3]) * s).sum())


def region_key(cube, face, density):
    """Which shared atlas region a face uses, and its size in texels."""
    mat = cube["_mat"][face]
    name = mat[0] if isinstance(mat, tuple) else mat
    su, sv = face_dims(cube, face)
    pu, pv = max(1, math.ceil(su * density - 1e-6)), max(1, math.ceil(sv * density - 1e-6))
    kface = SWAP.get(face, face) if cube["_mirror"] else face
    if name in UNIFORM:
        pu, pv = min(pu, 16), min(pv, 16)
        return ("swatch", name, pu, pv), pu, pv
    if face == "down" and name.startswith("camo") and su * sv > 30:
        return ("under",), 16, 16
    if name.startswith("camo") and pu * pv <= 24 and cube["_key"] is None:
        h = int(hash01(np.array(cube["origin"]))[()] * 3)
        return ("small", name, pu, pv, h), pu, pv
    if cube["_key"] is not None:
        return ("group", cube["_key"], kface), pu, pv
    return ("own", id(cube), face), pu, pv


def pack(sizes, width):
    order = sorted(range(len(sizes)), key=lambda i: (-sizes[i][1], -sizes[i][0], i))
    pos = [None] * len(sizes)
    x = y = shelf = 0
    for i in order:
        w, h = sizes[i]
        if x + w > width:
            x, y, shelf = 0, y + shelf, 0
        pos[i] = (x, y)
        x += w
        shelf = max(shelf, h)
    return pos, y + shelf


def texture(geo):
    faces = [(c, f) for _, c in geo.cubes() if "_mat" in c for f in FACES]
    for density in (1.0, 0.9, 0.8, 0.7, 0.6, 0.5):
        regions = {}
        for cube, face in faces:
            key, pu, pv = region_key(cube, face, density)
            if key not in regions:
                regions[key] = (cube, face, pu, pv)
        keys = list(regions)
        sizes = [(regions[k][2] + 2, regions[k][3] + 2) for k in keys]
        fit = None
        for size in (256, TEX_LIMIT):
            pos, height = pack(sizes, size)
            if height <= size:
                fit = size
                break
        if fit:
            break
    assert fit, "atlas does not fit"
    atlas = np.zeros((fit, fit, 4), np.uint8)
    placed = {}
    for key, (px, py) in zip(keys, pos):
        cube, face, pu, pv = regions[key]
        c = face_frame(cube, face)
        su, sv = face_dims(cube, face)
        ii = (np.arange(-1, pu + 1) + 0.5) / pu
        jj = (np.arange(-1, pv + 1) + 0.5) / pv
        fu, fv = np.meshgrid(np.clip(ii, 0, 1), np.clip(jj, 0, 1))
        P = c[3] + fu[..., None] * (c[2] - c[3]) + fv[..., None] * (c[0] - c[3])
        mat = ("under", None) if key[0] == "under" else cube["_mat"][face]
        rgb = paint(mat, P, fu, fv, pu, pv, seams=key[0] == "own" and su > 14 and sv > 14)
        atlas[py:py + pv + 2, px:px + pu + 2, :3] = np.clip(np.round(rgb), 0, 255).astype(np.uint8)
        atlas[py:py + pv + 2, px:px + pu + 2, 3] = 255
        placed[key] = (px + 1, py + 1, pu, pv)
    for cube, face in faces:
        key = region_key(cube, face, density)[0]
        u, v, pu, pv = placed[key]
        if cube["_mirror"] and face in ("north", "south", "up", "down"):
            info = {"uv": [u + pu, v], "uv_size": [-pu, pv]}     # mirrored copy
        else:
            info = {"uv": [u, v], "uv_size": [pu, pv]}
        cube.setdefault("uv", {})[face] = info
    stats = {"density": density, "regions": len(keys)}
    return Image.fromarray(atlas, "RGBA"), stats


def geo_json(geo, tex_size):
    bones = []
    for b in geo.bones:
        nb = {k: v for k, v in b.items() if k != "cubes"}
        if b.get("cubes"):
            nb["cubes"] = []
            for c in b["cubes"]:
                nc = {"origin": c["origin"], "size": c["size"]}
                if "rotation" in c:
                    nc["pivot"], nc["rotation"] = c["pivot"], c["rotation"]
                nc["uv"] = {f: c["uv"][f] for f in FACES if f in c["uv"]}
                nb["cubes"].append(nc)
        bones.append(nb)
    return {"format_version": "1.12.0", "minecraft:geometry": [{
        "description": {"identifier": "geometry.bohdana",
                        "texture_width": tex_size[0], "texture_height": tex_size[1],
                        "visible_bounds_width": 22, "visible_bounds_height": 8.5,
                        "visible_bounds_offset": [0, 3.75, 0]},
        "bones": bones}]}


# --------------------------------------------------------------------------
# Software preview renderer (orthographic, z-buffered, textured). It works on
# geometry JSON (ours or SBW's), mirrors X as Java does, and can pose bones.
class Posed:
    def __init__(self, data):
        self.bones = data["minecraft:geometry"][0]["bones"]
        self.by = {b["name"]: b for b in self.bones}

    def chain(self, bone, pts, pose):
        b = self.by[bone]
        while b is not None:
            pv = np.array(b["pivot"], float)
            if "rotation" in b:
                pts = (pts - pv) @ rot_matrix(b["rotation"]).T + pv
            if b["name"] in pose:
                kind, val = pose[b["name"]]
                if kind == "move":
                    pts = pts + np.array(val, float)
                else:
                    a = math.radians(val)
                    if kind == "yaw":
                        M = np.array([[math.cos(a), 0, math.sin(a)], [0, 1, 0],
                                      [-math.sin(a), 0, math.cos(a)]])
                    else:   # pitch/spin about X; positive raises the -Z end
                        M = np.array([[1, 0, 0], [0, math.cos(a), -math.sin(a)],
                                      [0, math.sin(a), math.cos(a)]])
                    pts = (pts - pv) @ M.T + pv
            b = self.by.get(b.get("parent"))
        return pts

    def hidden(self, name, hide):
        b = self.by[name]
        while b is not None:
            if b["name"] in hide:
                return True
            b = self.by.get(b.get("parent"))
        return False

    def quads(self, pose=None, hide=()):
        pose = pose or {}
        mirror = np.array([-1, 1, 1])
        out = []
        for b in self.bones:
            if hide and self.hidden(b["name"], hide):
                continue
            for c in b.get("cubes", []):
                if sum(v > 0 for v in c["size"]) < 2:
                    continue
                frames = {f: self.chain(b["name"], face_frame(c, f), pose) * mirror for f in FACES}
                centre = np.mean([p.mean(0) for p in frames.values()], axis=0)
                if min(c["size"]) <= 0:      # zero-thickness panel: two-sided
                    centre = None
                for f in FACES:
                    uvi = c.get("uv", {}).get(f)
                    if uvi and uvi["uv_size"][0] and uvi["uv_size"][1]:
                        out.append((frames[f], uvi, centre))
        return out


def camera(yaw, elev):
    el, yw = math.radians(elev), math.radians(yaw)
    cam = np.array([math.sin(yw) * math.cos(el), math.sin(el), -math.cos(yw) * math.cos(el)])
    right = np.cross([0, 1, 0], cam)
    if np.linalg.norm(right) < 1e-6:
        right = np.array([1.0, 0, 0])
    right /= np.linalg.norm(right)
    return cam, right, np.cross(cam, right)


def extent(quads, yaw, elev):
    _, right, up = camera(yaw, elev)
    p = np.concatenate([q[0] for q in quads])
    proj = np.stack((p @ right, p @ up), axis=1)
    return proj.min(0), proj.max(0)


def render(data, tex, size=(1000, 700), yaw=45, elev=25, pose=None, margin=40,
           scale=None, center=None, quads=None):
    w, h = size
    tw, th = tex.size
    texp = np.asarray(tex.convert("RGBA"))
    cam, right, up = camera(yaw, elev)
    quads = quads if quads is not None else Posed(data).quads(pose)
    lo, hi = extent(quads, yaw, elev)
    if scale is None:
        scale = min((w - 2 * margin) / (hi[0] - lo[0]), (h - 2 * margin) / (hi[1] - lo[1]))
    cx, cy = (lo + hi) / 2 if center is None else center
    zbuf = np.full((h, w), -1e30)
    pix = np.zeros((h, w, 4), np.uint8)
    light_dir = np.array([0.35, 1.0, -0.55])
    light_dir /= np.linalg.norm(light_dir)

    def project(p):
        return np.stack(((p @ right - cx) * scale + w / 2, h / 2 - (p @ up - cy) * scale), axis=1)

    for pts, uvi, centre in quads:
        normal = np.cross(pts[1] - pts[0], pts[3] - pts[0])
        n = np.linalg.norm(normal)
        if n < 1e-9:
            continue
        normal /= n
        if centre is None:
            normal = normal if normal @ cam > 0 else -normal
        elif (pts.mean(0) - centre) @ normal < 0:
            normal = -normal
        if normal @ cam <= 1e-6:
            continue
        u, v = uvi["uv"]
        su, sv = uvi["uv_size"]
        eu, ev = (0.01 if su > 0 else -0.01), (0.01 if sv > 0 else -0.01)
        uv = np.array([[u, v + sv - ev], [u + su - eu, v + sv - ev], [u + su - eu, v], [u, v]],
                      float)
        light = 0.62 + 0.38 * max(0.0, normal @ light_dir)
        scr = project(pts)
        for ids in ((0, 1, 2), (0, 2, 3)):
            s, P, T = scr[list(ids)], pts[list(ids)], uv[list(ids)]
            x0 = max(0, int(s[:, 0].min())); x1 = min(w - 1, math.ceil(s[:, 0].max()))
            y0 = max(0, int(s[:, 1].min())); y1 = min(h - 1, math.ceil(s[:, 1].max()))
            if x1 < x0 or y1 < y0:
                continue
            xx, yy = np.meshgrid(np.arange(x0, x1 + 1) + .5, np.arange(y0, y1 + 1) + .5)
            a, b, c = s
            den = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
            if abs(den) < 1e-9:
                continue
            l1 = ((b[1] - c[1]) * (xx - c[0]) + (c[0] - b[0]) * (yy - c[1])) / den
            l2 = ((c[1] - a[1]) * (xx - c[0]) + (a[0] - c[0]) * (yy - c[1])) / den
            l3 = 1 - l1 - l2
            depth = l1 * (P[0] @ cam) + l2 * (P[1] @ cam) + l3 * (P[2] @ cam)
            old = zbuf[y0:y1 + 1, x0:x1 + 1]
            m = (l1 >= -1e-6) & (l2 >= -1e-6) & (l3 >= -1e-6) & (depth > old)
            if not m.any():
                continue
            tx = np.clip(np.floor(l1 * T[0, 0] + l2 * T[1, 0] + l3 * T[2, 0]).astype(int), 0, tw - 1)
            ty = np.clip(np.floor(l1 * T[0, 1] + l2 * T[1, 1] + l3 * T[2, 1]).astype(int), 0, th - 1)
            col = texp[ty, tx].copy()
            col[..., :3] = np.clip(col[..., :3] * light, 0, 255).astype(np.uint8)
            region = pix[y0:y1 + 1, x0:x1 + 1]
            m &= col[..., 3] > 0
            region[m] = col[m]
            old[m] = depth[m]
    return Image.fromarray(pix, "RGBA")


def render_eye(data, tex, eye, yaw=0.0, pitch=0.0, fov=70.0, size=(960, 600), pose=None,
               hide=(), near=0.3):
    """Perspective view from a model-space eye (file coordinates).

    yaw 0 looks along -Z; negative yaw turns to the driver's left (file +X, which
    Java shows as -X). `fov` is vertical, as in Minecraft. Near-plane clipped,
    perspective-correct texturing, flat ground (y = 0) and sky behind."""
    w, h = size
    tw, th = tex.size
    texp = np.asarray(tex.convert("RGBA"))
    e = np.array(eye, float) * np.array([-1, 1, 1])
    yw, pt = math.radians(yaw), math.radians(pitch)
    f = np.array([math.sin(yw) * math.cos(pt), math.sin(pt), -math.cos(yw) * math.cos(pt)])
    r = np.cross(f, [0, 1, 0]); r /= np.linalg.norm(r)
    u = np.cross(r, f)
    F = (h / 2) / math.tan(math.radians(fov) / 2)
    inv = np.zeros((h, w))                      # 1/depth buffer, 0 = empty
    pix = np.zeros((h, w, 3), np.uint8)
    light_dir = np.array([0.35, 1.0, -0.55]); light_dir /= np.linalg.norm(light_dir)
    for pts, uvi, centre in Posed(data).quads(pose, hide):
        normal = np.cross(pts[1] - pts[0], pts[3] - pts[0])
        n = np.linalg.norm(normal)
        if n < 1e-9:
            continue
        normal /= n
        if centre is not None and (pts.mean(0) - centre) @ normal < 0:
            normal = -normal
        if centre is not None and (pts[0] - e) @ normal >= 0:
            continue                             # back face
        if centre is None and (pts[0] - e) @ normal > 0:
            normal = -normal
        uv0, (su, sv) = uvi["uv"], uvi["uv_size"]
        eu, ev = (0.01 if su > 0 else -0.01), (0.01 if sv > 0 else -0.01)
        uvq = np.array([[uv0[0], uv0[1] + sv - ev], [uv0[0] + su - eu, uv0[1] + sv - ev],
                        [uv0[0] + su - eu, uv0[1]], [uv0[0], uv0[1]]], float)
        rel = pts - e
        cam = np.stack((rel @ r, rel @ u, rel @ f), axis=1)
        poly = []                                # clip against depth >= near
        for i in range(4):
            a, b = i, (i + 1) % 4
            pa, pb = cam[a], cam[b]
            if pa[2] >= near:
                poly.append((pa, uvq[a]))
            if (pa[2] >= near) != (pb[2] >= near):
                t = (near - pa[2]) / (pb[2] - pa[2])
                poly.append((pa + t * (pb - pa), uvq[a] + t * (uvq[b] - uvq[a])))
        if len(poly) < 3:
            continue
        light = 0.62 + 0.38 * max(0.0, normal @ light_dir)
        P = np.array([p for p, _ in poly]); T = np.array([t for _, t in poly])
        sx = w / 2 + P[:, 0] / P[:, 2] * F
        sy = h / 2 - P[:, 1] / P[:, 2] * F
        iz = 1 / P[:, 2]
        for k in range(1, len(poly) - 1):
            ids = [0, k, k + 1]
            xs, ys = sx[ids], sy[ids]
            x0 = max(0, int(xs.min())); x1 = min(w - 1, math.ceil(xs.max()))
            y0 = max(0, int(ys.min())); y1 = min(h - 1, math.ceil(ys.max()))
            if x1 < x0 or y1 < y0:
                continue
            xx, yy = np.meshgrid(np.arange(x0, x1 + 1) + .5, np.arange(y0, y1 + 1) + .5)
            (ax, bx, cx), (ay, by, cy) = xs, ys
            den = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
            if abs(den) < 1e-9:
                continue
            l1 = ((by - cy) * (xx - cx) + (cx - bx) * (yy - cy)) / den
            l2 = ((cy - ay) * (xx - cx) + (ax - cx) * (yy - cy)) / den
            l3 = 1 - l1 - l2
            zi = l1 * iz[ids[0]] + l2 * iz[ids[1]] + l3 * iz[ids[2]]
            old = inv[y0:y1 + 1, x0:x1 + 1]
            m = (l1 >= -1e-6) & (l2 >= -1e-6) & (l3 >= -1e-6) & (zi > old)
            if not m.any():
                continue
            tu = (l1 * T[ids[0], 0] * iz[ids[0]] + l2 * T[ids[1], 0] * iz[ids[1]]
                  + l3 * T[ids[2], 0] * iz[ids[2]]) / zi
            tv = (l1 * T[ids[0], 1] * iz[ids[0]] + l2 * T[ids[1], 1] * iz[ids[1]]
                  + l3 * T[ids[2], 1] * iz[ids[2]]) / zi
            col = texp[np.clip(np.floor(tv).astype(int), 0, th - 1),
                       np.clip(np.floor(tu).astype(int), 0, tw - 1)]
            m &= col[..., 3] > 0
            region = pix[y0:y1 + 1, x0:x1 + 1]
            region[m] = np.clip(col[..., :3] * light, 0, 255).astype(np.uint8)[m]
            old[m] = zi[m]
    # background: sky, and ground with a one-block grid
    xx, yy = np.meshgrid(np.arange(w) + .5, np.arange(h) + .5)
    d = (f[None, None] + ((xx - w / 2) / F)[..., None] * r[None, None]
         - ((yy - h / 2) / F)[..., None] * u[None, None])
    sky = np.zeros((h, w, 3)); sky[...] = (178, 196, 214)
    down = d[..., 1] < -1e-6
    t = np.where(down, -e[1] / np.where(down, d[..., 1], -1), 0)
    gx, gz = e[0] + t * d[..., 0], e[2] + t * d[..., 2]
    grid = (np.abs(gx / 16 - np.round(gx / 16)) < 0.03) | (np.abs(gz / 16 - np.round(gz / 16)) < 0.03)
    ground = np.zeros((h, w, 3)); ground[...] = (116, 128, 84); ground[grid] = (98, 108, 72)
    bg = np.where(down[..., None], ground, sky)
    empty = inv == 0
    out = np.where(empty[..., None], bg, pix).astype(np.uint8)
    img = Image.fromarray(out, "RGB")
    dr = ImageDraw.Draw(img)                     # view centre marker
    dr.line((w / 2 - 8, h / 2, w / 2 + 8, h / 2), fill=(255, 60, 40))
    dr.line((w / 2, h / 2 - 8, w / 2, h / 2 + 8), fill=(255, 60, 40))
    return img


def spade_deploy_angle(data):
    """Right-handed angle about +X (deg) that puts the lowest point of the left
    spade at y = -1 (model units), swinging the blade rearward (+Z)."""
    posed = Posed(data)
    lo, hi = 90.0, 180.0
    for _ in range(60):
        mid = (lo + hi) / 2
        q = posed.quads({"bh_spade_l": ("pitch", mid)})
        q = [x for x, b in zip(q, _bones_of(posed)) if b == "bh_spade_l"]
        ymin = min(p[0][:, 1].min() for p in q)
        if ymin > -1:
            lo = mid
        else:
            hi = mid
    return round((lo + hi) / 2, 2)


def _bones_of(posed):
    """Bone name for each quad, in Posed.quads() order (no pose, no hide)."""
    out = []
    for b in posed.bones:
        for c in b.get("cubes", []):
            if sum(v > 0 for v in c["size"]) < 2:
                continue
            for f in FACES:
                uvi = c.get("uv", {}).get(f)
                if uvi and uvi["uv_size"][0] and uvi["uv_size"][1]:
                    out.append(b["name"])
    return out


def eye_clearance(data, eye, hide=("bh_fp_hide",)):
    """Smallest distance from the eye to any visible cube (axis-aligned cubes
    exactly, rotated cubes through their face planes' bounding boxes)."""
    posed = Posed(data)
    e = np.array(eye, float)
    best = 1e9
    for b in posed.bones:
        if posed.hidden(b["name"], hide):
            continue
        for c in b.get("cubes", []):
            if min(c["size"]) <= 0:
                continue
            pts = np.concatenate([face_frame(c, f) for f in FACES])
            lo, hi = pts.min(0), pts.max(0)
            dist = np.linalg.norm(np.maximum(0, np.maximum(lo - e, e - hi)))
            best = min(best, dist)
    return best


def downsample(img, size):
    return img.convert("RGBa").resize(size, Image.Resampling.LANCZOS).convert("RGBA")


def container_icon(data, tex):
    big = render(data, tex, (1024, 1024), yaw=45, elev=30, margin=8)
    big = big.crop(big.getbbox())
    k = min(126 / big.width, 104 / big.height)
    small = downsample(big, (max(1, round(big.width * k)), max(1, round(big.height * k))))
    icon = Image.new("RGBA", (128, 128))
    ox, oy = (128 - small.width) // 2, (128 - small.height) // 2
    shadow = Image.new("RGBA", small.size, (17, 17, 17, 0))
    shadow.putalpha(small.split()[3].point(lambda a: a * 0.55))
    icon.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(0.7)), (ox + 1, oy + 1))
    icon.alpha_composite(small, (ox, oy))
    return icon


def side_icon(data, tex):
    big = render(data, tex, (2400, 800), yaw=-90, elev=0, margin=4)
    big = big.crop(big.getbbox())
    a = np.asarray(big, float)
    lum = a[..., :3] @ [0.299, 0.587, 0.114]
    mask = a[..., 3] > 0
    lo, hi = np.percentile(lum[mask], 3), np.percentile(lum[mask], 97)
    val = 186 + 66 * np.clip((lum - lo) / max(1.0, hi - lo), 0, 1)
    out = np.zeros_like(a)
    out[..., 0] = out[..., 1] = out[..., 2] = val
    out[..., 3] = a[..., 3]
    big = Image.fromarray(out.astype(np.uint8), "RGBA")
    k = min(217 / big.width, 63 / big.height)
    small = downsample(big, (max(1, round(big.width * k)), max(1, round(big.height * k))))
    icon = Image.new("RGBA", (256, 64))
    icon.alpha_composite(small, ((256 - small.width) // 2, 64 - small.height))
    return icon


def previews(data, tex, out_dir, jar):
    out_dir.mkdir(parents=True, exist_ok=True)
    views = [("3/4 FRONT", dict(yaw=45, elev=24)), ("SIDE (LEFT)", dict(yaw=-90, elev=0)),
             ("3/4 REAR", dict(yaw=215, elev=26)), ("TOP", dict(yaw=0, elev=89.9)),
             ("FIRING: yaw 60, +35 deg, recoil", dict(yaw=40, elev=22, pose={
                 "turret": ("yaw", 60), "barrel": ("pitch", 35), "tube": ("move", [0, 0, 11])})),
             ("FRONT", dict(yaw=0, elev=0))]
    sheet = Image.new("RGB", (2000, 2040), (226, 229, 217))
    d = ImageDraw.Draw(sheet)
    for i, (label, kw) in enumerate(views):
        im = render(data, tex, (1000, 660), **kw)
        x, y = (i % 2) * 1000, (i // 2) * 680
        sheet.paste(im, (x, y + 20), im)
        d.text((x + 24, y + 4), label, font=font(20, True), fill=(40, 56, 44))
    sheet.save(out_dir / "bohdana-preview.png")
    # driving poses: steered front wheels, spades deployed
    phi = spade_deploy_angle(data)
    drive = {"wheelL0Turn": ("yaw", 25), "wheelR0Turn": ("yaw", 25)}
    dig = {"bh_spade_l": ("pitch", phi), "bh_spade_r": ("pitch", phi)}
    poses = [("STEER 25 deg (wheel?0Turn yaw)", dict(yaw=30, elev=40, pose=drive)),
             (f"SPADES DEPLOYED (+{phi} deg about +X)", dict(yaw=215, elev=20, pose=dig)),
             ("SPADES DEPLOYED, SIDE", dict(yaw=-90, elev=0, pose=dig)),
             ("SPADES STOWED, SIDE", dict(yaw=-90, elev=0))]
    sheet = Image.new("RGB", (2000, 1360), (226, 229, 217))
    d = ImageDraw.Draw(sheet)
    for i, (label, kw) in enumerate(poses):
        im = render(data, tex, (1000, 660), **kw)
        x, y = (i % 2) * 1000, (i // 2) * 680
        sheet.paste(im, (x, y + 20), im)
        d.text((x + 24, y + 4), label, font=font(20, True), fill=(40, 56, 44))
    sheet.save(out_dir / "bohdana-poses.png")
    # first-person views (bh_fp_hide hidden) and the gunner camera
    gun_eye = [a + b for a, b in zip(GUN_PIVOT, GUNNER_CAM)]
    views = [("driver-forward", DRIVER_EYE, 0, -8, ("bh_fp_hide",)),
             ("driver-left60", DRIVER_EYE, -60, -8, ("bh_fp_hide",)),
             ("driver-right60", DRIVER_EYE, 60, -8, ("bh_fp_hide",)),
             ("driver-forward-at-12-46-68", [12, 46, -68], 0, -8, ("bh_fp_hide",)),
             ("gunner", gun_eye, 0, -3, ()),
             ("gunner-elev30", None, 0, 27, ())]
    sheet = Image.new("RGB", (1940, 1890), (226, 229, 217))
    d = ImageDraw.Draw(sheet)
    for i, (name, eye, yaw, pitch, hide) in enumerate(views):
        pose = None
        if eye is None:                         # gunner camera rides the barrel
            a = math.radians(30)
            pose = {"barrel": ("pitch", 30)}
            dy, dz = GUNNER_CAM[1], GUNNER_CAM[2]
            eye = [GUN_PIVOT[0] + GUNNER_CAM[0], GUN_PIVOT[1] + dy * math.cos(a) - dz * math.sin(a),
                   GUN_PIVOT[2] + dy * math.sin(a) + dz * math.cos(a)]
        im = render_eye(data, tex, eye, yaw, pitch, 70, (960, 600), pose, hide)
        im.save(out_dir / f"fp-{name}.png")
        x, y = (i % 2) * 980, (i // 2) * 630
        sheet.paste(im, (x, y + 26))
        d.text((x + 8, y + 4), f"{name}  eye {[round(v, 1) for v in eye]}  yaw {yaw}  pitch {pitch}",
               font=font(18, True), fill=(40, 56, 44))
    sheet.save(out_dir / "bohdana-first-person.png")
    tex.resize((tex.width * 2, tex.height * 2), Image.Resampling.NEAREST).save(out_dir / "atlas-x2.png")
    for p, scale in ((ICON_CONTAINER, 4), (ICON_SIDE, 3)):
        icon = Image.open(p)
        bg = Image.new("RGBA", icon.size, (70, 74, 92, 255))
        bg.alpha_composite(icon)
        bg.resize((icon.width * scale, icon.height * scale), Image.Resampling.NEAREST).save(
            out_dir / ("icon-" + p.name))
    if jar and Path(jar).is_file():          # same scale and pose as SBW's PLZ-05
        with zipfile.ZipFile(jar) as z:
            plz = json.loads(z.read(PLZ_GEO))
            plz_tex = Image.open(io.BytesIO(z.read(PLZ_TEX))).convert("RGBA")
        for name, kw in (("compare-34", dict(yaw=45, elev=24)), ("compare-side", dict(yaw=-90, elev=0))):
            qa, qb = Posed(data).quads(), Posed(plz).quads()
            (la, ha), (lb, hb) = extent(qa, **kw), extent(qb, **kw)
            span = max((ha - la).max(), (hb - lb).max())
            scale = 900 / span
            out = Image.new("RGB", (2000, 760), (226, 229, 217))
            dd = ImageDraw.Draw(out)
            for i, (q, t, lab, lo, hi) in enumerate(((qa, tex, "BOHDANA (this art)", la, ha),
                                                     (qb, plz_tex, "SBW PLZ-05 (reference)", lb, hb))):
                im = render(None, t, (1000, 720), quads=q, scale=scale,
                            center=((lo[0] + hi[0]) / 2, lo[1] + 320 / scale), **kw)
                out.paste(im, (i * 1000, 30), im)
                dd.text((i * 1000 + 24, 6), lab, font=font(20, True), fill=(40, 56, 44))
            out.save(out_dir / f"{name}.png")


# --------------------------------------------------------------------------
def validate(data, tex_size, jar=None):
    bones = data["minecraft:geometry"][0]["bones"]
    names = [b["name"] for b in bones]
    by = {b["name"]: b for b in bones}
    assert len(names) == len(set(names)), "duplicate bone names"
    for b in bones:
        assert b.get("parent") is None or b["parent"] in by, b["name"]
    reference = [tuple(r) for r in skeleton_rows()]
    source = "embedded skeleton"
    if jar and Path(jar).is_file():
        with zipfile.ZipFile(jar) as z:
            ref = json.loads(z.read(PLZ_GEO))["minecraft:geometry"][0]["bones"]
        reference = [(b["name"], b.get("parent"), b["pivot"]) + ((b["rotation"],) if "rotation" in b else ())
                     for b in ref]
        source = Path(jar).name
    for row in reference:
        name, parent, pivot = row[0], row[1], row[2]
        b = by.get(name)
        assert b is not None, f"missing PLZ bone {name}"
        want = f"{name}Turn" if name in ("wheelL0", "wheelR0") else parent   # steering
        assert b.get("parent") == want, f"parent changed: {name}"
        assert b.get("rotation") == (list(row[3]) if len(row) > 3 else None), f"rotation changed: {name}"
        if name not in MOVABLE:
            assert b["pivot"] == list(pivot), f"pivot changed: {name}"
    ref_names = {r[0] for r in reference}
    for name in set(names) - ref_names - STEER:
        assert not name.startswith(RESERVED_PREFIXES) and "_dogTag_" not in name, name
    for side in "LR":        # steering bones: under Wheel?, pivot on the front hub
        turn = by[f"wheel{side}0Turn"]
        assert turn["parent"] == f"Wheel{side}" and "rotation" not in turn
        assert turn["pivot"] == by[f"wheel{side}0"]["pivot"]
        assert names.index(turn["name"]) < names.index(f"wheel{side}0")
        assert not by[f"wheel{side}0"].get("cubes"), "front wheel geometry belongs on Turn"
    allowed_wheels = set(WHEEL_GEO_BONE.values())
    for b in bones:          # only the six used wheels carry cubes; tracks/locks stay empty
        n = b["name"]
        if b.get("cubes") and (n.startswith(("wheel", "track", "Track", "smallwhl")) or n in {
                "Wheel", "WheelL", "WheelR", "paojia", "zhuzi", "paojia2", "shensuo"}):
            assert n in allowed_wheels, f"unexpected cubes on {n}"
    for n in allowed_wheels:     # wheel parts are round about the wheel bone pivot
        piv = np.array(by[n]["pivot"])
        for c in by[n]["cubes"]:
            if "pivot" in c:
                assert np.allclose(c["pivot"][1:], piv[1:]) and c["rotation"][1:] == [0, 0], n
            lo = np.array(c["origin"]) - piv
            hi = lo + np.array(c["size"])
            assert abs(lo[2] + hi[2]) < 1e-3, n                # centred on the hub in z
            assert max(abs(lo[1]), abs(hi[1]), abs(lo[2]), abs(hi[2])) <= TYRE_R + 1.2, n
    tw, th = tex_size
    count = 0
    for b in bones:
        for c in b.get("cubes", []):
            if b["name"] not in DOG_TAG_CUBES:
                count += 1
                assert all(v > 0 for v in c["size"]), (b["name"], c)
            assert all(math.isfinite(v) for v in c["origin"] + c["size"])
            for f, info in c["uv"].items():
                assert f in FACES
                (u, v), (su, sv) = info["uv"], info["uv_size"]
                assert 0 <= min(u, u + su) and max(u, u + su) <= tw, (b["name"], f, info)
                assert 0 <= min(v, v + sv) and max(v, v + sv) <= th, (b["name"], f, info)
    return {"checked_against": source, "plz_bones": len(reference), "bones": len(bones),
            "cubes": count}


def bounds(data, pose=None):
    q = Posed(data).quads(pose)
    p = np.concatenate([x[0] for x in q]) * np.array([-1, 1, 1])     # back to file X
    return p.min(0), p.max(0)


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n")


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--preview", type=Path, help="write preview renders to this directory")
    ap.add_argument("--jar", default=str(SBW_JAR), help="SBW jar to verify the skeleton against")
    args = ap.parse_args()
    geo = build()
    tex, stats = texture(geo)
    data = geo_json(geo, tex.size)
    report = validate(data, tex.size, args.jar)
    write_json(GEO_PATH, data)
    TEX_PATH.parent.mkdir(parents=True, exist_ok=True)
    tex.save(TEX_PATH)
    ICON_CONTAINER.parent.mkdir(parents=True, exist_ok=True)
    container_icon(data, tex).save(ICON_CONTAINER)
    side_icon(data, tex).save(ICON_SIDE)
    lo, hi = bounds(data)
    report.update({
        "texture": list(tex.size), "texel_density": stats["density"],
        "atlas_regions": stats["regions"],
        "bounds_blocks": {"min": (lo / 16).round(3).tolist(), "max": (hi / 16).round(3).tolist()},
        "pivots": {n: geo.by[n]["pivot"] for n in ["turret", "barrelBase", "barrel", "tube"]
                   + sorted(f"wheel{s}{i}" for s in "LR" for i in AXLES)},
        "muzzle": [0, GUN_PIVOT[1], MUZZLE_Z],
        "new_bones": {n: {"parent": geo.by[n]["parent"], "pivot": geo.by[n]["pivot"]}
                      for n in sorted(STEER | {"bh_spade_l", "bh_spade_r", "bh_fp_hide"})},
        "spade_deploy_deg_about_+X": spade_deploy_angle(data),
        "driver_eye": DRIVER_EYE,
        "driver_eye_clearance": round(eye_clearance(data, DRIVER_EYE), 2),
        "gunner_cam_barrel_local": GUNNER_CAM,
    })
    assert report["driver_eye_clearance"] > 2.0, "driver eye too close to visible geometry"
    if args.preview:
        previews(data, tex, args.preview, args.jar)
    print(json.dumps(report))


if __name__ == "__main__":
    main()
