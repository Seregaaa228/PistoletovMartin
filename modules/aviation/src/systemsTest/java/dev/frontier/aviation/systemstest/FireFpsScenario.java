package dev.frontier.aviation.systemstest;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.google.gson.JsonObject;
import dev.frontier.aviation.AircraftProfile;
import dev.frontier.aviation.api.AircraftArmament;
import dev.frontier.aviation.api.AircraftController;
import dev.frontier.aviation.control.ControlLease;
import dev.frontier.aviation.control.PilotCommand;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Frame time, live particles and server tick time while the pilot holds the cannon
 * trigger, against the same seconds without firing. The F-16 hovers nose slightly down
 * so the rounds hit the ground ~200 blocks ahead, as in a strafing run.
 */
final class FireFpsScenario {
    static final int TIMEOUT_TICKS = 700;
    private static final int SETTLE = 60, QUIET = 160, FIRING = 200;
    private final ServerPlayer pilot;
    private final VehicleEntity plane;
    private final ControlLease lease;
    private final JsonObject result = new JsonObject();
    private final List<Long> quietServer = new ArrayList<>(), firingServer = new ArrayList<>();
    private int ticks, fired;

    FireFpsScenario(ServerPlayer pilot) {
        this.pilot = pilot;
        if (pilot.isPassenger()) throw new IllegalStateException("fps fixture needs a free pilot");
        var level = pilot.serverLevel();
        plane = AircraftProfile.F16.entity.get().create(level);
        if (plane == null) throw new IllegalStateException("F-16 creation failed");
        Entity body = plane;
        body.moveTo(pilot.getX(), pilot.getY() + 30, pilot.getZ(), 0, 9);
        plane.setNoGravity(true);
        plane.setInvulnerable(true);
        plane.setEnergy(plane.getMaxEnergy());
        if (!level.addFreshEntity(plane) || !pilot.startRiding(body, true)) throw new IllegalStateException("fps mount failed");
        lease = AircraftController.acquire(pilot, plane).orElseThrow();
        result.addProperty("pass", false);
    }

    boolean tick() {
        ticks++;
        AircraftController.submit(pilot, lease, PilotCommand.neutral(3_000_000L + ticks));
        ((Entity) plane).setDeltaMovement(0, 0, 0);
        if (ticks == 5 && !AircraftArmament.select(pilot, plane, lease, "cannon")) return finish("cannon selection failed");
        long[] times = pilot.server.tickTimes;
        long last = times[(pilot.server.getTickCount() + times.length - 1) % times.length];
        if (ticks == SETTLE) FrameRecorder.start("quiet");
        if (ticks > SETTLE && ticks <= SETTLE + QUIET) quietServer.add(last);
        if (ticks == SETTLE + QUIET) FrameRecorder.start("firing");
        if (ticks > SETTLE + QUIET && ticks <= SETTLE + QUIET + FIRING) {
            firingServer.add(last);
            var shot = AircraftArmament.fire(pilot, plane, lease, "cannon", 1, null);
            if (shot == AircraftArmament.Result.QUEUED) fired++;
            ((VehicleEntity) plane).getGunData("Cannon").ammo.set(500);
        }
        if (ticks < SETTLE + QUIET + FIRING + 5) return false;
        FrameRecorder.stop();
        result.add("quietFrames", FrameRecorder.summary("quiet"));
        result.add("firingFrames", FrameRecorder.summary("firing"));
        result.add("quietServer", summary(quietServer));
        result.add("firingServer", summary(firingServer));
        result.addProperty("shotsQueued", fired);
        result.addProperty("pass", fired > 20);
        return finish(null);
    }

    private static JsonObject summary(List<Long> nanos) {
        long[] v = nanos.stream().mapToLong(Long::longValue).sorted().toArray();
        var o = new JsonObject();
        if (v.length == 0) return o;
        o.addProperty("avgMs", Math.round(Arrays.stream(v).average().orElse(0) / 1e4) / 100.0);
        o.addProperty("p95Ms", Math.round(v[(int) (v.length * .95)] / 1e4) / 100.0);
        o.addProperty("maxMs", Math.round(v[v.length - 1] / 1e4) / 100.0);
        return o;
    }

    private boolean finish(String error) {
        if (error != null) result.addProperty("error", error);
        AircraftController.release(pilot, lease);
        pilot.stopRiding();
        ((Entity) plane).discard();
        return true;
    }

    JsonObject result() { return result; }

    /** Client thread: frame intervals and the particle count per frame for a named phase. */
    static final class FrameRecorder {
        private static final java.util.Map<String, List<long[]>> PHASES = new java.util.concurrent.ConcurrentHashMap<>();
        private static volatile String phase;
        private static long previous;

        static void start(String name) { PHASES.put(name, java.util.Collections.synchronizedList(new ArrayList<>())); previous = 0; phase = name; }
        static void stop() { phase = null; }

        static JsonObject summary(String name) {
            var frames = PHASES.getOrDefault(name, List.of());
            long[] dt, particles;
            synchronized (frames) {
                dt = frames.stream().mapToLong(f -> f[0]).sorted().toArray();
                particles = frames.stream().mapToLong(f -> f[1]).toArray();
            }
            var o = new JsonObject();
            if (dt.length == 0) return o;
            double avg = Arrays.stream(dt).average().orElse(0) / 1e6;
            o.addProperty("frames", dt.length);
            o.addProperty("avgFps", Math.round(1000 / avg));
            o.addProperty("p95Ms", Math.round(dt[(int) (dt.length * .95)] / 1e4) / 100.0);
            o.addProperty("p99Ms", Math.round(dt[(int) (dt.length * .99)] / 1e4) / 100.0);
            o.addProperty("maxMs", Math.round(dt[dt.length - 1] / 1e4) / 100.0);
            o.addProperty("maxParticles", Arrays.stream(particles).max().orElse(0));
            return o;
        }

        @net.minecraftforge.eventbus.api.SubscribeEvent
        public void frame(net.minecraftforge.event.TickEvent.RenderTickEvent event) {
            if (event.phase != net.minecraftforge.event.TickEvent.Phase.START) return;
            String name = phase;
            long now = System.nanoTime();
            if (name == null) { previous = 0; return; }
            if (previous != 0) {
                long count = 0;
                try { count = Long.parseLong(Minecraft.getInstance().particleEngine.countParticles().trim()); }
                catch (NumberFormatException ignored) { }
                var list = PHASES.get(name);
                if (list != null) list.add(new long[] {now - previous, count});
            }
            previous = now;
        }
    }
}
