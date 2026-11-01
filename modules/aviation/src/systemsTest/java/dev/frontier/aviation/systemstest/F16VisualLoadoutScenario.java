package dev.frontier.aviation.systemstest;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.google.gson.JsonObject;
import dev.frontier.aviation.AircraftProfile;
import dev.frontier.aviation.api.AircraftController;
import dev.frontier.aviation.control.ControlLease;
import dev.frontier.aviation.control.PilotCommand;
import dev.frontier.aviation.control.ModeRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;
import dev.sbw.deepstrike.vehicle.Su24mEntity;
import java.nio.file.Files;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLPaths;

/** Disposable, real F-16 loadout arranged for external side/rear screenshots. */
final class F16VisualLoadoutScenario {
    private final ServerPlayer pilot;
    private final VehicleEntity plane;
    private final ArmorStand dummy;
    private final Vec3 anchor;
    private final ControlLease lease;
    private final JsonObject result = new JsonObject();
    private int ticks;

    F16VisualLoadoutScenario(ServerPlayer pilot) {
        this.pilot = pilot;
        if (pilot.isPassenger()) throw new IllegalStateException("visual fixture needs a free observer");
        plane = AircraftProfile.F16.entity.get().create(pilot.serverLevel());
        if (plane == null || !((Object) plane instanceof Su24mEntity))
            throw new IllegalStateException("F-16 visual fixture creation failed");
        plane.moveTo(0.5, -50, 0.5, 0, 0);
        anchor = plane.position();
        dummy = EntityType.ARMOR_STAND.create(pilot.serverLevel());
        if (dummy == null) throw new IllegalStateException("cannot create dummy pilot");
        dummy.moveTo(anchor.x, anchor.y, anchor.z, 0, 0);
        dummy.setNoGravity(true);
        dummy.setInvulnerable(true);
        plane.setNoGravity(true);
        plane.setInvulnerable(true);
        plane.setEnergy(plane.getMaxEnergy());

        // Distinct native stations/types: large A/G, medium A/G, large A/A,
        // and light A/A. These are disposable magazine counts in a fresh world.
        setAmmo("Missile", 1);
        setAmmo("MediumAGMissile", 1);
        setAmmo("AAMissile", 1);
        setAmmo("LightAAMissile", 2);
        if (!pilot.serverLevel().addFreshEntity(plane) || !pilot.serverLevel().addFreshEntity(dummy) || !dummy.startRiding(plane, true))
            throw new IllegalStateException("F-16 visual fixture spawn failed");
        lease = AircraftController.acquire(dummy, plane)
            .orElseThrow(() -> new IllegalStateException("F-16 observer lease unavailable"));
        result.addProperty("vehicle", "frontier_aviation:f16");
        result.addProperty("payload", "large A/G, medium A/G, large A/A, light A/A");
        result.addProperty("outsideObserver", true);
        result.addProperty("pass", false);
    }

    private void setAmmo(String name, int amount) {
        var gun = plane.getGunData(name);
        if (gun == null) throw new IllegalStateException("native F-16 magazine missing: " + name);
        gun.ammo.set(amount);
    }

    boolean tick() {
        ticks++;
        if (plane.isRemoved()) return finish("fixture aircraft removed");
        plane.setPos(anchor);
        plane.setDeltaMovement(Vec3.ZERO);
        boolean braking = Files.exists(FMLPaths.GAMEDIR.get().resolve("aircraft-visual-brake.txt"));
        AircraftController.submit(dummy, lease, new PilotCommand(ticks, 0, 0, 0, 0, braking, false, false, ModeRequest.KEEP, ModeRequest.KEEP, ModeRequest.KEEP));
        if (ticks == 10) writeStage("loaded-ready");
        if (ticks >= 380) return finish("visual screenshot acknowledgement timeout");
        try {
            var captured = FMLPaths.GAMEDIR.get().resolve("aircraft-visual-captured.txt");
            if (!Files.isRegularFile(captured)) return false;
            String ack = Files.readString(captured).trim();
            if (!ack.startsWith(System.getProperty("frontier.aircraftSystemsRunId") + ":")) return false;
            result.addProperty("capturedStages", ack.substring(ack.indexOf(':') + 1));
            result.addProperty("brakeTelemetry", AircraftController.observe(plane).orElseThrow().telemetry().brake());
            result.addProperty("heading", plane.getYRot());
            result.addProperty("position", plane.position().toString());
            boolean side = ack.contains("side");
            boolean rear = ack.contains("rear");
            boolean brake = ack.contains("brake");
            var probeFile = FMLPaths.GAMEDIR.get().resolve("aircraft-visual-client.json");
            var probe = Files.isRegularFile(probeFile) ? com.google.gson.JsonParser.parseString(Files.readString(probeFile)).getAsJsonObject() : null;
            boolean clientBrake = probe != null && probe.get("visualBrake").getAsBoolean() && probe.get("brakePose").getAsFloat() > .8f;
            result.addProperty("clientBrakePoseObserved", clientBrake);
            result.addProperty("pass", side && rear && brake && clientBrake && result.get("brakeTelemetry").getAsBoolean());
            if (!result.get("pass").getAsBoolean()) result.addProperty("error", "missing screenshot or active brake state");
            return finish(null);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void writeStage(String stage) {
        var progress = new JsonObject();
        progress.addProperty("runId", System.getProperty("frontier.aircraftSystemsRunId"));
        progress.addProperty("stage", stage);
        try {
            Files.writeString(FMLPaths.GAMEDIR.get().resolve("aircraft-visual-progress.json"), progress.toString());
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private boolean finish(String error) {
        if (error != null) {
            result.addProperty("error", error);
            result.addProperty("pass", false);
        }
        result.addProperty("loadedStations", dev.frontier.aviation.ordnance.F16Loadout.visualBits((Su24mEntity) plane));
        AircraftController.release(dummy, lease);
        dummy.stopRiding(); dummy.discard();
        plane.discard();
        return true;
    }

    JsonObject result() { return result; }
}
