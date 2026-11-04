package dev.frontier.aviation.control.diagnostics;

import dev.frontier.aviation.LightAviation;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Freeze detector. A daemon thread looks every 10 ms at two heartbeats: the start of each
 * rendered frame, and the start/end of each server tick (integrated or dedicated). When a
 * frame has not started, or a tick has not finished, for longer than the threshold, the
 * stuck thread's stack is sampled every 10 ms until it moves again, and one report goes to
 * the log under [frontier-stall]. Between freezes it only reads a few volatiles.
 * Threshold: -Dfrontier.stallWatchMs (default 200); off with -Dfrontier.stallWatch=false.
 */
@Mod.EventBusSubscriber(modid = LightAviation.ID)
public final class ThreadStallWatch {
    private static final boolean ENABLED = !"false".equals(System.getProperty("frontier.stallWatch"));
    private static final long THRESHOLD = Long.getLong("frontier.stallWatchMs", 200) * 1_000_000L;
    private static final Heartbeat RENDER = new Heartbeat("Render thread"), SERVER = new Heartbeat("Server thread");
    private static volatile boolean started;

    private ThreadStallWatch() {}

    private static final class Heartbeat {
        final String label;
        volatile Thread thread;
        volatile long since;
        volatile boolean busy;
        // Watcher thread only.
        StallStacks stacks;
        long stallSince;

        Heartbeat(String label) { this.label = label; }

        void beat(boolean busy) {
            thread = Thread.currentThread();
            since = System.nanoTime();
            this.busy = busy;
            if (!started) start();
        }
    }

    @SubscribeEvent
    public static void renderTick(TickEvent.RenderTickEvent event) {
        // A frame is late when the next one has not started: always busy.
        if (ENABLED && event.phase == TickEvent.Phase.START) RENDER.beat(true);
    }

    @SubscribeEvent
    public static void serverTick(TickEvent.ServerTickEvent event) {
        // A tick is late while it runs; the server idles between ticks and while paused.
        if (!ENABLED) return;
        if (event.phase == TickEvent.Phase.START) SERVER.beat(true);
        else SERVER.busy = false;
    }

    private static synchronized void start() {
        if (started) return;
        started = true;
        Thread watcher = new Thread(ThreadStallWatch::watch, "frontier-stall-watch");
        watcher.setDaemon(true);
        watcher.start();
    }

    private static void watch() {
        while (true) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException stop) {
                return;
            }
            long now = System.nanoTime();
            poll(RENDER, now);
            poll(SERVER, now);
        }
    }

    private static void poll(Heartbeat heartbeat, long now) {
        Thread thread = heartbeat.thread;
        long since = heartbeat.since;
        if (heartbeat.busy && thread != null && now - since > THRESHOLD) {
            if (heartbeat.stacks == null || heartbeat.stallSince != since) {
                // A previous stall ended when this beat began.
                report(heartbeat, since);
                heartbeat.stacks = new StallStacks();
                heartbeat.stallSince = since;
            }
            heartbeat.stacks.add(thread.getStackTrace());
            return;
        }
        report(heartbeat, heartbeat.busy ? heartbeat.since : now);
    }

    /** Logs a finished stall, which ended at {@code end}. */
    private static void report(Heartbeat heartbeat, long end) {
        if (heartbeat.stacks == null) return;
        System.out.println(heartbeat.stacks.summary(heartbeat.label, (end - heartbeat.stallSince) / 1_000_000L));
        heartbeat.stacks = null;
    }
}
