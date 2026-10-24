# Original air and sea vehicle assets

`air_sea_assets.py` authors every cube, pixel material, animation and icon for
`ah_6`, `mi_28`, `speedboat` and `tiny_speedboat`. It imports only reusable atlas,
font and face constants from the accepted IFV authoring helper. Native geometry,
native textures and native icon pixels are never imported into the result.

Run from the project root:

```sh
python3 art/air_sea_assets.py
python3 art/air-sea-validate.py
```

| Vehicle | Near triangles | Far triangles | Native moving parts retained |
| --- | ---: | ---: | --- |
| AH-6 | 804 | 240 | main/tail rotors; fixed paired cannon flash anchors |
| Mi-28 | 912 | 300 | main/tail rotors; turret, barrel and recoil; missile dummy anchors |
| Speedboat | 636 | 240 | turret and barrel; gun action; wheel/rudder; paired propellers |
| Tiny speedboat | 540 | 192 | wheel/rudder; native dye tint mask |

All models use a 128 × 128 original pixel atlas and face toward negative model Z.
The native renderer scale remains 1.0. All retained native anchor pivots are
compared to the pinned `0.8.9.1-hotfix` JAR during generation and saved validation.
Every vehicle has exactly two visual profiles: near and one far model at 64
blocks, matching the pinned selector's global threshold and first-match behavior.

Flare bones contain no cubes. Native animation names are retained, with cannon
flash peak 5.5 and machine-gun peak 3; all short firing animations reset to zero.
Static transform parents used by native moving parts are referenced in animation
metadata so they survive the model baker. The tiny boat's color overlay uses a
dedicated atlas tile and the native `EmissiveTexture` tint path at both distances.

The 256 × 64 transparent vehicle icons are software projections of the authored
near models. Review sheets show the authored models and include both LODs,
movement/aim poses and the native-path icons.

`air-sea-resource-sha256.json` hashes the 29 shipped resources. The saved-resource
validator checks those hashes, two-level profiles, file references, exact native
pivots, bone trees, animation targets and flash resets, positive cube dimensions,
UV bounds, icons and dye mask. `air-sea-metrics.json` records bounds and counts.

Verification is static validation and software projection. No Minecraft launch,
mod build, installation, server data or Java changes are part of these assets.
