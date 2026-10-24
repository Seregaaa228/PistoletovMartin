#!/usr/bin/env python3
"""Independent check of shipped ground assets against the pinned native JAR."""
import hashlib
import json
import math
from pathlib import Path
import zipfile

from PIL import Image

HERE = Path(__file__).resolve().parent
RES = HERE.parent / "src/main/resources/resourcepacks/frontier_ifv/assets/superbwarfare"
JAR = HERE.parents[2] / "instance/mods/superbwarfare-0.8.9.1-hotfix-mc1.20.1-993063bed-all.jar"
REQUIRED = {
    "lav_25": {"root", "base", "turret", "barrel", "barrel2"},
    "lav_ad": {"root", "base", "turret", "barrel", "gun", "rot_barrel"},
    "hpj_11": {"root", "turret", "barrel", "rot_barrel", "move_rdr", "move_radar2", "move_rdr2"},
    "mortar": {"mortar", "move_paoguan", "bone2", "shell", "paotong", "move_monitor", "move_jiaojia", "dizuo"},
    "tow": {"tow", "base", "turret", "barrel", "move_missile", "move_guanmiao"},
}


def read(path):
    return json.loads(path.read_text())


def resolve(resource):
    namespace, suffix = resource.split(":", 1)
    assert namespace == "superbwarfare" and ".." not in suffix
    path = RES / suffix
    assert path.is_file(), path
    return path


def main():
    hashes = read(HERE / "ground-sha256.json")
    assert len(hashes) == 42
    for suffix, digest in hashes.items():
        assert suffix.startswith(("animations/", "models/", "textures/", "sbw/vehicles/"))
        assert any(v in Path(suffix).name for v in REQUIRED)
        assert hashlib.sha256((RES/suffix).read_bytes()).hexdigest() == digest, suffix
    report = []
    with zipfile.ZipFile(JAR) as native:
        for vehicle, required in REQUIRED.items():
            original = json.loads(native.read(f"assets/superbwarfare/models/bedrock/vehicle/{vehicle}.geo.json"))
            nb = {b["name"]: b for b in original["minecraft:geometry"][0]["bones"]}
            profile = read(RES/f"sbw/vehicles/{vehicle}.json")
            assert set(profile) == {"Models", "Animation"} and len(profile["Models"]) == 2
            assert profile["Models"][0].get("LODDistance", 0) == 0
            assert profile["Models"][1]["LODDistance"] == 64
            animation = read(resolve(profile["Animation"]))["animations"]
            idle = {n: a for n, a in animation.items() if ".idle" in n}
            assert idle and all(a["bones"]["flare"]["scale"] == 0 for a in idle.values() if "flare" in a.get("bones", {}))
            flare_keys = [track["scale"] for a in animation.values() for n, track in a.get("bones", {}).items()
                          if n == "flare" and isinstance(track.get("scale"), dict)]
            assert flare_keys and max(max(value) for keys in flare_keys for value in keys.values()) == 5.5
            for level, entry in enumerate(profile["Models"]):
                g = read(resolve(entry["Model"]))["minecraft:geometry"][0]
                assert g["description"]["texture_width"] == g["description"]["texture_height"] == 128
                bs = g["bones"]; by = {b["name"]: b for b in bs}
                assert len(by) == len(bs) and required <= by.keys()
                for name in required:
                    assert by[name]["pivot"] == nb[name]["pivot"], (vehicle, level, name)
                for b in bs:
                    assert "poly_mesh" not in b
                    if "parent" in b:
                        assert b["parent"] in by
                    seen = set(); a = b
                    while "parent" in a:
                        assert a["name"] not in seen
                        seen.add(a["name"]); a = by[a["parent"]]
                for a in animation.values():
                    assert set(a.get("bones", {})) <= by.keys()
                assert not by["flare"].get("cubes"), "shared native flash anchor must be empty"
                if vehicle.startswith("lav"):
                    wheels = {n for n in nb if n.startswith(("wheelL", "wheelR"))}
                    assert len(wheels) == 8 and wheels <= by.keys()
                    for name in wheels:
                        assert by[name]["pivot"] == nb[name]["pivot"] and by[name].get("cubes")
                if vehicle == "tow":
                    assert by["move_missile"].get("cubes") and by["move_guanmiao"].get("cubes")
                if vehicle == "mortar":
                    assert by["bone2"]["rotation"] == nb["bone2"]["rotation"]
                    assert by["move_jiaojia"]["parent"] == "bone2"
                    assert idle["animation.main.idle"]["bones"]["shell"]["scale"] == 0
                if vehicle == "hpj_11":
                    assert by["flare"]["pivot"] == [0, 26.8, -45.2484]
                cubes = [c for b in bs for c in b.get("cubes", [])]
                triangles = len(cubes)*12
                assert (500 <= triangles <= 1000) if not level else (150 <= triangles <= 300)
                for c in cubes:
                    assert all(math.isfinite(q) for q in c["origin"]+c["size"]) and min(c["size"]) > 0
                    assert len(c["uv"]) == 6
                    for face in c["uv"].values():
                        assert all(0 <= u < u+s <= 128 for u,s in zip(face["uv"], face["uv_size"]))
                assert Image.open(resolve(entry["Texture"])).size == (128, 128)
                report.append({"id":vehicle,"level":level,"bones":len(bs),"cubes":len(cubes),"triangles":triangles})
            assert Image.open(RES/f"textures/vehicle_icon/container/{vehicle}.png").size == (128,128)
            assert Image.open(RES/f"textures/vehicle_icon/{vehicle}_icon.png").size == (256,64)
            if vehicle in ("lav_ad", "hpj_11"):
                heat = Image.open(RES/f"textures/bedrock/vehicle/{vehicle}_heat.png").convert("RGBA")
                assert heat.size == (128,128)
                assert heat.getpixel((0,0))[3] == 0 and heat.getpixel((5,55))[3] > 0
    result = {"status":"pass","owned_resource_files":len(hashes),
              "evidence":"Saved-file validation against installed pinned native pivots, not an in-game test",
              "resources":report}
    (HERE/"ground-independent-validation.json").write_text(json.dumps(result, indent=2)+"\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
