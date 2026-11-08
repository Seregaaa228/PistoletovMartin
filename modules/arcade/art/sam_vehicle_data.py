#!/usr/bin/env python3
"""SBW vehicle data for the air-defence complexes, derived from their models.

Writes data/frontier_arcade_vehicles/sbw/vehicles/<id>.json (server: health, seats, collision
boxes, engine, damage table) and assets/frontier_arcade_vehicles/sbw/vehicles/<id>.json
(client: model and texture). Seat and box positions are vehicle-local blocks: x left, y up,
z forward, i.e. (x, y, -z) / 16 of the model units written by sam_assets.py. Run after it.

The sites carry no SBW weapon: their radar and launcher are SamSiteEntity's own. The crew
seat looks out from a mast-top camera so the whole sky is in view.
"""
from __future__ import annotations

import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
RES = HERE.parent / "src/main/resources"
NS = "frontier_arcade_vehicles"
GRAD = RES / f"data/{NS}/sbw/vehicles/grad.json"

TRUCK_ENGINE = {"Buoyancy": 0, "EnergyCostRate": 56, "WheelDifferential": 0.45, "EngineSoundVolume": 0.7,
                "MaxForwardSpeedRate": 1.0, "MaxBackwardSpeedRate": 0.6, "Increment": 0.016, "Decrement": 0.012,
                "SteeringSpeed": 0.05, "WheelRotSpeed": 1.25}

VEHICLES = {
    # model extents (x half width, y top, z front, z rear), wheel x, axles z, eye points (sam/*.py reports), mast camera
    # S-125: launcher core, crew booth and radar trailer as separate boxes (centre x, z, half x, half z, top y)
    "s125_pechora": {"health": 260, "type": "Defense", "boxes": ((0, 0, 40, 40, 34), (-50.5, 50.5, 13, 13, 40),
                                                                 (48, 50, 14, 16, 46)),
                     "crew": (-50, 25.6, 50), "camera": (48, 77, 50)},
    "s300ps": {"health": 380, "type": "Car", "box": (26, 52, -98, 92), "wheel_x": 18.6, "axles": (-62, -27, 26, 61),
               "driver": (16.6, 40, -84), "driver_camera": (16.6, 56, -86), "crew": (-16.6, 40, -84), "camera": (0, 96, -40)},
    "s400_triumf": {"health": 400, "type": "Car", "box": (26, 52, -98, 92), "wheel_x": 18.6, "axles": (-62, -27, 26, 61),
                    "driver": (16.6, 40, -84), "driver_camera": (16.6, 56, -86), "crew": (-16.6, 40, -84),
                    "camera": (0, 96, -40)},
    "pantsir_s1": {"health": 340, "type": "Car", "box": (20.5, 72, -89, 82), "wheel_x": 16.5, "axles": (-64, -36, 16, 38),
                   "driver": (9.5, 42.5, -71), "driver_camera": (9.5, 63, -74), "crew": (-9.5, 42.5, -71),
                   "camera": (0, 108, 30)},
    "patriot_m901": {"health": 380, "type": "Car", "box": (19.6, 68, -104, 100), "wheel_x": 15.6,
                     "axles": (-80, -30, -10, 60, 80), "driver": (9.5, 38.5, -86), "driver_camera": (9.5, 54, -86),
                     "crew": (-9.5, 38.5, -86), "camera": (0, 88, 40)},
}


def local(x, y, z):
    return [round(x / 16, 4), round(y / 16, 4), round(-z / 16, 4)]


def seat(eye, dismount_x, camera=None, driver=False):
    """A seat at the reported eye point. The cab glass is opaque, so the driver looks out from
    just above the cab roof and the crew commander from the mast."""
    # The seat sits ten units (0.6 m) below the eye point the model reports.
    s = {"Transform": "Vehicle", "Position": local(eye[0], eye[1] - 10, eye[2]), "IsEnclosed": True,
         "DismountInfo": {"Transform": "Vehicle", "Position": [dismount_x, 0.375, round(-eye[2] / 16, 4)]}}
    if driver:
        s.update({"RotateWithVehicle": True, "MinPitch": -45, "MaxPitch": 40, "MinYaw": -100, "MaxYaw": 100,
                  "CameraPos": {"Transform": "Vehicle", "UseFixedCameraPos": True,
                                "Position": local(*camera)}})
    else:
        # The crew commander: a free look over the whole sky from the mast.
        s.update({"HidePassenger": True, "MinPitch": -30, "MaxPitch": 89,
                  "CameraPos": {"Transform": "Vehicle", "UseFixedCameraPos": True, "Position": local(*camera)}})
    return s


def server(name, spec, damage):
    if spec["type"] == "Defense":
        obb = [{"Part": "Collision", "Size": [round(hx / 16, 4), round(top / 32, 4), round(hz / 16, 4)],
                "Position": local(cx, top / 2, cz)} for cx, cz, hx, hz, top in spec["boxes"]]
        data = {"MaxHealth": spec["health"], "MaxEnergy": 5000000, "OBB": obb,
                "Seats": [seat(spec["crew"], -3.0, spec["camera"])],
                "Mass": 40, "EngineType": "Fixed", "ThirdPersonCameraPos": [0, 2, 9]}
    else:
        hx, top, front, rear = spec["box"]
        obb = [{"Part": "Collision", "Size": [round(hx / 16, 4), round(top / 32, 4), round((rear - front) / 32, 4)],
                "Position": [0, round(top / 32, 4), round(-(rear + front) / 32, 4)]}]
        for z in spec["axles"]:
            for part, s in (("WheelLeft", 1), ("WheelRight", -1)):
                obb.append({"Part": part, "Size": [0.27, 0.72, 0.72],
                            "Position": [round(s * spec["wheel_x"] / 16, 4), 0.72, round(-z / 16, 4)]})
        data = {"MaxHealth": spec["health"], "MaxEnergy": 5000000, "UpStep": 1.1, "OBB": obb,
                "Seats": [seat(spec["driver"], 2.4, spec["driver_camera"], driver=True), seat(spec["crew"], -2.4, spec["camera"])],
                "Mass": 30, "EngineType": "Wheel", "EngineInfo": dict(TRUCK_ENGINE),
                "EngineSound": "superbwarfare:truck_engine", "HudType": "@Land",
                "ThirdPersonCameraPos": [0, 2.5, 13], "InertiaRotateRate": 0.6,
                "TerrainCompat": [[round(s * spec["wheel_x"] / 16, 4), 0, round(-z / 16, 4)]
                                  for z in (spec["axles"][0], spec["axles"][-1]) for s in (1, -1)],
                "CollisionLevel": {"Level": 4, "PowerLimits": [[0, 0, True], [0, 0, True], [0.1, 0.05, False], [1, 0.3, False]]}}
    data.update({"DamageModifiers": damage,
                 "DestroyInfo": {"ExplosionDamage": 220, "ExplosionRadius": 14, "SympatheticDetonation": True,
                                 "SympatheticDetonationForce": 1.2, "SympatheticDetonationChance": 0.35},
                 "VehicleIcon": f"{NS}:textures/vehicle_icon/{name}_icon.png",
                 "ContainerIcon": "superbwarfare:textures/gui/vehicle/type/defense.png",
                 "VehicleContainerType": "Medium", "Type": spec["type"], "HUDColor": "0x00ff66"})
    return data


def main():
    damage = json.loads(GRAD.read_text())["DamageModifiers"]
    for name, spec in VEHICLES.items():
        out = RES / f"data/{NS}/sbw/vehicles/{name}.json"
        out.write_text(json.dumps(server(name, spec, damage), indent=2) + "\n")
        client = {"Models": [{"Model": f"{NS}:models/bedrock/vehicle/{name}.geo.json",
                              "Texture": f"{NS}:textures/bedrock/vehicle/{name}.png"}]}
        path = RES / f"assets/{NS}/sbw/vehicles/{name}.json"
        path.write_text(json.dumps(client, indent=2) + "\n")
        print(out.relative_to(RES), path.relative_to(RES))


if __name__ == "__main__":
    main()
