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
        "he": (1000, 48), "ap": (1150, 460, 18), "wheeled": False,
        "driver": [0.75, 0.84, 3.31],
    },
    "bohdana": {
        "health": 380, "speed": 1.0, "velocity": 19, "gravity": 0.06, "reload": 110,
        "he": (950, 44), "ap": (1100, 440, 17), "wheeled": True,
        # Eye points in model units (bohdana_assets.py): driver and cab passenger; gunner camera barrel-local.
        "wheel_radius": 10.6,
        "driver_eye": (11.5, 46.5, -73), "crew_eye": (-11.5, 46.5, -73), "gunner_cam": (0, 10, -30),
        "engine": {"MaxForwardSpeedRate": 1.25, "MaxBackwardSpeedRate": 0.7, "Increment": 0.02, "Decrement": 0.012,
                   "SteeringSpeed": 0.06, "WheelRotSpeed": 1.3},
    },
    "grad": {
        "health": 300, "speed": 0.95, "velocity": 16, "gravity": 0.05, "reload": 600,
        "rockets": True, "wheeled": True,
        "wheel_radius": 12,
        "driver_eye": (10.2, 43, -37), "crew_eye": (-10.2, 43, -37), "gunner_cam": (0, 20, -5),
        "engine": {"MaxForwardSpeedRate": 1.15, "MaxBackwardSpeedRate": 0.7, "Increment": 0.02, "Decrement": 0.012,
                   "SteeringSpeed": 0.065, "WheelRotSpeed": 1.25},
        # First tube centre x, y; tube pitch; muzzle-face z (model units).
        "tubes": (-15.3, 38.2, 3.4, -21),
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


def solid(bone_list):
    """The bones' cubes that are real bulk: not slivers (antennas, mirrors, lamps), and not rotated."""
    out = []
    for bone in bone_list:
        # Rotated cubes (bevels, tilted plates) are left out: their origin/size box is not where they sit.
        cubes = [c for c in bone.get("cubes", []) if min(c["size"]) >= 1.5 and c["size"][0] * c["size"][1] * c["size"][2] >= 12
                 and not any(c.get("rotation", [0, 0, 0]))]
        if cubes:
            out.append({"cubes": cubes})
    return out


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
        # Drives like a truck: SBW's wheeled engine steers the front wheels (wheelL0Turn / wheelR0Turn).
        data["EngineSound"] = "superbwarfare:truck_engine"
        data["EngineType"] = "Wheel"
        data["Type"] = "APC"
        data["EngineInfo"] = {"Buoyancy": 0, "EnergyCostRate": 48, "WheelDifferential": 0.45, "EngineSoundVolume": 0.7,
                              **spec["engine"]}

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
            "Magazine": 40, "RPM": 600, "DefaultFireMode": "Auto", "AvailableFireModes": "Auto",
            "Icon": "superbwarfare:textures/overlay/vehicle/weapon/icons/he_shell.png",
            "Name": "item.superbwarfare.medium_rocket_he", "RecoilForce": 0.2, "ShootShake": [3, 4, 12],
            "SoundInfo": {"Fire1P": "superbwarfare:medium_rocket_fire", "Fire3P": "superbwarfare:medium_rocket_fire",
                          "Fire3PFar": "superbwarfare:medium_rocket_fire", "Fire3PVeryFar": "superbwarfare:medium_rocket_fire"},
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

    # Collision, from the model's solid parts (thin antennas, mirrors and lamps do not count). SBW stands the vehicle
    # on these boxes and hits, boards and collides with them, so each follows what it covers:
    #   hull: ground to deck, bumper to bumper, one box (no gaps, no step to snag);
    #   turret / launcher: its own box turning with the turret; the gun or rocket pack: one that elevates with it;
    #   wheels or tracks: the running gear as the parts SBW damages separately.
    turret_names = {x["name"] for x in subtree(b, "turret")}
    barrel_names = {x["name"] for x in subtree(b, "barrel")}
    hull_lo, hull_hi = extent(solid([x for n, x in b.items() if n not in turret_names]))
    top_lo, top_hi = extent(solid([x for n, x in b.items() if n in turret_names and n not in barrel_names]))
    gun_lo, gun_hi = extent(solid([b[n] for n in barrel_names]))
    half = lambda lo, hi: [round(blocks(hi[i] - lo[i]) / 2, 4) for i in range(3)]
    # Model to data frame (x stays, z flips), relative to a pivot.
    at = lambda lo, hi, pivot: [round(blocks((lo[0] + hi[0]) / 2 - pivot[0]), 4), round(blocks((lo[1] + hi[1]) / 2 - pivot[1]), 4),
                                round(blocks(-((lo[2] + hi[2]) / 2 - pivot[2])), 4)]
    hull_lo[1] = 0  # the hull box stands on the ground
    width = max(abs(hull_lo[0]), abs(hull_hi[0]))
    hull_lo[0], hull_hi[0] = -width, width
    boxes = [
        {"Part": "Collision", "Size": half(hull_lo, hull_hi), "Position": at(hull_lo, hull_hi, [0, 0, 0])},
        {"Part": "Turret", "Size": half(top_lo, top_hi), "Position": at(top_lo, top_hi, turret),
         "Transform": "Turret", "Rotation": "Turret"},
        {"Size": half(gun_lo, gun_hi), "Position": at(gun_lo, gun_hi, barrel), "Transform": "Barrel", "Rotation": "Barrel"},
    ]
    engine_z = blocks(-hull_lo[2]) - 1.0 if spec.get("wheeled") else blocks(-hull_hi[2]) + 1.2
    boxes.append({"Part": "MainEngine", "Size": [0.6, 0.4, 0.8], "Position": [0, round(blocks(hull_hi[1]) * 0.6, 4), round(engine_z, 4)]})
    if spec.get("wheeled"):
        # A truck rides on its tyres: probe exactly under each wheel and give each wheel its own box, as SBW's pickup
        # does. Probes at the bumpers and a tank's 2.1-block step made it hop and lean on level road.
        hubs = [b[name]["pivot"] for name in ("wheelL0Turn", "wheelL3", "wheelL6", "wheelR0Turn", "wheelR3", "wheelR6")]
        r = round(blocks(spec["wheel_radius"]), 4)
        data["TerrainCompat"] = [[blocks(x), 0, blocks(-z)] for x, _, z in hubs]
        boxes += [{"Part": "WheelLeft" if x > 0 else "WheelRight", "Size": [0.28, r, r], "Position": [blocks(x), r, blocks(-z)]}
                  for x, _, z in hubs]
        data["UpStep"] = 1.1
        data["Mass"] = 14
    else:
        side = [round(blocks(width) - 0.35, 4), 0.6, round(blocks(hull_hi[2] - hull_lo[2]) / 2 * 0.85, 4)]
        mid = round(blocks(-(hull_lo[2] + hull_hi[2]) / 2), 4)
        boxes += [{"Part": "WheelLeft", "Size": [0.35, 0.6, side[2]], "Position": [side[0], 0.6, mid]},
                  {"Part": "WheelRight", "Size": [0.35, 0.6, side[2]], "Position": [-side[0], 0.6, mid]}]
        probes = []
        for i in range(6):
            z = round(blocks(-hull_hi[2]) + 0.6 + (blocks(hull_hi[2] - hull_lo[2]) - 1.2) * i / 5, 4)
            probes += [[side[0], 0, z], [-side[0], 0, z]]
        data["TerrainCompat"] = probes
    data["OBB"] = boxes
    data["ThirdPersonCameraPos"] = [-0.8669625, round(blocks(top_hi[1]) - 1.5, 3), 6]
    if spec.get("wheeled"):
        # Truck crews sit in the cab, the driver at the wheel with a free look through the windscreen; the
        # second cab seat replaces PLZ-05's roof gunners. SBW data: +X left, +Z forward.
        driver, gunner = data["Seats"][0], data["Seats"][1]
        # Seen from outside and in F5 at the wheel; only the gunner, inside the mount, stays hidden.
        driver.update({"IsEnclosed": True, "HidePassenger": False, "Transform": "Vehicle", "RotateWithVehicle": True,
                       "Position": seat_from_eye(spec["driver_eye"]), "MinYaw": -100, "MaxYaw": 100,
                       "MinPitch": -45, "MaxPitch": 40})
        # The first-person camera is pinned at the eye point the model was made for, whatever the rider's height
        # and SBW's seat placement: SBW honours a fixed camera position for any seat.
        driver["CameraPos"] = camera_at(spec["driver_eye"])
        crew = copy.deepcopy(driver)
        crew["Position"] = seat_from_eye(spec["crew_eye"])
        crew["CameraPos"] = camera_at(spec["crew_eye"])
        crew["DismountInfo"] = {"Transform": "Vehicle", "Position": [-2.2, 0.375, crew["Position"][2]]}
        driver["DismountInfo"] = {"Transform": "Vehicle", "Position": [2.2, 0.375, driver["Position"][2]]}
        if spec.get("gunner_cam"):
            cam = spec["gunner_cam"]
            gunner["CameraPos"] = {"Transform": "Barrel", "UseFixedCameraPos": True,
                                   "Position": [blocks(cam[0]), blocks(cam[1]), blocks(-cam[2])],
                                   "ZoomPosition": [blocks(cam[0]), blocks(cam[1]), blocks(-cam[2])], "Direction": "Barrel"}
        data["Seats"] = [driver, gunner, crew]
    else:
        data["Seats"][0]["Position"] = spec["driver"]
    return data


def animations(vehicle, spec):
    """
    The vehicle's own animations, under the names Plz05Entity plays. Each model needs its own file: SBW pairs an
    animation with a model by file name and binds it to that model's bones, and PLZ-05's file, bound to PLZ-05's bone
    order, swung whatever of ours sat at those indices (a truck jack turned 67 degrees).
    """
    kick = 1.0 if spec.get("rockets") else 3.0
    fire = {"loop": "hold_on_last_frame", "animation_length": 1, "bones": {
        "barrelBase": {"rotation": {"0.0": [0, 0, 0], "0.05": [-kick, 0, 0], "0.3": [-kick * 0.8, 0, 0],
                                    "0.5": [kick * 0.3, 0, 0], "0.8": [0, 0, 0]}}}}
    if not spec.get("rockets"):
        # The barrel slides back in its cradle and runs out again.
        fire["bones"]["tube"] = {"position": {"0.0": [0, 0, 0], "0.0167": [0, 0, 11], "0.3": [0, 0, 0]}}
    lock = {"loop": True, "bones": {}}
    if vehicle == "msta_s":
        # The travel lock on the glacis stands up to hold the barrel while driving.
        lock["bones"]["paojia"] = {"rotation": [67.5, 0, 0]}
    return {"format_version": "1.8.0", "animations": {
        "animation.main.idle": {"loop": True}, "animation.main.fire": fire, "animation.plz_05.lock_turret": lock}}


def camera_at(eye):
    """SBW seat camera fixed at a model point (vehicle frame, blocks)."""
    x, y, z = eye
    return {"Transform": "Vehicle", "UseFixedCameraPos": True, "Position": [blocks(x), blocks(y), blocks(-z)]}


def seat_from_eye(eye):
    """Seat position (blocks, SBW frame) that puts a seated player's eye at a model point: SBW stands the rider's
    feet at the seat position, so the eye is 1.62 above it and the hips 0.7."""
    x, y, z = eye
    return [blocks(x), round(blocks(y) - 1.62, 4), blocks(-z)]


def main():
    plz = json.loads(PLZ.read_text())
    for vehicle, spec in VEHICLES.items():
        data = build(vehicle, spec, plz)
        out = RES / f"data/{NS}/sbw/vehicles/{vehicle}.json"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(data, indent=2) + "\n")
        animation = RES / f"assets/{NS}/animations/bedrock/vehicle/{vehicle}.animation.json"
        animation.parent.mkdir(parents=True, exist_ok=True)
        animation.write_text(json.dumps(animations(vehicle, spec), indent=2) + "\n")
        client = {"Animation": f"{NS}:animations/bedrock/vehicle/{vehicle}.animation.json",
                  "Models": [{"Model": f"{NS}:models/bedrock/vehicle/{vehicle}.geo.json",
                              "Texture": f"{NS}:textures/bedrock/vehicle/{vehicle}.png"}]}
        out = RES / f"assets/{NS}/sbw/vehicles/{vehicle}.json"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(client, indent=2) + "\n")
        print(vehicle, "turret", data["TurretPos"], "barrel", data["BarrelPos"],
              "shoot", data["Weapons"]["Main"]["ShootPos"]["Positions"][0], "hull", data["OBB"][0]["Size"])


if __name__ == "__main__":
    main()
