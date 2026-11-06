package dev.frontier.aviation.client;

import dev.frontier.aviation.LightAviation;
import dev.frontier.aviation.control.client.FlightClient;
import dev.frontier.aviation.control.diagnostics.FlightTimingHistogram;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Measures eligible frame intervals, a pacing metric rather than render cost. */
@Mod.EventBusSubscriber(modid = LightAviation.ID, value = Dist.CLIENT)
public final class FlightFramePerformance {
    /** The flight benchmark also enables it, and records without window focus. */
    static final boolean BENCH = System.getProperty("frontier.flightBench") != null;
    public static final boolean ENABLED = Boolean.getBoolean("frontier.flightPerformance") || BENCH;

    /** Flips the client task budget every 10 s and keeps a histogram for each half. */
    private static final boolean BUDGET_AB = ENABLED && Boolean.getBoolean("frontier.taskBudgetAB");
    private static final long AB_PERIOD_NANOS = 10_000_000_000L, HITCH_33 = 33_000_000L, HITCH_50 = 50_000_000L;

    private static final FlightTimingHistogram FRAME_INTERVAL =
            ENABLED ? new FlightTimingHistogram() : null;
    private static final FlightTimingHistogram[] BY_BUDGET =
            BUDGET_AB ? new FlightTimingHistogram[] {new FlightTimingHistogram(), new FlightTimingHistogram()} : null;
    private static final long[] HITCHES_33 = new long[2], HITCHES_50 = new long[2];
    private static long hitches33, hitches50;
    private static ClientLevel measuredLevel;
    private static long previousFrameAt;
    private static boolean hasPreviousFrame, previousBudget;

    private FlightFramePerformance() {}

    @SubscribeEvent
    public static void renderTick(TickEvent.RenderTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.START) return;

        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel currentLevel = minecraft.level;
        if (currentLevel != measuredLevel) {
            if (measuredLevel != null) dumpAndReset("level_change");
            measuredLevel = currentLevel;
            hasPreviousFrame = false;
        }

        if (currentLevel == null || minecraft.isPaused() || (!minecraft.isWindowActive() && !BENCH)
                || minecraft.screen != null || (FlightClient.controlledEntity() == null && !FlightBenchClient.holding())) {
            hasPreviousFrame = false;
            return;
        }

        long now = System.nanoTime();
        if (hasPreviousFrame) {
            long interval = Math.max(0, now - previousFrameAt);
            FRAME_INTERVAL.record(interval);
            if (interval >= HITCH_33) hitches33++;
            if (interval >= HITCH_50) hitches50++;
            if (BUDGET_AB) {
                // The interval belongs to the mode the previous frame ran its tasks in.
                int mode = previousBudget ? 1 : 0;
                BY_BUDGET[mode].record(interval);
                if (interval >= HITCH_33) HITCHES_33[mode]++;
                if (interval >= HITCH_50) HITCHES_50[mode]++;
            }
        }
        if (BUDGET_AB) ClientTaskBudget.enabled = (now / AB_PERIOD_NANOS & 1) == 0;
        previousBudget = ClientTaskBudget.enabled;
        previousFrameAt = now;
        hasPreviousFrame = true;
    }

    @SubscribeEvent
    public static void levelUnload(LevelEvent.Unload event) {
        if (!ENABLED || !(event.getLevel() instanceof ClientLevel unloading)
                || unloading != measuredLevel) return;
        dumpAndReset("level_unload");
        measuredLevel = null;
        hasPreviousFrame = false;
    }

    /** Starts the benchmark's measured window. */
    static void benchReset() {
        FRAME_INTERVAL.reset();
        hitches33 = hitches50 = 0;
    }

    /** The measured window as one JSON object. */
    static String benchSummary() {
        long samples = FRAME_INTERVAL.count(), sum = FRAME_INTERVAL.sumNanos();
        double seconds = sum / 1e9;
        return "{\"frames\":" + samples + ",\"seconds\":" + String.format(java.util.Locale.ROOT, "%.2f", seconds)
            + ",\"averageFps\":" + String.format(java.util.Locale.ROOT, "%.1f", seconds > 0 ? samples / seconds : 0)
            + ",\"p50UpperMs\":" + FRAME_INTERVAL.percentileUpperBound(50) / 1e6
            + ",\"p99UpperMs\":" + FRAME_INTERVAL.percentileUpperBound(99) / 1e6
            + ",\"p999UpperMs\":" + FRAME_INTERVAL.percentileUpperBound(99.9) / 1e6
            + ",\"maxMs\":" + FRAME_INTERVAL.maxNanos() / 1e6
            + ",\"over33ms\":" + hitches33 + ",\"over50ms\":" + hitches50
            + ",\"over33PerMinute\":" + String.format(java.util.Locale.ROOT, "%.1f", seconds > 0 ? hitches33 * 60 / seconds : 0) + "}";
    }

    private static void dumpAndReset(String lifecycleEnd) {
        System.out.println("[flight-performance] metric=frameInterval unit=ns lifecycleEnd=" + lifecycleEnd
                + " samples=" + FRAME_INTERVAL.count()
                + " sum=" + FRAME_INTERVAL.sumNanos()
                + " min=" + FRAME_INTERVAL.minNanos()
                + " max=" + FRAME_INTERVAL.maxNanos()
                + " p50Upper=" + FRAME_INTERVAL.percentileUpperBound(50)
                + " p95Upper=" + FRAME_INTERVAL.percentileUpperBound(95)
                + " p99Upper=" + FRAME_INTERVAL.percentileUpperBound(99));
        FRAME_INTERVAL.reset();
        if (!BUDGET_AB) return;
        for (int mode = 0; mode < 2; mode++) {
            FlightTimingHistogram histogram = BY_BUDGET[mode];
            System.out.println("[flight-performance] metric=frameInterval taskBudget=" + (mode == 1)
                    + " unit=ns samples=" + histogram.count() + " sum=" + histogram.sumNanos()
                    + " max=" + histogram.maxNanos()
                    + " p50Upper=" + histogram.percentileUpperBound(50)
                    + " p99Upper=" + histogram.percentileUpperBound(99)
                    + " p999Upper=" + histogram.percentileUpperBound(99.9)
                    + " over33ms=" + HITCHES_33[mode] + " over50ms=" + HITCHES_50[mode]);
            histogram.reset();
            HITCHES_33[mode] = HITCHES_50[mode] = 0;
        }
    }
}
