package dev.frontier.aviation.systemstest;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.frontier.aviation.AircraftProfile;
import dev.frontier.aviation.api.AircraftController;
import dev.frontier.aviation.control.ControlLease;
import dev.frontier.aviation.control.ModeRequest;
import dev.frontier.aviation.control.PilotCommand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;

/**
 * Full throttle, then a pull-up, level, a push-over and level again, flown by a
 * fixture pilot through the real control API. Records speed, pitch and altitude each
 * tick to show how manoeuvres change the speed.
 */
final class ManeuverTraceScenario {
    static final int TIMEOUT_TICKS = 900;
    private final ServerPlayer observer;
    private final VehicleEntity plane;
    private final ArmorStand pilot;
    private final ControlLease lease;
    private final JsonObject result = new JsonObject();
    private final JsonArray speed = new JsonArray(), pitch = new JsonArray(), altitude = new JsonArray(), stick = new JsonArray();
    private Vec3 last;
    private float peakNoseUp;
    private double slowest = Double.MAX_VALUE;
    private int ticksBelowStall;
    private final float stallKmh;
    private int ticks;

    ManeuverTraceScenario(ServerPlayer observer, AircraftProfile profile) {
        this.observer = observer;
        var level = observer.serverLevel();
        plane = profile.entity.get().create(level);
        pilot = EntityType.ARMOR_STAND.create(level);
        if (plane == null || pilot == null) throw new IllegalStateException("maneuver fixture creation failed");
        result.addProperty("aircraft", profile.id);
        stallKmh = dev.frontier.aviation.aircraft.AircraftCatalog.performance(profile.definition().typeId())
            .map(dev.frontier.aviation.aircraft.AircraftPerformance::stallKmh).orElse(0f);
        Entity body = plane;
        body.moveTo(observer.getX(), observer.getY() + 320, observer.getZ(), 0, 0);
        plane.setEnergy(plane.getMaxEnergy());
        body.setDeltaMovement(0, 0, 2.9);
        pilot.setInvulnerable(true);
        pilot.setNoGravity(true);
        if (!level.addFreshEntity(plane) || !level.addFreshEntity(pilot) || !pilot.startRiding(body, true))
            throw new IllegalStateException("maneuver seat failed");
        lease = AircraftController.acquire(pilot, plane).orElseThrow();
        result.addProperty("pass", false);
    }

    boolean tick() {
        ticks++;
        Entity body = plane;
        if (ticks % 10 == 1) observer.teleportTo(body.getX() + 20, body.getY() + 10, body.getZ() - 20);
        // 0-199 level (the pilot holds the horizon), 200-239 pull up, 240-339 hands off
        // (a slow jet must stall and drop its nose), 340-399 push over, 400-599 level.
        boolean level = ticks < 200 || ticks >= 400;
        float axis = ticks >= 200 && ticks < 240 ? -.6f : ticks >= 340 && ticks < 400 ? .6f
            : level ? Math.max(-1, Math.min(1, -body.getXRot() / 20f)) : 0;
        if (ticks >= 240 && ticks < 340) {
            peakNoseUp = Math.min(peakNoseUp, body.getXRot());
            if (last != null) {
                double kmh = body.position().distanceTo(last) * 72;
                slowest = Math.min(slowest, kmh);
                if (kmh < stallKmh) ticksBelowStall++;
            }
            if (ticks == 339) result.addProperty("pitchAfterHandsOff", Math.round(body.getXRot()));
        }
        var command = new PilotCommand(ticks, axis, 0, 0, 1, false, false, false,
            ticks == 1 ? ModeRequest.ENABLE : ModeRequest.KEEP, ModeRequest.KEEP, ModeRequest.KEEP);
        AircraftController.submit(pilot, lease, command);
        Vec3 now = body.position();
        if (last != null) speed.add(Math.round(now.distanceTo(last) * 720) / 10.0);
        last = now;
        pitch.add(Math.round(body.getXRot()));
        altitude.add(Math.round(body.getY()));
        stick.add(axis);
        if (ticks < 600 && !body.isRemoved()) return false;
        result.add("kmh", speed);
        result.add("pitch", pitch);
        result.add("altitude", altitude);
        result.add("stick", stick);
        result.addProperty("peakNoseUp", Math.round(peakNoseUp));
        result.addProperty("survived", !body.isRemoved());
        result.addProperty("slowestHandsOffKmh", Math.round(slowest));
        result.addProperty("stallKmh", stallKmh);
        // Pulled steeply up and left alone, a jet that stays below its stall speed must let
        // its nose fall; one with the thrust to keep climbing need not. Not crashed.
        // Two seconds below stall speed; a brief dip that thrust recovers is not a stall.
        boolean stalled = ticksBelowStall >= 40;
        result.addProperty("ticksBelowStall", ticksBelowStall);
        boolean noseFell = result.has("pitchAfterHandsOff") && result.get("pitchAfterHandsOff").getAsInt() > peakNoseUp + 30;
        result.addProperty("pass", speed.size() > 500 && !body.isRemoved() && (!stalled || noseFell));
        AircraftController.release(pilot, lease);
        pilot.discard();
        body.discard();
        return true;
    }

    JsonObject result() { return result; }
}
