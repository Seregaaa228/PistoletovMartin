package dev.frontier.aviation.systemstest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import dev.frontier.aviation.AircraftProfile;
import dev.frontier.aviation.api.AircraftController;
import java.nio.file.Files;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Level flight with the real client in control: the runner starts the engine (I) and
 * holds W (throttle up) like a player; every tick the server speed, the speed of the
 * aircraft the pilot's client draws, throttle and power are recorded, to show how the
 * speed builds up and whether it surges and drops.
 */
final class FlightTraceScenario {
    static final int TIMEOUT_TICKS = 900;
    private static final int TRACE_TICKS = 700;
    private final ServerPlayer pilot;
    private final VehicleEntity plane;
    private final JsonObject result = new JsonObject();
    private final JsonArray server = new JsonArray(), power = new JsonArray(),
        throttle = new JsonArray(), altitude = new JsonArray(), phase = new JsonArray(), boost = new JsonArray();
    private Vec3 lastServer;
    private int ticks;

    FlightTraceScenario(ServerPlayer pilot, AircraftProfile profile) {
        this.pilot = pilot;
        if (pilot.isPassenger()) throw new IllegalStateException("trace needs a free player");
        plane = profile.entity.get().create(pilot.level());
        if (plane == null) throw new IllegalStateException(profile.id + " creation failed");
        result.addProperty("aircraft", profile.id);
        ((Entity) plane).moveTo(pilot.getX(), pilot.getY() + 160, pilot.getZ(), 0, 0);
        plane.setEnergy(plane.getMaxEnergy());
        ((Entity) plane).setDeltaMovement(0, 0, 1.6);
        if (!pilot.serverLevel().addFreshEntity(plane) || !pilot.startRiding(plane, true))
            throw new IllegalStateException("trace mount failed");
        result.addProperty("pass", false);
    }

    boolean tick() {
        ticks++;
        Entity body = plane;
        if (ticks == 10) progress("trace-ready");
        if (ticks == 21) ClientRecorder.start(body.getId());
        if (ticks > 20 && ticks <= 20 + TRACE_TICKS) {
            AircraftController.observe(plane).ifPresent(snapshot -> boost.add(snapshot.telemetry().boost() ? 1 : 0));
            Vec3 now = body.position();
            if (lastServer != null) server.add(kmh(now.distanceTo(lastServer)));
            lastServer = now;
            AircraftController.observe(plane).ifPresent(snapshot -> {
                var state = snapshot.telemetry().state();
                power.add(Math.round(state.effectivePower() * 100) / 100.0);
                throttle.add(Math.round(state.targetThrottle() * 100) / 100.0);
                phase.add(state.enginePhase().name().substring(0, 1));
            });
            altitude.add(Math.round(body.getY()));
        }
        if (ticks > 20 + TRACE_TICKS || !pilot.isPassenger() || body.isRemoved()) {
            result.add("serverKmh", server);
            ClientRecorder.stop();
            result.add("clientKmh", ClientRecorder.moved);
            result.add("hudKmh", ClientRecorder.hud);
            result.add("power", power);
            result.add("throttle", throttle);
            result.add("enginePhase", phase);
            result.add("altitude", altitude);
            result.add("boost", boost);
            result.addProperty("pass", server.size() > TRACE_TICKS / 2);
            progress("trace-done");
            pilot.stopRiding();
            body.discard();
            return true;
        }
        return false;
    }

    private static double kmh(double blocksPerTick) {
        return Math.round(blocksPerTick * 72 * 10) / 10.0;
    }

    private void progress(String stage) {
        var progress = new JsonObject();
        progress.addProperty("runId", System.getProperty("frontier.aircraftSystemsRunId"));
        progress.addProperty("stage", stage);
        try { Files.writeString(FMLPaths.GAMEDIR.get().resolve("aircraft-trace-progress.json"), progress.toString()); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }

    /**
     * Client half, on the client thread each client tick: how far the aircraft the pilot
     * sees moved, and the native HUD speed (VehicleEntity.absoluteSpeed, blocks/tick).
     */
    static final class ClientRecorder {
        static volatile int target = -1;
        /** Client ticks during which boost (SBW's Ctrl mapping) is held, like a player would. */
        static final int BOOST_FROM = 300, BOOST_TO = 540;
        private static int clientTicks;
        static JsonArray moved = new JsonArray(), hud = new JsonArray();
        private static Vec3 last;

        static void start(int id) { moved = new JsonArray(); hud = new JsonArray(); last = null; clientTicks = 0; target = id; }
        static void stop() {
            target = -1;
            Minecraft.getInstance().execute(() -> com.atsuishio.superbwarfare.init.ModKeyMappings.MOVE_CTRL.setDown(false));
        }

        @net.minecraftforge.eventbus.api.SubscribeEvent
        public void tick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
            if (event.phase != net.minecraftforge.event.TickEvent.Phase.END || target < 0) return;
            var level = Minecraft.getInstance().level;
            Entity drawn = level == null ? null : level.getEntity(target);
            if (drawn == null) return;
            clientTicks++;
            if (clientTicks == BOOST_FROM) com.atsuishio.superbwarfare.init.ModKeyMappings.MOVE_CTRL.setDown(true);
            if (clientTicks == BOOST_TO) com.atsuishio.superbwarfare.init.ModKeyMappings.MOVE_CTRL.setDown(false);
            Vec3 seen = drawn.position();
            if (last != null) moved.add(kmh(seen.distanceTo(last)));
            last = seen;
            if (drawn instanceof VehicleEntity vehicle) hud.add(kmh(vehicle.getAbsoluteSpeed()));
        }
    }

    JsonObject result() { return result; }
}
