#!/usr/bin/env python3
"""Validate shipped resource files independently of the generator's memory."""
import hashlib
import json
import math
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent / "src/main/resources/resourcepacks/frontier_ifv"
ASSETS = ROOT / "assets/superbwarfare"
EXPECTED_PIVOTS = {
    "bmp_2": {"base": [0, 0, 0], "turret": [0, 36, 11.25],
              "barrel": [0, 40.8656, -1.78073]},
    "bradley": {"base": [0, 12, 0], "turret": [-2.42952, 35.776, 2.04492],
                "barrel": [-2.42952, 42.7592, -7.568]},
}


def read(path):
    return json.loads(path.read_text())


def resolve(location):
    namespace, path = location.split(":", 1)
    assert namespace == "superbwarfare"
    result = ROOT / "assets" / namespace / path
    assert result.is_file(), result
    return result


def main():
    from PIL import Image
    report = []
    referenced = set()
    for vehicle, pivots in EXPECTED_PIVOTS.items():
        profile_path = ASSETS / f"sbw/vehicles/{vehicle}.json"
        referenced.add(profile_path)
        profile = read(profile_path)
        assert set(profile) == {"Animation", "Models"}, "visual profile only"
        assert len(profile["Models"]) == 2, "native selector supports one far LOD"
        assert "LODDistance" not in profile["Models"][0]
        assert profile["Models"][1]["LODDistance"] == 64
        anim_path = resolve(profile["Animation"])
        referenced.add(anim_path)
        anim = read(anim_path)["animations"]
        assert anim["animation.cannon.idle"]["bones"]["flare"]["scale"] == 0
        assert anim["animation.machine_gun.idle"]["bones"]["flare2"]["scale"] == 0
        # Native renderer draws a shared muzzle_flare at the animated transform;
        # custom cubes under flare bones are hidden by that renderer.
        cannon_flash = anim["animation.cannon.fire"]["bones"]["flare"]["scale"]
        mg_flash = anim["animation.machine_gun.fire"]["bones"]["flare2"]["scale"]
        assert max(max(v) for v in cannon_flash.values()) == 5.5
        assert max(max(v) for v in mg_flash.values()) == 3
        assert cannon_flash["0.075"] == mg_flash["0.045"] == [0, 0, 0]
        for lod, entry in enumerate(profile["Models"]):
            model_path, tex_path = resolve(entry["Model"]), resolve(entry["Texture"])
            referenced.update((model_path, tex_path))
            with Image.open(tex_path) as texture:
                assert texture.size == (128, 128)
            geometry = read(model_path)["minecraft:geometry"][0]
            desc, bones = geometry["description"], geometry["bones"]
            assert desc["texture_width"] == desc["texture_height"] == 128
            by_name = {b["name"]: b for b in bones}
            assert len(by_name) == len(bones), "unique bone names"
            for name, pivot in pivots.items():
                assert by_name[name]["pivot"] == pivot, (vehicle, lod, name)
            assert by_name["base"]["parent"] == "root"
            assert by_name["turret"]["parent"] == "base"
            assert by_name["barrel"]["parent"] == "turret"
            assert by_name["barrel2"]["parent"] == "barrel"
            assert not by_name["flare"].get("cubes")
            assert not by_name["flare2"].get("cubes")
            expected_x = -1.8 if vehicle == "bmp_2" else -2.42952
            expected_y = 43.65 if vehicle == "bmp_2" else 44.6
            expected_z = -62 if vehicle == "bmp_2" else -63
            assert by_name["flare"]["pivot"] == [expected_x, expected_y, expected_z]
            assert by_name["flare2"]["pivot"] == [5, expected_y, -22 if vehicle == "bmp_2" else -26]
            assert by_name["move_Track"]["parent"] == "root"
            assert not any(n.startswith(("trackMov", "trackRot")) for n in by_name)
            for side in "LR":
                for i in range(6):
                    wheel = by_name[f"wheel{side}{i}"]
                    assert wheel["parent"] == "Wheel" + side
                    assert by_name["Wheel"+side]["parent"] == "move_Track"
                    assert wheel.get("cubes"), "wheel animation has visible geometry"
            for bone in bones:
                seen = set()
                ancestor = bone
                while "parent" in ancestor:
                    assert ancestor["name"] not in seen, "bone cycle"
                    seen.add(ancestor["name"])
                    ancestor = by_name[ancestor["parent"]]
            cubes = [c for b in bones for c in b.get("cubes", [])]
            triangles = len(cubes) * 12
            assert (600 <= triangles <= 1000) if lod == 0 else (180 <= triangles <= 300)
            for cube in cubes:
                assert all(math.isfinite(v) for v in cube["origin"] + cube["size"])
                assert all(v > 0 for v in cube["size"])
                assert set(cube["uv"]) == {"north", "south", "east", "west", "up", "down"}
                for face in cube["uv"].values():
                    assert all(0 <= u < u+s <= 128 for u,s in zip(face["uv"],face["uv_size"]))
                origin, size = cube["origin"], cube["size"]
                assert -40 <= origin[0] and origin[0]+size[0] <= 40
                assert 0 <= origin[1] and origin[1]+size[1] <= 80
                assert -90 <= origin[2] and origin[2]+size[2] <= 75
            for animation in anim.values():
                assert set(animation.get("bones", {})) <= set(by_name)
            report.append(f"{vehicle} {'near' if lod == 0 else 'far'}: {len(cubes)} cubes, {triangles} triangles, {len(bones)} bones")
    # This validator owns only the accepted IFV model batch. Other unified
    # resources are checked by their own independent validators.
    shipped = {p for p in ASSETS.rglob("*") if p.is_file()
               and any(vehicle in p.name for vehicle in EXPECTED_PIVOTS)
               and 'vehicle_icon' not in p.parts}
    assert shipped == referenced, "IFV resources must be referenced, without native LOD fallbacks"
    checksums = read(HERE / "resource-sha256.json")
    for path, digest in checksums.items():
        assert hashlib.sha256((ROOT / path).read_bytes()).hexdigest() == digest, path
    print("PASS: resource references, hierarchy, native pivots, six wheels/side, shared native flash transforms/scales, hidden idle flashes, UVs, bounds, budgets, SHA256")
    print("\n".join(report))


if __name__ == "__main__":
    main()
