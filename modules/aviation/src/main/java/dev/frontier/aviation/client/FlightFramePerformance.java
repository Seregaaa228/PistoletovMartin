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
    public static final boolean ENABLED = Boolean.getBoolean("frontier.flightPerformance");

    private static final FlightTimingHistogram FRAME_INTERVAL =
            ENABLED ? new FlightTimingHistogram() : null;
    private static ClientLevel measuredLevel;
    private static long previousFrameAt;
    private static boolean hasPreviousFrame;

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

        if (currentLevel == null || minecraft.isPaused() || !minecraft.isWindowActive()
                || minecraft.screen != null || FlightClient.controlledEntity() == null) {
            hasPreviousFrame = false;
            return;
        }

        long now = System.nanoTime();
        if (hasPreviousFrame) {
            FRAME_INTERVAL.record(Math.max(0, now - previousFrameAt));
        }
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
    }
}
