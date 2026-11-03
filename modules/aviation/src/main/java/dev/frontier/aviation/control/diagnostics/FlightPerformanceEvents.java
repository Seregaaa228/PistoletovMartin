package dev.frontier.aviation.control.diagnostics;

import dev.frontier.aviation.LightAviation;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Opt-in whole-server tick timing; this is not a flight-only measurement. */
@Mod.EventBusSubscriber(modid = LightAviation.ID)
public final class FlightPerformanceEvents {
    public static final boolean ENABLED = Boolean.getBoolean("frontier.flightPerformance")
        || System.getProperty("frontier.flightBench") != null;

    private static final FlightTimingHistogram WHOLE_SERVER_TICK =
            ENABLED ? new FlightTimingHistogram() : null;
    private static long tickStartedAt;
    private static boolean tickStarted;

    private FlightPerformanceEvents() {}

    @SubscribeEvent
    public static void serverTick(TickEvent.ServerTickEvent event) {
        if (!ENABLED) return;

        if (event.phase == TickEvent.Phase.START) {
            tickStartedAt = System.nanoTime();
            tickStarted = true;
        } else if (tickStarted) {
            long finishedAt = System.nanoTime();
            WHOLE_SERVER_TICK.record(Math.max(0, finishedAt - tickStartedAt));
            tickStarted = false;
        }
    }

    @SubscribeEvent
    public static void serverStopped(ServerStoppedEvent event) {
        if (!ENABLED) return;
        dumpAndReset("server_stopped");
    }

    /** Starts a fresh measured window (flight benchmark). */
    public static void reset() {
        if (!ENABLED) return;
        WHOLE_SERVER_TICK.reset();
        tickStarted = false;
    }

    public static void dumpAndReset() { dumpAndReset("lifecycle"); }

    /** {@code window} names what the histogram covers, e.g. "bench" for the measured flight. */
    public static void dumpAndReset(String window) {
        if (!ENABLED) return;
        System.out.println("[flight-performance] metric=wholeServerTick window=" + window + " unit=ns samples=" + WHOLE_SERVER_TICK.count()
                + " sum=" + WHOLE_SERVER_TICK.sumNanos()
                + " min=" + WHOLE_SERVER_TICK.minNanos()
                + " max=" + WHOLE_SERVER_TICK.maxNanos()
                + " p50Upper=" + WHOLE_SERVER_TICK.percentileUpperBound(50)
                + " p95Upper=" + WHOLE_SERVER_TICK.percentileUpperBound(95)
                + " p99Upper=" + WHOLE_SERVER_TICK.percentileUpperBound(99));
        WHOLE_SERVER_TICK.reset();
        tickStarted = false;
    }
}
