# Original ground assets

The five ground models use the accepted IFVs' original olive pixel materials and newly authored cube geometry. Native SBW geometry, textures, and animation curves were not copied. Native pivots, resource paths, and controller names are retained as compatibility landmarks.

| Model | Near triangles | Far triangles, 64 blocks |
|---|---:|---:|
| LAV-25 | 600 | 192 |
| LAV-AD | 708 | 204 |
| HPJ-11 | 504 | 156 |
| Mortar | 504 | 156 |
| TOW | 504 | 168 |

Counts conservatively include six faces per cube and hidden loading-shell cubes. The shared native muzzle flash is outside these counts. Each model has exactly one far level, a 128 × 128 tiled atlas, and original container/HUD vehicle icons. LAV-AD and HPJ-11 have matching heat atlases with transparent body materials.

`ground_assets.py` writes only the five client model profiles, near/far geometry, textures, animations, and icons. It also writes the `ground-*` review artifacts. Regenerate and inspect saved resources with:

```sh
python3 art/ground_assets.py
python3 art/ground-validate.py
```

The independent validator compares functional pivots to the installed pinned SBW JAR and checks all 42 owned resource hashes. It checks eight native wheels on each LAV, aiming/radar/support pivots, empty flash anchors, native animation references, UVs, icons, heat coverage, and model budgets.

TOW keeps the native `move_missile` visibility state and its operator sight. HPJ-11 keeps all three radar anchors; its central bore/flash aligns with native `ShootPos`. The mortar keeps its native barrel pivot, 90-degree intermediary, support linkage, monitor visibility, and loading-shell animation. The software preview displays an elevated mortar review pose; it does not prove in-game transform behavior.

LAV-AD shows eight permanent launcher casings. Its native renderer has no per-round model visibility callback for FIM ammunition, so the art does not add permanently visible missile tips or imitate an ammo counter. Native weapon/HUD data still supplies current counts and shot sequencing.

Fire animations preserve native names. Flash anchors contain no geometry, idle scales are zero, cannon flash peaks at 5.5, and rotating barrels use a short original spin curve. The mortar loading animation schedules its small flash at the native one-second launch point. Native classes and all server data remain unchanged.

`ground-preview.png` and `ground-lod-comparison.png` render the authored geometry/UVs in software. Game loading, aiming, zoom hiding, state transitions, save compatibility, and performance measurement belong to the integration test.
