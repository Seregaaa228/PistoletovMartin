package dev.frontier.aviation.systemstest;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.google.gson.JsonObject;
import dev.frontier.aviation.AircraftProfile;
import dev.frontier.aviation.api.AircraftArmament;
import dev.frontier.aviation.api.AircraftController;
import dev.frontier.aviation.control.ControlLease;
import dev.frontier.aviation.control.PilotCommand;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** Verifies a tracked medium AGM hits a moving ground mob after pilot egress. */
final class F16MovingGroundHomingScenario {
    private static final int PILOT_EGRESS_TICK = 15;
    private static final int TIMEOUT_TICKS = 240;

    private final ServerPlayer pilot;
    private final VehicleEntity plane;
    private final Mob target;
    private final ControlLease lease;
    private final JsonObject result = new JsonObject();
    private final double initialHealth;
    private final Vec3 initialTargetPosition;
    private int ticks;
    private int initialMissileSpawns;
    private boolean queued;
    private boolean pilotExited;

    static F16MovingGroundHomingScenario start(ServerPlayer pilot) {
        return new F16MovingGroundHomingScenario(pilot);
    }

    private F16MovingGroundHomingScenario(ServerPlayer pilot) {
        this.pilot = pilot;
        var level = pilot.serverLevel();
        if (pilot.isPassenger()) throw new IllegalStateException("moving ground homing needs a free pilot");
        ImpactObservations.clear();

        int groundY = groundY(level, pilot.blockPosition());
        plane = AircraftProfile.F16.entity.get().create(level);
        // Peaceful test worlds immediately remove hostile mobs; a passive mob
        // remains available for the full lock and impact sequence.
        target = EntityType.COW.create(level);
        if (plane == null || target == null) throw new IllegalStateException("F-16 or ground target creation failed");

        // Face north (+Z) as in the F-16 fixture and put the target 60 blocks
        // ahead on the actual ground surface, within the native seeker sector.
        plane.moveTo(pilot.getX(), groundY + 10, pilot.getZ(), 0, 0);
        plane.setNoGravity(true);
        plane.setInvulnerable(true);
        target.moveTo(pilot.getX(), groundY, pilot.getZ() + 60, 0, 0);
        target.setInvulnerable(false);
        target.setPersistenceRequired();
        // A small native ground velocity makes the living target move while
        // its normal AI, collision, gravity, and tick behavior remain enabled.
        target.setDeltaMovement(0.12, 0, 0);

        if (!level.addFreshEntity(plane) || !level.addFreshEntity(target)
                || !pilot.startRiding(plane, true)) {
            plane.discard();
            target.discard();
            throw new IllegalStateException("F-16 moving-target fixture setup failed");
        }
        initialHealth = target.getHealth();
        initialTargetPosition = target.position();
        lease = AircraftController.acquire(pilot, plane)
            .orElseThrow(() -> new IllegalStateException("F-16 lease acquisition failed"));
        result.addProperty("vehicle", "frontier_aviation:f16");
        result.addProperty("pilot", pilot.getUUID().toString());
        result.addProperty("target", target.getUUID().toString());
        result.addProperty("targetType", "minecraft:cow");
        result.addProperty("targetInitialDistance", plane.position().distanceTo(target.position()));
        result.addProperty("targetInitialHealth", initialHealth);
        result.addProperty("pass", false);
    }

    boolean tick() {
        ticks++;
        if (plane.isRemoved()) return fail("fixture aircraft removed");
        if (!pilotExited && !AircraftController.owns(pilot, plane, lease))
            return fail("pilot lease lost before egress");

        if (!pilotExited) AircraftController.submit(pilot, lease, PilotCommand.neutral(3_000_000L + ticks));
        if (target.isAlive()) target.move(MoverType.SELF, new Vec3(.12, 0, 0));

        if (ticks == 5) {
            if (!AircraftArmament.select(pilot, plane, lease, "medium_agmissile"))
                return fail("medium AGM selection failed");
            var gun = plane.getGunData("MediumAGMissile");
            if (gun == null) return fail("native MediumAGMissile gun missing");
            gun.ammo.set(1);
            initialMissileSpawns = ProjectileObservations.count("superbwarfare:wire_guide_missile");
            var from = plane.getShootPosForHud(pilot, 1);
            var ray = plane.getShootDirectionForHud(pilot, 1);
            var point = target.getBoundingBox().getCenter();
            var sector = dev.frontier.aviation.weapons.WeaponAimResolver.sector(gun);
            result.addProperty("hudOrigin", from.toString());
            result.addProperty("hudDirection", ray.toString());
            result.addProperty("targetCenter", point.toString());
            result.addProperty("seekSector", sector);
            result.addProperty("lineOfSight", dev.frontier.aviation.weapons.WeaponAimResolver.resolve(
                pilot.level(), pilot, from, point.subtract(from), from.distanceTo(point) + 1).toString());
            var fireResult = AircraftArmament.fireTracked(pilot, plane, lease, "medium_agmissile", 1, target);
            result.addProperty("fireResult", fireResult.toString());
            queued = fireResult == AircraftArmament.Result.QUEUED;
            result.addProperty("fireTrackedQueued", queued);
            if (!queued) return fail("tracked medium AGM launch denied");
        }

        if (ticks == PILOT_EGRESS_TICK) {
            pilot.stopRiding();
            pilotExited = true;
            // Leave the aircraft to its own native flight tick after egress,
            // with a clear turn-away heading; do not teleport or freeze it.
            plane.setYRot(90);
            plane.yRotO = 90;
            result.addProperty("pilotExitedAfterLaunch", true);
        }

        if (ticks > 5 && ticks % 10 == 0 && target.isAlive()) {
            result.addProperty("targetPositionAt" + ticks, target.position().toString());
        }

        int missileSpawns = ProjectileObservations.count("superbwarfare:wire_guide_missile")
            - initialMissileSpawns;
        if (ticks >= 5 && missileSpawns > 0) result.addProperty("nativeMissileSpawned", true);
        boolean damaged = !target.isAlive() || target.getHealth() < initialHealth;
        var traces = ImpactObservations.owned(pilot.getUUID(), plane.getUUID());
        boolean directHit = traces.stream()
            .anyMatch(trace -> trace.type.equals("superbwarfare:wire_guide_missile") && target.getUUID().equals(trace.hitEntity));
        boolean groundImpact = traces.stream().anyMatch(trace -> trace.type.equals("superbwarfare:wire_guide_missile")
            && trace.hit != null && trace.hit.distanceTo(target.position()) <= 12);
        if (pilotExited && damaged && (directHit || groundImpact)) {
            result.addProperty("targetHealthAfter", target.isAlive() ? target.getHealth() : 0);
            result.addProperty("targetDestroyed", !target.isAlive());
            result.addProperty("directHit", directHit);
            result.addProperty("nearbyGroundImpact", groundImpact);
            result.addProperty("targetMovedDistance", target.position().distanceTo(initialTargetPosition));
            result.addProperty("aircraftHeadingAtImpact", plane.getYRot());
            result.addProperty("nativeMissileSpawns", missileSpawns);
            result.addProperty("pass", queued && pilotExited && missileSpawns == 1
                && damaged && target.position().distanceTo(initialTargetPosition) > 1);
            cleanup();
            return true;
        }

        if (ticks >= TIMEOUT_TICKS) return fail("no target damage or destruction observed");
        return false;
    }

    private boolean fail(String message) {
        result.addProperty("targetAliveAtEnd", target.isAlive());
        result.addProperty("targetHealthAtEnd", target.getHealth());
        result.addProperty("targetPositionAtEnd", target.position().toString());
        var traces = ImpactObservations.owned(pilot.getUUID(), plane.getUUID());
        for (var trace : traces) {
            if (!trace.type.equals("superbwarfare:wire_guide_missile")) continue;
            result.addProperty("missilePositionAtEnd", trace.entity.position().toString());
            result.addProperty("missileRemovedAtEnd", trace.entity.isRemoved());
            result.addProperty("missileBlockHit", String.valueOf(trace.hit));
            result.addProperty("missileEntityHit", String.valueOf(trace.hitEntity));
            result.addProperty("missileNativeTarget", ((com.atsuishio.superbwarfare.entity.projectile.WireGuideMissileEntity) trace.entity).getTargetUUID());
        }
        result.addProperty("error", message);
        result.addProperty("pass", false);
        cleanup();
        return true;
    }

    private void cleanup() {
        AircraftController.release(pilot, lease);
        pilot.stopRiding();
        for (var trace : ImpactObservations.owned(pilot.getUUID(), plane.getUUID())) {
            if (!trace.entity.isRemoved()) trace.entity.discard();
        }
        target.discard();
        plane.discard();
    }

    JsonObject result() { return result; }

    private static int groundY(net.minecraft.server.level.ServerLevel level, BlockPos around) {
        int x = around.getX();
        int z = around.getZ() + 60;
        if (!level.hasChunkAt(new BlockPos(x, level.getMinBuildHeight(), z)))
            throw new IllegalStateException("target chunk not loaded");
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }
}
