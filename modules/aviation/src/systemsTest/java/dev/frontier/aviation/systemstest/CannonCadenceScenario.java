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

/**
 * A real held fire button on the native cannon. The server-side RPM ceiling
 * must leave SBW's own 1200 RPM client cadence untouched.
 */
final class CannonCadenceScenario {
    private static final String SHELL = "superbwarfare:small_cannon_shell";
    private final ServerPlayer pilot;
    private final VehicleEntity plane;
    private final ControlLease lease;
    private final JsonObject result = new JsonObject();
    private final Map<BlockPos, BlockState> platform = new LinkedHashMap<>();
    private int ticks, spawnsBefore, firstShot = -1, lastShot = -1, lastCount;
    private long firstNanos, lastNanos;

    CannonCadenceScenario(ServerPlayer pilot) {
        this.pilot = pilot;
        if (pilot.isPassenger()) throw new IllegalStateException("cadence needs an unmounted test player");
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
        if (!level.addFreshEntity(plane) || !pilot.startRiding(plane, true)) throw new IllegalStateException("cadence mount failed");
        lease = AircraftController.acquire(pilot, plane).orElseThrow();
        if (!AircraftArmament.select(pilot, plane, lease, "cannon")) throw new IllegalStateException("cannon selection failed");
        plane.getGunData("Cannon").ammo.set(500);
        spawnsBefore = ProjectileObservations.count(SHELL);
        result.addProperty("pass", false);
    }

    boolean tick() {
        ticks++;
        // The fixture's own lease only selects the weapon; the real client then
        // acquires its own epoch, which the trigger and launcher require.
        if (pilot.getVehicle() != plane) return finish("pilot dismounted");
        if (ticks == 20) progress("cadence-ready");
        int shots = ProjectileObservations.count(SHELL) - spawnsBefore;
        if (shots > lastCount) {
            if (firstShot < 0) { firstShot = ticks; firstNanos = System.nanoTime(); }
            lastShot = ticks; lastNanos = System.nanoTime(); lastCount = shots;
        }
        // Settle once fire has stopped for a full second.
        if (firstShot >= 0 && ticks - lastShot > 20) {
            int span = lastShot - firstShot + 1;
            result.addProperty("rounds", shots);
            result.addProperty("fireSpanTicks", span);
            double seconds = (lastNanos - firstNanos) / 1e9;
            result.addProperty("fireSpanSeconds", seconds);
            result.addProperty("heat", plane.getWeaponHeat("Cannon"));
            // A two-second hold at native 1200 RPM is about 40 rounds. Server
            // ticks may lag under load, so judge the wall-clock rate, not ticks.
            double rate = seconds > 0 ? (shots - 1) / seconds : 0;
            result.addProperty("roundsPerSecond", rate);
            boolean pass = shots >= 32 && shots <= 50 && rate >= 17 && rate <= 23;
            result.addProperty("pass", pass);
            return finish(pass ? null : "cannon cadence differs from native 1200 RPM");
        }
        return ticks >= 400 && finish("no cannon fire observed");
    }

    private void progress(String stage) {
        var progress = new JsonObject();
        progress.addProperty("runId", System.getProperty("frontier.aircraftSystemsRunId"));
        progress.addProperty("stage", stage);
        try { Files.writeString(FMLPaths.GAMEDIR.get().resolve("aircraft-cadence-progress.json"), progress.toString()); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }

    private boolean finish(String error) {
        if (error != null) { result.addProperty("pass", false); result.addProperty("error", error); }
        result.addProperty("ticks", ticks);
        AircraftController.release(pilot, lease);
        pilot.stopRiding(); plane.discard();
        platform.forEach((pos, state) -> pilot.serverLevel().setBlockAndUpdate(pos, state));
        return true;
    }

    JsonObject result() { return result; }
}
