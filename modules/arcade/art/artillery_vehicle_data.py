#!/usr/bin/env python3
"""Vehicle data for our PLZ-05-based guns, derived from their models.

Starts from the pack's PLZ-05 data (seats, engine, damage table) and fills in what the model decides: turret and
barrel positions, the muzzle (one point, or a grid of tube mouths for the launcher), collision boxes, terrain
probes and camera height. Writes data/frontier_arcade_vehicles/sbw/vehicles/<id>.json (server) and
assets/frontier_arcade_vehicles/sbw/vehicles/<id>.json (client). Run after the *_assets.py model scripts.
"""
from __future__ import annotations

import copy
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
RES = HERE.parent / "src/main/resources"
PLZ = RES / "data/superbwarfare/sbw/vehicles/plz_05.json"
NS = "frontier_arcade_vehicles"

SHELL_HE = {"Damage": 450, "ShellType": "HE", "Icon": "superbwarfare:textures/overlay/vehicle/weapon/icons/he_shell.png",
            "Name": "weapon.superbwarfare.cannon_he"}

VEHICLES = {
    "msta_s": {
        "health": 520, "speed": 0.72, "velocity": 20, "gravity": 0.06, "reload": 140,
        "he": (1000, 48), "ap": (1150, 460, 18), "wheeled": False, "visible_driver": True,
        "driver": [0.75, 0.84, 3.31],
    },
    "bohdana": {
        "health": 380, "speed": 1.0, "velocity": 19, "gravity": 0.06, "reload": 110,
        "he": (950, 44), "ap": (1100, 440, 17), "wheeled": True, "visible_driver": False,
        "driver": [0.75, 1.375, 4.25],
    },
    "grad": {
        "health": 300, "speed": 0.95, "velocity": 16, "gravity": 0.05, "reload": 600,
        "rockets": True, "wheeled": True, "visible_driver": False,
        # First tube centre x, y; tube pitch; muzzle-face z (model units).
        "tubes": (-15.3, 38.2, 3.4, -21),
        "driver": [0.656, 1.19, 2.19],
    },
}


def bones(vehicle):
    geo = json.loads((RES / f"assets/{NS}/models/bedrock/vehicle/{vehicle}.geo.json").read_text())
    return {b["name"]: b for b in geo["minecraft:geometry"][0]["bones"]}


def subtree(all_bones, root):
    names = {root}
    grew = True
    while grew:
        grew = False
        for name, bone in all_bones.items():
            if name not in names and bone.get("parent") in names:
                names.add(name)
                grew = True
    return [all_bones[n] for n in names]


def extent(bone_list):
    lo, hi = [1e9] * 3, [-1e9] * 3
    for bone in bone_list:
        for cube in bone.get("cubes", []):
            o, s = cube["origin"], cube["size"]
            for i in range(3):
                lo[i] = min(lo[i], o[i])
                hi[i] = max(hi[i], o[i] + s[i])
    return lo, hi


def blocks(v):
    return round(v / 16, 4)


def build(vehicle, spec, plz):
    b = bones(vehicle)
    data = copy.deepcopy(plz)
    turret, barrel = b["turret"]["pivot"], b["barrel"]["pivot"]
    # Model -Z is the vehicle's front; SBW data uses +Z forward.
    data["TurretPos"] = [blocks(turret[0]), blocks(turret[1]), blocks(-turret[2])]
    data["BarrelPos"] = [blocks(barrel[0] - turret[0]), blocks(barrel[1] - turret[1]), blocks(-(barrel[2] - turret[2]))]
    data["MaxHealth"] = spec["health"]
    data["EngineInfo"]["MaxForwardSpeedRate"] = spec["speed"]
    data["VehicleIcon"] = f"{NS}:textures/vehicle_icon/{vehicle}_icon.png"
    if spec.get("wheeled"):
        data["EngineSound"] = "superbwarfare:truck_engine"

    weapon = data["Weapons"]["Main"]
    gun_lo, gun_hi = extent(subtree(b, "barrel"))
    weapon["Velocity"] = spec["velocity"]
    weapon["Gravity"] = spec["gravity"]
    weapon["EmptyReloadTime"] = spec["reload"]
    weapon["Spread"] = 0
    # Barrel-local shoot points: the frame turns with the barrel, +Z out of the muzzle.
    reach = blocks(barrel[2] - gun_lo[2]) - 0.3
    if spec.get("rockets"):
        weapon.update({
            "Projectile": "superbwarfare:medium_rocket", "AmmoType": ["superbwarfare:medium_rocket_he"],
            "ShellType": "HE", "Damage": 200, "ExplosionDamage": 420, "ExplosionRadius": 16,
            "Magazine": 40, "RPM": 120, "DefaultFireMode": "Auto", "AvailableFireModes": "Auto",
            "Icon": "superbwarfare:textures/overlay/vehicle/weapon/icons/he_shell.png",
            "Name": "item.superbwarfare.medium_rocket_he", "RecoilForce": 0.2, "ShootShake": [3, 4, 12],
            "SoundInfo": {"Fire1P": "superbwarfare:medium_rocket_fire", "Fire3P": "superbwarfare:medium_rocket_fire",
                          "Fire3PFar": "superbwarfare:plz_05_far", "Fire3PVeryFar": "superbwarfare:plz_05_veryfar"},
        })
        # Four rows of ten tube mouths on the pack's front face (from grad_assets.py), fired in turn.
        x0, y0, pitch, mouth = spec["tubes"]
        reach = blocks(barrel[2] - mouth) - 0.1
        positions = []
        for row in range(4):
            for col in range(10):
                x, y = x0 + pitch * col, y0 + pitch * row
                positions.append([blocks(x - barrel[0]), blocks(y - barrel[1]), reach])
        weapon["ShootPos"] = {"Transform": "Barrel", "Positions": positions, "Directions": ["Barrel"] * len(positions)}
    else:
        he_damage, he_radius = spec["he"]
        ap_damage, ap_blast, ap_radius = spec["ap"]
        weapon.update({**SHELL_HE, "ExplosionDamage": he_damage, "ExplosionRadius": he_radius})
        for ammo in weapon["AmmoType"]:
            if isinstance(ammo, dict) and ammo["Ammo"] == "superbwarfare:large_shell_ap":
                ammo["Override"].update({"Damage": ap_damage, "ExplosionDamage": ap_blast, "ExplosionRadius": ap_radius})
        weapon["ShootPos"] = {"Transform": "Barrel", "Positions": [[0, 0, reach]], "Directions": ["Barrel"]}

    # Collision: hull below the turret ring, the turret above it; wheels/tracks as the side parts.
    body_lo, body_hi = extent([x for n, x in b.items() if n not in {y["name"] for y in subtree(b, "turret")}])
    top_lo, top_hi = extent(subtree(b, "turret"))
    size = lambda lo, hi: [blocks(hi[0] - lo[0]) / 2, blocks(hi[1] - lo[1]) / 2, blocks(hi[2] - lo[2]) / 2]
    centre = lambda lo, hi: [blocks((hi[0] + lo[0]) / 2), blocks((hi[1] + lo[1]) / 2), blocks(-(hi[2] + lo[2]) / 2)]
    hull = size(body_lo, body_hi)
    data["OBB"] = [
        {"Part": "Collision", "Size": hull, "Position": centre(body_lo, body_hi)},
        {"Part": "WheelLeft", "Size": [0.3, hull[1] * 0.5, hull[2] * 0.85],
         "Position": [blocks(body_hi[0]) - 0.3, hull[1] * 0.5, centre(body_lo, body_hi)[2]]},
        {"Part": "WheelRight", "Size": [0.3, hull[1] * 0.5, hull[2] * 0.85],
         "Position": [blocks(body_lo[0]) + 0.3, hull[1] * 0.5, centre(body_lo, body_hi)[2]]},
        {"Part": "MainEngine", "Size": [0.6, 0.4, 0.8],
         "Position": [0, blocks(body_hi[1]) - 0.4, blocks(-body_lo[2]) - 1.0 if spec.get("wheeled") else blocks(-body_hi[2]) + 1.2]},
        {"Part": "Turret", "Size": size(top_lo, top_hi), "Position": [0, blocks(top_hi[1] - top_lo[1]) / 2, 0],
         "Transform": "Turret", "Rotation": "Turret"},
    ]
    # Terrain probes along both sides, front to back.
    half_x, z0, z1 = max(abs(blocks(body_lo[0])), abs(blocks(body_hi[0]))) - 0.2, blocks(-body_hi[2]) + 0.5, blocks(-body_lo[2]) - 0.5
    probes = []
    for i in range(6):
        z = round(z0 + (z1 - z0) * i / 5, 4)
        probes += [[half_x, 0, z], [-half_x, 0, z]]
    data["TerrainCompat"] = probes
    data["ThirdPersonCameraPos"] = [-0.8669625, round(blocks(top_hi[1]) - 1.5, 3), 6]
    # Driver from the model's hatch or cab seat (SBW data: +X left, +Z forward). Truck crews ride inside.
    data["Seats"][0]["Position"] = spec["driver"]
    if not spec["visible_driver"]:
        for seat in data["Seats"]:
            seat["HidePassenger"] = True
    return data


def main():
    plz = json.loads(PLZ.read_text())
    for vehicle, spec in VEHICLES.items():
        data = build(vehicle, spec, plz)
        out = RES / f"data/{NS}/sbw/vehicles/{vehicle}.json"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(data, indent=2) + "\n")
        client = {"Animation": "superbwarfare:animations/bedrock/vehicle/plz_05.animation.json",
                  "Models": [{"Model": f"{NS}:models/bedrock/vehicle/{vehicle}.geo.json",
                              "Texture": f"{NS}:textures/bedrock/vehicle/{vehicle}.png"}]}
        out = RES / f"assets/{NS}/sbw/vehicles/{vehicle}.json"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(client, indent=2) + "\n")
        print(vehicle, "turret", data["TurretPos"], "barrel", data["BarrelPos"],
              "shoot", data["Weapons"]["Main"]["ShootPos"]["Positions"][0], "hull", data["OBB"][0]["Size"])


if __name__ == "__main__":
    main()
