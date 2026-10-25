#!/usr/bin/env python3
"""Original Frontier worker/press source meshes. Export PNG, Gecko4 JSON and editable Blockbench files.

Standard library only. Run from any directory; output stays in this module.
"""
from pathlib import Path
import base64
import json
import math
import struct
import uuid
import zlib

MODULE = Path(__file__).resolve().parents[2]
ASSETS = MODULE / "src/main/resources/assets/frontier_industry"
ART = Path(__file__).resolve().parent
SIZE = 128


def cube(name, origin, size, color):
    return {"name": name, "origin": origin, "size": size, "color": color}


WORKER = [
    {"name": "root", "pivot": [0, 0, 0]},
    {"name": "body", "parent": "root", "pivot": [0, 12, 0], "cubes": [
        cube("coat", [-4, 12, -2], [8, 11, 4], [56, 84, 102]),
        cube("vest", [-4.2, 14, -2.2], [8.4, 7, 4.4], [218, 151, 47])]},
    {"name": "head", "parent": "body", "pivot": [0, 23, 0], "cubes": [
        cube("face", [-4, 23, -4], [8, 8, 8], [185, 139, 103]),
        cube("helmet", [-4.5, 29, -4.5], [9, 2.5, 9], [224, 177, 55])]},
    {"name": "left_arm", "parent": "body", "pivot": [-5, 22, 0], "cubes": [
        cube("left_sleeve", [-7, 12, -2], [3, 10, 4], [56, 84, 102]),
        cube("left_glove", [-7, 10, -2], [3, 2, 4], [90, 78, 58])]},
    {"name": "right_arm", "parent": "body", "pivot": [5, 22, 0], "cubes": [
        cube("right_sleeve", [4, 12, -2], [3, 10, 4], [56, 84, 102]),
        cube("right_glove", [4, 10, -2], [3, 2, 4], [90, 78, 58])]},
    {"name": "left_leg", "parent": "root", "pivot": [-2, 12, 0], "cubes": [
        cube("left_trouser", [-4, 2, -2], [4, 10, 4], [45, 59, 70]),
        cube("left_boot", [-4, 0, -3], [4, 2, 5], [48, 43, 36])]},
    {"name": "right_leg", "parent": "root", "pivot": [2, 12, 0], "cubes": [
        cube("right_trouser", [0, 2, -2], [4, 10, 4], [45, 59, 70]),
        cube("right_boot", [0, 0, -3], [4, 2, 5], [48, 43, 36])]},
    {"name": "tool", "parent": "right_arm", "pivot": [5.5, 11, 0], "cubes": [
        cube("wrench_handle", [5, 7, -1], [1, 6, 1], [152, 160, 161]),
        cube("wrench_head", [4, 6, -1], [3, 1, 1], [152, 160, 161])]}
]
PRESS = [
    {"name": "root", "pivot": [0, 0, 0], "cubes": [
        cube("base", [-7, 0, -7], [14, 2, 14], [49, 66, 72]),
        cube("left_column", [-7, 2, -5], [3, 13, 10], [62, 83, 89]),
        cube("right_column", [4, 2, -5], [3, 13, 10], [62, 83, 89]),
        cube("top", [-7, 14, -6], [14, 2, 12], [188, 132, 47]),
        cube("bed", [-4, 3, -5], [8, 2, 10], [135, 144, 142])]},
    {"name": "ram", "parent": "root", "pivot": [0, 12, 0], "cubes": [
        cube("piston", [-2, 10, -2], [4, 4, 4], [141, 154, 156]),
        cube("ram_plate", [-4, 8, -4], [8, 2, 8], [188, 132, 47])]}
]

WORKER_ANIMATIONS = {
    "animation.worker.idle": {"loop": True, "animation_length": 2, "bones": {
        "head": {"rotation": {"0.0": [0, -2, 0], "1.0": [0, 2, 0], "2.0": [0, -2, 0]}}}},
    "animation.worker.walk": {"loop": True, "animation_length": 1, "bones": {
        "left_leg": {"rotation": {"0.0": [25, 0, 0], "0.5": [-25, 0, 0], "1.0": [25, 0, 0]}},
        "right_leg": {"rotation": {"0.0": [-25, 0, 0], "0.5": [25, 0, 0], "1.0": [-25, 0, 0]}},
        "left_arm": {"rotation": {"0.0": [-18, 0, 0], "0.5": [18, 0, 0], "1.0": [-18, 0, 0]}},
        "right_arm": {"rotation": {"0.0": [18, 0, 0], "0.5": [-18, 0, 0], "1.0": [18, 0, 0]}}}},
    "animation.worker.repair": {"loop": True, "animation_length": 1, "bones": {
        "body": {"rotation": [5, 0, 0]},
        "head": {"rotation": [12, 0, 0]},
        "left_arm": {"rotation": [-58, 0, -10]},
        "right_arm": {"rotation": {"0.0": [-65, 0, 10], "0.5": [-85, 0, 10], "1.0": [-65, 0, 10]}}}}
}
PRESS_ANIMATIONS = {
    "animation.press.stop": {"loop": True, "animation_length": 1, "bones": {"ram": {"position": [0, 0, 0]}}},
    "animation.press.work": {"loop": True, "animation_length": 1.5, "bones": {
        "ram": {"position": {"0.0": [0, 0, 0], "0.6": [0, -2.5, 0], "0.9": [0, -2.5, 0], "1.5": [0, 0, 0]}}}}
}


def identifier(value):
    return str(uuid.uuid5(uuid.NAMESPACE_URL, "frontier-industry/repair/" + value))


def png(pixels):
    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data))
    rows = b"".join(b"\0" + bytes(channel for color in row for channel in color) for row in pixels)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", SIZE, SIZE, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b"")


def export(name, bones, animations, texture_directory):
    pixels = [[[44, 51, 54] for _ in range(SIZE)] for _ in range(SIZE)]
    x = y = shelf = 0
    elements = []
    groups = {}
    geometry = []
    for source in bones:
        bone = {key: value for key, value in source.items() if key != "cubes"}
        groups[source["name"]] = {"name": source["name"], "origin": source["pivot"], "uuid": identifier(name + "/" + source["name"]), "children": []}
        for original in source.get("cubes", []):
            sx, sy, sz = original["size"]
            width, height = math.ceil(2 * (sx + sz)), math.ceil(sy + sz)
            if x + width > SIZE:
                x, y, shelf = 0, y + shelf + 1, 0
            assert y + height <= SIZE
            for py in range(y, y + height):
                for px in range(x, x + width):
                    shade = -10 if (px + py) % 11 == 0 else 0
                    pixels[py][px] = [max(0, min(255, c + shade)) for c in original["color"]]
            if original["name"] == "face":
                for eye in (2, 5):
                    pixels[y + 11][x + 8 + eye] = [30, 34, 36]
            uv = [x, y]
            bone.setdefault("cubes", []).append({"origin": original["origin"], "size": original["size"], "uv": uv})
            element_id = identifier(name + "/" + original["name"])
            face_uv = {
                "east": [x, y + sz, x + sz, y + sz + sy],
                "north": [x + sz, y + sz, x + sz + sx, y + sz + sy],
                "west": [x + sz + sx, y + sz, x + 2 * sz + sx, y + sz + sy],
                "south": [x + 2 * sz + sx, y + sz, x + 2 * sz + 2 * sx, y + sz + sy],
                "up": [x + sz, y, x + sz + sx, y + sz],
                "down": [x + sz + sx, y, x + sz + 2 * sx, y + sz]}
            elements.append({"name": original["name"], "type": "cube", "uuid": element_id,
                "from": original["origin"], "to": [a + b for a, b in zip(original["origin"], original["size"])],
                "origin": source["pivot"], "box_uv": True, "uv_offset": uv,
                "faces": {face: {"uv": uv, "texture": 0} for face, uv in face_uv.items()}})
            groups[source["name"]]["children"].append(element_id)
            x += width + 1
            shelf = max(shelf, height)
        geometry.append(bone)
    outliner = []
    for source in bones:
        group = groups[source["name"]]
        if "parent" in source:
            groups[source["parent"]]["children"].append(group)
        else:
            outliner.append(group)
    bb_animations = []
    for animation_name, animation in animations.items():
        animators = {}
        for bone_name, channels in animation["bones"].items():
            frames = []
            for channel, values in channels.items():
                for timestamp, vector in (values if isinstance(values, dict) else {"0.0": values}).items():
                    frames.append({"channel": channel, "time": float(timestamp), "interpolation": "linear",
                        "data_points": [dict(zip(("x", "y", "z"), map(str, vector)))]})
            animators[groups[bone_name]["uuid"]] = {"name": bone_name, "type": "bone", "keyframes": frames}
        bb_animations.append({"name": animation_name, "uuid": identifier(animation_name), "loop": "loop", "length": animation["animation_length"], "animators": animators})
    texture = png(pixels)
    bbmodel = {"meta": {"format_version": "4.10", "model_format": "bedrock", "box_uv": True},
        "name": name, "model_identifier": "geometry." + name, "resolution": {"width": SIZE, "height": SIZE},
        "elements": elements, "outliner": outliner, "animations": bb_animations,
        "textures": [{"name": name + ".png", "uuid": identifier(name + "/texture"), "id": "0", "width": SIZE, "height": SIZE,
            "uv_width": SIZE, "uv_height": SIZE, "source": "data:image/png;base64," + base64.b64encode(texture).decode()}]}
    files = {
        ASSETS / "geo" / (name + ".geo.json"): {"format_version": "1.12.0", "minecraft:geometry": [{
            "description": {"identifier": "geometry." + name, "texture_width": SIZE, "texture_height": SIZE,
                "visible_bounds_width": 2 if name == "worker" else 1.5, "visible_bounds_height": 2.5 if name == "worker" else 1.5,
                "visible_bounds_offset": [0, 1 if name == "worker" else .5, 0]}, "bones": geometry}]},
        ASSETS / "animations" / (name + ".animation.json"): {"format_version": "1.8.0", "animations": animations},
        ART / (name + ".bbmodel"): bbmodel}
    for path, value in files.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    path = ASSETS / "textures" / texture_directory / ("worker.png" if name == "worker" else "press_workstation.png")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(texture)


if __name__ == "__main__":
    export("worker", WORKER, WORKER_ANIMATIONS, "entity")
    export("press", PRESS, PRESS_ANIMATIONS, "block")
    print("Exported two original models, five animations, two textures and editable Blockbench sources")
