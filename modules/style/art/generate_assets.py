#!/usr/bin/env python3
"""Original cube-and-pixel IFV art. No source meshes or textures are imported.

Python 3 + Pillow + NumPy. Run from any directory. Only writes ../resources and
this art directory. The native mod supplies mechanics; these files are visuals.
"""
from __future__ import annotations

import hashlib
import json
import math
from pathlib import Path
import random

import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
RES = HERE.parent / "src/main/resources/resourcepacks/frontier_ifv" / "assets" / "superbwarfare"
FACES = ("north", "south", "east", "west", "up", "down")
PIVOTS = {
    "bmp_2": {"base": [0, 0, 0], "turret": [0, 36, 11.25],
              "barrel": [0, 40.8656, -1.78073]},
    "bradley": {"base": [0, 12, 0], "turret": [-2.42952, 35.776, 2.04492],
                "barrel": [-2.42952, 42.7592, -7.568]},
}
TILES = {
    "hull": 0, "hull_dark": 1, "hull_light": 2, "roof": 3,
    "panel": 4, "hatch": 5, "vent": 6, "optic": 7,
    "track": 8, "tread": 9, "wheel": 10, "rubber": 11,
    "metal": 12, "bore": 13, "lamp": 14, "tail": 15,
    "stencil": 16, "grille": 17, "tow_front": 18, "launcher": 19,
    "flash": 20, "flash_core": 21, "bolt": 22, "hatch_line": 23,
}


def write_json(path: Path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n")


def tile_uv(name):
    n = TILES[name]
    return {"uv": [(n % 8) * 16, (n // 8) * 16], "uv_size": [16, 16]}


class Model:
    def __init__(self, vehicle, lod):
        self.vehicle, self.lod = vehicle, lod
        self.bones = []
        self.by_name = {}
        self.bone("root", None, [0, 0, 0])
        self.bone("base", "root", PIVOTS[vehicle]["base"])
        self.bone("turret", "base", PIVOTS[vehicle]["turret"])
        self.bone("barrel", "turret", PIVOTS[vehicle]["barrel"])
        self.bone("barrel2", "barrel", PIVOTS[vehicle]["barrel"])
        self.bone("move_Track", "root", [0, 0, 0])
        for side in "LR":
            self.bone("Wheel" + side, "move_Track", [0, 0, 0])
        # Both flash bones exist at every LOD so animation tracks resolve safely.
        self.bone("flare", "barrel2", [0, 0, 0])
        self.bone("flare2", "barrel", [0, 0, 0])

    def bone(self, name, parent, pivot):
        bone = {"name": name, "pivot": pivot}
        if parent:
            bone["parent"] = parent
        self.bones.append(bone)
        self.by_name[name] = bone
        return bone

    def cube(self, bone, origin, size, material="hull", faces=None):
        uv = {face: tile_uv(material) for face in FACES}
        if faces:
            uv.update({f: tile_uv(t) for f, t in faces.items()})
        cube = {"origin": origin, "size": size, "uv": uv}
        self.by_name[bone].setdefault("cubes", []).append(cube)

    def gun(self, x, y, muzzle, rear, width, mg_x, mg_muzzle):
        self.cube("barrel2", [x-width/2, y-width/2, muzzle],
                  [width, width, rear-muzzle], "metal", {"north": "bore"})
        if self.lod == 0:
            self.cube("barrel", [x-5, y-4, rear-3], [10, 8, 11], "hull_dark")
            self.cube("barrel2", [x-width*.8, y-width*.8, muzzle],
                      [width*1.6, width*1.6, 5], "metal", {"north": "bore"})
            self.cube("barrel", [mg_x-1, y-1, mg_muzzle],
                      [2, 2, rear-mg_muzzle+2], "metal", {"north": "bore"})
        self.by_name["flare"]["pivot"] = [x, y, muzzle-1]
        self.by_name["flare2"]["pivot"] = [mg_x, y, mg_muzzle-1]
        # Native GeoVehicleRenderer renders its shared muzzle_flare at these
        # bone transforms and hides any custom flare geometry. Keep bones only.

    def tracks(self, width, front, rear, top, wheel_y, wheel_size):
        """One continuous band per side; 6 independently animated wheel bones.

        No trackMov/trackRot bone names: native belt code assumes native geometry.
        All coordinates are absolute Bedrock coordinates, including bone pivots.
        """
        for side, sign in (("L", 1), ("R", -1)):
            center_x = sign * width
            self.cube("move_Track", [center_x-5, 1, front],
                      [10, top-1, rear-front], "track",
                      {"up": "tread", "down": "tread", "north": "tread", "south": "tread"})
            # Wheels sit outside the band, with the hubs proud of the rubber.
            wheel_x = center_x + sign*3
            for i in range(6):
                z = front + 11 + i*(rear-front-22)/5
                bone = "wheel" + side + str(i)
                self.bone(bone, "Wheel"+side, [wheel_x, wheel_y, round(z, 4)])
                if self.lod < 2:
                    self.cube(bone, [wheel_x-4, wheel_y-wheel_size/2, z-wheel_size/2],
                              [8, wheel_size, wheel_size], "rubber",
                              {"east": "wheel", "west": "wheel"})
                    if self.lod == 0:
                        hub_x = wheel_x+sign*4.2
                        self.cube(bone, [hub_x-0.8, wheel_y-3.5, z-3.5],
                                  [1.6, 7, 7], "hull_dark", {"east": "bolt", "west": "bolt"})

    def data(self):
        return {"format_version": "1.12.0", "minecraft:geometry": [{
            "description": {"identifier": "geometry.arcade_"+self.vehicle,
                            "texture_width": 128, "texture_height": 128,
                            "visible_bounds_width": 12, "visible_bounds_height": 8,
                            "visible_bounds_offset": [0, 3, 0]},
            "bones": self.bones}]}


def bmp(lod):
    m = Model("bmp_2", lod)
    m.tracks(27, -64, 59, 21, 10, 14)
    # Low, long Soviet hull. The stepped glacis is deliberately Minecraft-like.
    m.cube("base", [-28, 17, -54], [56, 16, 115], "hull", {"up": "roof"})
    m.cube("base", [-27, 18, -72], [54, 8, 20], "hull_dark")
    m.cube("base", [-27, 26, -65], [54, 7, 22], "hull", {"up": "roof"})
    m.cube("turret", [-17, 35, -5], [34, 12, 34], "hull", {"up": "roof"})
    m.cube("turret", [-13, 47, -1], [26, 3, 26], "hull_light", {"up": "hatch"})
    # Single roof-mounted anti-tank missile tube is a defining BMP-2 cue.
    m.cube("turret", [-4, 54, 0], [8, 7, 29], "launcher",
           {"north": "bore", "south": "bore"})
    m.gun(-1.8, 43.65, -61, -8, 2.8, 5, -21)
    if lod < 2:
        m.cube("turret", [-2, 50, 12], [4, 5, 8], "hull_dark")
    if lod == 0:
        # Wide slab fenders and rear doors, with texture doing the small detail.
        for x in (-33, 25):
            m.cube("base", [x, 24, -62], [8, 4, 124], "hull_dark", {"up": "hull"})
        for x in (-22, 2):
            m.cube("base", [x, 33, 31], [20, 2, 24], "hatch_line")
        m.cube("base", [-20, 33, -39], [17, 2, 15], "hatch")
        m.cube("base", [3, 33, -39], [20, 1.5, 24], "vent")
        for x in (-24, 18):
            m.cube("base", [x, 28, -68], [6, 4, 4], "hull_dark", {"north": "lamp"})
        for x in (-22, 4):
            m.cube("base", [x, 19, 60], [18, 13, 2], "panel", {"south": "hatch_line"})
        for x in (-25, 22):
            m.cube("base", [x, 27, 62], [3, 3, 1], "tail")
        # Three coarse side armor panels per side, no tiny greebles.
        for x in (-29.5, 28):
            for z in (-35, -6, 23):
                m.cube("base", [x, 24, z], [1.5, 8, 25], "panel")
        m.cube("turret", [-14, 50, 3], [10, 3, 10], "hatch")
        m.cube("turret", [7, 50, 8], [6, 4, 8], "hull_dark", {"north": "optic"})
        m.cube("turret", [-18, 40, 12], [2, 7, 13], "stencil")
        m.cube("turret", [16, 40, 12], [2, 7, 13], "stencil")
        m.cube("turret", [-13, 51, 16], [1.2, 17, 1.2], "metal")
        m.cube("turret", [10, 38, -9], [5, 5, 6], "hull_dark", {"north": "lamp"})
    return m


def bradley(lod):
    m = Model("bradley", lod)
    m.tracks(22, -64, 53, 20, 9, 14)
    # Taller box hull, tall offset turret and the paired TOW launcher.
    m.cube("base", [-24, 15, -50], [48, 23, 106], "hull", {"up": "roof"})
    m.cube("base", [-23, 16, -66], [46, 12, 18], "hull_dark")
    m.cube("base", [-23, 28, -59], [46, 10, 17], "hull_light", {"up": "roof"})
    m.cube("turret", [-18.43, 37, -14], [32, 15, 35], "hull", {"up": "roof"})
    m.cube("turret", [-14.43, 52, -9], [24, 4, 26], "hull_light", {"up": "hatch"})
    m.cube("turret", [15, 42, -22], [9, 13, 33], "launcher", {"north": "tow_front"})
    m.gun(-2.42952, 44.6, -62, -12, 2.4, 5, -25)
    if lod < 2:
        m.cube("turret", [1, 56, 4], [10, 4, 10], "hatch")
    if lod == 0:
        for x in (-29, 23):
            m.cube("base", [x, 23, -61], [6, 5, 115], "hull_dark", {"up": "hull_light"})
        # Three large applique plates on each flank read well at game distance.
        for x in (-28, 24):
            for z in (-47, -14, 19):
                m.cube("base", [x, 24, z], [4, 13, 30], "panel")
        m.cube("base", [-18, 38, -39], [14, 2, 15], "hatch")
        m.cube("base", [4, 38, -37], [15, 1.5, 26], "vent")
        for x in (-20, 14):
            m.cube("base", [x, 31, -62], [6, 5, 4], "hull_dark", {"north": "lamp"})
        m.cube("base", [-17, 17, 55], [34, 20, 2], "hatch_line")
        for x in (-22, 19):
            m.cube("base", [x, 29, 56], [3, 5, 2], "tail")
        m.cube("turret", [-17, 44, 21], [28, 10, 8], "panel")
        m.cube("turret", [-17, 56, -7], [8, 6, 9], "hull_dark", {"north": "optic"})
        m.cube("turret", [11, 44, -3], [4, 4, 8], "hull_dark")
        # Separate bores emphasize the Bradley's two-tube TOW silhouette.
        for y in (43, 49.5):
            m.cube("turret", [16, y, -22.5], [7, 5, 1], "bore")
        for x in (-19.43, 13.57):
            m.cube("turret", [x, 41, 4], [1, 8, 14], "stencil")
        m.cube("turret", [-13, 54, 15], [1.2, 18, 1.2], "metal")
    return m


def atlas(vehicle):
    """Hand-coded pixel materials, with deliberately sparse surface variation."""
    image = Image.new("RGBA", (128, 128), (0, 0, 0, 255))
    sand = vehicle == "bradley"
    base = (183, 155, 103) if sand else (83, 99, 57)
    dark = (130, 111, 75) if sand else (54, 67, 39)
    light = (207, 179, 125) if sand else (111, 126, 75)
    palettes = {
        "hull": base, "hull_dark": dark, "hull_light": light,
        "roof": base, "panel": base, "hatch": dark, "vent": dark,
        "optic": (27, 47, 48), "track": (40, 42, 36), "tread": (43, 43, 36),
        "wheel": dark, "rubber": (37, 38, 31), "metal": (60, 65, 50),
        "bore": (25, 27, 24), "lamp": (226, 214, 150), "tail": (130, 49, 34),
        "stencil": base, "grille": dark, "tow_front": dark, "launcher": base,
        "flash": (251, 161, 43), "flash_core": (255, 231, 140),
        "bolt": dark, "hatch_line": base,
    }
    for material, index in TILES.items():
        patch = Image.new("RGBA", (16, 16), palettes[material]+(255,))
        d = ImageDraw.Draw(patch)
        # Deterministic pixels; no sampled or copied native art.
        rng = random.Random(index+ (100 if sand else 0))
        if material in {"hull", "hull_dark", "hull_light", "roof", "panel", "launcher"}:
            for _ in range(10):
                x, y = rng.randrange(0, 15, 2), rng.randrange(0, 15, 2)
                delta = rng.choice((-6, -4, 4, 5))
                color = tuple(max(0, min(255, c+delta)) for c in palettes[material])
                d.rectangle((x,y,x+1,y+1), fill=color)
        if material == "panel":
            d.line([(0,15),(15,15),(15,0)], fill=dark, width=1)
            for x,y in ((2,2),(13,2),(2,13),(13,13)):
                d.point((x,y), fill=light)
        if material in {"hatch", "hatch_line"}:
            d.rectangle((1,1,14,14), outline=dark if material == "hatch_line" else base, width=1)
            d.rectangle((5,6,10,8), fill=dark)
            d.line((5,6,10,6),fill=light)
        if material in {"vent", "grille"}:
            d.rectangle((1,1,14,14),fill=(36,40,30))
            for y in range(3,15,3):
                d.line((2,y,13,y),fill=base,width=1)
        if material == "optic":
            d.rectangle((2,4,13,11), fill=(43,76,78))
            d.rectangle((3,4,12,6),fill=(94,136,133))
            d.rectangle((3,7,8,8),fill=(64,108,111))
        if material == "track":
            d.rectangle((0,0,15,2),fill=(24,27,23))
            d.rectangle((0,13,15,15),fill=(24,27,23))
            for x in range(0,16,4):
                d.rectangle((x,3,x+1,12),fill=(60,60,47))
        if material == "tread":
            for y in range(0,16,4):
                d.rectangle((0,y,15,y+1),fill=(71,71,55))
                d.rectangle((6,y,9,y+1),fill=(91,88,65))
        if material == "wheel":
            d.rectangle((0,0,15,15),fill=(31,34,29))
            d.rectangle((2,2,13,13),fill=dark)
            d.rectangle((4,4,11,11),fill=base)
            d.rectangle((6,6,9,9),fill=dark)
            for x,y in ((4,4),(11,4),(4,11),(11,11)):
                d.point((x,y),fill=light)
        if material == "bore":
            d.rectangle((0,0,15,15),fill=(28,30,26))
            d.rectangle((2,2,13,13),outline=(64,68,53),width=2)
            d.rectangle((5,5,10,10),fill=(12,15,13))
        if material == "tow_front":
            for y in (1,9):
                d.rectangle((2,y,13,y+5),fill=(29,32,27))
                d.rectangle((4,y+1,11,y+4),fill=(15,19,17))
        if material == "lamp":
            d.rectangle((0,0,15,15),fill=dark)
            d.rectangle((3,3,12,12),fill=(226,214,150))
            d.rectangle((4,4,10,6),fill=(250,239,185))
        if material == "tail":
            d.rectangle((0,0,15,15),fill=dark)
            d.rectangle((3,3,12,12),fill=(158,61,41))
            d.line((4,4,11,4),fill=(204,93,60),width=2)
        if material == "bolt":
            d.rectangle((2,2,13,13),fill=base)
            d.rectangle((5,5,10,10),fill=dark)
            d.rectangle((6,5,9,6),fill=light)
        if material == "stencil":
            # Small original tactical stencil: 21 for BMP, chevron for Bradley.
            ink = (63,60,43) if sand else (204,211,173)
            if sand:
                d.line([(3,5),(8,11),(13,5)],fill=ink,width=2)
                d.rectangle((5,3,10,4),fill=ink)
            else:
                for x,y in ((2,4),(3,4),(4,4),(4,5),(3,6),(2,7),(2,8),(3,8),(4,8),
                            (7,5),(8,4),(8,5),(8,6),(8,7),(7,8),(8,8),(9,8)):
                    d.rectangle((x+1,y+1,x+1,y+1),fill=ink)
        image.paste(patch, ((index % 8)*16, (index//8)*16))
    return image


def animations():
    # Native animation controller names retained, with original, short recoil.
    return {"format_version": "1.8.0", "animations": {
        "animation.cannon.idle": {"loop": True, "bones": {"flare": {"scale": 0}}},
        "animation.machine_gun.idle": {"loop": True, "bones": {"flare2": {"scale": 0}}},
        "animation.cannon.fire": {
            "loop": "hold_on_last_frame", "animation_length": 0.22,
            "bones": {
                "barrel2": {"position": {"0.0": [0,0,0], "0.035": [0,0,2],
                                         "0.11": [0,0,0.8], "0.22": [0,0,0]}},
                "flare": {"scale": {"0.0": [0,0,0], "0.008": [4,4,4],
                                    "0.045": [5.5,5.5,5.5], "0.075": [0,0,0]}}}},
        "animation.machine_gun.fire": {
            "loop": "hold_on_last_frame", "animation_length": 0.12,
            "bones": {"flare2": {"scale": {"0.0": [0,0,0], "0.008": [3,3,3],
                                                "0.045": [0,0,0]}}}},
    }}


# Textured orthographic rasterizer for previews of the actual generated cubes.
FACE_CORNERS = {
    "north": ((0,0,0),(1,0,0),(1,1,0),(0,1,0)),
    "south": ((1,0,1),(0,0,1),(0,1,1),(1,1,1)),
    "east": ((1,0,0),(1,0,1),(1,1,1),(1,1,0)),
    "west": ((0,0,1),(0,0,0),(0,1,0),(0,1,1)),
    "up": ((0,1,0),(1,1,0),(1,1,1),(0,1,1)),
    "down": ((0,0,1),(1,0,1),(1,0,0),(0,0,0)),
}
NORMALS = {"north": [0,0,-1], "south": [0,0,1], "east": [1,0,0],
           "west": [-1,0,0], "up": [0,1,0], "down": [0,-1,0]}


def render(model, tex, size=(1000,720), yaw=35):
    w,h = size
    # Camera looks from front/left; both the glacis and turret silhouette show.
    camera = np.array([math.sin(math.radians(yaw)), .62, -math.cos(math.radians(yaw))])
    camera /= np.linalg.norm(camera)
    right = np.cross([0,1,0],camera); right /= np.linalg.norm(right)
    up = np.cross(camera,right)
    cubes = [(bone,c) for bone in model.bones if bone["name"] not in {"flare","flare2"}
             for c in bone.get("cubes",[])]
    allp = np.array([np.array(c["origin"])+np.array(v)*c["size"] for _,c in cubes for v in FACE_CORNERS["north"]+FACE_CORNERS["south"]])
    proj = np.stack((allp@right,allp@up),axis=1)
    lo,hi = proj.min(axis=0), proj.max(axis=0)
    scale = min((w-120)/(hi[0]-lo[0]), (h-140)/(hi[1]-lo[1]))
    cx, cy = (lo+hi)/2
    zbuf = np.full((h,w), -1e30)
    pixels = np.zeros((h,w,4),dtype=np.uint8)
    texp = np.asarray(tex)
    def project(points):
        return np.stack(((points@right-cx)*scale+w/2, h/2-(points@up-cy)*scale),axis=1)
    def triangle(pts,uv,light):
        screen = project(pts)
        x0=max(0,int(screen[:,0].min())); x1=min(w-1,math.ceil(screen[:,0].max()))
        y0=max(0,int(screen[:,1].min())); y1=min(h-1,math.ceil(screen[:,1].max()))
        if x1<x0 or y1<y0:return
        xx,yy = np.meshgrid(np.arange(x0,x1+1)+.5,np.arange(y0,y1+1)+.5)
        a,b,c=screen
        den=(b[1]-c[1])*(a[0]-c[0])+(c[0]-b[0])*(a[1]-c[1])
        if abs(den)<1e-8:return
        u=((b[1]-c[1])*(xx-c[0])+(c[0]-b[0])*(yy-c[1]))/den
        v=((c[1]-a[1])*(xx-c[0])+(a[0]-c[0])*(yy-c[1]))/den
        t=1-u-v
        depth=u*(pts[0]@camera)+v*(pts[1]@camera)+t*(pts[2]@camera)
        old=zbuf[y0:y1+1,x0:x1+1]
        mask=(u>=-1e-6)&(v>=-1e-6)&(t>=-1e-6)&(depth>old)
        if not mask.any():return
        texx=np.clip(np.floor(u*uv[0,0]+v*uv[1,0]+t*uv[2,0]).astype(int),0,127)
        texy=np.clip(np.floor(u*uv[0,1]+v*uv[1,1]+t*uv[2,1]).astype(int),0,127)
        color=texp[texy,texx].copy()
        color[:,:,:3]=np.clip(color[:,:,:3].astype(float)*light,0,255).astype(np.uint8)
        pixels[y0:y1+1,x0:x1+1][mask]=color[mask]
        old[mask]=depth[mask]
    light_dir=np.array([-.4,1,-.7]); light_dir/=np.linalg.norm(light_dir)
    for _,cube in cubes:
        origin=np.array(cube["origin"]); extent=np.array(cube["size"])
        for face,corners in FACE_CORNERS.items():
            normal=np.array(NORMALS[face])
            if normal@camera<=0:continue
            points=np.array(corners)*extent+origin
            info=cube["uv"][face]; u,v=info["uv"]; su,sv=info["uv_size"]
            uv=np.array([[u,v+sv-.01],[u+su-.01,v+sv-.01],[u+su-.01,v],[u,v]])
            light=.72+.28*max(0,normal@light_dir)
            for ids in ((0,1,2),(0,2,3)):
                triangle(points[list(ids)],uv[list(ids)],light)
    result=Image.fromarray(pixels,"RGBA")
    # Grounded contact shadow; generated from the same world coordinate footprint.
    shadow=Image.new("RGBA",size)
    d=ImageDraw.Draw(shadow)
    ground=np.array([[-33,0,-69],[33,0,-69],[33,0,62],[-33,0,62]])
    d.polygon([tuple(p) for p in project(ground)],fill=(12,23,22,45))
    shadow.alpha_composite(result)
    return shadow


def font(size, mono=False):
    root=Path('/usr/share/fonts/truetype/dejavu')
    return ImageFont.truetype(str(root/('DejaVuSansMono.ttf' if mono else 'DejaVuSans.ttf')),size)


def make_previews(models,textures,metrics):
    for vehicle in ("bmp_2","bradley"):
        im=Image.new("RGBA",(1200,900),(225,228,215,255))
        d=ImageDraw.Draw(im)
        d.text((54,40),"BMP-2  /  OLIVE" if vehicle=="bmp_2" else "M2 BRADLEY  /  SAND",font=font(32),fill=(31,46,38))
        d.text((56,89),"Original Minecraft cube art · Superb Warfare visual replacement",font=font(17),fill=(88,101,88))
        art=render(models[(vehicle,0)],textures[vehicle],(1140,700))
        im.alpha_composite(art,(30,122))
        tri=metrics[vehicle]["lods"][0]["triangles"]
        d.text((56,841),f"128 × 128 PIXEL ATLAS   /   {tri} TRIANGLES   /   6 WHEELS PER SIDE",font=font(17,True),fill=(74,89,75))
        im.convert("RGB").save(HERE/(vehicle+"-preview.png"))
    combined=Image.new("RGB",(1800,1040),(226,229,217))
    d=ImageDraw.Draw(combined)
    d.text((56,39),"ARCADE IFV",font=font(42),fill=(28,44,35))
    d.text((58,97),"Two familiar silhouettes. Built from cubes and original pixels.",font=font(20),fill=(83,99,83))
    d.line((56,143,1744,143),fill=(179,188,170),width=2)
    for i,v in enumerate(("bmp_2","bradley")):
        x=i*900
        render_im=render(models[(v,0)],textures[v],(930,690))
        combined.paste(render_im,(x-15,170),render_im)
        d.text((x+56,837),"BMP-2" if v=="bmp_2" else "M2 BRADLEY",font=font(31),fill=(34,50,39))
        d.text((x+58,880),"SOVIET OLIVE  /  LOW HULL  /  ROOF ATGM" if v=="bmp_2" else "AMERICAN SAND  /  TALL HULL  /  TWIN TOW",font=font(15,True),fill=(83,99,83))
        tris=[str(a['triangles']) for a in metrics[v]['lods']]
        d.text((x+58,917),"TRIANGLES    "+"  →  ".join(tris),font=font(16,True),fill=(72,90,73))
    d.text((58,991),"Generated assets shown directly · native aim pivots and wheel bones retained · no mechanics changed",font=font(17),fill=(93,106,90))
    combined.save(HERE/"arcade-ifv-preview.png")
    # Honest LOD comparison, rendered at equal scale and view per column.
    strip=Image.new("RGB",(1200,960),(226,229,217)); sd=ImageDraw.Draw(strip)
    for row,v in enumerate(("bmp_2","bradley")):
        for lod in range(2):
            art=render(models[(v,lod)],textures[v],(600,410))
            strip.paste(art,(lod*600,row*480+35),art)
            label=f"{v.upper()}  /  {'NEAR' if lod==0 else 'LOD '+str(lod)}  /  {metrics[v]['lods'][lod]['triangles']} TRI"
            sd.text((lod*600+28,row*480+440),label,font=font(16,True),fill=(51,73,53))
    strip.save(HERE/"lod-comparison.png")


def validate(models):
    metrics={}
    for vehicle in ("bmp_2","bradley"):
        entries=[]
        for lod in range(2):
            model=models[(vehicle,lod)]
            names=[b['name'] for b in model.bones]
            assert len(names)==len(set(names)), (vehicle,lod,'duplicate bones')
            for bone in model.bones:
                assert bone.get('parent') in names or 'parent' not in bone
                seen=set(); ancestor=bone
                while 'parent' in ancestor:
                    assert ancestor['name'] not in seen, 'bone cycle'
                    seen.add(ancestor['name']); ancestor=model.by_name[ancestor['parent']]
            for key in ('base','turret','barrel'):
                assert model.by_name[key]['pivot']==PIVOTS[vehicle][key]
            assert model.by_name['turret']['parent']=='base'
            assert model.by_name['barrel']['parent']=='turret'
            assert model.by_name['move_Track']['parent']=='root'
            assert not any(n.startswith(('trackMov','trackRot')) for n in names)
            for side in 'LR':
                for i in range(6):
                    assert 'wheel'+side+str(i) in names
            cubes=[c for b in model.bones for c in b.get('cubes',[])]
            for c in cubes:
                assert all(math.isfinite(v) for v in c['origin']+c['size'])
                assert all(v>0 for v in c['size'])
                assert set(c['uv'])==set(FACES)
                for f in c['uv'].values():
                    assert len(f['uv'])==len(f['uv_size'])==2
                    assert all(0<=u and u+s<=128 for u,s in zip(f['uv'],f['uv_size']))
            tri=len(cubes)*12
            assert (600<=tri<=1000) if lod==0 else ((180<=tri<=300) if lod==1 else (100<=tri<=144)), (vehicle,lod,tri)
            regular=[c for b in model.bones if not b['name'].startswith('flare') for c in b.get('cubes',[])]
            lo=np.min([c['origin'] for c in regular],axis=0)
            hi=np.max([np.array(c['origin'])+c['size'] for c in regular],axis=0)
            assert all(lo>=[-40,0,-90]) and all(hi<=[40,80,75]),(vehicle,lod,lo,hi)
            # Visible-bound box in the geometry must enclose every static cube.
            assert max(abs(lo[0]),abs(hi[0]),abs(lo[2]),abs(hi[2]))<=12*16/2
            entries.append({'lod':lod,'cubes':len(cubes),'triangles':tri,
                            'static_cubes':len(regular),'static_triangles':len(regular)*12,
                            'bones':len(model.bones),'bounds_model_units':[lo.tolist(),hi.tolist()]})
        metrics[vehicle]={'native_render_scale':.9 if vehicle=='bmp_2' else 1.0,
                          'pivots_absolute':PIVOTS[vehicle], 'lod_distances':[0,64], 'lods':entries}
    return metrics


def main():
    models={(v,l):builder(l) for v,builder in (("bmp_2",bmp),("bradley",bradley)) for l in range(2)}
    metrics=validate(models)
    textures={v:atlas(v) for v in ("bmp_2","bradley")}
    for v in ("bmp_2","bradley"):
        for lod in range(2):
            suffix=f".lod{lod}" if lod else ""
            folder="vehicle_lod" if lod else "vehicle"
            write_json(RES/"models/bedrock"/folder/(v+suffix+".geo.json"),models[(v,lod)].data())
        for folder,suffix in (("vehicle",""),("vehicle_lod",".lod")):
            path=RES/"textures/bedrock"/folder/(v+suffix+".png")
            path.parent.mkdir(parents=True,exist_ok=True); textures[v].save(path)
        write_json(RES/"animations/bedrock/vehicle"/(v+".animation.json"),animations())
        profiles=[]
        for lod in range(2):
            suffix=f".lod{lod}" if lod else ""
            folder="vehicle_lod" if lod else "vehicle"
            p={"Model":f"superbwarfare:models/bedrock/{folder}/{v}{suffix}.geo.json",
               "Texture":f"superbwarfare:textures/bedrock/{folder}/{v}{'.lod' if lod else ''}.png"}
            # Pinned native selector returns the first eligible model, and its
            # global default threshold is 64. A single far LOD is reliable.
            if lod:p['LODDistance']=64
            profiles.append(p)
        write_json(RES/"sbw/vehicles"/(v+".json"),{
            "Animation":f"superbwarfare:animations/bedrock/vehicle/{v}.animation.json","Models":profiles})
        # Atlas enlargement is a review artifact, never shipped as a resource.
        textures[v].resize((512,512),Image.Resampling.NEAREST).save(HERE/(v+"-atlas.png"))
    make_previews(models,textures,metrics)
    files={str(p.relative_to(RES.parent.parent)):hashlib.sha256(p.read_bytes()).hexdigest()
           for p in sorted(RES.rglob('*')) if p.is_file()}
    write_json(HERE/"asset-metrics.json",metrics)
    write_json(HERE/"resource-sha256.json",files)
    print(json.dumps(metrics,indent=2))


if __name__ == '__main__':
    main()
