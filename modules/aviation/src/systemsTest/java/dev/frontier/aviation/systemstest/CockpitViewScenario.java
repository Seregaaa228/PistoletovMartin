package dev.frontier.aviation.systemstest;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.google.gson.JsonObject;
import dev.frontier.aviation.AircraftProfile;
import dev.frontier.aviation.api.AircraftArmament;
import dev.frontier.aviation.api.AircraftController;
import dev.frontier.aviation.control.ControlLease;
import dev.frontier.aviation.control.PilotCommand;
import java.nio.file.Files;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Puts the player in each jet in turn, hovering above the ground with the cannon selected,
 * and lets the runner photograph the cockpit view, the right-mouse (aim/zoom) view and the
 * outside view, to check that no camera sits inside the airframe.
 */
final class CockpitViewScenario {
    static final int TIMEOUT_TICKS = 2400;
    private static final AircraftProfile[] JETS = {AircraftProfile.SU25, AircraftProfile.F16, AircraftProfile.J10};
    private final ServerPlayer pilot;
    private final JsonObject result = new JsonObject();
    private VehicleEntity plane;
    private ControlLease lease;
    private int index = -1, stageTicks;

    CockpitViewScenario(ServerPlayer pilot) {
        this.pilot = pilot;
        if (pilot.isPassenger()) throw new IllegalStateException("view fixture needs a free pilot");
        result.addProperty("pass", false);
        next();
    }

    private void next() {
        if (plane != null) {
            AircraftController.release(pilot, lease);
            pilot.stopRiding();
            ((Entity) plane).discard();
        }
        index++;
        stageTicks = 0;
        if (index >= JETS.length) return;
        var level = pilot.serverLevel();
        plane = JETS[index].entity.get().create(level);
        if (plane == null) throw new IllegalStateException("jet creation failed");
        Entity body = plane;
        int ground = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, pilot.getBlockX(), pilot.getBlockZ());
        body.moveTo(pilot.getX(), ground + 25, pilot.getZ(), 0, 5);
        plane.setNoGravity(true);
        plane.setInvulnerable(true);
        plane.setEnergy(plane.getMaxEnergy());
        if (!level.addFreshEntity(plane) || !pilot.startRiding(body, true)) throw new IllegalStateException("view mount failed");
        lease = AircraftController.acquire(pilot, plane).orElseThrow();
    }

    boolean tick() {
        if (index >= JETS.length) {
            result.addProperty("pass", true);
            return true;
        }
        stageTicks++;
        AircraftController.submit(pilot, lease, PilotCommand.neutral(5_000_000L + index * 10_000L + stageTicks));
        ((Entity) plane).setDeltaMovement(0, 0, 0);
        if (stageTicks == 5) AircraftArmament.select(pilot, plane, lease, "cannon");
        if (stageTicks == 30) progress("view-" + JETS[index].id);
        try {
            var done = FMLPaths.GAMEDIR.get().resolve("aircraft-view-done.txt");
            if (stageTicks > 30 && Files.isRegularFile(done)
                    && Files.readString(done).equals(System.getProperty("frontier.aircraftSystemsRunId") + ":" + JETS[index].id)) {
                result.addProperty(JETS[index].id, "captured");
                next();
            } else if (stageTicks > 700) {
                result.addProperty("error", "no capture for " + JETS[index].id);
                return true;
            }
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        return false;
    }

    private void progress(String stage) {
        var progress = new JsonObject();
        progress.addProperty("runId", System.getProperty("frontier.aircraftSystemsRunId"));
        progress.addProperty("stage", stage);
        try { Files.writeString(FMLPaths.GAMEDIR.get().resolve("aircraft-view-progress.json"), progress.toString()); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }

    JsonObject result() { return result; }
}
