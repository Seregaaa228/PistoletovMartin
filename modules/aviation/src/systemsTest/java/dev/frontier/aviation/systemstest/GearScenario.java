package dev.frontier.aviation.systemstest;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.google.gson.JsonObject;
import dev.frontier.aviation.AircraftProfile;
import dev.frontier.aviation.api.AircraftController;
import dev.frontier.aviation.control.ControlLease;
import java.nio.file.Files;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLPaths;

/** Actual G key while airborne; native animation progress plus a client screenshot. */
final class GearScenario {
    private final ServerPlayer pilot;
    private final VehicleEntity plane;
    private final ControlLease lease;
    private final JsonObject result = new JsonObject();
    private int ticks;

    GearScenario(ServerPlayer pilot) {
        this.pilot = pilot;
        if (pilot.isPassenger()) throw new IllegalStateException("gear fixture needs a free player");
        plane = AircraftProfile.F16.entity.get().create(pilot.level());
        if (plane == null) throw new IllegalStateException("F16 creation failed");
        plane.moveTo(pilot.getX(), pilot.level().getMaxBuildHeight() - 64, pilot.getZ(), 0, 0);
        plane.setEnergy(plane.getMaxEnergy());
        if (!pilot.serverLevel().addFreshEntity(plane) || !pilot.startRiding(plane, true))
            throw new IllegalStateException("gear mount failed");
        lease = AircraftController.acquire(pilot, plane).orElseThrow();
        result.addProperty("initialGearDown", AircraftController.observe(plane).orElseThrow().telemetry().state().gearDown());
        result.addProperty("pass", false);
    }

    boolean tick() {
        ticks++;
        if (!AircraftController.owns(pilot, plane, lease)) return finish("gear pilot lost lease");
        if (ticks == 10) {
            var progress = new JsonObject();
            progress.addProperty("runId", System.getProperty("frontier.aircraftSystemsRunId"));
            progress.addProperty("stage", "gear-ready");
            try { Files.writeString(FMLPaths.GAMEDIR.get().resolve("aircraft-gear-progress.json"), progress.toString()); }
            catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        }
        if (ticks >= 180) return finish("gear screenshot acknowledgement timeout");
        try {
            var captured = FMLPaths.GAMEDIR.get().resolve("aircraft-gear-captured.txt");
            if (!Files.isRegularFile(captured) || !Files.readString(captured)
                    .equals(System.getProperty("frontier.aircraftSystemsRunId"))) return false;
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        boolean down = AircraftController.observe(plane).orElseThrow().telemetry().state().gearDown();
        float nativeProgress = ((dev.sbw.deepstrike.vehicle.Su24mEntity) plane).landingGearProgress(1);
        result.addProperty("gearDownAfterG", down);
        result.addProperty("nativeRetractionProgress", nativeProgress);
        result.addProperty("airborne", !plane.onGround());
        result.addProperty("pass", result.get("initialGearDown").getAsBoolean() && !down
            && nativeProgress >= .99f && !plane.onGround());
        return finish(null);
    }

    private boolean finish(String error) {
        if (error != null) { result.addProperty("error", error); result.addProperty("pass", false); }
        AircraftController.release(pilot, lease);
        pilot.stopRiding(); plane.discard();
        return true;
    }

    JsonObject result() { return result; }
}
