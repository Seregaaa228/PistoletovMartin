#!/usr/bin/env python3
"""Original cube art for the msta_s tracked 152 mm self-propelled howitzer.

The look follows the Soviet/Russian 2S19 Msta-S. Every cube and every texture
pixel is generated here; no SBW mesh or texture data is copied. Only the bone
*skeleton* (names, parents, pivots, rotations) of Superb Warfare's PLZ-05
is reproduced, because the reused Plz05Entity/Plz05Renderer and PLZ-05
animations drive bones by name.

Python 3 + Pillow + NumPy. Deterministic. Writes:
  src/main/resources/assets/frontier_arcade_vehicles/models/bedrock/vehicle/msta_s.geo.json
  src/main/resources/assets/frontier_arcade_vehicles/textures/bedrock/vehicle/msta_s.png
  src/main/resources/assets/frontier_arcade_vehicles/textures/vehicle_icon/container/msta_s.png
  src/main/resources/assets/frontier_arcade_vehicles/textures/vehicle_icon/msta_s_icon.png

Usage:
  python3 msta_s_assets.py                 # write resources
  python3 msta_s_assets.py --preview DIR   # also write review renders to DIR
  python3 msta_s_assets.py --plz-jar JAR   # also check skeleton against SBW jar
                                           # (and add a PLZ-05 comparison render)

Model space: 16 units = 1 block, +Y up, the gun points to -Z, +X is the
vehicle's LEFT side (same as PLZ-05: flightL/mdoorL are at +X).
"""
from __future__ import annotations

import argparse
import json
import math
import re
import zipfile
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
RES = HERE.parent / "src/main/resources/assets/frontier_arcade_vehicles"
GEO_OUT = RES / "models/bedrock/vehicle/msta_s.geo.json"
TEX_OUT = RES / "textures/bedrock/vehicle/msta_s.png"
ICON_CONTAINER_OUT = RES / "textures/vehicle_icon/container/msta_s.png"
ICON_SIDE_OUT = RES / "textures/vehicle_icon/msta_s_icon.png"

TEX_SIZE = 512
DENSITY = 0.85          # texels per model unit (SBW tanks sit around 0.5-1.0)
FACES = ("north", "south", "east", "west", "up", "down")

# ---------------------------------------------------------------------------
# Skeleton of SBW 0.8.9.1 plz_05.geo.json (names, parents, pivots, rotations).
# trackMov*/trackRot* bones are generated in skeleton() below.
# ---------------------------------------------------------------------------
PLZ_SKELETON = [
    ('root', None, [0, 0, 0]),
    ('Wheel', 'root', [-0.7488, 14.04, -15.7248]),
    ('WheelL', 'Wheel', [30.9816, 16.2864, -50.9184]),
    ('wheelL0', 'WheelL', [28.9224, 14.508, -77.8752]),
    ('wheelL01', 'wheelL0', [32.1048, 14.508, -77.8752]),
    ('wheelL02', 'wheelL0', [25.9272, 14.508, -77.8752]),
    ('wheelL1', 'WheelL', [28.9224, 7.3008, -54.288]),
    ('wheelL11', 'wheelL1', [30.9816, 7.3008, -54.288]),
    ('wheelL12', 'wheelL1', [24.804, 7.3008, -54.288]),
    ('wheelL2', 'WheelL', [28.9224, 7.3008, -34.632]),
    ('wheelL21', 'wheelL2', [30.9816, 7.3008, -34.632]),
    ('wheelL22', 'wheelL2', [19.188, 7.3008, -34.632]),
    ('wheelL3', 'WheelL', [28.9224, 7.3008, -17.784]),
    ('wheelL31', 'wheelL3', [30.9816, 7.3008, -17.784]),
    ('wheelL32', 'wheelL3', [24.804, 7.3008, -17.784]),
    ('wheelL4', 'WheelL', [28.9224, 7.3008, -0.936]),
    ('wheelL41', 'wheelL4', [30.9816, 7.3008, -0.936]),
    ('wheelL42', 'wheelL4', [24.804, 7.3008, -0.936]),
    ('wheelL5', 'WheelL', [28.9224, 7.3008, 14.976]),
    ('wheelL51', 'wheelL5', [30.9816, 7.3008, 14.976]),
    ('wheelL52', 'wheelL5', [24.804, 7.3008, 14.976]),
    ('wheelL6', 'WheelL', [28.9224, 7.3008, 36.504]),
    ('wheelL61', 'wheelL6', [30.9816, 7.3008, 36.504]),
    ('wheelL62', 'wheelL6', [24.804, 7.3008, 36.504]),
    ('wheelL7', 'WheelL', [28.9224, 13.66195, 50.9184]),
    ('wheelL71', 'wheelL7', [32.19121, 13.66193, 50.9184]),
    ('wheelL72', 'wheelL7', [26.01361, 13.66193, 50.9184]),
    ('smallwhlL', 'Wheel', [0, -0.936, 0]),
    ('smallwhlL1', 'smallwhlL', [27.4248, 17.5968, -40.248]),
    ('smallwhlL2', 'smallwhlL', [27.4248, 17.5968, -8.424]),
    ('smallwhlL3', 'smallwhlL', [27.4248, 17.5968, 24.336]),
    ('WheelR', 'Wheel', [-30.9816, 16.2864, -50.9184]),
    ('wheelR0', 'WheelR', [-28.9224, 14.508, -77.8752]),
    ('wheelR01', 'wheelR0', [-32.1048, 14.508, -77.8752]),
    ('wheelR02', 'wheelR0', [-25.9272, 14.508, -77.8752]),
    ('wheelR1', 'WheelR', [-28.9224, 7.3008, -54.288]),
    ('wheelR11', 'wheelR1', [-30.9816, 7.3008, -54.288]),
    ('wheelR12', 'wheelR1', [-24.804, 7.3008, -54.288]),
    ('wheelR2', 'WheelR', [-28.9224, 7.3008, -34.632]),
    ('wheelR21', 'wheelR2', [-30.9816, 7.3008, -34.632]),
    ('wheelR22', 'wheelR2', [-19.188, 7.3008, -34.632]),
    ('wheelR3', 'WheelR', [-28.9224, 7.3008, -17.784]),
    ('wheelR31', 'wheelR3', [-30.9816, 7.3008, -17.784]),
    ('wheelR32', 'wheelR3', [-24.804, 7.3008, -17.784]),
    ('wheelR4', 'WheelR', [-28.9224, 7.3008, -0.936]),
    ('wheelR41', 'wheelR4', [-30.9816, 7.3008, -0.936]),
    ('wheelR42', 'wheelR4', [-24.804, 7.3008, -0.936]),
    ('wheelR5', 'WheelR', [-28.9224, 7.3008, 14.976]),
    ('wheelR51', 'wheelR5', [-30.9816, 7.3008, 14.976]),
    ('wheelR52', 'wheelR5', [-24.804, 7.3008, 14.976]),
    ('wheelR6', 'WheelR', [-28.9224, 7.3008, 36.504]),
    ('wheelR61', 'wheelR6', [-30.9816, 7.3008, 36.504]),
    ('wheelR62', 'wheelR6', [-24.804, 7.3008, 36.504]),
    ('wheelR7', 'WheelR', [-28.9224, 13.66195, 50.9184]),
    ('wheelR71', 'wheelR7', [-32.19119, 13.66193, 50.9184]),
    ('wheelR72', 'wheelR7', [-26.01359, 13.66193, 50.9184]),
    ('smallwhlR', 'Wheel', [0, -0.936, 0]),
    ('smallwhlR1', 'smallwhlR', [-27.4248, 17.5968, -40.248]),
    ('smallwhlR2', 'smallwhlR', [-27.4248, 17.5968, -8.424]),
    ('smallwhlR3', 'smallwhlR', [-27.4248, 17.5968, 24.336]),
    ('TrackL', 'root', [31.824, 22.39958, 51.40027]),
    ('bone21', 'TrackL', [31.824, 18.34122, -56.42234]),
    ('TrackR', 'root', [-31.824, 22.39958, 51.40027]),
    ('bone2', 'TrackR', [-31.824, 18.34122, -56.42234]),
    ('base', 'root', [0, 0, 0]),
    ('turret', 'base', [0, 30.888, 14.04]),
    ('barrelBase', 'turret', [0, 44.928, -1.404]),
    ('barrel', 'barrelBase', [0, 44.928, -1.404]),
    ('xroll1', 'barrel', [0, 44.928, -1.404]),
    ('xroll2', 'barrel', [7.488, 44.928, -1.404]),
    ('xroll3', 'barrel', [-7.488, 44.928, -1.404]),
    ('bone5', 'barrel', [0, 44.928, -40.248]),
    ('bone6', 'barrel', [0, 44.928, -40.71319]),
    ('bone7', 'barrel', [0, 44.928, -43.05319]),
    ('tube', 'barrel', [0, 44.928, -1.404]),
    ('bone17', 'tube', [0, 44.928, -57.56119]),
    ('bone8', 'tube', [0, 44.928, -138.99319]),
    ('bone9', 'tube', [0, 44.928, -97.34119]),
    ('bone10', 'tube', [0, 44.928, -157.24519]),
    ('bone11', 'tube', [0, 44.928, -156.30919]),
    ('bone12', 'tube', [0, 44.928, -162.38945]),
    ('bone14', 'bone12', [0, 44.928, -166.608]),
    ('bone13', 'bone12', [0, 44.928, -167.076]),
    ('bone15', 'bone12', [0, 44.928, -166.608]),
    ('bone16', 'bone12', [0, 44.928, -166.608]),
    ('bone19', 'barrel', [0, -0.936, 0]),
    ('bone20', 'bone19', [-2.9952, 50.4504, -42.49159]),
    ('bone23', 'bone19', [-2.9952, 50.4504, -47.17159]),
    ('bone22', 'bone19', [2.9952, 50.4504, -43.42759]),
    ('bone24', 'bone19', [3.3696, 56.0664, -36.4104]),
    ('yroll', 'turret', [0, 30.888, 14.04]),
    ('trtbody', 'turret', [21.762, 36.97793, -12.62685]),
    ('trtitems', 'turret', [0, 30.888, 14.04]),
    ('tifront', 'trtitems', [-18.486, 35.05947, -17.08822]),
    ('tifrontdoor', 'tifront', [-18.486, 35.05947, -17.08822]),
    ('smoke', 'tifront', [-24.336, 48.8814, -19.3425]),
    ('bone36', 'tifront', [18.486, 49.7838, -13.4175]),
    ('tifchuang', 'tifront', [-18.486, 35.05947, -17.08822]),
    ('tileft', 'trtitems', [29.75271, 42.07919, 21.06]),
    ('menchuang', 'tileft', [3.0888, 55.5048, 50.076]),
    ('tidoorL1', 'menchuang', [30.3387, 48.05926, -11.29842]),
    ('tidoorL2', 'menchuang', [29.96428, 50.77366, 20.00324]),
    ('tidoorL3', 'menchuang', [30.0909, 44.95267, 29.18963]),
    ('tiright', 'trtitems', [29.75271, 42.07919, 21.06]),
    ('menchuang2', 'tiright', [3.0888, 55.5048, 50.076]),
    ('tidoorR1', 'menchuang2', [30.3387, 48.05926, -11.29842]),
    ('tidoorR2', 'menchuang2', [29.96428, 50.77366, 20.00324]),
    ('tiback', 'trtitems', [0, 30.888, 14.04]),
    ('huojia', 'tiback', [0, 54.1008, 59.67]),
    ('bone26', 'huojia', [0, 54.1944, 59.67]),
    ('bone28', 'bone26', [6.786, 52.0884, 60.138]),
    ('bone29', 'bone26', [-6.786, 52.0884, 60.138]),
    ('bone30', 'bone26', [0, 54.1944, 59.67]),
    ('bone31', 'bone26', [0, 54.1944, 59.67]),
    ('tiback1', 'tiback', [25.9272, 44.93081, 56.0664]),
    ('tiback2', 'tiback', [0, 30.888, 14.04]),
    ('bone25', 'tiback2', [-27.4248, 42.07528, 57.6576]),
    ('bone27', 'tiback2', [-27.4248, 56.11528, 57.6576]),
    ('tibackdoor', 'tiback', [0, 30.888, 14.04]),
    ('bone32', 'tibackdoor', [0, 30.888, 14.04]),
    ('bone33', 'tibackdoor', [-9.594, 30.888, 14.04]),
    ('bone34', 'tibackdoor', [0, 30.888, 14.04]),
    ('bone35', 'tibackdoor', [9.594, 30.888, 14.04]),
    ('titop', 'trtitems', [0, -4.212, -15.912]),
    ('move_titop1', 'titop', [0, -0.936, 0]),
    ('bone37', 'move_titop1', [73.476, 89.81128, -77.9688]),
    ('bone39', 'move_titop1', [18.4392, 58.22201, -4.9608]),
    ('bone38', 'move_titop1', [73.476, 104.03848, -77.9688]),
    ('titop2', 'titop', [0.1872, -4.212, -16.848]),
    ('bone41', 'titop2', [-73.2888, 89.81128, -78.9048]),
    ('bone40', 'titop2', [-18.252, 58.69001, -5.8968]),
    ('titop3', 'titop', [0, -4.212, -18.72]),
    ('bone43', 'titop3', [-15.912, 57.56681, 11.8872]),
    ('bone44', 'titop3', [-15.912, 57.80081, 11.8872]),
    ('bone45', 'titop3', [-15.912, 58.73681, 11.8872]),
    ('titop4', 'titop', [-19.188, 57.0024, 23.03711]),
    ('titop5', 'titop', [18.4392, 59.85636, 28.314]),
    ('bone46', 'titop5', [18.4392, 57.19241, 22.1832]),
    ('bone47', 'titop5', [18.4392, 59.06441, 22.1832]),
    ('titop6', 'titop', [30.9816, -4.212, -21.06]),
    ('bone48', 'titop6', [15.0696, 57.56681, 9.5472]),
    ('bone49', 'titop6', [15.0696, 57.80081, 9.5472]),
    ('bone50', 'titop6', [15.0696, 58.73681, 9.5472]),
    ('titop7', 'titop', [0.0936, 57.33281, 51.6672]),
    ('titoplight', 'titop', [-4.68, 59.904, 52.416]),
    ('titop8', 'titop', [-14.73867, 63.6948, 51.9948]),
    ('body_left_dogTag_10x10', 'turret', [31.18667, 42.60435, -4.72611], [0, -90, -7]),
    ('body_right_dogTag_10x10', 'turret', [-31.18667, 42.60435, -4.72611], [0, 90, 7]),
    ('body', 'base', [0, 12.12891, -82.43603]),
    ('paojia', 'body', [0, 20.87326, -85.1823]),
    ('zhuzi', 'paojia', [0.00334, 32.31391, -89.11153]),
    ('shensuo', 'zhuzi', [0.00334, 32.12987, -87.71359], [84.6815, 5.2957, 44.7539]),
    ('paojia2', 'body', [0, 27.79862, -86.38562]),
    ('main', 'body', [0, 12.12891, -82.43603]),
    ('bottom', 'body', [0, 12.12891, -82.43603]),
    ('hang1', 'bottom', [23.0256, 11.0448, -59.904]),
    ('hang2', 'bottom', [23.0256, 11.0448, -40.248]),
    ('hang3', 'bottom', [23.0256, 11.0448, -23.4]),
    ('hang4', 'bottom', [23.0256, 11.0448, -6.552]),
    ('hang5', 'bottom', [23.0256, 11.0448, 9.36]),
    ('hang6', 'bottom', [23.0256, 11.0448, 30.888]),
    ('frontrL', 'body', [20.9664, 13.572, -78.8112]),
    ('frontrR', 'body', [-20.9664, 13.572, -78.8112]),
    ('sidearmorL', 'body', [35.334, 16.43425, -80.45635]),
    ('sidearmorR', 'body', [-35.334, 16.43425, -80.45635]),
    ('items', 'body', [0, 12.12891, -82.43603]),
    ('engine', 'items', [-20.124, 32.994, -40.248]),
    ('ctd2', 'items', [13.53688, 32.8536, -32.02861]),
    ('bone3', 'ctd2', [14.976, 27.7056, -47.06929]),
    ('bone18', 'bone3', [11.2788, 31.68141, -48.03052]),
    ('bone4', 'bone3', [15.0696, 27.7056, -47.06929]),
    ('mifront1', 'items', [-11.7, 31.54045, -49.12785]),
    ('mifront2', 'items', [0, 12.12891, -82.43603]),
    ('flightL', 'items', [0, -1.872, 0]),
    ('flightR', 'items', [0, -1.872, 0]),
    ('mdoorL', 'items', [33.9008, 21.46007, -30.42]),
    ('mdoorB', 'items', [-0.936, 12.12891, -82.43603]),
    ('miback1', 'items', [-7.254, 23.4, 54.52481]),
    ('blightL', 'items', [0, 12.12891, 56.09197]),
    ('blightR', 'items', [0, 12.12891, 56.09197]),
    ('backup', 'items', [-15.444, 6.552, 134.316]),
    ('miback', 'items', [-19.59314, 25.58022, 54.99]),
]
TRACK_BONE_PIVOT = {"L": [29.016, 20.2706, -80.5345], "R": [-29.016, 20.2706, -80.5345]}
TRACK_BONE_COUNT = 50

# The gun trunnion is moved forward from PLZ-05's z=-1.404 so the 65 degree
# elevation clears the long Msta turret roof instead of punching through it.
TRUNNION = [0, 44.928, -24.0]
MOVED_PIVOTS = {"barrelBase": TRUNNION, "barrel": TRUNNION, "tube": TRUNNION}
MOVABLE = {"turret", "barrelBase", "barrel", "tube"}
MUZZLE_Z = -200.0

# SBW name patterns (VehicleModelBoneGroups). New bones must not match them.
SBW_PATTERNS = [r"^wheel[LR].*$", r"^w_[lLrR].*$", r"^shell\d+$",
                r"^track(Mov|Rot)[LR]\d+$", r"^flare.*", r"^laser.*",
                r"^.*_dogTag_\d+x\d+$"]


def skeleton():
    out = []
    for entry in PLZ_SKELETON:
        name, parent, pivot = entry[:3]
        rot = entry[3] if len(entry) > 3 else None
        out.append((name, parent, list(pivot), rot))
        if name in ("bone21", "bone2"):
            side = "L" if name == "bone21" else "R"
            for i in range(TRACK_BONE_COUNT):
                out.append((f"trackMov{side}{i}", "Track" + side, list(TRACK_BONE_PIVOT[side]), None))
                out.append((f"trackRot{side}{i}", f"trackMov{side}{i}", list(TRACK_BONE_PIVOT[side]), None))
    return out


# ---------------------------------------------------------------------------
# Math helpers (Bedrock convention: X and Y rotations are negated, order ZYX)
# ---------------------------------------------------------------------------
def rot_matrix(r):
    x, y, z = (math.radians(-r[0]), math.radians(-r[1]), math.radians(r[2]))
    rx = np.array([[1, 0, 0], [0, math.cos(x), -math.sin(x)], [0, math.sin(x), math.cos(x)]])
    ry = np.array([[math.cos(y), 0, math.sin(y)], [0, 1, 0], [-math.sin(y), 0, math.cos(y)]])
    rz = np.array([[math.cos(z), -math.sin(z), 0], [math.sin(z), math.cos(z), 0], [0, 0, 1]])
    return rz @ ry @ rx


def affine(rot, pivot):
    m = np.eye(4)
    if rot is not None and any(abs(v) > 1e-12 for v in rot):
        r = rot_matrix(rot)
        p = np.array(pivot, float)
        m[:3, :3] = r
        m[:3, 3] = p - r @ p
    return m


FACE_CORNERS = {
    # c0 bottom-left, c1 bottom-right, c2 top-right, c3 top-left of the texture
    "north": ((0, 0, 0), (1, 0, 0), (1, 1, 0), (0, 1, 0)),
    "south": ((1, 0, 1), (0, 0, 1), (0, 1, 1), (1, 1, 1)),
    "east": ((1, 0, 0), (1, 0, 1), (1, 1, 1), (1, 1, 0)),
    "west": ((0, 0, 1), (0, 0, 0), (0, 1, 0), (0, 1, 1)),
    "up": ((0, 1, 0), (1, 1, 0), (1, 1, 1), (0, 1, 1)),
    "down": ((0, 0, 1), (1, 0, 1), (1, 0, 0), (0, 0, 0)),
}
NORMALS = {"north": (0, 0, -1), "south": (0, 0, 1), "east": (1, 0, 0),
           "west": (-1, 0, 0), "up": (0, 1, 0), "down": (0, -1, 0)}


def r6(v):
    return round(float(v), 4)


# ---------------------------------------------------------------------------
# Model builder
# ---------------------------------------------------------------------------
class Model:
    def __init__(self):
        self.bones = []
        self.by = {}
        for name, parent, pivot, rot in skeleton():
            pivot = MOVED_PIVOTS.get(name, pivot)
            self.bone(name, parent, pivot, rot)

    def bone(self, name, parent, pivot, rot=None):
        assert name not in self.by, name
        b = {"name": name, "pivot": list(pivot)}      # exact PLZ-05 values
        if parent:
            b["parent"] = parent
        if rot:
            b["rotation"] = list(rot)
        b["_cubes"] = []
        self.bones.append(b)
        self.by[name] = b
        return name

    # -- primitives ---------------------------------------------------------
    def cube(self, bone, origin, size, mat, faces=None, rot=None, pivot=None,
             share=None, ctx=None, skip=()):
        assert all(s > 0 for s in size), (bone, origin, size)
        c = {"origin": [r6(v) for v in origin], "size": [r6(v) for v in size],
             "mat": mat, "faces": dict(faces or {}), "share": share,
             "ctx": ctx or {}, "skip": set(skip)}
        if rot is not None and any(abs(v) > 1e-9 for v in rot):
            c["rotation"] = [r6(v) for v in rot]
            c["pivot"] = [r6(v) for v in pivot]
        self.by[bone]["_cubes"].append(c)
        return c

    def box(self, bone, x0, y0, z0, x1, y1, z1, mat="camo", faces=None, **kw):
        xa, xb = sorted((x0, x1)); ya, yb = sorted((y0, y1)); za, zb = sorted((z0, z1))
        return self.cube(bone, [xa, ya, za], [xb - xa, yb - ya, zb - za], mat, faces, **kw)

    def mbox(self, bone, x0, y0, z0, x1, y1, z1, mat="camo", faces=None, bone_r=None, **kw):
        """Box on the left (+X) side and its mirror on the right."""
        self.box(bone, x0, y0, z0, x1, y1, z1, mat, faces, **kw)
        mf = None
        if faces:
            mf = {({"east": "west", "west": "east"}.get(k, k)): v for k, v in faces.items()}
        self.box(bone_r or bone, -x0, y0, z0, -x1, y1, z1, mat, mf, **kw)

    def plate(self, bone, x0, x1, a, b, t, toward, mat="camo", faces=None, extend=0.0, **kw):
        """Plate whose one face runs along a->b in the (z, y) plane.

        a, b: (z, y) points. Thickness t is added on the side of the vector
        `toward` (dz, dy). Rotation is about X only.
        """
        dz, dy = b[0] - a[0], b[1] - a[1]
        length = math.hypot(dz, dy)
        rx = math.degrees(math.atan2(dy, dz))
        th = math.radians(-rx)
        ny, nz = math.cos(th), math.sin(th)     # where local +Y ends up (y, z)
        up = (toward[1] * ny + toward[0] * nz) > 0
        ylo = a[1] if up else a[1] - t
        return self.cube(bone, [min(x0, x1), ylo, a[0] - extend],
                         [abs(x1 - x0), t, length + 2 * extend], mat, faces,
                         rot=[rx, 0, 0], pivot=[(x0 + x1) / 2, a[1], a[0]], **kw)

    def mplate(self, bone, x0, x1, a, b, t, toward, mat="camo", faces=None, **kw):
        self.plate(bone, x0, x1, a, b, t, toward, mat, faces, **kw)
        self.plate(bone, -x0, -x1, a, b, t, toward, mat, faces, **kw)

    def prism_z(self, bone, cx, cy, z0, z1, a, n=8, mat="gun", faces=None, share=None, ctx=None):
        """n-gon prism along Z with apothem a (union of n/2 rotated slabs)."""
        za, zb = sorted((z0, z1))
        h = a * math.tan(math.pi / n)
        for k in range(n // 2):
            ang = k * 360.0 / n
            ins = 0.012 * k
            sh = None if share is None else (share, k)
            if abs(ang - 90) < 1e-6:
                self.cube(bone, [cx - h, cy - a, za + ins], [2 * h, 2 * a, zb - za - 2 * ins],
                          mat, faces, share=sh, ctx=ctx)
            else:
                self.cube(bone, [cx - a, cy - h, za + ins], [2 * a, 2 * h, zb - za - 2 * ins],
                          mat, faces, rot=[0, 0, ang], pivot=[cx, cy, (za + zb) / 2],
                          share=sh, ctx=ctx)

    def prism_x(self, bone, x0, x1, cy, cz, a, n=8, mat="metal", faces=None, share=None,
                ctx=None, phase=0.0):
        """n-gon prism along X (wheels) with apothem a."""
        xa, xb = sorted((x0, x1))
        h = a * math.tan(math.pi / n)
        for k in range(n // 2):
            ang = phase + k * 360.0 / n
            ins = 0.012 * k
            sh = None if share is None else (share, k)
            if abs(ang % 180) < 1e-6:
                self.cube(bone, [xa + ins, cy - h, cz - a], [xb - xa - 2 * ins, 2 * h, 2 * a],
                          mat, faces, share=sh, ctx=ctx)
            elif abs(ang % 180 - 90) < 1e-6:
                self.cube(bone, [xa + ins, cy - a, cz - h], [xb - xa - 2 * ins, 2 * a, 2 * h],
                          mat, faces, share=sh, ctx=ctx)
            else:
                self.cube(bone, [xa + ins, cy - h, cz - a], [xb - xa - 2 * ins, 2 * h, 2 * a],
                          mat, faces, rot=[ang, 0, 0], pivot=[(xa + xb) / 2, cy, cz],
                          share=sh, ctx=ctx)

    def disc_y(self, bone, cx, cz, y0, y1, a, mat="hatch", faces=None):
        """Flat octagonal disc (hatch lid, ring) lying in the XZ plane."""
        h = a * math.tan(math.pi / 8)
        self.box(bone, cx - a, y0, cz - h, cx + a, y1, cz + h, mat, faces)
        self.box(bone, cx - h, y0 + 0.01, cz - a, cx + h, y1 - 0.01, cz + a, mat, faces)
        for k, ang in enumerate((45.0, -45.0)):
            e = 0.02 * (k + 1)
            self.cube(bone, [cx - a, y0 + e, cz - h], [2 * a, y1 - y0 - 2 * e, 2 * h], mat, faces,
                      rot=[0, ang, 0], pivot=[cx, (y0 + y1) / 2, cz])

    def cube_count(self):
        return sum(len(b["_cubes"]) for b in self.bones)


# ---------------------------------------------------------------------------
# Geometry
# ---------------------------------------------------------------------------
P_LOCK = (20.87326, -85.1823)          # paojia pivot (y, z)
GLACIS_NOSE = (-90.0, 21.0)            # (z, y)
GLACIS_TOP = (-62.0, 31.2)
DECK_Y = 31.2
ENGINE_DECK_Y = 28.6
HULL_REAR = 57.0
TURRET_FRONT, TURRET_REAR = -34.0, 62.0
TURRET_X = 30.5
TURRET_Y0, TURRET_Y1 = 32.0, 59.5
ROAD_Z = [-54.288, -34.632, -17.784, -0.936, 14.976, 36.504]
ROAD_Y = 7.3008
ROAD_A = 5.8          # road wheel apothem
IDLER = (-77.8752, 14.508, 5.0)
SPROCKET = (50.9184, 13.66195, 5.0)
ROLLERS = [(-40.248, 17.5968), (-8.424, 17.5968), (24.336, 17.5968)]
TRACK_T = 1.2
TRACK_X = (22.8, 35.0)


def build_hull(m: Model):
    B = m.bone("msta_hull", "main", [0, 0, 0])
    D = m.bone("msta_hull_detail", "items", [0, 0, 0])
    # Lower hull tub between the tracks.
    m.box(B, -22.5, 6, -84, 22.5, 21.5, HULL_REAR, "camo", {"down": "under"})
    # Upper glacis: long shallow T-72 style plate, plus its side cheeks.
    m.plate(B, -22.5, 22.5, GLACIS_NOSE, GLACIS_TOP, 8, (1, -1))
    m.box(B, -22.4, 19.5, -86, 22.4, 27, -62, "under")              # filler
    # Lower nose plate with a weld bead along the ridge.
    m.plate(B, -22.5, 22.5, GLACIS_NOSE, (-84.0, 7.0), 6, (1, 0.2))
    m.box(B, -22.6, 20.3, -90.9, 22.6, 21.6, -89.4, "camo_dark")
    # Bolted appliqué strips on the glacis (a few raised bevelled plates).
    for x0, x1 in ((-21, -8.5), (8.5, 21)):
        m.plate(B, x0, x1, (-88.5, 21.9), (-66.0, 30.1), 0.6, (-1, 1), "camo_light")
    # Sponsons over the tracks: high front deck, low engine deck at the rear.
    m.mbox(B, 22.5, 21.5, -62, 34.5, DECK_Y, 24, "camo")
    m.box(B, -22.5, 21.5, -62, 22.5, DECK_Y, 24, "camo")
    m.box(B, -34.5, 21.5, 24, 34.5, ENGINE_DECK_Y, HULL_REAR, "camo")
    # Step between the decks is bevelled.
    m.plate(B, -34.5, 34.5, (24.0, DECK_Y), (27.0, ENGINE_DECK_Y), 1.0, (1, -1), "camo_dark")
    # Bevel along the deck side edges.
    m.mplate(B, 34.5, 35.4, (-62.0, 30.0), (24.0, 30.0), 1.2, (0, -1), "camo_dark")
    # Front fenders over the track run-in and the hull side lip.
    m.mbox(B, 22.5, 21.5, -92.5, 35.6, 22.7, -62, "camo", {"down": "under"})
    m.mbox(B, 34.5, 21.5, -62, 35.6, 22.7, HULL_REAR + 1.5, "camo_dark", {"down": "under"})
    # Mudflaps at the front.
    m.mbox(B, 23.0, 15.5, -93.0, 35.4, 21.5, -92.4, "rubber")
    # Rubber side skirts in segments, each with a tiny bolt strip on top.
    z = -71.0
    for i, L in enumerate((15.5, 16.5, 16.5, 16.5, 16.5, 16.5, 17.0)):
        m.mbox(B, 35.2, 15.0, z, 36.1, 21.5, z + L - 0.5, "skirt", share=("skirt", L))
        m.mbox(B, 35.0, 20.6, z + 0.6, 36.4, 21.4, z + L - 1.1, "metal_dark")
        z += L
    # Hull rear plate (slight slope) with lights and a stowage box.
    m.plate(B, -34.0, 34.0, (HULL_REAR, ENGINE_DECK_Y), (HULL_REAR + 1.8, 9.0), 2.0, (-1, 0), "camo")
    m.box(B, -22.4, 6, HULL_REAR - 1, 22.4, 9.5, HULL_REAR + 2.2, "camo_dark")
    for sx in (1, -1):
        m.box(D, sx * 27.5, 23.4, HULL_REAR + 1.0, sx * 31.0, 25.9, HULL_REAR + 2.1, "metal_dark")
        m.box(D, sx * 28.0, 23.8, HULL_REAR + 2.1, sx * 30.5, 25.5, HULL_REAR + 2.5, "tail")
        # Tow hooks front and rear.
        m.box(D, sx * 12.0, 13.0, -90.0, sx * 15.5, 16.5, -86.5, "metal_dark")
        m.box(D, sx * 12.6, 13.6, -91.4, sx * 14.9, 15.9, -90.0, "metal_dark")
        m.box(D, sx * 12.0, 10.0, HULL_REAR + 1.4, sx * 15.5, 13.5, HULL_REAR + 4.0, "metal_dark")
    m.box(D, -18, 14.0, HULL_REAR + 1.2, 18, 24.5, HULL_REAR + 5.5, "box", ctx={"latch": 2})
    m.box(D, -18.3, 24.5, HULL_REAR + 1.0, 18.3, 25.2, HULL_REAR + 5.8, "camo_dark")
    # Unditching beam on the rear.
    m.prism_x(D, -24, 24, 18.0, HULL_REAR + 7.0, 1.8, 8, "wood")
    for sx in (1, -1):
        m.box(D, sx * 20.0, 15.8, HULL_REAR + 5.5, sx * 21.4, 20.2, HULL_REAR + 9.2, "metal_dark")

    # Headlights with wire guards on the front fenders.
    for sx in (1, -1):
        x0, x1 = sx * 26.0, sx * 31.0
        m.box(D, x0, 22.7, -88.5, x1, 26.0, -84.5, "metal_dark")
        m.box(D, sx * 26.6, 23.2, -89.0, sx * 30.4, 25.6, -88.5, "light")
        m.box(D, sx * 25.6, 22.7, -90.2, sx * 26.1, 26.8, -89.6, "metal_dark")
        m.box(D, sx * 30.9, 22.7, -90.2, sx * 31.4, 26.8, -89.6, "metal_dark")
        m.box(D, sx * 25.6, 26.4, -90.2, sx * 31.4, 26.9, -84.5, "metal_dark")
        # Side marker lights.
        m.box(D, sx * 34.7, 22.7, -91.5, sx * 35.5, 24.1, -90.0, "amber")

    # Driver's hatch (left of the travel lock), hinge and two periscopes.
    hx, hz = 12.0, -53.0
    m.box(D, hx - 6.5, DECK_Y, hz - 5.5, hx + 6.5, DECK_Y + 0.6, hz + 5.5, "camo_dark")
    m.disc_y(D, hx, hz, DECK_Y + 0.6, DECK_Y + 1.5, 4.6, "hatch")
    m.box(D, hx - 5.0, DECK_Y + 0.6, hz + 4.6, hx + 5.0, DECK_Y + 1.6, hz + 5.8, "metal_dark")
    m.box(D, hx - 1.2, DECK_Y + 1.4, hz - 3.2, hx + 1.2, DECK_Y + 2.0, hz - 1.0, "metal_dark")
    for px in (hx - 3.0, hx + 3.0):
        m.box(D, px - 1.3, DECK_Y, hz - 8.6, px + 1.3, DECK_Y + 1.9, hz - 6.2, "camo_dark",
              {"north": "glass"})
    # Engine deck grilles and access panels.
    for sx in (1, -1):
        m.box(D, sx * 8.0, ENGINE_DECK_Y, 30.0, sx * 30.0, ENGINE_DECK_Y + 0.4, 52.0, "grille")
        m.box(D, sx * 8.0, ENGINE_DECK_Y, 26.0, sx * 30.0, ENGINE_DECK_Y + 0.3, 29.4, "camo_dark")
    m.box(D, -6.5, ENGINE_DECK_Y, 28.0, 6.5, ENGINE_DECK_Y + 0.5, 54.0, "camo_light")
    # Exhaust grille on the left sponson (T-72 heritage).
    m.box(D, 35.4, 23.0, 30.0, 36.3, 27.6, 40.0, "exhaust")
    m.box(D, 35.4, 22.4, 29.4, 36.6, 23.0, 40.6, "metal_dark")
    m.box(D, 35.4, 27.6, 29.4, 36.6, 28.2, 40.6, "metal_dark")
    # Fender stowage boxes with lids and latches.
    for sx, z0, z1 in ((1, -46.0, -30.0), (-1, -58.0, -40.0), (-1, -36.0, -22.0)):
        m.box(D, sx * 26.0, DECK_Y, z0, sx * 34.4, DECK_Y + 3.8, z1, "box", ctx={"latch": 2})
        m.box(D, sx * 25.8, DECK_Y + 3.8, z0 - 0.2, sx * 34.6, DECK_Y + 4.4, z1 + 0.2, "camo_dark")
    # Spare track links on the glacis.
    for i in range(5):
        z0 = -86.5 + i * 3.4
        y0 = 21.0 + (z0 + 90) * (10.2 / 28) + 0.6
        m.box(D, -20.5, y0, z0, -9.5, y0 + 1.1, z0 + 3.0, "track_link", share=("sparelink",))
    # Tow cables along both upper hull sides with eye ends.
    for sx in (1, -1):
        x0 = sx * 35.5
        m.prism_z(D, x0, 29.4, -60.0, 20.0, 0.55, 8, "cable")
        m.box(D, x0 - 0.9, 28.3, -62.6, x0 + 0.9, 30.5, -59.8, "metal_dark")
        m.box(D, x0 - 0.9, 28.3, 19.8, x0 + 0.9, 30.5, 22.6, "metal_dark")
        for zc in (-40.0, -10.0, 12.0):
            m.box(D, x0 - 0.8, 28.6, zc, x0 + 0.8, 30.2, zc + 1.4, "metal_dark")
    # Hinge cheeks for the travel lock and the rest pad on the deck.
    for sx in (1, -1):
        m.box(D, sx * 6.2, 20.4, -88.4, sx * 7.8, 25.2, -83.6, "metal_dark")
        m.box(D, sx * 3.5, DECK_Y, -62.8, sx * 6.3, DECK_Y + 1.0, -59.2, "metal_dark")
    # Suspension arm housings along the lower hull.
    for side in (1, -1):
        for zc in ROAD_Z:
            m.box(D, side * 21.5, 4.6, zc - 1.6, side * 24.2, 9.6, zc + 1.6, "under",
                  share=("arm",))


def belt_path(circles, n_samples=72):
    pts = []
    for cz, cy, r in circles:
        for k in range(n_samples):
            a = 2 * math.pi * k / n_samples
            pts.append((cz + r * math.cos(a), cy + r * math.sin(a)))
    pts = sorted(set(pts))

    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
    lower, upper = [], []
    for p in pts:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], p) <= 0:
            lower.pop()
        lower.append(p)
    for p in reversed(pts):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], p) <= 0:
            upper.pop()
        upper.append(p)
    return lower[:-1] + upper[:-1]       # counter-clockwise in (z, y)


def build_tracks(m: Model):
    circles = [(IDLER[0], IDLER[1], IDLER[2] + 0.4)]
    circles += [(z, ROAD_Y, 6.1) for z in ROAD_Z]
    circles += [(SPROCKET[0], SPROCKET[1], SPROCKET[2] + 0.6)]
    circles += [(z, y, 2.25) for z, y in ROLLERS]
    hull = belt_path(circles)
    # Offset the inner path outward by half the link thickness.
    cz = sum(p[0] for p in hull) / len(hull)
    cy = sum(p[1] for p in hull) / len(hull)
    n = len(hull)
    seg = []
    for i in range(n):
        a, b = hull[i], hull[(i + 1) % n]
        seg.append((a, b, math.hypot(b[0] - a[0], b[1] - a[1])))
    perim = sum(s[2] for s in seg)
    pitch = perim / round(perim / 3.35)
    count = int(round(perim / pitch))

    def at(s):
        s %= perim
        for a, b, L in seg:
            if s <= L:
                t = s / L if L else 0
                pz, py = a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t
                dz, dy = (b[0] - a[0]) / L, (b[1] - a[1]) / L
                nz, ny = dy, -dz                 # outward for CCW path
                if (pz - cz) * nz + (py - cy) * ny < 0:
                    nz, ny = -nz, -ny
                return pz + nz * TRACK_T / 2, py + ny * TRACK_T / 2, dz, dy
            s -= L
        raise AssertionError
    for side, bone, sx in (("L", "bone21", 1), ("R", "bone2", -1)):
        x0, x1 = sorted((sx * TRACK_X[0], sx * TRACK_X[1]))
        for k in range(count):
            pz, py, dz, dy = at(k * pitch)
            rx = math.degrees(math.atan2(dy, dz))
            L = pitch - 0.2
            m.cube(bone, [x0, py - TRACK_T / 2, pz - L / 2], [x1 - x0, TRACK_T, L],
                   "track_link", rot=[rx, 0, 0], pivot=[(x0 + x1) / 2, py, pz],
                   share=("link",))
    return count


def build_wheels(m: Model):
    for side, sx in (("L", 1), ("R", -1)):
        # Road wheels: double rubber-tyred discs, dished outer face, hub, cap.
        for i, zc in enumerate(ROAD_Z, start=1):
            bone = f"wheel{side}{i}"
            ctx = {"cy": ROAD_Y, "cz": zc, "r": ROAD_A}
            sh = ("road",)
            m.prism_x(bone, sx * 29.4, sx * 33.6, ROAD_Y, zc, ROAD_A, 12, "rubber",
                      {"east": "roadwheel", "west": "roadwheel"}, share=sh + ("o", side), ctx=ctx)
            m.prism_x(bone, sx * 24.0, sx * 28.2, ROAD_Y, zc, ROAD_A, 12, "rubber",
                      {"east": "roadwheel", "west": "roadwheel"}, share=sh + ("i", side), ctx=ctx)
            m.prism_x(bone, sx * 33.6, sx * 34.0, ROAD_Y, zc, 3.9, 8, "wheel_dish",
                      share=sh + ("d", side), ctx=ctx)
            m.prism_x(bone, sx * 34.0, sx * 34.9, ROAD_Y, zc, 1.7, 8, "hub",
                      share=sh + ("h", side), ctx=ctx)
            m.box(bone, sx * 28.2, ROAD_Y - 1.4, zc - 1.4, sx * 29.4, ROAD_Y + 1.4, zc + 1.4,
                  "metal_dark", share=("axle",))
        # Idler: two steel rims with spokes and a hub.
        zc, yc, a = IDLER
        bone = f"wheel{side}0"
        ctx = {"cy": yc, "cz": zc, "r": a}
        for xa, xb, tag in ((29.6, 33.4, "o"), (24.2, 28.0, "i")):
            m.prism_x(bone, sx * xa, sx * xb, yc, zc, a, 12, "steel",
                      {"east": "idler", "west": "idler"}, share=("idler", tag, side), ctx=ctx)
        m.prism_x(bone, sx * 28.0, sx * 29.6, yc, zc, 2.2, 8, "metal_dark", share=("idlerhub", side))
        m.prism_x(bone, sx * 33.4, sx * 34.4, yc, zc, 1.8, 8, "hub", share=("idlercap", side), ctx=ctx)
        # Drive sprocket: toothed rings around a hub.
        zc, yc, a = SPROCKET
        bone = f"wheel{side}7"
        ctx = {"cy": yc, "cz": zc, "r": a}
        for xa, xb, tag in ((30.4, 33.0, "o"), (24.8, 27.4, "i")):
            m.prism_x(bone, sx * xa, sx * xb, yc, zc, a - 0.6, 12, "sprocket",
                      share=("spr", tag, side), ctx=ctx)
            m.prism_x(bone, sx * (xa + 0.4), sx * (xb - 0.4), yc, zc, a + 0.5, 12, "metal_dark",
                      share=("teeth", tag, side), phase=15.0)
        m.prism_x(bone, sx * 27.4, sx * 30.4, yc, zc, 2.4, 8, "metal_dark", share=("sprhub", side))
        m.prism_x(bone, sx * 33.0, sx * 34.2, yc, zc, 2.0, 8, "hub", share=("sprcap", side), ctx=ctx)
        # Return rollers (static bones in PLZ-05 as well).
        for j, (rz, ry) in enumerate(ROLLERS, start=1):
            m.prism_x(f"smallwhl{side}{j}", sx * 25.0, sx * 31.0, ry, rz, 2.0, 8, "rubber",
                      share=("roller", side))


def build_travel_lock(m: Model):
    """Yoke in `paojia`: lies on the glacis, the lock animation (+67.5 deg about
    paojia's pivot) stands it up so the barrel rests in it."""
    py, pz = P_LOCK
    c = -69.98                                 # lies along the 20 deg glacis
    def piece(x0, x1, s0, s1, w0, w1, mat="camo_dark"):
        m.cube("paojia", [x0, py + s0, pz + w0], [x1 - x0, s1 - s0, w1 - w0], mat,
               rot=[c, 0, 0], pivot=[0, py, pz])
    for sx in (1, -1):
        xa, xb = sorted((sx * 3.9, sx * 5.7))
        piece(xa, xb, -1.2, 27.4, -4.07, -2.07)
        xa, xb = sorted((sx * 3.7, sx * 5.9))
        piece(xa, xb, 25.8, 27.6, -4.3, -1.84, "rubber")
    piece(-5.7, 5.7, 19.9, 21.7, -4.07, -2.07)
    piece(-3.9, 3.9, 21.2, 21.75, -3.9, -2.2, "rubber")        # pad under the tube
    piece(-5.7, 5.7, 3.0, 4.6, -3.9, -2.3)
    piece(-6.0, 6.0, -1.5, 1.5, -4.3, -1.2, "metal_dark")       # hinge knuckle
    piece(-1.0, 1.0, 5.0, 19.9, -3.6, -2.5)                     # centre strut


def build_turret(m: Model):
    T = m.bone("msta_turret", "trtbody", [0, 0, 0])
    S = m.bone("msta_turret_side", "trtitems", [0, 0, 0])
    R = m.bone("msta_roof", "titop", [0, 0, 0])
    G = m.bone("msta_roof_mg", "move_titop1", [0, 0, 0])
    X, y0, y1 = TURRET_X, TURRET_Y0, TURRET_Y1
    f, r = TURRET_FRONT, TURRET_REAR
    # Turret ring and basket skirt (seen under the bustle).
    m.prism_z(T, 0, 31.6, -8.0, 36.0, 1.0, 8, "metal_dark")
    m.box(T, -23.0, 30.9, -8.0, 23.0, 32.0, 36.0, "metal_dark")
    # Main welded box: sides with a bevel at the top and the bottom edges.
    m.box(T, -X, y0 + 1.5, f + 4, X, y1 - 2.0, r - 1.5, "camo", {"down": "under"})
    m.box(T, -X + 2.0, y0, f + 4, X - 2.0, y0 + 1.5, r - 1.5, "camo", {"down": "under"})
    m.mplate(T, X - 2.0, X, (f + 4, y0), (r - 1.5, y0), 1.5, (0, 1), "camo",
             {"down": "under"})
    # Roof (separate, so the roof edges get proper bevels).
    m.box(T, -X + 2.2, y1 - 2.0, f + 6, X - 2.2, y1, r - 3.5, "camo_roof")
    for sx in (1, -1):
        # 45 deg bevel along the roof side edges.
        xa, xb = sorted((sx * (X - 2.2), sx * X))
        m.cube(T, [xa, y1 - 2.0, f + 6], [2.2, 2.2, r - 3.5 - f - 6], "camo",
               rot=[0, 0, sx * 45.0], pivot=[sx * (X - 2.2), y1 - 2.0, (f + r) / 2])
    # Rear: bustle with a chamfered lower edge and a rear plate.
    m.plate(T, -X + 0.4, X - 0.4, (r - 1.5, y1 - 2.0), (r + 0.5, y0 + 6.0), 3.0, (-1, 0), "camo")
    m.plate(T, -X + 0.4, X - 0.4, (r - 1.5, y0), (r + 0.5, y0 + 6.0), 2.0, (-1, 1), "camo",
            {"down": "under"})
    m.plate(T, -X + 2.2, X - 2.2, (r - 3.5, y1), (r - 1.5, y1 - 2.0), 1.6, (-1, -1), "camo")
    # Front: cheeks either side of the embrasure, sloped upper front plates.
    for sx in (1, -1):
        xa, xb = sorted((sx * 10.5, sx * X))
        m.box(T, xa, y0, f, xb, y1 - 7.0, f + 4.2, "camo")
        m.plate(T, xa, xb, (f, y1 - 7.0), (f + 7.0, y1), 4.0, (1, -1), "camo")
        m.plate(T, xa, xb, (f + 0.2, y0 + 1.8), (f + 3.0, y0), 2.0, (1, 1), "camo",
                {"down": "under"})
    m.box(T, -10.5, y0, f + 1.0, 10.5, y0 + 4.0, f + 4.2, "camo")
    m.box(T, -10.4, y0 + 4.0, f + 4.0, 10.4, y1 - 2.0, f + 4.3, "recess")
    m.box(T, -10.4, y1 - 2.0, f + 4.0, 10.4, y1 - 0.3, f + 7.0, "recess")
    # Front cheek bolt rows / weld lines (thin raised strips).
    for sx in (1, -1):
        xa, xb = sorted((sx * 10.5, sx * 11.3))
        m.box(T, xa, y0 + 4.0, f - 0.4, xb, y1 - 7.0, f + 0.2, "camo_dark")

    # Side hatches (loading doors) with hinges and handles; stowage boxes.
    for sx in (1, -1):
        xo = sx * X
        xa, xb = sorted((xo, xo + sx * 0.6))
        m.box(S, xa, y0 + 6.0, 3.0, xb, y1 - 6.0, 19.0, "hatch")
        for yh in (y0 + 8.0, y1 - 9.5):
            xa2, xb2 = sorted((xo, xo + sx * 1.3))
            m.box(S, xa2, yh, 18.4, xb2, yh + 1.6, 20.4, "metal_dark")
        xa3, xb3 = sorted((xo + sx * 0.6, xo + sx * 1.4))
        m.box(S, xa3, y0 + 13.0, 5.0, xb3, y0 + 13.6, 9.0, "metal_dark")
        # Stowage boxes on the rear half of the sides.
        for z0, z1 in ((21.5, 38.5), (39.5, 58.5)):
            xa4, xb4 = sorted((xo, xo + sx * 3.8))
            m.box(S, xa4, y0 + 3.0, z0, xb4, y0 + 16.0, z1, "box", ctx={"latch": 2})
            xa5, xb5 = sorted((xo, xo + sx * 4.1))
            m.box(S, xa5, y0 + 16.0, z0 - 0.2, xb5, y0 + 16.7, z1 + 0.2, "camo_dark")
            for zl in (z0 + 3.0, z1 - 4.0):
                xa6, xb6 = sorted((xo + sx * 3.8, xo + sx * 4.2))
                m.box(S, xa6, y0 + 13.0, zl, xb6, y0 + 15.2, zl + 1.0, "metal_dark")
        # Grab rails on the turret side above the boxes.
        xa7, xb7 = sorted((xo, xo + sx * 1.6))
        m.box(S, xa7, y1 - 4.2, 22.0, xb7, y1 - 3.6, 56.0, "metal_dark")
        for zr in (22.0, 39.0, 55.4):
            m.box(S, xa7, y1 - 5.0, zr, xb7, y1 - 3.6, zr + 0.6, "metal_dark")
    # Rear stowage basket and the rolled tarpaulin.
    m.box(S, -24.0, y0 + 7.0, r + 0.2, 24.0, y0 + 8.0, r + 5.0, "metal_dark")
    m.box(S, -24.0, y0 + 8.0, r + 4.4, 24.0, y0 + 15.0, r + 5.0, "grille")
    for sx in (1, -1):
        m.box(S, sx * 23.4, y0 + 8.0, r + 0.2, sx * 24.0, y0 + 15.0, r + 5.0, "metal_dark")
    m.prism_x(S, -20.0, 20.0, y0 + 11.0, r + 2.4, 2.4, 8, "tarp")
    # Smoke grenade launchers: two banks of four on the front cheeks.
    for sx in (1, -1):
        m.box(S, sx * 19.5, y1 - 8.8, f - 1.5, sx * 29.0, y1 - 7.6, f + 1.0, "metal_dark")
        for k in range(4):
            xc = sx * (21.0 + k * 2.25)
            m.prism_z(S, xc, y1 - 6.6, f - 4.2, f + 0.5, 0.95, 8, "camo_dark",
                      {"north": "bore"})
    # Dog tags sit on the side walls at z -9.7..0.3 (left free on purpose).

    # Roof: commander's cupola on the right (-X), gunner's hatch on the left.
    cx, cz = -18.0, 30.0
    # Cupola: octagonal ring built from slabs around Y.
    for k in range(4):
        ang = k * 45.0
        hh = 7.0 * math.tan(math.pi / 8)
        if k == 0:
            m.box(R, cx - 7.0, y1, cz - hh, cx + 7.0, y1 + 3.4, cz + hh, "camo")
        elif k == 2:
            m.box(R, cx - hh, y1 + 0.012, cz - 7.0, cx + hh, y1 + 3.388, cz + 7.0, "camo")
        else:
            m.cube(R, [cx - 7.0, y1 + 0.012 * k, cz - hh], [14.0, 3.4 - 0.024 * k, 2 * hh], "camo",
                   rot=[0, ang, 0], pivot=[cx, y1, cz])
    for k in range(4):  # vision blocks around the cupola
        ang = k * 90.0 + 45.0
        dx, dz = math.sin(math.radians(ang)) * 6.6, -math.cos(math.radians(ang)) * 6.6
        m.cube(R, [cx + dx - 1.3, y1 + 1.4, cz + dz - 0.6], [2.6, 1.4, 1.2], "glass",
               rot=[0, -ang, 0], pivot=[cx + dx, y1 + 2.1, cz + dz])
    m.box(R, cx - 1.3, y1 + 1.4, cz - 7.4, cx + 1.3, y1 + 2.8, cz - 6.2, "glass")
    # Cupola hatch lid (round from two slabs) with hinge and handle.
    hh = 5.6 * math.tan(math.pi / 8)
    m.box(R, cx - 5.6, y1 + 3.4, cz - hh, cx + 5.6, y1 + 4.4, cz + hh, "hatch")
    m.box(R, cx - hh, y1 + 3.41, cz - 5.6, cx + hh, y1 + 4.39, cz + 5.6, "hatch")
    for ang in (45.0, -45.0):
        m.cube(R, [cx - 5.6, y1 + 3.42, cz - hh], [11.2, 0.96, 2 * hh], "hatch",
               rot=[0, ang, 0], pivot=[cx, y1 + 3.9, cz])
    m.box(R, cx - 3.0, y1 + 3.4, cz + 5.2, cx + 3.0, y1 + 5.0, cz + 6.6, "metal_dark")
    m.box(R, cx - 1.0, y1 + 4.4, cz - 2.0, cx + 1.0, y1 + 5.0, cz + 1.0, "metal_dark")
    # Gunner's hatch on the left.
    gx, gz = 16.0, 26.0
    m.box(R, gx - 6.0, y1, gz - 6.0, gx + 6.0, y1 + 0.8, gz + 6.0, "camo_dark")
    m.box(R, gx - 5.2, y1 + 0.8, gz - 5.2, gx + 5.2, y1 + 1.6, gz + 5.2, "hatch")
    m.box(R, gx - 4.0, y1 + 0.8, gz + 5.2, gx + 4.0, y1 + 2.2, gz + 6.6, "metal_dark")
    m.box(R, gx - 1.0, y1 + 1.6, gz - 3.0, gx + 1.0, y1 + 2.2, gz, "metal_dark")
    # Gunner's sight housing with armoured doors, left front of the roof.
    m.box(R, 11.5, y1 - 0.5, f + 9.0, 21.5, y1 + 5.6, f + 19.0, "camo")
    m.plate(R, 11.5, 21.5, (f + 9.0, y1 + 5.6), (f + 12.0, y1 + 7.2), 1.6, (1, -1), "camo")
    m.box(R, 12.6, y1 + 1.2, f + 8.4, 20.4, y1 + 4.8, f + 9.0, "glass")
    m.box(R, 11.9, y1 + 4.8, f + 7.6, 21.1, y1 + 5.6, f + 9.0, "camo_dark")
    # Second (direct-fire) sight on the right front corner.
    m.box(R, -24.0, y1 - 0.5, f + 9.0, -18.0, y1 + 3.6, f + 15.0, "camo")
    m.box(R, -23.4, y1 + 0.8, f + 8.5, -18.6, y1 + 3.0, f + 9.0, "glass")
    # Ventilator dome, loader's hatch, antenna bases and antennas.
    m.prism_x(R, -5.0, 3.0, y1 + 0.4, 46.0, 2.6, 8, "grille", {"east": "camo", "west": "camo"})
    m.box(R, -2.0, y1, 6.0, 8.0, y1 + 0.9, 16.0, "hatch")
    m.box(R, 2.0, y1 + 0.9, 15.2, 6.0, y1 + 1.9, 16.4, "metal_dark")
    A = m.bone("msta_antenna", "titop", [0, 0, 0])
    for sx, h in ((1, 30.0), (-1, 24.0)):
        m.box(R, sx * 24.0 - 1.2, y1, 52.0, sx * 24.0 + 1.2, y1 + 2.4, 54.4, "metal_dark")
        m.box(A, sx * 24.0 - 0.35, y1 + 2.4, 52.85, sx * 24.0 + 0.35, y1 + 2.4 + h, 53.55,
              "antenna")
    # Roof weld lines / lifting eyes.
    for sx in (1, -1):
        for zl in (f + 22.0, r - 8.0):
            m.box(R, sx * 26.0 - 1.0, y1, zl, sx * 26.0 + 1.0, y1 + 1.4, zl + 0.7, "metal_dark")
    m.box(R, -X + 2.2, y1, 3.0, X - 2.2, y1 + 0.25, 3.6, "camo_dark")
    # Searchlight next to the gunner's sight.
    m.box(R, 23.5, y1 + 0.2, f + 10.0, 28.0, y1 + 4.0, f + 14.0, "metal_dark")
    m.box(R, 24.0, y1 + 0.7, f + 9.6, 27.5, y1 + 3.5, f + 10.0, "light")

    # Commander's remote 12.7 mm MG on the cupola (hidden in gunner's 1st person).
    m.box(G, cx - 0.9, y1 + 5.0, cz - 4.5, cx + 0.9, y1 + 7.6, cz - 2.5, "metal_dark")
    m.box(G, cx - 1.8, y1 + 7.4, cz - 10.0, cx + 1.8, y1 + 9.8, cz - 1.0, "gun_metal")
    m.box(G, cx + 1.8, y1 + 6.6, cz - 8.0, cx + 4.6, y1 + 9.4, cz - 3.0, "box")
    m.prism_z(G, cx, y1 + 8.6, cz - 10.0, cz - 30.0, 0.7, 8, "gun_metal", {"north": "bore"})
    m.prism_z(G, cx, y1 + 8.6, cz - 31.5, cz - 29.0, 0.95, 8, "gun_metal", {"north": "bore"})
    m.box(G, cx - 2.8, y1 + 7.0, cz - 13.0, cx + 2.8, y1 + 11.4, cz - 12.4, "camo_dark")
    m.box(G, cx - 0.4, y1 + 9.8, cz - 6.0, cx + 0.4, y1 + 11.2, cz - 5.2, "metal_dark")


def build_gun(m: Model):
    C = m.bone("msta_cradle", "barrel", [0, 0, 0])
    Tb = m.bone("msta_tube", "tube", [0, 0, 0])
    gy = TRUNNION[1]
    # Gun shield / mantlet: bevelled box sitting in the embrasure.
    m.box(C, -9.5, gy - 7.5, -38.0, 9.5, gy + 7.5, -30.0, "camo")
    for sy in (1, -1):
        ya, yb = sorted((gy + sy * 7.5, gy + sy * 9.0))
        m.box(C, -8.0, ya, -37.0, 8.0, yb, -29.0, "camo")
    # Bolt rows on the mantlet face.
    for sy in (1, -1):
        ya, yb = sorted((gy + sy * 6.0, gy + sy * 6.7))
        m.box(C, -8.5, ya, -38.5, 8.5, yb, -38.0, "metal_dark")
    # Cradle sleeve and the two recoil cylinders above the barrel.
    m.prism_z(C, 0, gy, -52.0, -38.0, 4.7, 8, "camo_dark")
    m.prism_z(C, 0, gy, -53.5, -52.0, 4.2, 8, "gun_metal")
    for sx in (1, -1):
        m.prism_z(C, sx * 3.3, gy + 6.2, -60.0, -38.0, 1.75, 8, "gun_metal")
        m.prism_z(C, sx * 3.3, gy + 6.2, -61.6, -60.0, 2.05, 8, "camo_dark")
        m.box(C, sx * 3.3 - 0.8, gy + 3.5, -50.0, sx * 3.3 + 0.8, gy + 4.6, -44.0, "metal_dark")
    m.box(C, -5.6, gy + 4.4, -58.0, 5.6, gy + 5.0, -54.0, "camo_dark")
    # Tube (recoils): breech, thick rear, thinner chase, bore evacuator,
    # double-baffle muzzle brake.
    m.box(Tb, -5.0, gy - 5.0, -26.0, 5.0, gy + 5.0, -8.0, "gun_metal")
    m.prism_z(Tb, 0, gy, -96.0, -26.0, 3.5, 8, "gun")
    m.prism_z(Tb, 0, gy, -100.0, -96.0, 3.3, 8, "gun")
    m.prism_z(Tb, 0, gy, -191.0, -100.0, 3.0, 8, "gun")
    m.prism_z(Tb, 0, gy, -152.0, -136.0, 4.3, 8, "gun")
    m.prism_z(Tb, 0, gy, -154.0, -152.0, 3.75, 8, "gun")
    m.prism_z(Tb, 0, gy, -136.0, -134.0, 3.75, 8, "gun")
    m.prism_z(Tb, 0, gy, -145.0, -144.4, 4.45, 8, "gun_metal")
    m.prism_z(Tb, 0, gy, -188.0, -186.0, 3.4, 8, "gun_metal")
    # Muzzle brake: front and rear baffles, top/bottom bars, open side ports.
    m.box(Tb, -5.0, gy - 3.9, MUZZLE_Z, 5.0, gy + 3.9, MUZZLE_Z + 4.5, "brake",
          {"north": "bore_brake"})
    m.box(Tb, -4.7, gy - 3.7, MUZZLE_Z + 9.0, 4.7, gy + 3.7, MUZZLE_Z + 13.5, "brake")
    for sy in (1, -1):
        ya, yb = sorted((gy + sy * 2.6, gy + sy * 3.7))
        m.box(Tb, -4.6, ya, MUZZLE_Z + 4.5, 4.6, yb, MUZZLE_Z + 9.0, "brake")
    m.box(Tb, -4.9, gy - 3.8, MUZZLE_Z + 4.4, 4.9, gy + 3.8, MUZZLE_Z + 4.6, "brake")


def build_model():
    m = Model()
    build_hull(m)
    links = build_tracks(m)
    build_wheels(m)
    build_travel_lock(m)
    build_turret(m)
    build_gun(m)
    return m, links


# ---------------------------------------------------------------------------
# Pose / transforms
# ---------------------------------------------------------------------------
def bone_matrices(bones, pose=None):
    pose = pose or {}
    by = {b["name"]: b for b in bones}
    cache = {}

    def get(name):
        if name in cache:
            return cache[name]
        b = by[name]
        pz = pose.get(name, [0, 0, 0])
        if isinstance(pz, dict):          # {"rot": [...], "pos": [...]}
            pos, pz = pz.get("pos", [0, 0, 0]), pz.get("rot", [0, 0, 0])
        else:
            pos = [0, 0, 0]
        rot = np.array(b.get("rotation", [0, 0, 0]), float) + np.array(pz, float)
        local = affine(rot, b["pivot"])
        local[:3, 3] += np.array(pos, float)
        mat = get(b["parent"]) @ local if b.get("parent") else local
        cache[name] = mat
        return mat
    for b in bones:
        get(b["name"])
    return cache


def cube_world_corners(cube, bone_m):
    o = np.array(cube["origin"], float)
    s = np.array(cube["size"], float)
    infl = float(cube.get("inflate", 0) or 0)
    o, s = o - infl, s + 2 * infl
    cm = affine(cube.get("rotation"), cube.get("pivot", [0, 0, 0]))
    full = bone_m @ cm
    out = {}
    for face, corners in FACE_CORNERS.items():
        local = np.array([o + s * np.array(c) for c in corners])
        world = (full[:3, :3] @ local.T).T + full[:3, 3]
        n = full[:3, :3] @ np.array(NORMALS[face], float)
        out[face] = (local, world, n)
    return out


# ---------------------------------------------------------------------------
# Texture painting (all procedural, sampled in world space)
# ---------------------------------------------------------------------------
def _hash(ix, iy, iz, seed):
    h = (ix.astype(np.int64) * 73856093) ^ (iy.astype(np.int64) * 19349663) \
        ^ (iz.astype(np.int64) * 83492791) ^ np.int64(seed * 2654435761 % (1 << 31))
    h = h & 0xFFFFFFFF
    h = ((h ^ (h >> 15)) * 2246822519) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 3266489917) & 0xFFFFFFFF
    h = h ^ (h >> 16)
    return (h & 0xFFFF).astype(float) / 65535.0


def vnoise(p, scale, seed):
    q = p / scale
    i = np.floor(q).astype(np.int64)
    f = q - i
    f = f * f * (3 - 2 * f)
    acc = np.zeros(len(p))
    for dx in (0, 1):
        wx = f[:, 0] if dx else 1 - f[:, 0]
        for dy in (0, 1):
            wy = f[:, 1] if dy else 1 - f[:, 1]
            for dz in (0, 1):
                wz = f[:, 2] if dz else 1 - f[:, 2]
                acc += wx * wy * wz * _hash(i[:, 0] + dx, i[:, 1] + dy, i[:, 2] + dz, seed)
    return acc


GREEN = np.array([70, 85, 59], float)      # muted Russian protective green
GREEN_DK = np.array([50, 62, 44], float)
BROWN = np.array([86, 76, 59], float)
UNDER = np.array([38, 42, 32], float)
DUST = np.array([96, 88, 70], float)

FLAT = {
    "under": UNDER, "recess": (28, 31, 25), "rubber": (30, 31, 29),
    "metal_dark": (46, 49, 42), "steel": (78, 80, 74), "glass": (44, 66, 72),
    "light": (196, 190, 150), "amber": (170, 120, 50), "tail": (120, 40, 34),
    "antenna": (34, 36, 32), "cable": (58, 58, 54), "wood": (92, 74, 54),
    "tarp": (86, 84, 62), "gun_metal": (50, 56, 44), "brake": (52, 58, 45),
    "track_link": (54, 54, 51), "hub": (60, 74, 48), "drum": (62, 76, 50),
    "bore": (14, 14, 13), "bore_brake": (14, 14, 13), "wheel_dish": (62, 78, 50),
    "skirt": (44, 52, 38), "exhaust": (32, 32, 30), "grille": (40, 46, 34),
    "sprocket": (60, 74, 48), "roadwheel": (62, 78, 50), "idler": (78, 80, 74),
}
CAMO = {"camo", "camo_dark", "camo_light", "camo_roof", "hatch", "box", "gun", "skirt"}
NO_BORDER = {"glass", "light", "amber", "tail", "antenna", "cable", "bore", "rubber"}


def camo(p):
    n1 = 0.68 * vnoise(p, 21.0, 11) + 0.32 * vnoise(p, 7.5, 12)
    n2 = 0.68 * vnoise(p, 25.0, 21) + 0.32 * vnoise(p, 8.5, 22)
    col = np.repeat(GREEN[None], len(p), 0)
    brown = n2 > 0.62
    dark = n1 > 0.61
    col[brown] = BROWN
    col[dark] = GREEN_DK
    return col


def paint(mat, P, Lc, uv, info):
    """Return (N,3) colours for texels at world points P (rest pose)."""
    n = len(P)
    nrm = info["normal"]
    ctx = info["ctx"]
    if mat in ("camo", "hatch", "box", "skirt", "camo_dark", "camo_light", "camo_roof", "gun"):
        col = camo(P)
        if mat == "camo_dark":
            col *= 0.84
        elif mat == "camo_light":
            col = col * 1.06 + 2
        elif mat == "skirt":
            col *= 0.8
        elif mat == "gun":
            col = col * 0.82 + np.array([2, 1, 0])
    else:
        col = np.repeat(np.array(FLAT[mat], float)[None], n, 0)
    u, v = uv[:, 0], uv[:, 1]              # 0..1 across the face, v=0 at top
    W, H = info["px"]
    if mat == "track_link":
        s = Lc[:, 2] - info["local_origin"][2]
        x = Lc[:, 0] - info["local_origin"][0]
        L = info["local_size"][2]
        w = info["local_size"][0]
        lug = (np.abs(s - L / 2) < L * 0.22)
        col[lug] = (66, 66, 62)
        horn = np.abs(x - w / 2) < 1.4
        col[horn & ~lug] = (44, 44, 42)
        edge = (x < 0.9) | (x > w - 0.9)
        col[edge] = (40, 40, 38)
    elif mat in ("roadwheel", "idler", "sprocket", "wheel_dish", "hub"):
        dy = P[:, 1] - ctx["cy"]
        dz = P[:, 2] - ctx["cz"]
        rr = np.hypot(dy, dz)
        R = ctx["r"]
        ang = np.arctan2(dy, dz)
        if mat == "roadwheel":
            col[rr > R - 1.4] = (30, 31, 29)
            col[(rr > R - 1.9) & (rr <= R - 1.4)] = (46, 52, 40)
            holes = (np.abs(rr - R * 0.52) < 0.75) & (np.cos(ang * 6) > 0.6)
            col[holes] = (36, 42, 32)
        elif mat == "idler":
            col[rr > R - 1.0] = (60, 62, 58)
            spoke = (rr < R - 1.6) & (rr > 2.2) & (np.cos(ang * 5) < 0.2)
            col[spoke] = (28, 30, 27)
        elif mat == "sprocket":
            win = (rr < R - 1.8) & (rr > 2.4) & (np.cos(ang * 6) < -0.3)
            col[win] = (26, 28, 25)
        elif mat == "wheel_dish":
            col[np.abs(rr - 2.3) < 0.5] = (48, 60, 40)
        elif mat == "hub":
            col[rr < 0.8] = (40, 46, 34)
            bolts = (np.abs(rr - 1.25) < 0.35) & (np.cos(ang * 4) > 0.5)
            col[bolts] = (88, 92, 80)
    elif mat == "grille":
        stripe = (np.floor((P[:, 2] if abs(nrm[1]) > 0.5 else P[:, 1]) * 1.1) % 2) == 0
        col[stripe] = (26, 29, 22)
    elif mat == "exhaust":
        col[(np.floor(P[:, 1] * 1.2) % 2) == 0] = (20, 20, 19)
    elif mat in ("bore", "bore_brake"):
        rr = np.hypot(P[:, 0], P[:, 1] - TRUNNION[1])
        if mat == "bore_brake":
            col[:] = (52, 58, 45)
            col[rr < 3.6] = (34, 38, 31)
            col[rr < 2.9] = (10, 10, 9)
    elif mat == "glass":
        col[v < 0.35] = (86, 112, 116)
    elif mat == "light":
        col[(u < 0.15) | (u > 0.85) | (v < 0.15) | (v > 0.85)] = (60, 62, 52)
    elif mat == "box":
        lat = ctx.get("latch", 0)
        if lat and abs(nrm[1]) < 0.5 and W > 6:
            for c in np.linspace(0.25, 0.75, lat):
                sel = (np.abs(u - c) < 1.2 / W) & (v < 0.35)
                col[sel] = (40, 43, 36)
    elif mat == "hatch":
        ring = ((u > 0.1) & (u < 0.9) & (v > 0.1) & (v < 0.9)) & ~((u > 0.16) & (u < 0.84) & (v > 0.16) & (v < 0.84))
        col[ring] *= 0.8
    elif mat == "cable":
        col[(np.floor(P[:, 2] * 1.4) % 2) == 0] = (44, 44, 41)
    elif mat == "drum":
        col[(np.abs(P[:, 0]) % 4.5) < 0.6] = (48, 60, 40)
    elif mat == "wood":
        col[(np.floor(uv[:, 1] * H) % 3) == 0] = (74, 58, 42)
    elif mat == "tarp":
        col[(np.floor(P[:, 0] * 0.35) % 3) == 0] *= 0.86

    # Soft vertical shading on side faces, dirt on low parts, grain.
    if abs(nrm[1]) < 0.7 and mat not in NO_BORDER:
        col *= (1.03 - 0.09 * v)[:, None]
    if mat not in ("glass", "light", "amber", "tail", "bore", "bore_brake"):
        dirt = np.clip((24.0 - P[:, 1]) / 20.0, 0, 1) ** 1.2
        dirt *= 0.35 + 0.65 * vnoise(P, 5.0, 31)
        if mat in ("rubber", "track_link", "roadwheel"):
            dirt *= 0.55
        col = col * (1 - 0.55 * dirt[:, None]) + DUST * (0.55 * dirt[:, None])
        grain = (vnoise(np.floor(P * 1.2), 1.0, 41) - 0.5) * 6
        col += grain[:, None]
        streak = (vnoise(P * np.array([3.0, 0.25, 3.0]), 1.0, 51) - 0.5) * 5
        if abs(nrm[1]) < 0.7:
            col += streak[:, None]
    # Panel lines: darker one-texel border on every face.
    if mat not in NO_BORDER and W >= 3 and H >= 3:
        ix = np.floor(u * W).astype(int)
        iy = np.floor(v * H).astype(int)
        edge = (ix == 0) | (iy == 0) | (ix == W - 1) | (iy == H - 1)
        col[edge] *= 0.8
        top = (iy == 1) & (ix > 0) & (ix < W - 1) & (abs(nrm[1]) < 0.7)
        col[top] *= 1.06
    return np.clip(col, 0, 255)


def under_face(mat, face):
    return face == "down" and mat in CAMO | {"under", "camo_roof"}


class Atlas:
    def __init__(self, size, density):
        self.size, self.density = size, density

    def build(self, model):
        mats = bone_matrices(model.bones)
        regions = []        # dicts with w,h and paint jobs
        keyed = {}
        uv_slots = []       # (cube, face, region)
        for b in model.bones:
            for c in b["_cubes"]:
                corners = cube_world_corners(c, mats[b["name"]])
                for face in FACES:
                    mat = c["faces"].get(face, c["mat"])
                    local, world, nrm = corners[face]
                    wu = np.linalg.norm(world[1] - world[0])
                    wv = np.linalg.norm(world[3] - world[0])
                    if under_face(mat, face):
                        key = ("under",)
                        w = h = 8
                    else:
                        w = max(1, int(round(wu * self.density)))
                        h = max(1, int(round(wv * self.density)))
                        key = None
                        if c["share"] is not None:
                            key = (c["share"], face, mat, w, h)
                    if key is not None and key in keyed:
                        reg = keyed[key]
                    else:
                        reg = {"w": w, "h": h, "mat": "under" if key == ("under",) else mat,
                               "world": world, "local": local, "normal": nrm,
                               "ctx": c["ctx"], "origin": c["origin"], "size": c["size"]}
                        regions.append(reg)
                        if key is not None:
                            keyed[key] = reg
                    uv_slots.append((c, face, reg))
        # Shelf packing, tallest first.
        order = sorted(regions, key=lambda r: (-r["h"], -r["w"]))
        x = y = shelf = 0
        for r in order:
            if x + r["w"] > self.size:
                x, y, shelf = 0, y + shelf, 0
            r["x"], r["y"] = x, y
            x += r["w"]
            shelf = max(shelf, r["h"])
        used_h = y + shelf
        if used_h > self.size:
            return None, used_h
        img = np.zeros((self.size, self.size, 4), np.uint8)
        for r in regions:
            W, H = r["w"], r["h"]
            ii, jj = np.meshgrid(np.arange(W) + 0.5, np.arange(H) + 0.5)
            s = (ii / W).ravel()
            t = (jj / H).ravel()
            c0, c1, c2, c3 = r["world"]
            P = c3[None] + (c2 - c3)[None] * s[:, None] + (c0 - c3)[None] * t[:, None]
            l0, l1, l2, l3 = r["local"]
            Lc = l3[None] + (l2 - l3)[None] * s[:, None] + (l0 - l3)[None] * t[:, None]
            info = {"normal": r["normal"], "ctx": r["ctx"], "px": (W, H),
                    "local_origin": np.array(r["origin"]), "local_size": np.array(r["size"])}
            col = paint(r["mat"], P, Lc, np.stack((s, t), 1), info)
            img[r["y"]:r["y"] + H, r["x"]:r["x"] + W, :3] = col.reshape(H, W, 3).round().astype(np.uint8)
            img[r["y"]:r["y"] + H, r["x"]:r["x"] + W, 3] = 255
        for c, face, r in uv_slots:
            c.setdefault("uv", {})[face] = {"uv": [r["x"], r["y"]], "uv_size": [r["w"], r["h"]]}
        return Image.fromarray(img, "RGBA"), used_h


def geo_json(model):
    bones = []
    for b in model.bones:
        out = {"name": b["name"]}
        if "parent" in b:
            out["parent"] = b["parent"]
        out["pivot"] = b["pivot"]
        if "rotation" in b:
            out["rotation"] = b["rotation"]
        cubes = []
        for c in b["_cubes"]:
            cc = {"origin": c["origin"], "size": c["size"]}
            if "rotation" in c:
                cc["pivot"] = c["pivot"]
                cc["rotation"] = c["rotation"]
            cc["uv"] = c["uv"]
            cubes.append(cc)
        if cubes:
            out["cubes"] = cubes
        bones.append(out)
    return {"format_version": "1.12.0", "minecraft:geometry": [{
        "description": {"identifier": "geometry.msta_s",
                        "texture_width": TEX_SIZE, "texture_height": TEX_SIZE,
                        "visible_bounds_width": 28, "visible_bounds_height": 10,
                        "visible_bounds_offset": [0, 4, 0]},
        "bones": bones}]}


# ---------------------------------------------------------------------------
# Software renderer (orthographic, textured, z-buffered) for previews/icons
# ---------------------------------------------------------------------------
def geo_quads(geo, tex_size, pose=None, skip_bones=()):
    g = geo["minecraft:geometry"][0]
    bones = g["bones"]
    mats = bone_matrices(bones, pose)
    tw, th = g["description"]["texture_width"], g["description"]["texture_height"]
    sx, sy = tex_size[0] / tw, tex_size[1] / th
    by = {b["name"]: b for b in bones}

    def hidden(name):
        while name:
            if name in skip_bones:
                return True
            name = by[name].get("parent")
        return False
    quads = []
    for b in bones:
        if hidden(b["name"]):
            continue
        for c in b.get("cubes", []):
            corners = cube_world_corners(c, mats[b["name"]])
            uvd = c.get("uv")
            if isinstance(uvd, list):
                u0, v0 = uvd
                X, Y, Z = (math.floor(v) if False else v for v in c["size"])
                uvd = {"north": {"uv": [u0 + Z, v0 + Z], "uv_size": [X, Y]},
                       "east": {"uv": [u0, v0 + Z], "uv_size": [Z, Y]},
                       "south": {"uv": [u0 + Z + X + Z, v0 + Z], "uv_size": [X, Y]},
                       "west": {"uv": [u0 + Z + X, v0 + Z], "uv_size": [Z, Y]},
                       "up": {"uv": [u0 + Z, v0], "uv_size": [X, Z]},
                       "down": {"uv": [u0 + Z + X, v0], "uv_size": [X, Z]}}
            for face, fu in (uvd or {}).items():
                if face not in corners:
                    continue
                (u, v), (su, sv) = fu["uv"], fu["uv_size"]
                if su == 0 or sv == 0:
                    continue
                uv = np.array([[u, v + sv], [u + su, v + sv], [u + su, v], [u, v]], float)
                uv *= [sx, sy]
                _, world, n = corners[face]
                quads.append((world, uv, n))
    return quads


def camera(yaw, pitch):
    """Direction from the model towards the camera."""
    cy, sy = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
    cp, sp = math.cos(math.radians(pitch)), math.sin(math.radians(pitch))
    d = np.array([sy * cp, sp, -cy * cp])
    d /= np.linalg.norm(d)
    right = np.cross([0, 1, 0], d)
    if np.linalg.norm(right) < 1e-6:
        right = np.array([1.0, 0, 0])
    right /= np.linalg.norm(right)
    up = np.cross(d, right)
    return d, right, up


def project_bounds(quads, cam):
    d, right, up = cam
    pts = np.concatenate([q[0] for q in quads])
    return np.stack((pts @ right, pts @ up), 1)


def render(quads, tex, size, cam, scale, center, light=(-0.45, 1.0, -0.6), bg=None):
    w, h = size
    d, right, up = cam
    texp = np.asarray(tex.convert("RGBA"))
    TH, TW = texp.shape[:2]
    zbuf = np.full((h, w), -1e30)
    out = np.zeros((h, w, 4), np.uint8)
    L = np.array(light, float)
    L /= np.linalg.norm(L)
    cx, cy = center

    def proj(p):
        return np.stack(((p @ right - cx) * scale + w / 2, h / 2 - (p @ up - cy) * scale), 1)
    for world, uv, n in quads:
        if n @ d <= 1e-6:
            continue
        shade = 0.6 + 0.4 * max(0.0, float(n @ L)) + 0.05 * float(n[1] > 0.5)
        scr = proj(world)
        depth = world @ d
        for ids in ((0, 1, 2), (0, 2, 3)):
            a, b, c = scr[list(ids)]
            x0 = max(0, int(math.floor(min(a[0], b[0], c[0]))))
            x1 = min(w - 1, int(math.ceil(max(a[0], b[0], c[0]))))
            y0 = max(0, int(math.floor(min(a[1], b[1], c[1]))))
            y1 = min(h - 1, int(math.ceil(max(a[1], b[1], c[1]))))
            if x1 < x0 or y1 < y0:
                continue
            den = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
            if abs(den) < 1e-9:
                continue
            xx, yy = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
            l1 = ((b[1] - c[1]) * (xx - c[0]) + (c[0] - b[0]) * (yy - c[1])) / den
            l2 = ((c[1] - a[1]) * (xx - c[0]) + (a[0] - c[0]) * (yy - c[1])) / den
            l3 = 1 - l1 - l2
            dep = l1 * depth[ids[0]] + l2 * depth[ids[1]] + l3 * depth[ids[2]]
            zb = zbuf[y0:y1 + 1, x0:x1 + 1]
            mask = (l1 >= -1e-6) & (l2 >= -1e-6) & (l3 >= -1e-6) & (dep > zb)
            if not mask.any():
                continue
            tu = l1 * uv[ids[0], 0] + l2 * uv[ids[1], 0] + l3 * uv[ids[2], 0]
            tv = l1 * uv[ids[0], 1] + l2 * uv[ids[1], 1] + l3 * uv[ids[2], 1]
            ui = np.clip(np.floor(np.minimum(tu, np.max(uv[:, 0]) - 1e-3)), 0, TW - 1).astype(int)
            vi = np.clip(np.floor(np.minimum(tv, np.max(uv[:, 1]) - 1e-3)), 0, TH - 1).astype(int)
            col = texp[vi, ui].astype(float)
            alpha_ok = col[..., 3] > 8
            mask &= alpha_ok
            col[..., :3] *= shade
            sub = out[y0:y1 + 1, x0:x1 + 1]
            sub[mask] = np.clip(col[mask], 0, 255).astype(np.uint8)
            sub[mask, 3] = 255
            zb[mask] = dep[mask]
    img = Image.fromarray(out, "RGBA")
    if bg is not None:
        base = Image.new("RGBA", size, bg)
        base.alpha_composite(img)
        return base
    return img


def fit(quads_list, cam, size, margin):
    pts = np.concatenate([project_bounds(q, cam) for q in quads_list])
    lo, hi = pts.min(0), pts.max(0)
    scale = min((size[0] - 2 * margin[0]) / (hi[0] - lo[0]), (size[1] - 2 * margin[1]) / (hi[1] - lo[1]))
    return scale, (lo + hi) / 2


def downsample(img, size):
    return img.convert("RGBa").resize(size, Image.Resampling.LANCZOS).convert("RGBA")


def container_icon(geo, tex):
    q = geo_quads(geo, tex.size)
    cam = camera(48, 30)
    big = (512, 512)
    scale, center = fit([q], cam, big, (6, 40))
    img = render(q, tex, big, cam, scale, center)
    img = downsample(img, (128, 128))
    return img


def side_icon(geo, tex):
    q = geo_quads(geo, tex.size, skip_bones=("msta_antenna",))
    cam = camera(-90, 0)           # from the vehicle's right: gun points left
    big = (1024, 256)
    pts = project_bounds(q, cam)
    lo, hi = pts.min(0), pts.max(0)
    scale = min(864 / (hi[0] - lo[0]), 236 / (hi[1] - lo[1]))
    center = ((lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2)
    img = render(q, tex, big, cam, scale, center, light=(0.2, 1, 0.3))
    a = np.asarray(img).astype(float)
    lum = a[..., 0] * 0.3 + a[..., 1] * 0.55 + a[..., 2] * 0.15
    hi_l = np.percentile(lum[a[..., 3] > 0], 85)
    # SBW style: near-white silhouette, darker parts read as light grey.
    grey = 196 + 59 * np.clip(lum / hi_l, 0, 1) ** 1.4
    out = np.zeros_like(a)
    out[..., 0] = out[..., 1] = out[..., 2] = grey
    out[..., 3] = a[..., 3]
    img = Image.fromarray(out.astype(np.uint8), "RGBA")
    img = downsample(img, (256, 64))
    # Re-centre horizontally like SBW (x 20..236).
    bbox = img.getbbox()
    canvas = Image.new("RGBA", (256, 64), (0, 0, 0, 0))
    canvas.alpha_composite(img, (128 - (bbox[0] + bbox[2]) // 2, 0))
    return canvas


# ---------------------------------------------------------------------------
# Validation
# ---------------------------------------------------------------------------
def validate(geo, plz_geo=None):
    g = geo["minecraft:geometry"][0]
    bones = g["bones"]
    names = [b["name"] for b in bones]
    assert len(names) == len(set(names)), "duplicate bone names"
    by = {b["name"]: b for b in bones}
    ref = {n: (p, list(pv), r) for n, p, pv, r in skeleton()}
    if plz_geo is not None:
        jb = plz_geo["minecraft:geometry"][0]["bones"]
        jref = {b["name"]: (b.get("parent"), b["pivot"], b.get("rotation")) for b in jb}
        assert set(jref) == set(ref), "embedded skeleton differs from the jar"
        for n, (p, pv, r) in jref.items():
            assert ref[n][0] == p and ref[n][1] == pv and ref[n][2] == r, n
    for n, (p, pv, r) in ref.items():
        assert n in by, f"missing bone {n}"
        b = by[n]
        assert b.get("parent") == p, (n, b.get("parent"), p)
        assert b.get("rotation") == r, n
        if n not in MOVABLE:
            assert b["pivot"] == pv, (n, b["pivot"], pv)
    for b in bones:
        if b.get("parent"):
            assert b["parent"] in by, b["name"]
        if b["name"] not in ref:
            assert not any(re.match(p, b["name"]) for p in SBW_PATTERNS), b["name"]
    tw, th = g["description"]["texture_width"], g["description"]["texture_height"]
    count = 0
    lo = np.full(3, 1e9)
    hi = np.full(3, -1e9)
    mats = bone_matrices(bones)
    for b in bones:
        for c in b.get("cubes", []):
            count += 1
            assert all(s > 0 for s in c["size"]), (b["name"], c)
            assert set(c["uv"]) == set(FACES), (b["name"], c)
            for f in c["uv"].values():
                (u, v), (su, sv) = f["uv"], f["uv_size"]
                assert 0 <= min(u, u + su) and max(u, u + su) <= tw, f
                assert 0 <= min(v, v + sv) and max(v, v + sv) <= th, f
            for _, world, _ in cube_world_corners(c, mats[b["name"]]).values():
                lo = np.minimum(lo, world.min(0))
                hi = np.maximum(hi, world.max(0))
    return count, lo, hi


# ---------------------------------------------------------------------------
# Previews
# ---------------------------------------------------------------------------
def font(size):
    try:
        return ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", size)
    except OSError:
        return ImageFont.load_default()


def previews(out_dir, geo, tex, plz=None):
    out_dir.mkdir(parents=True, exist_ok=True)
    bg = (206, 210, 200, 255)
    q = geo_quads(geo, tex.size)
    views = [("3/4 front-left", camera(48, 28)), ("3/4 rear-right", camera(-140, 26)),
             ("right side", camera(-90, 0)), ("front", camera(0, 8)), ("top", camera(0, 89.9))]
    sheet = Image.new("RGBA", (1800, 1500), bg)
    d = ImageDraw.Draw(sheet)
    slots = [(0, 0, 900, 600), (900, 0, 900, 600), (0, 600, 1800, 420), (0, 1020, 600, 480),
             (600, 1020, 1200, 480)]
    for (title, cam), (x, y, w, h) in zip(views, slots):
        s, c = fit([q], cam, (w, h), (30, 40))
        im = render(q, tex, (w, h), cam, s, c)
        sheet.alpha_composite(im, (x, y))
        d.text((x + 16, y + 10), title, fill=(30, 40, 30), font=font(20))
    sheet.convert("RGB").save(out_dir / "msta_s-preview.png")
    # Poses: gun elevated 55 deg + turret 35 deg; travel lock engaged.
    poses = Image.new("RGBA", (1800, 600), bg)
    d = ImageDraw.Draw(poses)
    for i, (title, pose) in enumerate((
            ("elevated 55, turret 35", {"barrel": [-55, 0, 0], "turret": [0, 35, 0]}),
            ("travel lock engaged (barrel +1.2)", {"barrel": [-1.2, 0, 0], "paojia": [67.5, 0, 0],
                                                   "zhuzi": [-7.5, 0, 0], "paojia2": [67.5, 0, 0]}),
            ("max depression -3, tube recoiled", {"barrel": [3, 0, 0], "tube": {"pos": [0, 0, 11]}}))):
        qq = geo_quads(geo, tex.size, pose)
        cam = camera(70, 18) if i != 1 else camera(-90, 0)
        s, c = fit([qq], cam, (600, 600), (30, 60))
        poses.alpha_composite(render(qq, tex, (600, 600), cam, s, c), (i * 600, 0))
        d.text((i * 600 + 14, 10), title, fill=(30, 40, 30), font=font(18))
    poses.convert("RGB").save(out_dir / "msta_s-poses.png")
    # Close-up of the travel lock engaged.
    qq = geo_quads(geo, tex.size, {"barrel": [-1.2, 0, 0], "paojia": [67.5, 0, 0]})
    cam = camera(-60, 15)
    pts = np.array([[0, 20, -100], [0, 50, -60], [30, 30, -80], [-30, 30, -80]], float)
    pr = np.stack((pts @ cam[1], pts @ cam[2]), 1)
    lo, hi = pr.min(0), pr.max(0)
    render(qq, tex, (800, 600), cam, 9.0, (lo + hi) / 2, bg=bg).convert("RGB").save(out_dir / "msta_s-lock.png")
    tex.resize((1024, 1024), Image.Resampling.NEAREST).save(out_dir / "msta_s-atlas-x2.png")
    if plz is not None:
        pgeo, ptex = plz
        pq = geo_quads(pgeo, ptex.size)
        cmp_img = Image.new("RGBA", (1800, 1300), bg)
        d = ImageDraw.Draw(cmp_img)
        for row, cam in enumerate((camera(48, 28), camera(-90, 0))):
            s, c = fit([q, pq], cam, (900, 650), (30, 50))
            for col_i, (qq, tx, name) in enumerate(((pq, ptex, "SBW PLZ-05"), (q, tex, "msta_s"))):
                pts = project_bounds(qq, cam)
                cc = ((pts[:, 0].min() + pts[:, 0].max()) / 2, c[1])
                im = render(qq, tx, (900, 650), cam, s, cc)
                cmp_img.alpha_composite(im, (col_i * 900, row * 650))
                d.text((col_i * 900 + 16, row * 650 + 10), name + " (same scale)",
                       fill=(30, 40, 30), font=font(20))
        cmp_img.convert("RGB").save(out_dir / "msta_s-vs-plz05.png")
    icons = Image.new("RGBA", (128 * 4 + 40, 128 * 4 + 300), bg)
    ci = Image.open(ICON_CONTAINER_OUT)
    si = Image.open(ICON_SIDE_OUT)
    icons.alpha_composite(ci.resize((512, 512), Image.Resampling.NEAREST), (20, 10))
    dark = Image.new("RGBA", (552, 160), (110, 130, 150, 255))
    dark.alpha_composite(si.resize((512, 128), Image.Resampling.NEAREST), (20, 16))
    icons.alpha_composite(dark, (0, 540))
    icons.convert("RGB").save(out_dir / "msta_s-icons.png")


# ---------------------------------------------------------------------------
def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, separators=(",", ":")) + "\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", type=Path)
    ap.add_argument("--plz-jar", type=Path)
    args = ap.parse_args()
    model, links = build_model()
    tex, used = Atlas(TEX_SIZE, DENSITY).build(model)
    assert tex is not None, f"atlas overflow: needs {used} rows"
    geo = geo_json(model)
    plz_geo = plz = None
    if args.plz_jar:
        with zipfile.ZipFile(args.plz_jar) as z:
            plz_geo = json.loads(z.read("assets/superbwarfare/models/bedrock/vehicle/plz_05.geo.json"))
            import io
            ptex = Image.open(io.BytesIO(z.read("assets/superbwarfare/textures/bedrock/vehicle/plz_05.png")))
            plz = (plz_geo, ptex.convert("RGBA"))
    count, lo, hi = validate(geo, plz_geo)
    write_json(GEO_OUT, geo)
    TEX_OUT.parent.mkdir(parents=True, exist_ok=True)
    tex.save(TEX_OUT, optimize=True)
    ICON_CONTAINER_OUT.parent.mkdir(parents=True, exist_ok=True)
    container_icon(geo, tex).save(ICON_CONTAINER_OUT, optimize=True)
    side_icon(geo, tex).save(ICON_SIDE_OUT, optimize=True)
    if args.preview:
        previews(args.preview, geo, tex, plz)
    print(json.dumps({
        "cubes": count, "track_links_per_side": links, "atlas_rows_used": used,
        "bounds_units": [lo.round(2).tolist(), hi.round(2).tolist()],
        "bounds_blocks": [(lo / 16).round(3).tolist(), (hi / 16).round(3).tolist()],
        "trunnion": TRUNNION, "muzzle": [0, TRUNNION[1], MUZZLE_Z]}, indent=1))


if __name__ == "__main__":
    main()
