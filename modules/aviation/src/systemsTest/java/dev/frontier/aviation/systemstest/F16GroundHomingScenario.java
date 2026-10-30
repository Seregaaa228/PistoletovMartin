package dev.frontier.aviation.systemstest;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.google.gson.JsonObject;
import dev.frontier.aviation.AircraftProfile;
import dev.frontier.aviation.api.AircraftArmament;
import dev.frontier.aviation.api.AircraftController;
import dev.frontier.aviation.control.ControlLease;
import dev.frontier.aviation.control.PilotCommand;
import dev.frontier.aviation.missile.ManualMissileShots;
import dev.frontier.aviation.weapons.WeaponAimResolver;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** A real projectile must retain its launch target after the pilot leaves. */
final class F16GroundHomingScenario {
    private final ServerPlayer pilot;
    private final VehicleEntity plane;
    private final ControlLease lease;
    private final Map<BlockPos, BlockState> changed = new LinkedHashMap<>();
    private final JsonObject result = new JsonObject();
    private Vec3 target;
    private int ticks;

    F16GroundHomingScenario(ServerPlayer pilot) {
        this.pilot = pilot;
        var level = pilot.serverLevel();
        if (pilot.isPassenger()) throw new IllegalStateException("pilot already mounted");
        ImpactObservations.clear();
        plane = AircraftProfile.F16.entity.get().create(level);
        if (plane == null) throw new IllegalStateException("no F-16");
        plane.moveTo(pilot.getX(), level.getMinBuildHeight() + 128, pilot.getZ(), 0, 0);
        plane.setNoGravity(true);
        plane.setInvulnerable(true);
        if (!level.addFreshEntity(plane) || !pilot.startRiding(plane, true)) throw new IllegalStateException("mount failed");
        lease = AircraftController.acquire(pilot, plane).orElseThrow();
        result.addProperty("pass", false);
    }

    boolean tick() {
        ticks++;
        if (ticks < 10) {
            if (!AircraftController.owns(pilot, plane, lease)) return finish("lost pilot lease");
            AircraftController.submit(pilot, lease, PilotCommand.neutral(2_000_000L + ticks));
        }
        if (ticks == 5) {
            if (!AircraftArmament.select(pilot, plane, lease, "medium_agmissile")) return finish("missing medium missile");
            Vec3 from = plane.getShootPosForHud(pilot, 1);
            Vec3 ray = plane.getShootDirectionForHud(pilot, 1);
            BlockPos center = BlockPos.containing(from.add(ray.normalize().scale(80)));
            for (int x = -10; x <= 10; x++) for (int y = -10; y <= 10; y++) {
                BlockPos pos = center.offset(x, y, 0);
                if (!pilot.serverLevel().hasChunkAt(pos)) return finish("target chunk not loaded");
                changed.putIfAbsent(pos, pilot.serverLevel().getBlockState(pos));
                pilot.serverLevel().setBlockAndUpdate(pos, Blocks.BEDROCK.defaultBlockState());
            }
            var aim = WeaponAimResolver.resolve(pilot.level(), pilot, from, ray, 256);
            if (aim.isEmpty()) return finish("target ray missed wall");
            target = aim.get().point();
            ImpactObservations.expect(pilot.getUUID(), plane.getUUID(), target);
            plane.getGunData("MediumAGMissile").ammo.set(1);
            var fired = AircraftArmament.fire(pilot, plane, lease, "medium_agmissile", 1, target);
            result.addProperty("queued", fired.toString());
            if (fired != AircraftArmament.Result.QUEUED) return finish("launch denied");
        }
        if (ticks == 10) {
            pilot.stopRiding();
            plane.setYRot(90);
            result.addProperty("pilotLeftAfterLaunch", true);
        }
        for (var trace : ImpactObservations.owned(pilot.getUUID(), plane.getUUID())) {
            if (!trace.type.equals("superbwarfare:wire_guide_missile") || trace.hit == null) continue;
            double error = target.distanceTo(trace.hit);
            boolean marked = trace.entity.getPersistentData().getBoolean(ManualMissileShots.LOCKED_WIRE);
            result.addProperty("impactError", error);
            result.addProperty("markedHoming", marked);
            result.addProperty("pass", ticks >= 10 && marked && error <= 3);
            return finish(null);
        }
        return ticks >= 200 && finish("no confirmed impact");
    }

    private boolean finish(String error) {
        if (error != null) result.addProperty("error", error);
        for (var entry : changed.entrySet()) pilot.serverLevel().setBlockAndUpdate(entry.getKey(), entry.getValue());
        AircraftController.release(pilot, lease);
        pilot.stopRiding();
        plane.discard();
        return true;
    }
    JsonObject result() { return result; }
}
