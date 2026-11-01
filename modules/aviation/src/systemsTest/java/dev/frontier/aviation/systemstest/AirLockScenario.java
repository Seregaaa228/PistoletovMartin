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
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Real seeker key + click on a fast crossing aircraft, through the client
 * trigger, network message and server launcher, without a server fire shortcut.
 * The target moves three blocks per tick across the seeker cone, so a client's
 * interpolated position is several blocks behind the server's when it fires.
 */
final class AirLockScenario {
    private static final double SPEED = 3, HALF_SWEEP = 20, AHEAD = 100, ABOVE = 8;
    private static final String MISSILE = "superbwarfare:ru_9m100_missile";
    private final ServerPlayer pilot;
    private final VehicleEntity plane, target;
    private final ControlLease lease;
    private final JsonObject result = new JsonObject();
    private final Map<BlockPos, BlockState> platform = new LinkedHashMap<>();
    private final Vec3 centre;
    private int ticks, direction = 1, spawnsBefore;
    private double offset;

    AirLockScenario(ServerPlayer pilot) {
        this.pilot = pilot;
        if (pilot.isPassenger()) throw new IllegalStateException("air lock needs an unmounted test player");
        var level = pilot.serverLevel();
        var origin = BlockPos.containing(pilot.getX(), level.getMinBuildHeight() + 128, pilot.getZ());
        for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) {
            var pos = origin.offset(x, -1, z);
            platform.put(pos, level.getBlockState(pos));
            level.setBlockAndUpdate(pos, Blocks.BEDROCK.defaultBlockState());
        }
        plane = AircraftProfile.F16.entity.get().create(level);
        target = AircraftProfile.F16.entity.get().create(level);
        if (plane == null || target == null) throw new IllegalStateException("F16 creation failed");
        plane.moveTo(origin.getX() + .5, origin.getY() + .1, origin.getZ() + .5, 0, 0);
        plane.setEnergy(plane.getMaxEnergy());
        plane.setInvulnerable(true);
        centre = new Vec3(origin.getX() + .5, origin.getY() + ABOVE, origin.getZ() + .5 + AHEAD);
        target.moveTo(centre.x, centre.y, centre.z, 0, 0);
        target.setNoGravity(true);
        target.setInvulnerable(true);
        if (!level.addFreshEntity(plane) || !level.addFreshEntity(target) || !pilot.startRiding(plane, true))
            throw new IllegalStateException("air lock setup failed");
        lease = AircraftController.acquire(pilot, plane).orElseThrow();
        if (!AircraftArmament.select(pilot, plane, lease, "aamissile")) throw new IllegalStateException("AAM selection failed");
        plane.getGunData("AAMissile").ammo.set(2);
        spawnsBefore = ProjectileObservations.count(MISSILE);
        result.addProperty("target", target.getUUID().toString());
        result.addProperty("targetSpeedBlocksPerTick", SPEED);
        result.addProperty("pass", false);
    }

    boolean tick() {
        ticks++;
        // The fixture's own lease only selects the weapon; the real client then
        // acquires its own epoch, which the trigger and launcher require.
        if (pilot.getVehicle() != plane) return finish("pilot dismounted");
        if (!target.isAlive()) return finish("target removed before launch");
        // Triangle sweep across the cone; delta movement keeps native client
        // interpolation honest about the speed it is trailing.
        offset += direction * SPEED;
        if (Math.abs(offset) >= HALF_SWEEP) { offset = Math.copySign(HALF_SWEEP, offset); direction = -direction; }
        target.setDeltaMovement(new Vec3(direction * SPEED, 0, 0));
        target.setPos(centre.x + offset, centre.y, centre.z);
        if (ticks == 40) progress("lock-ready");
        int rounds = plane.getGunData("AAMissile").ammo.get();
        int spawns = ProjectileObservations.count(MISSILE) - spawnsBefore;
        if (rounds < 2 && spawns >= 1) {
            result.addProperty("roundsAfter", rounds);
            result.addProperty("nativeMissileSpawns", spawns);
            result.addProperty("launchTick", ticks);
            result.addProperty("pass", rounds == 1 && spawns == 1);
            return finish(rounds == 1 && spawns == 1 ? null : "unexpected launch count");
        }
        return ticks >= 400 && finish("no air-to-air launch on the locked crossing target");
    }

    private void progress(String stage) {
        var progress = new JsonObject();
        progress.addProperty("runId", System.getProperty("frontier.aircraftSystemsRunId"));
        progress.addProperty("stage", stage);
        try { Files.writeString(FMLPaths.GAMEDIR.get().resolve("aircraft-airlock-progress.json"), progress.toString()); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }

    private boolean finish(String error) {
        if (error != null) { result.addProperty("pass", false); result.addProperty("error", error); }
        result.addProperty("ticks", ticks);
        AircraftController.release(pilot, lease);
        pilot.stopRiding(); plane.discard(); target.discard();
        platform.forEach((pos, state) -> pilot.serverLevel().setBlockAndUpdate(pos, state));
        return true;
    }

    JsonObject result() { return result; }
}
