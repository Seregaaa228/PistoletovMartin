#!/usr/bin/env python3
"""Original Minecraft cube assets for four native Superb Warfare vehicles.

Only anchor metadata is read from the pinned native JAR. No native geometry,
texture pixels, or icons are copied. Run with Python 3, NumPy and Pillow.
"""
from __future__ import annotations

import hashlib
import io
import json
import math
from pathlib import Path
import zipfile

import numpy as np
from PIL import Image, ImageDraw

import generate_assets as common

HERE = Path(__file__).resolve().parent
RES = HERE.parent / "src/main/resources/resourcepacks/frontier_ifv/assets/superbwarfare"
NATIVE = HERE.parents[2] / "instance/mods/superbwarfare-0.8.9.1-hotfix-mc1.20.1-993063bed-all.jar"
VEHICLES = ("ah_6", "mi_28", "speedboat", "tiny_speedboat")
LABELS = ("AH-6 LITTLE BIRD", "MI-28 HAVOC", "PATROL SPEEDBOAT", "TINY SPEEDBOAT")
TILES = dict(common.TILES, glass=24, seat=25, deck=26, dye=27, water=28)
PIVOTS = {
    "ah_6": {"root": [0, 0, 0], "move_propeller": [0, 1.4625, 0],
             "move_tailPropeller": [5.57213, 25.20143, 73.80508],
             "flare2": [13.15, 9.24, -22.18133], "flare3": [-13.15, 9.24, -22.18133]},
    "mi_28": {"root": [0, 0, 0], "base": [0, 24.57375, -39.75],
              "turret": [0, 13.2885, -50.55], "barrel": [0, 6.6915, -49.93672],
              "barrel2": [0, 6.59625, -43.91775],
              "propellerBase": [0, 3.91125, 0],
              "move_propeller": [0, 45.91125, -2.85345],
              "move_tailPropeller": [0, 51.64343, 143.35793],
              "dummy_1_2_1": [-31.6875, 12, 0.75], "dummy_1_2_2": [31.6875, 12, 0.75],
              "flare": [0.00001, 7.91482, -87.92799]},
    "speedboat": {"root": [0, 0, 0], "waterMask": [0, 18.25, 16],
                  "turret": [0, 49.7995, 5.8341], "barrel": [0, 59.2769, 5.53526],
                  "flare": [0, 60.09, -28.68133],
                  "mg2": [-14.11122, 60.85582, 5.17643],
                  "action": [-14.11122, 60.87289, -18.86158],
                  "fxp": [-0.01581, 24.6022, -15.30523],
                  "move_control": [-0.01581, 24.6022, -15.30523],
                  "move_rudder": [0, 9.6672, 51.27234],
                  "move_propeller": [7.98, 3.078, 46.056],
                  "move_propeller2": [-7.98, 3.078, 46.056]},
    "tiny_speedboat": {"root": [0, 0, 0], "waterMask": [-0.5, 0, 0],
                       "move_rudder": [0, 2.025, 15.2],
                       "fxp": [0, 7.15905, -8.78825],
                       "move_control": [0, 8.44086, -10.17091]},
}
BOUNDS = {"ah_6": (18, 8, [0, 2, 1]), "mi_28": (28, 11, [0, 3.5, 2]),
          "speedboat": (16, 8, [0, 2, 0]), "tiny_speedboat": (6, 4, [0, 0.5, 0])}


def uv(material):
    n = TILES[material]
    return {"uv": [n % 8 * 16, n // 8 * 16], "uv_size": [16, 16]}


class Model:
    def __init__(self, vehicle, lod):
        self.vehicle, self.lod = vehicle, lod
        self.bones, self.by_name = [], {}
        self.bone("root")
        if vehicle == "ah_6":
            for name in ("move_propeller", "move_tailPropeller", "flare2", "flare3"):
                self.bone(name, "root")
        elif vehicle == "mi_28":
            self.bone("base", "root")
            self.bone("turret", "root")
            self.bone("barrel", "turret")
            self.bone("barrel2", "barrel")
            self.bone("flare", "barrel")
            self.bone("propellerBase", "root", rotation=[3, 0, 0])
            self.bone("move_propeller", "propellerBase")
            self.bone("move_tailPropeller", "root")
            self.bone("dummy_1_2_1", "root")
            self.bone("dummy_1_2_2", "root")
        else:
            self.bone("waterMask", "root")
            self.bone("move_rudder", "root")
            self.bone("fxp", "root", rotation=[22.5 if vehicle == "speedboat" else 45, 0, 0])
            self.bone("move_control", "fxp")
            if vehicle == "speedboat":
                self.bone("turret", "root")
                self.bone("barrel", "turret")
                self.bone("mg2", "barrel")
                self.bone("action", "mg2")
                self.bone("flare", "barrel")
                self.bone("move_propeller", "root")
                self.bone("move_propeller2", "root")

    def bone(self, name, parent=None, pivot=None, rotation=None):
        b = {"name": name, "pivot": pivot if pivot is not None else PIVOTS[self.vehicle][name]}
        if parent:
            b["parent"] = parent
        if rotation:
            b["rotation"] = rotation
        self.bones.append(b)
        self.by_name[name] = b
        return b

    def cube(self, bone, origin, size, material="hull", faces=None, rotation=None, pivot=None):
        c = {"origin": origin, "size": size,
             "uv": {f: uv(material) for f in common.FACES}}
        if faces:
            c["uv"].update({f: uv(t) for f, t in faces.items()})
        if rotation:
            c.update(rotation=rotation, pivot=pivot)
        self.by_name[bone].setdefault("cubes", []).append(c)

    def body(self, origin, size, material="hull", faces=None):
        self.cube("root", origin, size, material, faces)

    def rotor(self, blades, radius, y, z=0):
        if self.lod:
            # Two broad crossed blades remain legible in the far silhouette.
            self.cube("move_propeller", [-radius, y, z-1.5], [radius*2, 1.5, 3], "metal")
            self.cube("move_propeller", [-1.5, y, z-radius], [3, 1.5, radius*2], "metal")
            return
        for i in range(blades):
            angle = i*360/blades
            self.cube("move_propeller", [2, y, z-1.7], [radius-2, 1.5, 3.4],
                      "metal", rotation=[0, angle, 0], pivot=[0, y, z])
            self.cube("move_propeller", [radius-7, y-0.1, z-1.9], [7, 1.7, 3.8],
                      "hull_light", rotation=[0, angle, 0], pivot=[0, y, z])

    def data(self):
        w, h, offset = BOUNDS[self.vehicle]
        return {"format_version": "1.12.0", "minecraft:geometry": [{
            "description": {"identifier": "geometry.arcade_"+self.vehicle,
                            "texture_width": 128, "texture_height": 128,
                            "visible_bounds_width": w, "visible_bounds_height": h,
                            "visible_bounds_offset": offset}, "bones": self.bones}]}


def ah6(lod):
    m = Model("ah_6", lod)
    # Compact six-blade scout helicopter, cabin in front and a narrow tail boom.
    m.body([-10, 8, -14], [20, 19, 31], faces={"up": "roof"})
    m.body([-9, 9, -27], [18, 11, 14], "glass")
    m.body([-8, 20, -23], [16, 9, 15], "glass")
    m.body([-8, 27, -10], [16, 5, 25], "hull_dark")
    m.body([-4, 29, 10], [8, 7, 14], "vent")
    m.body([-3, 24, 18], [6, 6, 32], "hull_dark")
    m.body([-2, 25, 50], [4, 4, 24], "hull")
    m.body([-1.5, 16, 70], [3, 27, 7], "hull_dark")
    m.body([-13, 40, 69], [26, 2, 6], "hull")
    for x in (-15, 12):
        m.body([x, 0, -24], [3, 3, 45], "metal")
        if not lod:
            for z in (-11, 11):
                m.body([x+0.5, 3, z], [2, 8, 3], "metal")
    m.cube("move_propeller", [-2, 32, -2], [4, 11, 4], "metal")
    m.rotor(6, 57, 42)
    tp = PIVOTS["ah_6"]["move_tailPropeller"]
    m.cube("move_tailPropeller", [tp[0]-0.7, tp[1]-15, tp[2]-1.3], [1.4, 30, 2.6], "metal")
    m.cube("move_tailPropeller", [tp[0]-0.7, tp[1]-1.3, tp[2]-15], [1.4, 2.6, 30], "metal")
    # Gun mouths coincide with the native flare anchors. Fixed guns remain fixed.
    for sign in (-1, 1):
        x = sign*13.15
        m.body([min(7, x), 8, -5], [abs(x-7)+3 if sign>0 else 9, 3, 9], "hull_dark")
        m.body([x-1.2, 8.04, -22.18133], [2.4, 2.4, 24], "metal", {"north": "bore"})
        if not lod:
            m.body([x-2.2, 7.04, -19], [4.4, 4.4, 5], "metal", {"north": "bore"})
            m.body([sign*20.5-3.5, 5.7, -17], [7, 7, 17], "launcher", {"north": "tow_front"})
            m.body([sign*20.5-4, 5.2, -6], [8, 8, 3], "hull_dark")
            m.body([sign*15-4, 11, 1], [8, 2.5, 15], "seat")
    if not lod:
        # Coarse cabin rails, lower nose, skids and engine vents.
        m.body([-9, 7, -24], [18, 3, 15], "hull_dark")
        m.body([-1, 9, -27.4], [2, 20, 2], "hull_dark")
        m.body([-9, 19, -25], [18, 2, 2], "hull_dark")
        m.body([-9, 28, -22], [18, 2, 14], "hull")
        for x in (-10.5, 9):
            m.body([x, 10, -13], [1.5, 17, 3], "hull_dark")
            m.body([x, 25, -11], [1.5, 2, 24], "hull_light")
            m.body([x, 12, 6], [1.5, 10, 8], "stencil")
            m.body([x, 10, -6], [1.5, 12, 3], "hull_dark")
        m.body([-6, 32, -5], [12, 2, 12], "hatch")
        m.body([-3, 34, 17], [6, 5, 8], "grille")
        m.body([-2, 11, -28], [4, 4, 3], "lamp")
        m.body([-1, 29, 57], [2, 11, 12], "hull")
        m.body([-11, 41, 70], [6, 1.5, 5], "hull_light")
        m.body([5, 41, 70], [6, 1.5, 5], "hull_light")
        for x in (-15, 12):
            m.body([x, 2, -27], [3, 3, 7], "metal")
            m.body([x, 2, 17], [3, 3, 7], "metal")
        m.cube("move_propeller", [-4, 40, -4], [8, 4, 8], "hull_dark")
        m.cube("move_tailPropeller", [tp[0]-1.5, tp[1]-2.5, tp[2]-2.5], [3, 5, 5], "hull_light")
        m.body([-0.6, 31, 10], [1.2, 10, 1.2], "metal")
    return m


def mi28(lod):
    m = Model("mi_28", lod)
    # Long tandem cockpit and armoured engine shoulders distinguish the Havoc.
    m.body([-12, 12, -25], [24, 20, 69], faces={"up": "roof"})
    m.body([-10, 14, -61], [20, 15, 37], "hull_dark")
    m.body([-9, 25, -55], [18, 11, 23], "glass")
    m.body([-8, 31, -30], [16, 12, 21], "glass")
    if not lod:
        m.body([-10, 37, -8], [20, 8, 36], "hull")
    for x in (-21, 11):
        m.body([x, 29, -15], [10, 12, 45], "hull_dark", {"north": "grille", "south": "vent"})
    m.body([-5, 22, 43], [10, 10, 57], "hull_dark")
    m.body([-3, 26, 98], [6, 7, 47], "hull")
    m.body([-2, 28, 135], [4, 34, 13], "hull_dark")
    m.body([-24, 31, 104], [48, 3, 12], "hull")
    for sign in (-1, 1):
        m.body([min(sign*39, sign*12), 23, -8], [27, 3, 26], "hull")
        m.body([sign*23-5, 12, -14], [10, 11, 29], "launcher", {"north": "tow_front"})
        if not lod:
            for y in (17, 22):
                m.body([sign*33-5, y, -12], [10, 4, 32], "launcher", {"north": "tow_front"})
            m.body([sign*15-1.5, 2, -18], [3, 13, 5], "metal")
        m.body([sign*15-3, 0, -23], [6, 10, 12], "rubber", {"east": "wheel", "west": "wheel"})
    m.body([-3, 0, 110], [6, 10, 12], "rubber", {"east": "wheel", "west": "wheel"})
    m.cube("turret", [-7, 9.3, -56], [14, 8, 12], "hull_dark")
    m.cube("barrel", [-4, 4.7, -54], [8, 6, 12], "hull")
    m.cube("barrel2", [-1.6, 6.31482, -87.92799], [3.2, 3.2, 40], "metal", {"north": "bore"})
    m.cube("move_propeller", [-3, 41, -5.85], [6, 11, 6], "metal")
    m.rotor(5, 105, 51, -2.85345)
    tp = PIVOTS["mi_28"]["move_tailPropeller"]
    m.cube("move_tailPropeller", [-1, tp[1]-19, tp[2]-1.7], [2, 38, 3.4], "metal")
    m.cube("move_tailPropeller", [-1, tp[1]-1.7, tp[2]-19], [2, 3.4, 38], "metal")
    if not lod:
        for z,y,w in ((-58,25,20),(-33,31,19),(-10,40,18)):
            m.body([-w/2, y, z], [w, 2.5, 4], "hull")
        m.body([-1.2, 26, -57], [2.4, 10, 25], "hull_dark")
        m.body([-1.2, 33, -30], [2.4, 10, 22], "hull_dark")
        m.body([-9, 33, -35], [18, 4, 5], "hull_light")
        m.body([-8, 16, -66], [16, 7, 8], "hull")
        m.body([-5, 20, -70], [10, 5, 5], "optic")
        m.body([-4, 12, -63], [8, 4, 6], "lamp")
        for sign in (-1, 1):
            m.body([sign*12-1, 17, -50], [2, 11, 15], "stencil")
            m.body([sign*18-3, 40, -8], [6, 2, 22], "vent")
            m.body([sign*20-3, 27, 27], [6, 9, 12], "grille")
            m.body([sign*11-1, 14, 24], [2, 12, 13], "panel")
            m.body([sign*15-4, 4, -19], [8, 4, 4], "wheel")
            m.body([sign*34-5, 12, 19], [10, 3, 8], "hull_dark")
            m.body([sign*22-5, 25, -7], [10, 2, 18], "panel")
            m.body([sign*38-2, 25, 7], [4, 3, 10], "hull_light")
        m.cube("barrel2", [-2.4, 5.51482, -87.92799], [4.8, 4.8, 6], "metal", {"north": "bore"})
        m.cube("barrel", [4, 6, -50], [4, 5, 9], "panel")
        m.body([-5, 44, 10], [10, 2, 12], "hatch")
        m.body([-1, 40, 18], [2, 18, 2], "metal")
        m.body([-4, 28, 67], [8, 3, 15], "stencil")
        m.body([-1.5, 33, 114], [3, 13, 20], "hull")
        m.body([-22, 34, 106], [9, 1.5, 9], "hull_light")
        m.body([13, 34, 106], [9, 1.5, 9], "hull_light")
        m.cube("move_propeller", [-6, 49, -8.85345], [12, 6, 12], "hull_dark")
        m.cube("move_tailPropeller", [-3, tp[1]-3, tp[2]-3], [6, 6, 6], "hull_light")
        m.body([-2, 9, 112], [4, 17, 4], "metal")
    return m


def speedboat(lod):
    m = Model("speedboat", lod)
    # Stepped bow and high gun gantry read as a patrol boat from far away.
    m.body([-17, 5, -66], [34, 12, 121], "hull_dark")
    m.body([-13, 6, -82], [26, 11, 17], "hull_dark")
    m.body([-7, 8, -94], [14, 10, 13], "hull")
    m.body([-16, 17, -80], [32, 6, 49], "hull", {"up": "deck"})
    for x in (-20, 16):
        m.body([x, 16, -61], [4, 12, 115], "hull")
    m.body([-16, 17, 50], [32, 9, 5], "hull")
    m.body([-15, 17, -30], [30, 3, 80], "deck")
    m.body([-11, 20, -23], [22, 13, 16], "panel", {"north": "glass", "up": "optic"})
    m.body([-14, 34, -15], [28, 10, 4], "glass")
    for x in (-14, 12):
        m.body([x, 27, -13], [2, 22, 2], "metal")
    m.body([-15, 47, -16], [30, 3, 37], "hull_dark")
    m.cube("turret", [-6, 49.8, 0], [12, 5, 12], "hull_dark")
    m.cube("barrel", [-3.5, 56.09, -2], [7, 8, 13], "metal")
    m.cube("mg2", [-1.4, 58.69, -28.68133], [2.8, 2.8, 29], "metal", {"north": "bore"})
    for name in ("move_propeller", "move_propeller2"):
        x,y,z = PIVOTS["speedboat"][name]
        m.cube(name, [x-1, y-5, z+7], [2, 10, 2], "metal")
    m.cube("move_rudder", [-1, 0.5, 49.3], [2, 9, 8], "metal")
    # Original water occluder dimensions track the native cockpit opening.
    m.cube("waterMask", [-15.5, 12.5, -29], [31, 9, 79], "water")
    if not lod:
        for x in (-18, 16):
            m.body([x, 27, -69], [2, 2, 120], "hull_light")
            m.body([x-0.5, 21, -36], [3, 6, 16], "stencil")
            m.body([x-0.5, 20, -62], [3, 6, 9], "lamp")
            m.body([x-0.5, 18, 42], [3, 5, 8], "tail")
        for z in (20, 38):
            m.body([-12, 20, z], [24, 4, 7], "seat")
            m.body([-12, 24, z+5], [24, 9, 3], "seat")
        m.body([-6, 20, -4], [12, 4, 10], "seat")
        m.body([-6, 24, 3], [12, 10, 3], "seat")
        for x in (-14, 12):
            m.body([x, 27, 18], [2, 20, 2], "metal")
        m.body([-15, 44, -16], [30, 3, 3], "hull")
        m.body([-1, 34, -16], [2, 10, 4], "hull_dark")
        m.body([-11, 31, -21], [22, 2, 10], "hull_dark", {"up": "optic"})
        m.cube("move_control", [-5, 23.6, -16.3], [10, 2, 2], "metal")
        m.cube("move_control", [-1, 20, -16.3], [2, 8, 2], "metal")
        m.cube("action", [2.5, 59, -5], [2, 3, 6], "hull_light")
        m.cube("mg2", [-5, 56.09, -4], [10, 3, 8], "metal")
        m.cube("barrel", [-8, 56, 2], [5, 7, 10], "hull_dark")
        m.body([-13, 50, 14], [5, 5, 5], "lamp")
        m.body([8, 50, 14], [5, 5, 5], "optic")
        m.body([-1, 50, 18], [2, 15, 2], "metal")
        m.body([-9, 22, -69], [18, 2, 20], "hatch_line")
        m.body([-8, 3, -62], [16, 2, 108], "metal")
        for x in (-11, 7):
            m.body([x, 13, 47], [4, 10, 13], "grille")
        for name in ("move_propeller", "move_propeller2"):
            x,y,z = PIVOTS["speedboat"][name]
            m.cube(name, [x-5, y-1, z+7], [10, 2, 2], "metal")
    return m


def tinyboat(lod):
    m = Model("tiny_speedboat", lod)
    # Small open leisure boat: low hull, open footwell, bow deck, outboard.
    m.body([-10, 0, -17], [20, 4, 36], "hull_dark")
    m.body([-7, 1, -22], [14, 5, 6], "hull")
    for x in (-13, 10):
        m.body([x, 3, -17], [3, 7, 36], "hull")
    m.body([-10, 4, 17], [20, 6, 4], "hull")
    m.body([-10, 5, -20], [20, 4, 9], "deck")
    m.body([-9, 4, -9], [18, 1.5, 24], "deck")
    m.body([-8, 6, 6], [16, 3, 6], "seat")
    m.body([-8, 9, 10], [16, 7, 2], "seat")
    m.body([-6, 7, -12], [12, 5, 3], "panel", {"up": "optic"})
    m.body([-7, 12, -13], [14, 7, 1.5], "glass")
    m.cube("move_rudder", [-2, 1, 16.9], [4, 8, 7], "hull_dark")
    m.cube("move_control", [-3, 7.4, -11.2], [6, 1.5, 1.5], "metal")
    m.cube("waterMask", [-9, 4.25, -10], [18, 5, 26], "water")
    # Dye strips occupy their own atlas tile; the native renderer tints it.
    for x in (-13.2, 12):
        m.body([x, 6, -16], [1.2, 2, 34], "dye")
    if not lod:
        for x in (-13, 10):
            m.body([x, 10, -16], [3, 1.5, 35], "hull_light")
            m.body([x, 6, -18], [3, 5, 4], "hull")
            m.body([x, 3, 17], [3, 6, 5], "hull_dark")
        for x in (-9, 7):
            m.body([x, 9, -16], [2, 4, 7], "metal")
            m.body([x, 12, -13.2], [2, 7, 2], "hull_dark")
            m.body([x, 4, 8], [2, 2, 8], "hull_dark")
        m.body([-8, 8, -18], [16, 1.5, 6], "panel")
        m.body([-8, 10, -12.5], [16, 2, 3], "hull_light")
        m.body([-1, 12, -13.4], [2, 7, 2], "hull_dark")
        m.body([-9, 5, -20.5], [4, 3, 2], "lamp")
        m.body([5, 5, -20.5], [4, 3, 2], "lamp")
        m.body([-9, 5, 20], [3, 3, 1.5], "tail")
        m.body([6, 5, 20], [3, 3, 1.5], "tail")
        m.body([-5, 9, -19], [10, 1, 5], "hatch")
        m.body([-3, 10, 17], [6, 5, 6], "vent")
        m.cube("move_rudder", [-1.5, -2, 20], [3, 6, 3], "metal")
        m.cube("move_control", [-0.75, 5.5, -11.2], [1.5, 5.5, 1.5], "metal")
        m.body([-7, 6, -2], [14, 1, 3], "hatch_line")
        m.body([-9, 8.5, 7], [18, 1, 4], "seat")
        m.body([-7, 15, 10], [14, 1.5, 2], "seat")
        m.body([-2, 8, -21], [4, 2, 2], "metal")
        m.body([-9, 1, -18], [18, 2, 4], "hull_dark")
        m.body([-9, 1, 17], [18, 2, 5], "hull_dark")
    return m


def atlas(vehicle):
    # Reuse the accepted original tile vocabulary, recoloured for these classes.
    im = common.atlas("bmp_2")
    if vehicle in ("speedboat", "tiny_speedboat"):
        arr = np.array(im)
        for name in ("hull", "hull_dark", "hull_light", "roof", "panel", "hatch", "vent",
                     "wheel", "metal", "stencil", "grille", "launcher", "bolt", "hatch_line"):
            n = TILES[name]
            patch = arr[n//8*16:n//8*16+16, n%8*16:n%8*16+16, :3].astype(float)
            # Retain pixel patterns, turn army green into a neutral slate palette.
            luminance = patch @ np.array([.24, .59, .17])
            patch = np.stack((luminance*.92+10, luminance*1.01+12, luminance*1.08+14), axis=2)
            arr[n//8*16:n//8*16+16, n%8*16:n%8*16+16, :3] = np.clip(patch, 0, 255)
        im = Image.fromarray(arr)
    d = ImageDraw.Draw(im)
    for name, color in (("glass", (36, 67, 73, 255)), ("seat", (52, 57, 48, 255)),
                        ("deck", (91, 99, 87, 255)), ("dye", (167, 176, 170, 255)),
                        ("water", (0, 0, 0, 0))):
        n = TILES[name]; x,y = n%8*16, n//8*16
        d.rectangle((x,y,x+15,y+15), fill=color)
        if name == "glass":
            d.rectangle((x,y,x+15,y+1), fill=(23,39,40,255))
            d.rectangle((x+1,y+2,x+14,y+4), fill=(100,145,146,255))
            d.rectangle((x+2,y+5,x+7,y+7), fill=(60,100,107,255))
            d.line((x+1,y+13,x+14,y+13), fill=(26,49,51,255))
        elif name == "seat":
            for sy in (3,8,13): d.line((x+1,y+sy,x+14,y+sy),fill=(36,42,35,255))
        elif name == "deck":
            for sx in range(1,16,4): d.line((x+sx,y,x+sx,y+15),fill=(69,77,67,255))
        elif name == "dye":
            d.rectangle((x,y,x+15,y+1), fill=(208,214,207,255))
            d.rectangle((x,y+14,x+15,y+15), fill=(101,115,108,255))
    # Original simple class markings, no copied national emblems or symbols.
    n=TILES["stencil"]; x,y=n%8*16,n//8*16
    d.rectangle((x,y,x+15,y+15), fill=(83,99,57,255) if vehicle in ("ah_6","mi_28") else (81,90,94,255))
    ink=(211,215,182,255)
    for sx in (3,7,11): d.rectangle((x+sx,y+5,x+sx+1,y+10),fill=ink)
    return im


def tint_mask():
    im=Image.new("RGBA", (128,128), (0,0,0,0)); d=ImageDraw.Draw(im)
    n=TILES["dye"]; x,y=n%8*16,n//8*16
    d.rectangle((x,y,x+15,y+15),fill=(225,231,224,225))
    d.line((x,y+1,x+15,y+1),fill=(255,255,255,240))
    return im


def animations(vehicle):
    def idle(bones):
        return {"loop": True,"bones":{b:{"scale":0} for b in bones}}
    def shot(bone, peak, recoil=None):
        bones={bone:{"scale":{"0.0":[0,0,0],"0.008":[peak]*3,
                              "0.045":[0,0,0],"0.12":[0,0,0]}}}
        if recoil:
            bones[recoil]={"position":{"0.0":[0,0,0],"0.025":[0,0,1.5],"0.12":[0,0,0]}}
        return {"loop":"hold_on_last_frame","animation_length":.12,"bones":bones}
    a={}
    if vehicle=="ah_6":
        for suffix,bone in (("","flare2"),(".1","flare3")):
            a["animation.cannon.idle"+suffix]=idle([bone])
            a["animation.cannon.fire"+suffix]=shot(bone,5.5)
    elif vehicle=="mi_28":
        a["animation.cannon.idle"]=idle(["flare"])
        a["animation.cannon.fire"]=shot("flare",5.5,"barrel2")
        # Reference static transform parent so the model baker retains it.
        a["animation.cannon.idle"]["bones"]["propellerBase"]={"position":[0,0,0]}
    elif vehicle=="speedboat":
        a["animation.machine_gun.idle"]=idle(["flare"])
        a["animation.machine_gun.idle"]["bones"].update({"mg2":{"position":[0,0,0]},"fxp":{"position":[0,0,0]}})
        a["animation.machine_gun.fire"]=shot("flare",3,"action")
        a["animation.machine_gun.fire"]["bones"]["mg2"]={"position":{"0.0":[0,0,0],"0.025":[0,0,.25],"0.12":[0,0,0]}}
    else:
        a["animation.boat.idle"]={"loop":True,"bones":{"fxp":{"position":[0,0,0]}}}
    return {"format_version":"1.8.0","animations":a}


def rotation_matrix(rotation):
    x,y,z=np.radians(rotation)
    rx=np.array([[1,0,0],[0,math.cos(x),-math.sin(x)],[0,math.sin(x),math.cos(x)]])
    ry=np.array([[math.cos(y),0,math.sin(y)],[0,1,0],[-math.sin(y),0,math.cos(y)]])
    rz=np.array([[math.cos(z),-math.sin(z),0],[math.sin(z),math.cos(z),0],[0,0,1]])
    return rz@ry@rx


def transformed_faces(model, poses=None):
    """Resolve absolute Bedrock pivots to faces for honest previews and checks."""
    poses=poses or {}
    def transform(points,bone):
        p=np.array(bone["pivot"])
        r=np.array(bone.get("rotation",[0,0,0]))+np.array(poses.get(bone["name"],[0,0,0]))
        points=(points-p)@rotation_matrix(r).T+p
        if "parent" in bone: points=transform(points,model.by_name[bone["parent"]])
        return points
    for bone in model.bones:
        if bone["name"].startswith("flare") or bone["name"]=="waterMask": continue
        for cube in bone.get("cubes",[]):
            for face,corners in common.FACE_CORNERS.items():
                points=np.array(corners)*cube["size"]+cube["origin"]
                if "rotation" in cube:
                    p=np.array(cube["pivot"])
                    points=(points-p)@rotation_matrix(cube["rotation"]).T+p
                points=transform(points,bone)
                # Derive actual face normal after all static/live rotations.
                normal=np.cross(points[1]-points[0], points[2]-points[1])
                if face in ("north","south","east","west","up","down"): normal=-normal
                norm=np.linalg.norm(normal)
                if norm: normal/=norm
                yield points,normal,cube["uv"][face]


def render(model, tex, size=(840,520), yaw=35, poses=None, elevation=.62):
    """Software orthographic projection of the shipped model, including pivots."""
    w,h=size
    camera=np.array([math.sin(math.radians(yaw)),elevation,-math.cos(math.radians(yaw))]);camera/=np.linalg.norm(camera)
    right=np.cross([0,1,0],camera);right/=np.linalg.norm(right)
    up=np.cross(camera,right)
    faces=list(transformed_faces(model,poses))
    allp=np.concatenate([p for p,_,_ in faces]); proj=np.stack((allp@right,allp@up),axis=1)
    lo,hi=proj.min(axis=0),proj.max(axis=0);cx,cy=(lo+hi)/2
    margin=max(6,min(w,h)*.055)
    scale=min((w-margin*2)/(hi[0]-lo[0]),(h-margin*2)/(hi[1]-lo[1]))
    zbuf=np.full((h,w),-1e30);pixels=np.zeros((h,w,4),dtype=np.uint8);texp=np.array(tex)
    def triangle(points,uv,light):
        screen=np.stack(((points@right-cx)*scale+w/2,h/2-(points@up-cy)*scale),axis=1)
        x0=max(0,int(screen[:,0].min()));x1=min(w-1,math.ceil(screen[:,0].max()))
        y0=max(0,int(screen[:,1].min()));y1=min(h-1,math.ceil(screen[:,1].max()))
        if x1<x0 or y1<y0:return
        xx,yy=np.meshgrid(np.arange(x0,x1+1)+.5,np.arange(y0,y1+1)+.5)
        a,b,c=screen;den=(b[1]-c[1])*(a[0]-c[0])+(c[0]-b[0])*(a[1]-c[1])
        if abs(den)<1e-8:return
        u=((b[1]-c[1])*(xx-c[0])+(c[0]-b[0])*(yy-c[1]))/den
        v=((c[1]-a[1])*(xx-c[0])+(a[0]-c[0])*(yy-c[1]))/den;t=1-u-v
        depth=u*(points[0]@camera)+v*(points[1]@camera)+t*(points[2]@camera)
        tx=np.clip(np.floor(u*uv[0,0]+v*uv[1,0]+t*uv[2,0]).astype(int),0,127)
        ty=np.clip(np.floor(u*uv[0,1]+v*uv[1,1]+t*uv[2,1]).astype(int),0,127)
        color=texp[ty,tx].copy();color[:,:,:3]=np.clip(color[:,:,:3]*light,0,255)
        old=zbuf[y0:y1+1,x0:x1+1];mask=(u>=-1e-6)&(v>=-1e-6)&(t>=-1e-6)&(depth>old)&(color[:,:,3]>0)
        pixels[y0:y1+1,x0:x1+1][mask]=color[mask];old[mask]=depth[mask]
    light=np.array([-.4,1,-.7]);light/=np.linalg.norm(light)
    for points,normal,info in faces:
        if normal@camera<=0:continue
        u,v=info["uv"];su,sv=info["uv_size"]
        uv=np.array([[u,v+sv-.01],[u+su-.01,v+sv-.01],[u+su-.01,v],[u,v]])
        for ids in ((0,1,2),(0,2,3)):
            triangle(points[list(ids)],uv[list(ids)],.72+.28*max(0,normal@light))
    return Image.fromarray(pixels)


def native_metadata():
    """The reference is consulted only for names, pivots and native contracts."""
    with zipfile.ZipFile(NATIVE) as z:
        result={}
        for v in VEHICLES:
            data=json.loads(z.read(f"data/superbwarfare/sbw/vehicles/{v}.json"))
            native=json.loads(z.read(f"assets/superbwarfare/models/bedrock/vehicle/{v}.geo.json"))["minecraft:geometry"][0]
            bones={b["name"]:b for b in native["bones"]}
            for name,pivot in PIVOTS[v].items():
                assert bones[name]["pivot"]==pivot,(v,name,"native pivot drift")
            icon=data["VehicleIcon"]
            assert icon==f"superbwarfare:textures/vehicle_icon/{v}_icon.png"
            native_animation=f"assets/superbwarfare/animations/bedrock/vehicle/{v}.animation.json"
            animation_keys=list(json.loads(z.read(native_animation))["animations"]) if native_animation in z.namelist() else []
            assert set(animation_keys)<=set(animations(v)["animations"])
            result[v]={"native_render_scale":1,"native_vehicle_icon":icon,
                       "native_icon_size":list(Image.open(io.BytesIO(z.read("assets/"+icon.replace(":","/")))).size),
                       "native_turret_pos":data.get("TurretPos"),"native_barrel_pos":data.get("BarrelPos"),
                       "native_rotate_offset_height":data.get("RotateOffsetHeight"),
                       "native_animation_keys":animation_keys,"pivots_absolute":PIVOTS[v]}
        return result


def validate(models, textures):
    metrics=native_metadata();checks=[]
    for v in VEHICLES:
        lods=[]
        assert textures[v].size==(128,128)
        for lod in range(2):
            m=models[v,lod];names=[b["name"] for b in m.bones]
            assert len(names)==len(set(names))
            for name,pivot in PIVOTS[v].items():assert m.by_name[name]["pivot"]==pivot
            for bone in m.bones:
                seen=set();ancestor=bone
                while "parent" in ancestor:
                    assert ancestor["name"] not in seen
                    seen.add(ancestor["name"]);ancestor=m.by_name[ancestor["parent"]]
            for a in animations(v)["animations"].values():
                assert set(a["bones"])<=set(names)
            cubes=[c for b in m.bones for c in b.get("cubes",[])]
            for c in cubes:
                assert all(math.isfinite(n) for n in c["origin"]+c["size"])
                assert all(n>0 for n in c["size"])
                assert set(c["uv"])==set(common.FACES)
                for face in c["uv"].values():
                    assert all(0<=a and a+b<=128 for a,b in zip(face["uv"],face["uv_size"]))
            for bone in m.bones:
                if bone["name"].startswith("flare"):assert not bone.get("cubes")
            geometry=[p for p,_,_ in transformed_faces(m)]
            lo,hi=np.min(np.concatenate(geometry),axis=0),np.max(np.concatenate(geometry),axis=0)
            w,h,o=BOUNDS[v]
            assert np.all(lo>=np.array(o)*16-np.array([w/2,h/2,w/2])*16),(v,lod,"visible lower bounds",lo)
            assert np.all(hi<=np.array(o)*16+np.array([w/2,h/2,w/2])*16),(v,lod,"visible upper bounds",hi)
            tri=len(cubes)*12
            limits=(700,1200) if v in ("ah_6","mi_28") else (500,900)
            assert (limits[0]<=tri<=limits[1]) if not lod else 150<=tri<=300,(v,lod,tri)
            lods.append({"lod":lod,"cubes":len(cubes),"triangles":tri,"bones":len(m.bones),
                         "visible_triangles":sum(len(b.get("cubes",[]))*12 for b in m.bones if b["name"]!="waterMask"),
                         "bounds_model_units":[lo.tolist(),hi.tolist()]})
        metrics[v].update(lod_distances=[0,64],lods=lods,texture_size=[128,128],
                          dye_tint_retained=v=="tiny_speedboat")
        checks.append(v+": exact native pivots; valid tree, animation bones and 128px UVs; empty flare geometry; exactly two LODs")
    return metrics,checks


def make_previews(models,textures,metrics):
    sheet=Image.new("RGB",(1800,1350),(226,229,217));d=ImageDraw.Draw(sheet)
    d.text((44,28),"ARCADE AIR + SEA",font=common.font(37),fill=(29,47,36))
    d.text((46,79),"Original cube models and pixel materials / native Superb Warfare vehicles",font=common.font(19),fill=(83,99,83))
    for i,(v,label) in enumerate(zip(VEHICLES,LABELS)):
        x=(i%2)*900;y=(i//2)*603+115
        art=render(models[v,0],textures[v],(850,460),yaw=45 if v=="mi_28" else 35)
        sheet.paste(art,(x+25,y),art)
        d.text((x+44,y+458),label,font=common.font(26),fill=(34,50,39))
        near,far=(a["triangles"] for a in metrics[v]["lods"])
        d.text((x+46,y+498),f"128px ATLAS  /  {near} → {far} TRI  /  FAR AT 64 BLOCKS",font=common.font(16,True),fill=(74,90,74))
    sheet.save(HERE/"air-sea-preview.png")
    compare=Image.new("RGB",(1600,1640),(226,229,217));d=ImageDraw.Draw(compare)
    for row,v in enumerate(VEHICLES):
        for lod in range(2):
            art=render(models[v,lod],textures[v],(770,350),yaw=45 if v=="mi_28" else 35)
            compare.paste(art,(lod*800+15,row*410),art)
            d.text((lod*800+30,row*410+358),f"{v.upper()} / {'NEAR' if not lod else 'FAR'} / {metrics[v]['lods'][lod]['triangles']} TRI",font=common.font(17,True),fill=(46,67,48))
    compare.save(HERE/"air-sea-lod-comparison.png")
    # Live pose samples resolve the same pivots as native renderer callbacks.
    poses={"ah_6":{"move_propeller":[0,47,0],"move_tailPropeller":[42,0,0]},
           "mi_28":{"move_propeller":[0,41,0],"move_tailPropeller":[43,0,0],"turret":[0,28,0],"barrel":[-12,0,0]},
           "speedboat":{"turret":[0,-25,0],"barrel":[-10,0,0],"move_control":[0,0,24],"move_rudder":[0,15,0]},
           "tiny_speedboat":{"move_control":[0,0,24],"move_rudder":[0,15,0]}}
    pose_sheet=Image.new("RGB",(1600,1100),(226,229,217));d=ImageDraw.Draw(pose_sheet)
    for i,v in enumerate(VEHICLES):
        x=i%2*800;y=i//2*550
        art=render(models[v,0],textures[v],(770,480),poses=poses[v]);pose_sheet.paste(art,(x+15,y),art)
        d.text((x+30,y+490),v.upper()+" / ROTOR + AIM + STEERING POSE",font=common.font(16,True),fill=(46,67,48))
    pose_sheet.save(HERE/"air-sea-pose-check.png")


def main():
    builders=(ah6,mi28,speedboat,tinyboat)
    models={(v,lod):b(lod) for v,b in zip(VEHICLES,builders) for lod in range(2)}
    textures={v:atlas(v) for v in VEHICLES}
    metrics,checks=validate(models,textures)
    outputs=[]
    def write(path,data):
        common.write_json(path,data);outputs.append(path)
    def save(path,im):
        path.parent.mkdir(parents=True,exist_ok=True);im.save(path);outputs.append(path)
    for v in VEHICLES:
        profiles=[]
        for lod in range(2):
            folder="vehicle_lod" if lod else "vehicle"
            suffix=".lod1" if lod else ""
            model_path=RES/"models/bedrock"/folder/(v+suffix+".geo.json")
            texture_path=RES/"textures/bedrock"/folder/(v+(".lod" if lod else "")+".png")
            write(model_path,models[v,lod].data());save(texture_path,textures[v])
            p={"Model":"superbwarfare:"+str(model_path.relative_to(RES)),"Texture":"superbwarfare:"+str(texture_path.relative_to(RES))}
            if lod:p["LODDistance"]=64
            if v=="tiny_speedboat":p["EmissiveTexture"]="superbwarfare:textures/bedrock/vehicle/tiny_speedboat_color.png"
            profiles.append(p)
        write(RES/"animations/bedrock/vehicle"/(v+".animation.json"),animations(v))
        write(RES/"sbw/vehicles"/(v+".json"),{"Animation":f"superbwarfare:animations/bedrock/vehicle/{v}.animation.json","Models":profiles})
        # Original matching icon is projected directly from this generated model.
        icon=render(models[v,0],textures[v],(256,64),yaw=80,elevation=.14)
        save(RES/"textures/vehicle_icon"/(v+"_icon.png"),icon)
        textures[v].resize((512,512),Image.Resampling.NEAREST).save(HERE/("air-sea-"+v+"-atlas.png"))
    save(RES/"textures/bedrock/vehicle/tiny_speedboat_color.png",tint_mask())
    make_previews(models,textures,metrics)
    icon_sheet=Image.new("RGBA",(560,240),(226,229,217,255));d=ImageDraw.Draw(icon_sheet)
    for i,v in enumerate(VEHICLES):
        x=i%2*280;y=i//2*120
        icon_sheet.alpha_composite(Image.open(RES/"textures/vehicle_icon"/(v+"_icon.png")),(x+12,y+8))
        d.text((x+18,y+80),v,font=common.font(17),fill=(32,46,38))
    icon_sheet.convert("RGB").save(HERE/"air-sea-icons-preview.png")
    common.write_json(HERE/"air-sea-metrics.json",metrics)
    common.write_json(HERE/"air-sea-resource-sha256.json",{str(p.relative_to(RES.parent.parent)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(outputs)})
    checks.append("All assets are generated in this script; native JAR supplies contracts only; no mechanics, Java, data or source assets copied.")
    (HERE/"air-sea-validation.log").write_text("\n".join(checks)+"\n")
    print(json.dumps({v:[x["triangles"] for x in metrics[v]["lods"]] for v in VEHICLES},indent=2))


if __name__=="__main__":main()
