package dev.frontier.aviation.systemstest;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.google.gson.JsonObject;
import dev.frontier.aviation.AircraftProfile;
import dev.frontier.aviation.api.AircraftController;
import dev.frontier.aviation.control.ControlLease;
import dev.frontier.aviation.control.ModeRequest;
import dev.frontier.aviation.control.PilotCommand;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;

/**
 * Another pilot's jet as a player sees it: an NPC rolls an F-16 at a steady rate while
 * the player's client records the roll it draws each client tick. Every tick should
 * advance by a similar step; ticks that stand still while the next doubles are the
 * visible shudder of a remote aircraft.
 */
final class ObserverRollScenario {
    private final ServerPlayer observer;
    private final VehicleEntity plane;
    private final ArmorStand pilot;
    private final ControlLease lease;
    private final JsonObject result = new JsonObject();
    private int ticks;

    ObserverRollScenario(ServerPlayer observer) {
        this.observer = observer;
        var level = observer.serverLevel();
        plane = AircraftProfile.F16.entity.get().create(level);
        pilot = EntityType.ARMOR_STAND.create(level);
        if (plane == null || pilot == null) throw new IllegalStateException("observer fixture creation failed");
        Entity body = plane;
        body.moveTo(observer.getX() + 20, observer.getY() + 20, observer.getZ() + 20, 0, 0);
        plane.setNoGravity(true);
        plane.setInvulnerable(true);
        plane.setEnergy(plane.getMaxEnergy());
        pilot.setInvulnerable(true);
        pilot.setNoGravity(true);
        if (!level.addFreshEntity(plane) || !level.addFreshEntity(pilot) || !pilot.startRiding(body, true))
            throw new IllegalStateException("observer seat failed");
        lease = AircraftController.acquire(pilot, plane).orElseThrow();
        result.addProperty("pass", false);
    }

    boolean tick() {
        ticks++;
        ((Entity) plane).setDeltaMovement(0, 0, 0);
        float roll = ticks >= 40 && ticks < 140 ? .5f : 0;
        AircraftController.submit(pilot, lease, new PilotCommand(ticks, 0, 0, roll, 0, false, false, false,
            ticks == 1 ? ModeRequest.ENABLE : ModeRequest.KEEP, ModeRequest.KEEP, ModeRequest.KEEP));
        if (ticks == 60) Recorder.start(((Entity) plane).getId());
        if (ticks < 130) return false;
        Recorder.stop();
        List<Float> steps = Recorder.steps();
        double mean = steps.stream().mapToDouble(Math::abs).average().orElse(0);
        long still = steps.stream().filter(step -> Math.abs(step) < .25 * mean).count();
        long doubled = steps.stream().filter(step -> Math.abs(step) > 1.6 * mean).count();
        result.addProperty("clientTicks", steps.size());
        result.addProperty("meanRollStep", Math.round(mean * 100) / 100.0);
        result.addProperty("stillTicks", still);
        result.addProperty("doubledTicks", doubled);
        // A steady roll seen remotely: hardly any frozen or double steps.
        result.addProperty("pass", steps.size() > 40 && mean > .2 && still + doubled <= steps.size() / 10);
        AircraftController.release(pilot, lease);
        pilot.discard();
        ((Entity) plane).discard();
        return true;
    }

    JsonObject result() { return result; }

    /** Client thread: roll of the watched aircraft each client tick. */
    static final class Recorder {
        private static volatile int target = -1;
        private static final List<Float> STEPS = java.util.Collections.synchronizedList(new ArrayList<>());
        private static Float last;

        static void start(int id) { STEPS.clear(); last = null; target = id; }
        static void stop() { target = -1; }
        static List<Float> steps() { synchronized (STEPS) { return new ArrayList<>(STEPS); } }

        @net.minecraftforge.eventbus.api.SubscribeEvent
        public void tick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
            if (event.phase != net.minecraftforge.event.TickEvent.Phase.END || target < 0) return;
            var level = Minecraft.getInstance().level;
            Entity drawn = level == null ? null : level.getEntity(target);
            if (!(drawn instanceof VehicleEntity vehicle)) return;
            float roll = vehicle.getRoll();
            if (last != null) {
                float step = roll - last;
                if (step > 180) step -= 360;
                if (step < -180) step += 360;
                STEPS.add(step);
            }
            last = roll;
        }
    }
}
