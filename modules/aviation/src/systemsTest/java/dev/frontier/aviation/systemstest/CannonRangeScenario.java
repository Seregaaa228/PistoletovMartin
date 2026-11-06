package dev.frontier.aviation.systemstest;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.google.gson.JsonObject;
import dev.frontier.aviation.AircraftProfile;
import dev.frontier.aviation.api.AircraftArmament;
import dev.frontier.aviation.api.AircraftController;
import dev.frontier.aviation.control.ControlLease;
import dev.frontier.aviation.control.PilotCommand;
import dev.frontier.aviation.weapons.CannonShells;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;

/**
 * A pilot in a hovering F-16 fires the cannon at a wool wall 240 blocks ahead, outside
 * the simulation distance (where entity shells used to freeze): the wall must lose blocks.
 */
final class CannonRangeScenario {
    private static final int HALF = 14;
    private final int distance;
    /** The wall's chunk is unloaded before firing: shells must load it in the background. */
    private final boolean unloaded;
    private final ServerPlayer pilot;
    private final VehicleEntity plane;
    private final ControlLease lease;
    private final BlockPos wall;
    private final JsonObject result = new JsonObject();
    private int ticks, before, fired, start = -1;
    private final long impactsBefore = CannonShells.impacts, waitedBefore = CannonShells.waitedOut, spentBefore = CannonShells.spent;

    CannonRangeScenario(ServerPlayer pilot, int distance, boolean unloaded) {
        this.pilot = pilot;
        this.distance = distance;
        this.unloaded = unloaded;
        if (pilot.isPassenger()) throw new IllegalStateException("range fixture needs a free pilot");
        ServerLevel level = pilot.serverLevel();
        plane = AircraftProfile.F16.entity.get().create(level);
        if (plane == null) throw new IllegalStateException("F-16 creation failed");
        Entity body = plane;
        // Above the ground, not the player: an earlier scenario may leave them near the build limit.
        int ground = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, pilot.getBlockX(), pilot.getBlockZ());
        body.moveTo(pilot.getX(), ground + 40, pilot.getZ(), 0, 0);
        plane.setNoGravity(true);
        plane.setInvulnerable(true);
        plane.setEnergy(plane.getMaxEnergy());
        if (!level.addFreshEntity(plane) || !pilot.startRiding(body, true)) throw new IllegalStateException("range mount failed");
        lease = AircraftController.acquire(pilot, plane).orElseThrow();
        wall = BlockPos.containing(body.getX(), body.getY() + 2, body.getZ() + distance);
        result.addProperty("wallDistance", distance);
        if (unloaded) build(level);
        result.addProperty("pass", false);
    }

    /** The wall goes up once the player's view has loaded its chunk, like a house in sight. */
    private void build(ServerLevel level) {
        for (int dx = -HALF; dx <= HALF; dx++) for (int dy = -HALF; dy <= HALF; dy++)
            level.setBlockAndUpdate(wall.offset(dx, dy, 0), Blocks.WHITE_WOOL.defaultBlockState());
        before = solid(level);
        result.addProperty("wallEntityTicking", level.isPositionEntityTicking(wall));
        result.addProperty("loadedAfterTicks", ticks);
        if (!unloaded) start = ticks;
    }

    boolean tick() {
        ticks++;
        Entity body = plane;
        AircraftController.submit(pilot, lease, PilotCommand.neutral(2_000_000L + ticks));
        body.setDeltaMovement(0, 0, 0);
        if (ticks == 5 && !AircraftArmament.select(pilot, plane, lease, "cannon")) return finish("cannon selection failed");
        if (start < 0) {
            boolean loaded = pilot.serverLevel().isLoaded(wall);
            if (!unloaded && loaded) build(pilot.serverLevel());
            else if (unloaded && !loaded) {
                start = ticks;
                result.addProperty("unloadedAfterTicks", ticks);
            } else if (ticks > 600) return finish(unloaded ? "wall chunk never unloaded" : "wall chunk never loaded");
            return false;
        }
        int t = ticks - start;
        if (t >= 10 && t < 70
                && AircraftArmament.fire(pilot, plane, lease, "cannon", 1, null) == AircraftArmament.Result.QUEUED) fired++;
        if (t == 40) result.addProperty("shellsInFlight", CannonShells.flying(pilot.serverLevel()));
        if (t < 130) return false;
        int lost = before - solid(pilot.serverLevel());
        result.addProperty("shotsQueued", fired);
        result.addProperty("impacts", CannonShells.impacts - impactsBefore);
        result.addProperty("waitedOut", CannonShells.waitedOut - waitedBefore);
        result.addProperty("spent", CannonShells.spent - spentBefore);
        result.addProperty("wallLoadedAtEnd", pilot.serverLevel().isLoaded(wall));
        result.addProperty("wallBlocksLost", lost);
        result.addProperty("pass", fired > 10 && lost > 0 && (unloaded || !result.get("wallEntityTicking").getAsBoolean()));
        return finish(null);
    }

    private boolean finish(String error) {
        if (error != null) result.addProperty("error", error);
        AircraftController.release(pilot, lease);
        pilot.stopRiding();
        ((Entity) plane).discard();
        return true;
    }

    private int solid(ServerLevel level) {
        int count = 0;
        for (int dx = -HALF; dx <= HALF; dx++) for (int dy = -HALF; dy <= HALF; dy++)
            if (!level.getBlockState(wall.offset(dx, dy, 0)).isAir()) count++;
        return count;
    }

    JsonObject result() { return result; }
}
