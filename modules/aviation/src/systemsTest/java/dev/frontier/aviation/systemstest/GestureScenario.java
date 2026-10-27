package dev.frontier.aviation.systemstest;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.google.gson.JsonObject;
import dev.frontier.aviation.AircraftProfile;
import dev.frontier.aviation.api.AircraftArmament;
import dev.frontier.aviation.api.AircraftController;
import dev.frontier.aviation.control.ControlLease;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.loading.FMLPaths;

/** Real mouse -> client gesture -> network -> native queue, without a server fire shortcut. */
final class GestureScenario {
    private final ServerPlayer pilot;
    private final VehicleEntity plane;
    private final ControlLease lease;
    private final JsonObject result = new JsonObject();
    private final Map<BlockPos, BlockState> platform = new LinkedHashMap<>();
    private int ticks, stage, reloadAt;

    GestureScenario(ServerPlayer pilot) {
        this.pilot = pilot;
        if (pilot.isPassenger()) throw new IllegalStateException("gesture needs an unmounted test player");
        ImpactObservations.clear();
        var level = pilot.serverLevel();
        var origin = BlockPos.containing(pilot.getX(), level.getMinBuildHeight() + 128, pilot.getZ());
        for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) {
            var pos = origin.offset(x, -1, z);
            platform.put(pos, level.getBlockState(pos));
            level.setBlockAndUpdate(pos, Blocks.BEDROCK.defaultBlockState());
        }
        plane = AircraftProfile.F16.entity.get().create(level);
        if (plane == null) throw new IllegalStateException("F16 creation failed");
        plane.moveTo(origin.getX() + .5, origin.getY() + .1, origin.getZ() + .5, 0, 0);
        plane.setEnergy(plane.getMaxEnergy());
        plane.setInvulnerable(true);
        if (!level.addFreshEntity(plane) || !pilot.startRiding(plane, true)) throw new IllegalStateException("gesture mount failed");
        lease = AircraftController.acquire(pilot, plane).orElseThrow();
        if (!AircraftArmament.select(pilot, plane, lease, "missile")) throw new IllegalStateException("missile selection failed");
        plane.getGunData("Missile").ammo.set(2);
        result.addProperty("pass", false);
    }

    boolean tick() {
        ticks++;
        if (!AircraftController.owns(pilot, plane, lease)) return finish("real mouse pilot lost lease");
        var shots = ImpactObservations.owned(pilot.getUUID(), plane.getUUID());
        if (ticks == 10) progress("single-ready");
        if (stage == 0 && shots.size() == 1) {
            if (plane.getGunData("Missile").ammo.get() != 1) return finish("short click did not consume one round");
            result.addProperty("singleRoundsAfter", 1);
            stage = 1; reloadAt = ticks + 25;
        }
        if (stage == 1 && ticks >= reloadAt) {
            plane.getGunData("Missile").ammo.set(2); // Explicit disposable fixture refill between two independent gestures.
            progress("pair-ready"); stage = 2;
        }
        if (stage == 2 && shots.size() >= 3) {
            boolean pass = shots.size() == 3 && plane.getGunData("Missile").ammo.get() == 0
                && shots.stream().allMatch(trace -> trace.type.equals("superbwarfare:agm_65") && trace.direct)
                && shots.get(1).born == shots.get(2).born;
            result.addProperty("nativeMissileSpawns", shots.size());
            result.addProperty("pairSameTick", shots.get(1).born == shots.get(2).born);
            result.addProperty("pairRoundsAfter", plane.getGunData("Missile").ammo.get());
            result.addProperty("allMarkedDirect", shots.stream().allMatch(trace -> trace.direct));
            result.addProperty("pass", pass);
            return finish(pass ? null : "native gesture result mismatch");
        }
        return ticks >= 300 && finish("mouse gesture timeout");
    }

    private void progress(String stage) {
        var progress = new JsonObject();
        progress.addProperty("runId", System.getProperty("frontier.aircraftSystemsRunId"));
        progress.addProperty("stage", stage);
        try { Files.writeString(FMLPaths.GAMEDIR.get().resolve("aircraft-gesture-progress.json"), progress.toString()); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }

    private boolean finish(String error) {
        if (error != null) { result.addProperty("pass", false); result.addProperty("error", error); }
        for (var trace : ImpactObservations.owned(pilot.getUUID(), plane.getUUID())) trace.entity.discard();
        AircraftController.release(pilot, lease);
        pilot.stopRiding(); plane.discard();
        platform.forEach((pos, state) -> pilot.serverLevel().setBlockAndUpdate(pos, state));
        return true;
    }

    JsonObject result() { return result; }
}
