# Client graphics budgets

These caps bound specific cosmetic work. They do not guarantee an FPS target;
terrain, shaders, entity count, resolution, drivers and other mods still affect
frame time.

## Native dynamic light submissions

The client-only `LightBudgetMixin` guards the pinned native
`LightPositionRegistry.putSpark(long, int, int, int)` entry point. All native
muzzle, vehicle and projectile helpers reach this registry.

- Admit at most **64 distinct block positions per client world game tick**.
- Deduplicate repeated submissions to the same position during that tick.
- Admit a new position only while the actual native `active` set has fewer than
  **256** entries. Existing entries may refresh at that cap.
- Reject positions more than **64 blocks from the actual main camera**, including
  its FPV or remote camera position. The player body is not used for distance.
- Skip native radius iteration if the tick budget is exhausted or its center is
  beyond **70 blocks**. Native radius is clamped to five; centers that far away
  cannot contribute a position inside the 64-block admission radius.

The guard shadows the native private static final `LongOpenHashSet active`; it
does not maintain a guessed active count. Native tick, light-engine updates,
decay, expiry and cleanup are untouched. TTL and light-level arguments pass
through unchanged. Existing entries above the cap, if introduced elsewhere,
expire normally; the budget never clears or truncates the native registry.

`StyleGraphics.lightCalls`, `lightAdmitted` and `lightRejected` observe actual
putSpark calls. Admission is recorded at native RETURN only after the actual
native set contains the submitted position. `lightDistinctPositions` records
this tick's admitted/reserved distinct positions, and `nativeActiveLights`
records the actual set size from the most recent intercepted call. Rejection
reason counters and `lightRadiusCalls`/`lightRadiusSkipped` are also public.
Skipped radial loops never make their inner putSpark calls, so those are counted
separately from `lightCalls`.

## Explosion decoration

`ExplosionBudgetMixin` replaces only
`ParticleTool.spawnExplosionParticlesClient(ParticleType, Level, Vec3)`.

| Camera distance | Maximum particle spawn attempts per explosion |
| --- | ---: |
| Under 64 blocks | 8–30, based on native explosion class |
| 64 through 192 blocks | 8 |
| Over 192 through 512 blocks | 1 smoke puff |
| Beyond 512 blocks | 0 |

All explosions share **128 Level.addParticle attempts per client world game
tick**. A nearby effect begins with one vanilla `EXPLOSION` particle followed by
simple vanilla `SMOKE` and `FLAME`. The first particle and distant single puff
use the ordinary `Level.addParticle` overload that requests visibility; every
invocation counts against the same cap. Vanilla settings and its own visibility
rules may still decline a particle. No emitter is used, so no uncounted child
particle burst or delayed spawning is scheduled.

Public `explosionCalls`, `explosionSpawnAttempts`, `explosionOmittedSpawns` and
`explosionCulled` expose the budget decisions. Spawn attempts count calls into
the level, not particles accepted by vanilla's engine.

Server explosion dispatch, damage, sounds, shake, packets, gameplay smoke,
decoys and projectile processing retain their native paths.

## Native recon glow pass

`DroneLayerMixin` targets the concrete `DroneEntity` render signature, avoiding
its generated generic bridge. It skips the native unconditional energy-swirl
second pass only when the entity's exact registry ID is
`superbwarfare:drone`. UF, Geranium, relay and other addon drone IDs remain
unaffected even if their classes inherit from native `DroneEntity`. The normal
geometry render, rotor callbacks and HUD are untouched. `droneLayersSkipped`
records skipped native passes.

## Scope and integration

All counters and distinct-position reservations reset lazily at the first budget
operation after the client world object changes or its game tick changes. The
world identity comparison also handles dimensions whose clocks happen to match.
The helpers are used only from the three client mixins and contain no common-side
registration. `frontier_style_client.mixins.json` lists all three under `client`.
The manifest must list this configuration alongside the catalog configuration.

Method descriptors and the native static final field were checked against the
pinned SBW `0.8.9.1-hotfix` binary with `javap`; Minecraft APIs were checked
against the pinned mapped 1.20.1 dependency. This task does not build, launch
Minecraft, install files, or modify a user world.
