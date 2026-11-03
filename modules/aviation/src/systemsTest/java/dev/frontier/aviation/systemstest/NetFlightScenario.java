package dev.frontier.aviation.systemstest;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.google.gson.JsonObject;
import dev.frontier.aviation.AircraftProfile;
import dev.frontier.aviation.api.AircraftArmament;
import dev.frontier.aviation.api.AircraftController;
import dev.frontier.aviation.control.ControlLease;
import dev.frontier.aviation.control.ModeRequest;
import dev.frontier.aviation.control.PilotCommand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;

/**
 * What a nearby player receives while another pilot's F-16 flies past: packet bytes per
 * second by type, first in level flight, then while it fires the cannon.
 */
final class NetFlightScenario {
    static final int TIMEOUT_TICKS = 600;
    private static final int SETTLE = 60, CRUISE = 200, FIRING = 100;
    private final ServerPlayer observer;
    private final VehicleEntity plane;
    private final ArmorStand pilot;
    private final ControlLease lease;
    private final JsonObject result = new JsonObject();
    private int ticks;

    NetFlightScenario(ServerPlayer observer) {
        this.observer = observer;
        var level = observer.serverLevel();
        plane = AircraftProfile.F16.entity.get().create(level);
        pilot = EntityType.ARMOR_STAND.create(level);
        if (plane == null || pilot == null) throw new IllegalStateException("net fixture creation failed");
        Entity body = plane;
        int ground = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, observer.getBlockX(), observer.getBlockZ());
        body.moveTo(observer.getX(), ground + 80, observer.getZ(), 0, 0);
        plane.setEnergy(plane.getMaxEnergy());
        plane.setInvulnerable(true);
        pilot.setInvulnerable(true);
        pilot.setNoGravity(true);
        if (!level.addFreshEntity(plane) || !level.addFreshEntity(pilot) || !pilot.startRiding(body, true))
            throw new IllegalStateException("net seat failed");
        lease = AircraftController.acquire(pilot, plane).orElseThrow();
        result.addProperty("pass", false);
    }

    boolean tick() {
        ticks++;
        Entity body = plane;
        // The observer keeps pace 40 blocks to the side, as a wingman would.
        if (ticks % 5 == 1) observer.teleportTo(body.getX() + 40, body.getY(), body.getZ());
        float pitch = Math.max(-1, Math.min(1, -body.getXRot() / 20f));
        AircraftController.submit(pilot, lease, new PilotCommand(ticks, pitch, 0, 0, 1, false, false, false,
            ticks == 1 ? ModeRequest.ENABLE : ModeRequest.KEEP, ModeRequest.KEEP, ModeRequest.KEEP));
        if (ticks == 5) AircraftArmament.select(pilot, plane, lease, "cannon");
        if (ticks == SETTLE) PacketBytes.start();
        if (ticks == SETTLE + CRUISE) {
            PacketBytes.stop();
            result.add("cruise", PacketBytes.summary(CRUISE / 20.0));
            PacketBytes.start();
        }
        if (ticks > SETTLE + CRUISE && ticks <= SETTLE + CRUISE + FIRING) {
            AircraftArmament.fire(pilot, plane, lease, "cannon", 1, null);
            plane.getGunData("Cannon").ammo.set(500);
        }
        if (ticks < SETTLE + CRUISE + FIRING) return false;
        PacketBytes.stop();
        result.add("firing", PacketBytes.summary(FIRING / 20.0));
        result.addProperty("pass", true);
        AircraftController.release(pilot, lease);
        pilot.discard();
        body.discard();
        return true;
    }

    JsonObject result() { return result; }
}
