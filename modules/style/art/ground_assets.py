#!/usr/bin/env python3
"""Original cube art for five retained ground vehicles; no imported source art.

Native pivots/controller names are compatibility landmarks only. Writes exactly
these five IDs' client assets plus ground-* review artifacts; never server data.
"""
from __future__ import annotations

import copy
import hashlib
import json
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

import generate_assets as shared

HERE = Path(__file__).resolve().parent
RES = HERE.parent / "src/main/resources/resourcepacks/frontier_ifv/assets/superbwarfare"
IDS = ("lav_25", "lav_ad", "hpj_11", "mortar", "tow")
TITLE = {"lav_25": "LAV-25", "lav_ad": "LAV-AD", "hpj_11": "HPJ-11 CIWS",
         "mortar": "MORTAR", "tow": "TOW"}
PIVOTS = {
    "lav_25": {"root": [0, 0, 0], "base": [0, 0, 0],
               "turret": [1.935, 40.1749, 18.55],
               "barrel": [-0.74452, 45.9092, 2.282],
               "barrel2": [1.8522, 45.9092, -6.4412]},
    "lav_ad": {"root": [0, 0, 0], "base": [0, 0, 0],
               "turret": [1.935, 40.1749, 18.55],
               "barrel": [3.6845, 47.29, 23.85],
               "gun": [2.8395, 48.085, 20.725],
               "rot_barrel": [2.8395, 48.085, 20.725]},
    "hpj_11": {"root": [0, 0, 0], "turret": [0, 0, 0],
               "barrel": [0, 22, -4], "rot_barrel": [0, 26, -33.6],
               "move_rdr": [17, 50.02842, 16.95736],
               "move_radar2": [-16.99886, 48.45694, 17.02402],
               "move_rdr2": [-17, 52, 17]},
    "mortar": {"mortar": [0, 0, 0], "move_paoguan": [0, 3.2, 0],
               "bone2": [0, 3.2, 0], "shell": [0, 30.72651, 0.28063],
               "paotong": [-0.91605, 21.62302, -1.85197],
               "move_monitor": [0, 16.92347, 0.00569],
               "move_jiaojia": [0, 18.9455, -3.01388],
               "dizuo": [0, 0.94707, -0.00128]},
    "tow": {"tow": [0, 0, 0], "base": [0, 8.32, 0],
            "turret": [0, 15.5025, 0], "barrel": [0, 18.79638, 0],
            "move_missile": [-0.58134, 19.37099, 0.98475],
            "move_guanmiao": [0, 18.79638, 0]},
}
# Atlas slots 24/25 are authored here; keep the shared helper module immutable.
TILES = {**shared.TILES, "heat_metal": 24, "quad_tubes": 25}


def tile_uv(material):
    slot = TILES[material]
    return {"uv": [(slot % 8)*16, (slot // 8)*16], "uv_size": [16, 16]}


class GroundModel(shared.Model):
    def __init__(self, vehicle, lod):
        self.vehicle, self.lod = vehicle, lod
        self.bones, self.by_name = [], {}

    def anchor(self, name, parent, rotation=None):
        b = self.bone(name, parent, PIVOTS[self.vehicle][name])
        if rotation:
            b["rotation"] = rotation
        return b

    def cube(self, bone, origin, size, material="hull", faces=None, rotation=None, pivot=None):
        uv = {face: tile_uv(material) for face in shared.FACES}
        uv.update({face: tile_uv(mat) for face, mat in (faces or {}).items()})
        self.by_name[bone].setdefault("cubes", []).append({
            "origin": [round(v, 5) for v in origin],
            "size": [round(v, 5) for v in size], "uv": uv})
        if rotation:
            c = self.by_name[bone]["cubes"][-1]
            c["rotation"] = rotation
            c["pivot"] = pivot or [origin[i] + size[i]/2 for i in range(3)]

    def data(self):
        width, height, center = ((12, 8, [0, 3, 0]) if self.vehicle.startswith("lav")
                                 else (10, 8, [0, 2, 0]))
        return {"format_version": "1.12.0", "minecraft:geometry": [{
            "description": {"identifier": "geometry.frontier_" + self.vehicle,
                            "texture_width": 128, "texture_height": 128,
                            "visible_bounds_width": width, "visible_bounds_height": height,
                            "visible_bounds_offset": center}, "bones": self.bones}]}


def lav_chassis(vehicle, lod):
    m = GroundModel(vehicle, lod)
    for n, p in (("root", None), ("base", "root"), ("turret", "base"), ("barrel", "turret")):
        m.anchor(n, p)
    # Wheel groups are direct root children: the native zoom behavior hides them
    # with the hull; eight native steering/spin anchors remain in both LODs.
    for side, sign in (("L", 1), ("R", -1)):
        for i, z in enumerate((-35.4664, -13.6164, 14.0336, 35.1836)):
            x, y = sign * 21.8916, 9.96
            name = f"wheel{side}{i}" + ("Turn" if i < 2 else "")
            m.bone(name, "root", [x, y, z])
            m.cube(name, [x-4.3, y-9.8, z-9.8], [8.6, 19.6, 19.6], "rubber",
                   {"east": "wheel", "west": "wheel"})
            if not lod:
                m.cube(name, [x+sign*4.8-0.8, y-4.1, z-4.1], [1.6, 8.2, 8.2], "bolt")
                m.cube("base", [x-3, 19.6, z-11], [6, 2.5, 22], "hull_dark")
    m.cube("base", [-22.5, 17, -46], [45, 17, 96], "hull", {"up": "roof"})
    m.cube("base", [-21, 17, -57], [42, 9, 13], "hull_dark")
    m.cube("base", [-21, 26, -51], [42, 8, 14], "hull_light", {"up": "roof"})
    if not lod:
        for x in (-24, 21):
            m.cube("base", [x, 26, -45], [3, 6, 91], "panel")
        for x in (-18, 4):
            m.cube("base", [x, 34, -32], [14, 1.5, 18], "hatch" if x < 0 else "vent")
        for x in (-18, 12):
            m.cube("base", [x, 27, -54], [6, 4, 3], "lamp")
            m.cube("base", [x, 28, 49], [4, 4, 1.5], "tail")
        m.cube("base", [-15, 19, 49], [30, 13, 2], "hatch_line")
        m.cube("base", [-18, 34, 26], [32, 1.5, 20], "vent")
    return m


def lav25(lod):
    m = lav_chassis("lav_25", lod)
    m.anchor("barrel2", "barrel")
    m.bone("flare", "barrel2", [1.86, 45.89482, -32.20299])
    m.bone("flare2", "barrel", [-2.68, 45.17982, -3.40299])
    m.cube("turret", [-11, 35, 3], [27, 13, 30], "hull", {"up": "roof"})
    m.cube("turret", [-7, 48, 8], [20, 4, 23], "hull_light", {"up": "hatch"})
    # Native Cannon ShootPos produces this bore; preserve native recoil child.
    m.cube("barrel2", [0.46, 44.495, -32.203], [2.8, 2.8, 40], "metal", {"north": "bore"})
    m.cube("turret", [-21, 50, 8], [9, 7, 23], "launcher", {"north": "bore"})
    m.cube("turret", [-19, 43, 17], [10, 9, 8], "hull_dark")
    if not lod:
        m.cube("barrel", [-4.5, 41.5, -2], [12, 9, 11], "hull_dark")
        m.cube("barrel2", [-0.14, 43.895, -32.203], [4, 4, 4], "metal", {"north": "bore"})
        m.cube("barrel", [-3.68, 44.18, -3.403], [2, 2, 17], "metal", {"north": "bore"})
        for x in (-12, 15):
            m.cube("turret", [x, 40, 13], [1.5, 6, 15], "stencil")
        m.cube("turret", [-6, 52, 16], [9, 2.5, 10], "hatch")
        m.cube("turret", [6, 52, 17], [5, 4, 7], "optic")
        m.cube("turret", [10, 51, 28], [1.2, 15, 1.2], "metal")
    return m


FIM_POINTS = [(0.068, .76, 1.3125), (-.18, .76, 1.3125),
              (.068, .525, 1.3125), (-.18, .525, 1.3125),
              (-1.52, .72, 1.3125), (-1.77, .72, 1.3125),
              (-1.52, .488, 1.3125), (-1.77, .488, 1.3125)]


def lavad(lod):
    m = lav_chassis("lav_ad", lod)
    m.anchor("gun", "barrel")
    m.anchor("rot_barrel", "gun")
    m.bone("flare", "gun", [3.6845, 47.29, -7.51])
    m.cube("turret", [-12, 35, 5], [28, 13, 30], "hull_dark", {"up": "roof"})
    m.cube("turret", [-8, 48, 11], [21, 4, 20], "hull", {"up": "hatch"})
    m.cube("barrel", [-25, 50.5, 17], [34, 4, 5], "hull_dark")
    m.cube("rot_barrel", [1.8845, 45.49, -7.51], [3.6, 3.6, 30], "heat_metal", {"north": "bore"})
    # Launcher housings persist after firing. There is no native model-state
    # callback for FIM ammo; never paint eight permanent "loaded" missile tips.
    if lod:
        for x, y in ((-24.45, 53.398), (-1.012, 53.89)):
            m.cube("barrel", [x, y, 2.85], [8.4, 8.2, 20], "launcher", {"north": "quad_tubes"})
    else:
        for sx, sy, sz in FIM_POINTS:
            center = [3.6845+sx*16, 47.29+sy*16, 23.85-sz*16]
            m.cube("barrel", [center[0]-1.7, center[1]-1.7, center[2]],
                   [3.4, 3.4, 20.5], "launcher", {"north": "bore"})
        m.cube("gun", [-2, 43.4, 13], [11, 9.5, 12], "hull_dark")
        for x, y in ((-.6, 0), (.6, 0), (0, -.6), (0, .6)):
            m.cube("rot_barrel", [2.2+x, 45.8+y, -7.51], [2.8, 2.8, 3], "heat_metal", {"north": "bore"})
        for x in (-22.4, 2.8):
            m.cube("barrel", [x-1.5, 48, 15], [3, 7, 7], "metal")
        m.cube("turret", [-8, 52, 13], [9, 3, 9], "hatch")
        m.cube("turret", [8, 52, 18], [4, 6, 6], "optic")
        m.cube("turret", [10, 51, 30], [1.2, 16, 1.2], "metal")
    return m


def hpj11(lod):
    m = GroundModel("hpj_11", lod)
    m.anchor("root", None)
    m.anchor("turret", "root")
    m.anchor("barrel", "turret")
    m.anchor("rot_barrel", "barrel")
    m.anchor("move_rdr", "turret")
    m.anchor("move_radar2", "turret")
    m.anchor("move_rdr2", "move_radar2")
    m.bone("flare", "barrel", [0, 26.8, -45.2484])
    m.cube("root", [-20, 0, -15], [40, 4, 39], "hull_dark")
    m.cube("turret", [-12, 4, -11], [24, 26, 35], "hull", {"up": "roof"})
    m.cube("turret", [-10, 30, 3], [20, 10, 20], "hull_light")
    # Dual radar/sensor silhouette; original native camera/pitch anchors survive.
    m.cube("turret", [13, 23, 10], [8, 24, 15], "hull_dark")
    m.cube("move_rdr", [11, 45, 14], [12, 12, 6], "grille")
    m.cube("move_radar2", [-22, 25, 10], [10, 24, 15], "hull")
    m.cube("move_rdr2", [-24, 48, 14], [14, 9, 6], "optic")
    m.cube("barrel", [-7, 19.8, -15], [14, 14, 14], "metal")
    if lod:
        m.cube("rot_barrel", [-4.5, 22.3, -45.2484], [9, 9, 32], "heat_metal", {"north": "bore"})
        m.cube("turret", [-15, 10, 20], [30, 14, 8], "vent")
        for x in (-23, 16):
            m.cube("root", [x, 0, -19], [7, 3, 8], "metal")
        m.cube("barrel", [-6, 20.8, -30], [12, 12, 3], "metal")
    else:
        # Eleven square bores around one central line, only 11 long cubes.
        # The center bore and flare coincide with unchanged native ShootPos.
        for x, y in ((0, 0), (-3.5, 0), (3.5, 0), (0, -3.5), (0, 3.5),
                     (-2.6, -2.6), (2.6, -2.6), (-2.6, 2.6), (2.6, 2.6),
                     (-1.4, 1.4), (1.4, -1.4)):
            m.cube("rot_barrel", [x-.95, 26.8+y-.95, -45.2484], [1.9, 1.9, 32],
                   "heat_metal", {"north": "bore"})
        for z in (-34, -19):
            m.cube("barrel", [-5.8, 21, z], [11.6, 11.6, 2], "metal")
        for x in (-20, 14):
            for z in (-15, 19):
                m.cube("root", [x, 0, z], [6, 3, 8], "metal")
                m.cube("root", [x+1.5, 3, z+2], [3, 1, 3], "bolt")
        for x in (-13, 12):
            m.cube("turret", [x, 13, -8], [1, 11, 15], "panel")
            m.cube("turret", [x, 13, 10], [1, 11, 10], "vent")
        m.cube("turret", [-9, 9, 24], [18, 17, 2], "hatch_line")
        m.cube("turret", [-8, 40, 6], [16, 1.5, 14], "hatch")
        m.cube("turret", [10, 37, 20], [1.2, 15, 1.2], "metal")
        m.cube("move_rdr", [10, 43, 17], [14, 2, 5], "metal")
        m.cube("move_radar2", [-20, 23, 12], [6, 2, 9], "metal")
        m.cube("turret", [-8, 32, -1], [16, 5, 3], "stencil")
        m.cube("turret", [-10, 13, 26], [20, 9, 5], "hull_dark")
        m.cube("move_rdr", [10, 56, 14], [14, 1.5, 6], "hull_dark")
        m.cube("move_rdr2", [-25, 56, 14], [16, 1.5, 6], "hull_dark")
    return m


def mortar(lod):
    m = GroundModel("mortar", lod)
    for n, p, r in (("mortar", None, None), ("move_paoguan", "mortar", None),
                    ("bone2", "move_paoguan", [90, 0, 0]), ("shell", "bone2", None),
                    ("paotong", "bone2", None), ("move_monitor", "paotong", None),
                    ("move_jiaojia", "bone2", None), ("dizuo", "mortar", None)):
        m.anchor(n, p, r)
    m.bone("flare", "bone2", [0, 31.7, 0])
    m.cube("dizuo", [-6.5, 0, -5.5], [13, 1.8, 11], "hull_dark", {"up": "hatch"})
    m.cube("dizuo", [-3, 1.8, -3], [6, 1.5, 6], "metal")
    # A real open square bore: four continuous walls, no dark cap across it.
    for origin, size in (([-2.3, 3.2, -2.3], [4.6, 28.5, .85]),
                         ([-2.3, 3.2, 1.45], [4.6, 28.5, .85]),
                         ([-2.3, 3.2, -1.45], [.85, 28.5, 2.9]),
                         ([1.45, 3.2, -1.45], [.85, 28.5, 2.9])):
        m.cube("paotong", origin, size, "metal", {"up": "bore"})
    m.cube("move_jiaojia", [-5.5, 17.5, -4], [11, 2.8, 2], "hull_dark")
    for sign in (-1, 1):
        m.cube("move_jiaojia", [sign*4-1, 4, -4.8], [2, 15.5, 2], "hull_dark",
               rotation=[0, 0, -sign*14], pivot=[sign*4, 18, -3.8])
        m.cube("move_jiaojia", [sign*6-2, 3, -5], [4, 1.5, 4], "metal")
    m.cube("move_monitor", [-7, 16, -3], [4, 5, 2], "hull_dark", {"north": "optic"})
    m.cube("shell", [-1.2, 33, -1.2], [2.4, 6, 2.4], "launcher")
    if not lod:
        for x in (-5.5, 3):
            for z in (-4.5, 3):
                m.cube("dizuo", [x, 1.8, z], [2.5, .8, 1.5], "bolt")
        for y in (7, 17, 29):
            for x, z, sx, sz in ((-2.9, -2.9, 5.8, .8), (-2.9, 2.1, 5.8, .8),
                                 (-2.9, -2.1, .8, 4.2), (2.1, -2.1, .8, 4.2)):
                m.cube("paotong", [x, y, z], [sx, 1.4, sz], "hull_dark")
        m.cube("paotong", [2.3, 18, -.9], [2, 4, 2], "bolt")
        m.cube("move_jiaojia", [-5, 16, -6], [2, 3, 2], "metal")
        m.cube("move_jiaojia", [3, 16, -6], [2, 3, 2], "metal")
        m.cube("move_monitor", [-6.5, 16.7, -3.4], [3, 3.5, .6], "optic")
        m.cube("move_monitor", [-6, 15, -1.3], [2, 1, 1], "metal")
        m.cube("shell", [-.8, 39, -.8], [1.6, 1.4, 1.6], "metal")
        m.cube("dizuo", [-2, 1.9, 3], [4, .7, 1.5], "stencil")
        for x in (-6.5, 5.5):
            m.cube("dizuo", [x, 1.8, -5.5], [1, .8, 11], "metal")
        for x in (-3.8, 2.3):
            m.cube("paotong", [x, 17.5, -2], [1.5, 3, 4], "hull_dark")
        m.cube("move_jiaojia", [-6, 14, -5.5], [1, 6, 1], "metal")
        m.cube("move_jiaojia", [5, 14, -5.5], [1, 6, 1], "metal")
    return m


def tow(lod):
    m = GroundModel("tow", lod)
    for n, p in (("tow", None), ("base", "tow"), ("turret", "tow"),
                 ("barrel", "turret"), ("move_missile", "barrel"),
                 ("move_guanmiao", "turret")):
        m.anchor(n, p)
    m.bone("flare", "barrel", [-.58134, 19.37099, -11])
    # Three large stepped feet, with support braces rather than micro-details.
    m.cube("base", [-2, 7.5, -2], [4, 8, 4], "metal")
    for x, z in ((-6, 4), (6, 4), (0, -6)):
        m.cube("base", [x-1.5, 0, z-1.5], [3, 2, 3], "metal")
        m.cube("base", [x/2-1, 1.5, z/2-1], [2, 9, 2], "hull_dark")
    m.cube("turret", [-4, 14.2, -2.5], [8, 3.5, 5], "hull_dark")
    # Square open launcher tube, aligned with native barrel forward direction.
    for origin, size in (([-3.58, 16.37, -11], [6, 1, 24]),
                         ([-3.58, 21.37, -11], [6, 1, 24]),
                         ([-3.58, 17.37, -11], [1, 4, 24]),
                         ([1.42, 17.37, -11], [1, 4, 24])):
        m.cube("barrel", origin, size, "launcher", {"north": "metal"})
    m.cube("move_missile", [-1.68, 18.27, -11.4], [2.2, 2.2, 19], "metal", {"north": "bore"})
    m.cube("move_guanmiao", [3, 20.2, -4], [5, 5, 8], "hull_dark", {"north": "optic"})
    if not lod:
        for z in (-9, 9):
            for origin, size in (([-4.08, 15.87, z], [7, 1, 2]),
                                 ([-4.08, 21.87, z], [7, 1, 2]),
                                 ([-4.08, 16.87, z], [1, 5, 2]),
                                 ([1.92, 16.87, z], [1, 5, 2])):
                m.cube("barrel", origin, size, "hull_dark")
        m.cube("move_guanmiao", [3.7, 20.9, -4.5], [3.6, 3.6, .7], "optic")
        m.cube("move_guanmiao", [4, 18, 2], [2, 2.5, 3], "metal")
        m.cube("turret", [-5, 13, -.8], [2, 5, 1.6], "hull_dark")
        m.cube("turret", [-6, 16, -1], [3, 1.6, 2], "metal")
        m.cube("turret", [-2, 12, -3], [4, 2, 6], "bolt")
        m.cube("base", [-4, 9, -3], [8, 2, 6], "hull")
        m.cube("base", [-3, 11, -2], [6, 1.5, 4], "metal")
        for x, z in ((-6, 4), (6, 4), (0, -6)):
            m.cube("base", [x-1, 2, z-1], [2, 1, 2], "bolt")
        m.cube("move_missile", [-1.38, 18.57, -12], [1.6, 1.6, .8], "bore")
        m.cube("barrel", [-2, 22.37, 1], [3, 1.5, 7], "panel")
        m.cube("turret", [-1, 13, 3], [2, 2, 2], "bolt")
        for x, z in ((-6, 4), (6, 4), (0, -6)):
            m.cube("base", [min(0,x)-.5, 6, min(0,z)-.5],
                   [abs(x)+1, 1, abs(z)+1], "metal")
        m.cube("move_guanmiao", [3, 20.2, 4], [5, 1, 2], "metal")
        m.cube("move_guanmiao", [4, 23.8, 4], [3, 1.5, 2], "panel")
        m.cube("turret", [-3, 15.5, 3], [2, 2, 2], "metal")
        m.cube("turret", [-3.5, 15, 4], [3, 3, 1], "bolt")
    return m


def atlas(vehicle):
    # The IFV's original olive tiles are shared, not sampled native SBW textures.
    image = shared.atlas("bmp_2")
    d = ImageDraw.Draw(image)
    d.rectangle((0, 48, 15, 63), fill=(60, 65, 50, 255))
    for y in range(50, 63, 4):
        d.line((2, y, 13, y), fill=(76, 81, 63, 255), width=1)
    d.rectangle((16, 48, 31, 63), fill=(54, 67, 39, 255))
    for x in (18, 25):
        for y in (50, 57):
            d.rectangle((x, y, x+4, y+4), fill=(14, 20, 16, 255))
            d.point((x+1, y+1), fill=(63, 70, 51, 255))
    return image


def fire(scale=5.5, start=0):
    return {f"{start:.3f}": [0, 0, 0], f"{start+.008:.3f}": [4, 4, 4],
            f"{start+.045:.3f}": [scale]*3, f"{start+.075:.3f}": [0, 0, 0]}


def animations(vehicle):
    a = {}
    if vehicle == "lav_25":
        return shared.animations()
    weapon = {"lav_ad": "cannon", "hpj_11": "main", "mortar": "main", "tow": "missile"}[vehicle]
    a[f"animation.{weapon}.idle"] = {"loop": True, "bones": {"flare": {"scale": 0}}}
    bones = {"flare": {"scale": fire()}}
    length = .24
    if vehicle in ("lav_ad", "hpj_11"):
        bones["rot_barrel"] = {"rotation": {"0.0": [0, 0, 0], "0.05": [0, 0, -120],
                                                       "0.14": [0, 0, -270], "0.24": [0, 0, -360]}}
    if vehicle == "mortar":
        # Native mortar fires 20 ticks after its loading animation begins.
        a["animation.main.idle"]["bones"].update({"mortar": {"position": [0, 0, 0]}, "shell": {"scale": 0}})
        bones = {"shell": {"scale": {"0.0": [0, 0, 0], "0.15": [1, 1, 1],
                                       "0.75": [1, 1, 1], "0.95": [0, 0, 0]},
                            "position": {"0.15": [0, 3, 0], "0.4": [0, 0, 0], "0.9": [0, -16, 0]}},
                 "flare": {"scale": fire(start=1.0)}}
        length = 1.2
    a[f"animation.{weapon}.fire"] = {"loop": "hold_on_last_frame", "animation_length": length, "bones": bones}
    return {"format_version": "1.8.0", "animations": a}


def matrix(rotation, pivot):
    x, y, z = np.radians(rotation)
    rx = np.array([[1,0,0], [0,math.cos(x),-math.sin(x)], [0,math.sin(x),math.cos(x)]])
    ry = np.array([[math.cos(y),0,math.sin(y)], [0,1,0], [-math.sin(y),0,math.cos(y)]])
    rz = np.array([[math.cos(z),-math.sin(z),0], [math.sin(z),math.cos(z),0], [0,0,1]])
    out = np.eye(4); out[:3,:3] = rz @ ry @ rx
    out[:3,3] = np.asarray(pivot) - out[:3,:3] @ np.asarray(pivot)
    return out


def render(model, tex, size=(800, 560), yaw=35):
    """Project authored faces with hierarchy/rotations; previews are not game QA."""
    w, h = size
    camera = np.array([math.sin(math.radians(yaw)), .62, -math.cos(math.radians(yaw))])
    camera /= np.linalg.norm(camera)
    right = np.cross([0,1,0], camera); right /= np.linalg.norm(right)
    up = np.cross(camera, right)
    world, surfaces = {}, []
    for b in model.bones:
        rot = b.get("rotation", [0,0,0])
        if model.vehicle == "mortar" and b["name"] == "move_paoguan":
            rot = [-70, 0, 0]  # Review pose corresponding to native elevated tube.
        world[b["name"]] = world.get(b.get("parent"), np.eye(4)) @ matrix(rot, b["pivot"])
        if b["name"].startswith("flare") or b["name"] == "shell":
            continue
        for c in b.get("cubes", []):
            mat = world[b["name"]] @ matrix(c.get("rotation", [0,0,0]), c.get("pivot", [0,0,0]))
            for f, corners in shared.FACE_CORNERS.items():
                points = np.array(corners)*np.asarray(c["size"])+np.asarray(c["origin"])
                points = (np.c_[points, np.ones(4)] @ mat.T)[:,:3]
                normal = mat[:3,:3] @ shared.NORMALS[f]
                if normal @ camera > 0:
                    surfaces.append((points, normal, c["uv"][f]))
    allp = np.concatenate([x[0] for x in surfaces]); proj = np.stack((allp@right, allp@up), axis=1)
    lo, hi = proj.min(axis=0), proj.max(axis=0)
    scale = min((w-45)/(hi[0]-lo[0]), (h-50)/(hi[1]-lo[1])); cx, cy = (lo+hi)/2
    zbuf = np.full((h,w), -1e30); pixels = np.zeros((h,w,4), dtype=np.uint8); texp = np.asarray(tex)
    light_dir = np.array([-.4,1,-.7]); light_dir /= np.linalg.norm(light_dir)
    for pts, normal, info in surfaces:
        u0,v0 = info["uv"]; su,sv = info["uv_size"]
        uv = np.array([[u0,v0+sv-.01],[u0+su-.01,v0+sv-.01],[u0+su-.01,v0],[u0,v0]])
        for ids in ((0,1,2),(0,2,3)):
            p, tuv = pts[list(ids)], uv[list(ids)]
            screen = np.stack(((p@right-cx)*scale+w/2, h/2-(p@up-cy)*scale), axis=1)
            x0=max(0,int(screen[:,0].min())); x1=min(w-1,math.ceil(screen[:,0].max()))
            y0=max(0,int(screen[:,1].min())); y1=min(h-1,math.ceil(screen[:,1].max()))
            if x1<x0 or y1<y0: continue
            xx,yy=np.meshgrid(np.arange(x0,x1+1)+.5,np.arange(y0,y1+1)+.5)
            a,b,c=screen; den=(b[1]-c[1])*(a[0]-c[0])+(c[0]-b[0])*(a[1]-c[1])
            if abs(den)<1e-8: continue
            u=((b[1]-c[1])*(xx-c[0])+(c[0]-b[0])*(yy-c[1]))/den
            v=((c[1]-a[1])*(xx-c[0])+(a[0]-c[0])*(yy-c[1]))/den; t=1-u-v
            depth=u*(p[0]@camera)+v*(p[1]@camera)+t*(p[2]@camera); old=zbuf[y0:y1+1,x0:x1+1]
            mask=(u>=-1e-6)&(v>=-1e-6)&(t>=-1e-6)&(depth>old)
            tx=np.clip(np.floor(u*tuv[0,0]+v*tuv[1,0]+t*tuv[2,0]).astype(int),0,127)
            ty=np.clip(np.floor(u*tuv[0,1]+v*tuv[1,1]+t*tuv[2,1]).astype(int),0,127)
            color=texp[ty,tx].copy(); color[:,:,:3]=(color[:,:,:3]*(.72+.28*max(0,normal@light_dir))).astype(np.uint8)
            pixels[y0:y1+1,x0:x1+1][mask]=color[mask]; old[mask]=depth[mask]
    return Image.fromarray(pixels, "RGBA")


def validate_saved():
    metrics = {}
    for v in IDS:
        profile = json.loads((RES/f"sbw/vehicles/{v}.json").read_text())
        assert set(profile) == {"Models", "Animation"}
        assert len(profile["Models"]) == 2 and profile["Models"][1]["LODDistance"] == 64
        assert "LODDistance" not in profile["Models"][0]
        ani = json.loads((RES/f"animations/bedrock/vehicle/{v}.animation.json").read_text())["animations"]
        reports=[]
        for lod, entry in enumerate(profile["Models"]):
            path = RES/entry["Model"].split(":",1)[1]
            texture = RES/entry["Texture"].split(":",1)[1]
            assert Image.open(texture).size == (128,128)
            g = json.loads(path.read_text())["minecraft:geometry"][0]
            bs=g["bones"]; names={b["name"]:b for b in bs}
            assert len(names)==len(bs)
            for name, pivot in PIVOTS[v].items():
                assert names[name]["pivot"] == pivot, (v,lod,name)
            for b in bs:
                seen=set(); a=b
                while "parent" in a:
                    assert a["name"] not in seen
                    seen.add(a["name"]); a=names[a["parent"]]
            for a in ani.values():
                assert set(a.get("bones",{})) <= set(names)
            flares=[b for b in bs if b["name"].startswith("flare")]
            assert flares and all(not b.get("cubes") for b in flares)
            cubes=[c for b in bs for c in b.get("cubes",[])]; triangles=12*len(cubes)
            assert (500 <= triangles <= 1000) if lod == 0 else (150 <= triangles <= 300), (v,lod,triangles)
            for c in cubes:
                assert all(math.isfinite(q) for q in c["origin"]+c["size"])
                assert min(c["size"])>0 and "poly_mesh" not in c
                assert set(c["uv"]) == set(shared.FACES)
                for f in c["uv"].values():
                    assert all(0 <= u < u+s <=128 for u,s in zip(f["uv"],f["uv_size"]))
            if v.startswith("lav"):
                for side,sign in (("L",1),("R",-1)):
                    for i,z in enumerate((-35.4664,-13.6164,14.0336,35.1836)):
                        n=f"wheel{side}{i}"+("Turn" if i<2 else "")
                        assert names[n]["pivot"] == [sign*21.8916,9.96,z] and names[n].get("cubes")
            if v == "tow":
                assert names["move_missile"].get("cubes") and names["move_guanmiao"].get("cubes")
            if v == "hpj_11":
                assert names["flare"]["pivot"] == [0,26.8,-45.2484]
            reports.append({"lod":lod,"bones":len(bs),"cubes":len(cubes),"triangles":triangles})
        metrics[v]={"lod_distances":[0,64],"texture_size":[128,128],"native_pivots":PIVOTS[v],"lods":reports}
    return metrics


def main():
    builders = dict(lav_25=lav25,lav_ad=lavad,hpj_11=hpj11,mortar=mortar,tow=tow)
    models = {(v,l):builders[v](l) for v in IDS for l in range(2)}
    textures = {v:atlas(v) for v in IDS}
    owned=[]
    def write(path,data):
        shared.write_json(path,data); owned.append(path)
    def save(path,image):
        path.parent.mkdir(parents=True,exist_ok=True); image.save(path); owned.append(path)
    for v in IDS:
        entries=[]
        for lod in range(2):
            folder="vehicle_lod" if lod else "vehicle"; suffix=".lod1" if lod else ""
            write(RES/f"models/bedrock/{folder}/{v}{suffix}.geo.json",models[v,lod].data())
            texsuffix=".lod" if lod else ""
            save(RES/f"textures/bedrock/{folder}/{v}{texsuffix}.png",textures[v])
            entry={"Model":f"superbwarfare:models/bedrock/{folder}/{v}{suffix}.geo.json",
                   "Texture":f"superbwarfare:textures/bedrock/{folder}/{v}{texsuffix}.png"}
            if lod: entry["LODDistance"]=64
            entries.append(entry)
        write(RES/f"animations/bedrock/vehicle/{v}.animation.json",animations(v))
        write(RES/f"sbw/vehicles/{v}.json",{"Animation":f"superbwarfare:animations/bedrock/vehicle/{v}.animation.json","Models":entries})
        if v in ("lav_ad","hpj_11"):
            heat=Image.new("RGBA",(128,128),(0,0,0,0)); hd=ImageDraw.Draw(heat)
            hd.rectangle((0,48,15,63),fill=(231,95,24,210))
            for y in (51,55,59): hd.line((1,y,14,y),fill=(255,157,49,235),width=1)
            save(RES/f"textures/bedrock/vehicle/{v}_heat.png",heat)
        image=render(models[v,0],textures[v],(1000,680))
        image.save(HERE/f"ground-{v}-preview.png")
        textures[v].resize((512,512),Image.Resampling.NEAREST).save(HERE/f"ground-{v}-atlas.png")
        save(RES/f"textures/vehicle_icon/container/{v}.png",render(models[v,0],textures[v],(256,256)).resize((128,128),Image.Resampling.LANCZOS))
        save(RES/f"textures/vehicle_icon/{v}_icon.png",render(models[v,0],textures[v],(768,192),yaw=70).resize((256,64),Image.Resampling.LANCZOS))
    metrics=validate_saved()
    shared.write_json(HERE/"ground-metrics.json",metrics)
    checksums={str(p.relative_to(RES)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(owned)}
    shared.write_json(HERE/"ground-sha256.json",checksums)
    shared.write_json(HERE/"ground-validation.json",{"status":"pass","checks":["saved resources and references","exact native aiming/radar/support pivots","8 LAV wheels","empty animated flare anchors","128px tiled UV bounds","native state anchors","two levels 0/64","triangle budgets"],"metrics":metrics})
    sheet=Image.new("RGB",(1800,1320),(226,229,217)); d=ImageDraw.Draw(sheet)
    d.text((45,30),"FRONTIER / GROUND",font=shared.font(34),fill=(31,46,38))
    d.text((47,80),"Original cube art · native rigs · one far LOD at 64 blocks",font=shared.font(19),fill=(83,99,83))
    for i,v in enumerate(IDS):
        x=(i%3)*600; y=(i//3)*585+130
        im=render(models[v,0],textures[v],(580,450)); sheet.paste(im,(x+10,y),im)
        d.text((x+30,y+460),TITLE[v],font=shared.font(26),fill=(31,46,38))
        tri=[str(q['triangles']) for q in metrics[v]['lods']]
        d.text((x+30,y+500),"TRI  "+" → ".join(tri),font=shared.font(17,True),fill=(72,90,73))
    d.text((1230,850),"128 × 128",font=shared.font(28),fill=(31,46,38))
    d.text((1230,900),"SHARED PIXEL MATERIALS",font=shared.font(16,True),fill=(83,99,83))
    d.text((1230,960),"Software geometry preview.",font=shared.font(17),fill=(83,99,83))
    d.text((1230,990),"Game verification is separate.",font=shared.font(17),fill=(83,99,83))
    sheet.save(HERE/"ground-preview.png")
    lodsheet=Image.new("RGB",(1200,5*350),(226,229,217)); ld=ImageDraw.Draw(lodsheet)
    for row,v in enumerate(IDS):
        for lod in range(2):
            im=render(models[v,lod],textures[v],(580,290)); lodsheet.paste(im,(lod*600+10,row*350),im)
            ld.text((lod*600+22,row*350+300),f"{TITLE[v]} / {'NEAR' if not lod else '64+'} / {metrics[v]['lods'][lod]['triangles']} TRI",font=shared.font(15,True),fill=(51,73,53))
    lodsheet.save(HERE/"ground-lod-comparison.png")
    print(json.dumps({v:metrics[v]["lods"] for v in IDS},indent=2))


if __name__ == "__main__":
    main()
