#!/usr/bin/env python3
"""Build the air-defence complexes' models, textures and icons.

    python3 modules/arcade/art/sam_assets.py [names...] [--preview DIR]

Each vehicle is described in modules/arcade/art/sam/<module>.py on the aviation kit
(modules/aviation/art/realistic: kit.py geometry, paint.py materials), at the detail of the
aircraft remakes. A module exposes

    MODELS = {"<entity id>": (build, painter)}   build() -> kit.Model, painter() -> callable
    FIXED  = {...}                                bones that never move (hidden-face culling)
    DENSITY = 1.6                                 atlas texels per model unit (stepped down to fit)

This driver removes hidden faces, separates coplanar faces, packs one 1024 atlas per vehicle,
paints it, writes the geo (compact JSON), the texture and the two catalog icons, and with
--preview renders review images (front, rear, side, top, and the launcher raised/turned).

Contract with the game code (SamLayout.java, SamSiteRenderer.java), model units, 16 per block,
front -Z, left +X, ground y = 0:
  * bones root > base (hull); wheels under "Wheel": wheelL0Turn / wheelR0Turn steer, every
    other wheel[LR]<n> only spins (SBW's own names);
  * move_turret (yaw), move_launcher (elevation, child of move_turret), move_pack (S-300/S-400
    canisters, raised about X to vertical), move_radar (sweep), move_round_<n> (S-125 rounds);
  * inside move_* bones every cube is axis-aligned or turned about the bone's Z axis only:
    SBW's baker flips X/Y cube rotations in dynamic bones.
"""
from __future__ import annotations

import importlib
import json
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(ROOT / "modules/aviation/art/realistic"))
sys.path.insert(0, str(HERE))
import cull  # noqa: E402
import kit as K  # noqa: E402
import zfight  # noqa: E402
import grad_assets as G  # noqa: E402  (orthographic renderer and icon styles)

ASSETS = HERE.parent / "src/main/resources/assets/frontier_arcade_vehicles"
MODULES = ("sam.s125", "sam.maz", "sam.pantsir", "sam.patriot")
ATLAS = 1024


def models(strict=True):
    out = {}
    for name in MODULES:
        try:
            module = importlib.import_module(name)
        except ModuleNotFoundError as missing:
            # While a vehicle's module is being written, the others can still be built by name.
            if strict:
                raise
            print("skipped %s: %s" % (name, missing), file=sys.stderr)
            continue
        for vehicle, (build, painter) in module.MODELS.items():
            out[vehicle] = (module, build, painter)
    return out


def check_moving_rotations(model):
    """Dynamic bones may only hold cubes turned about Z (see the contract above)."""
    bad = [c for c in model.cubes if c.bone.startswith("move_") and c.rotation
           and (abs(c.rotation[0]) > 1e-6 or abs(c.rotation[1]) > 1e-6)]
    if bad:
        raise SystemExit("%s: %d cubes in move_* bones are turned about X/Y (first in %s)"
                         % (model.identifier, len(bad), bad[0].bone))


def export(vehicle, module, build, painter, preview):
    model = build()
    check_moving_rotations(model)
    cull.FIXED = set(getattr(module, "FIXED", {"base"}))
    cull.KEEP_ONE = ()
    hidden = cull.cull(model.cubes)
    left = zfight.resolve([c for c in model.cubes if any(True for _ in c.faces())])
    density = getattr(module, "DENSITY", 1.6)
    while True:
        atlas = K.Atlas(ATLAS, density)
        try:
            atlas.pack(model.cubes)
            break
        except ValueError:
            density = round(density - 0.1, 2)
    atlas.paint(model.cubes, painter())
    geo = K.geo_json(model, ATLAS, identifier="geometry." + vehicle, bounds=(18, 10, 4))
    for bone in geo["minecraft:geometry"][0]["bones"]:
        if bone.get("parent") is None:
            bone.pop("parent", None)
    from PIL import Image
    import io
    tex = Image.open(io.BytesIO(atlas.png())).convert("RGBA")
    out = {
        "geo": ASSETS / f"models/bedrock/vehicle/{vehicle}.geo.json",
        "texture": ASSETS / f"textures/bedrock/vehicle/{vehicle}.png",
        "container": ASSETS / f"textures/vehicle_icon/container/{vehicle}.png",
        "icon": ASSETS / f"textures/vehicle_icon/{vehicle}_icon.png",
    }
    out["geo"].parent.mkdir(parents=True, exist_ok=True)
    out["geo"].write_text(json.dumps(geo, separators=(",", ":")) + "\n")
    for key, image in (("texture", tex), ("container", G.container_icon(geo, tex)), ("icon", G.side_icon(geo, tex))):
        out[key].parent.mkdir(parents=True, exist_ok=True)
        image.save(out[key], optimize=True)
    quads = [q for q, _ in G.geo_quads(geo)]
    pts = np.concatenate(quads)
    metrics = {"cubes": len([c for c in model.cubes if c.uv]), "faces": len(quads), "hidden_faces": hidden,
               "coplanar_pairs_left": len(left), "density": density, "bones": [b.name for b in model.bones],
               "extent_blocks": [round(float(v) / 16, 2) for v in pts.max(0) - pts.min(0)],
               "min_y": round(float(pts[:, 1].min()), 2)}
    if preview:
        folder = Path(preview)
        folder.mkdir(parents=True, exist_ok=True)
        views = {"front": (35, .45), "rear": (215, .45), "side": (90, .12), "top": (0, 1.45), "front_low": (-25, .15)}
        for tag, (yaw, elevation) in views.items():
            im, _, _ = G.render(geo, tex, (1200, 800), yaw=yaw, elevation=elevation)
            im.save(folder / f"{vehicle}-{tag}.png")
        poses = getattr(module, "PREVIEW_POSES", {}).get(vehicle)
        if poses:
            im, _, _ = G.render(geo, tex, (1200, 800), yaw=215, elevation=.45, poses=poses)
            im.save(folder / f"{vehicle}-posed.png")
    return metrics


def main():
    preview = sys.argv[sys.argv.index("--preview") + 1] if "--preview" in sys.argv else None
    names = [a for a in sys.argv[1:] if not a.startswith("--") and a != preview]
    report = {}
    for vehicle, (module, build, painter) in models(strict=not names).items():
        if names and vehicle not in names:
            continue
        report[vehicle] = export(vehicle, module, build, painter, preview)
    print(json.dumps(report, indent=1))


if __name__ == "__main__":
    main()
