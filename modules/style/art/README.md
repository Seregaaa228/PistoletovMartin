# Original arcade IFV assets

The models, pixel textures and short recoil/flash animations in `../resources`
are original cube art. The generator imports no native mesh, animation or image.
Only native scale, aim pivots, bone/controller names and resource locations were
consulted for compatibility with Superb Warfare 0.8.9.1 hotfix for Minecraft 1.20.1.

Regenerate and validate from the project root:

```sh
python3 art/generate_assets.py
python3 art/validate_assets.py
```

Requires Python 3, Pillow and NumPy. All generated game resources stay under
`resources/assets/superbwarfare`; previews and metrics stay under `art`.

| Vehicle | Near | Far, 64 blocks | Texture |
| --- | --- | --- | --- |
| BMP-2 olive | 61 cubes / 732 triangles | 22 cubes / 264 triangles | 128 × 128 |
| M2 Bradley sand | 60 cubes / 720 triangles | 22 cubes / 264 triangles | 128 × 128 |

Each cube has six quad faces, counted conservatively as 12 triangles including
occluded faces. The native renderer supplies its shared muzzle-flash model at
our empty `flare`/`flare2` bone transforms; that shared effect is outside these
vehicle model counts. Custom flare cubes are omitted because native rendering
hides them. Cannon scale peaks at 5.5 and returns to zero at 75 ms; MG peaks at
3 and returns to zero at 45 ms.

The production profile intentionally has one far LOD at 64 blocks. The pinned
native selector chooses the first eligible LOD and applies a global default of
64; extra ascending LOD entries would not be reliable. There are no engine or
configuration changes. Far models keep all twelve visible animated wheels.

`root → base → turret → barrel → barrel2` preserves native aiming with absolute
Bedrock pivot coordinates. `move_Track` is a direct child of `root` so the
native first-person zoom hiding still works. Each side has `wheelL0`–`wheelL5`
or `wheelR0`–`wheelR5`, using native wheel rotation. Continuous bands use pixel
treads and deliberately avoid native `trackMov*`/`trackRot*` belt assumptions.
Idle animation scales hide the shared native flash; fire briefly scales it at
the preserved muzzle pivots and moves the recoil child two model units backward.
No gameplay properties are changed.

Exact pivots, static bounds and renderer scales are in `asset-metrics.json`.
`resource-sha256.json` checks deterministic generated files. The previews use a
small software rasterizer that draws the actual generated cubes and UV atlas;
they are art previews, not evidence of an in-game run.
