package dev.frontier.nations.compat;

import com.mojang.logging.LogUtils;
import com.talhanation.recruits.util.DelayedExecutor;
import java.util.concurrent.ScheduledExecutorService;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;

/** Pinned Recruits1.15.2 retains a non-daemon scheduler after player logout. */
public final class RecruitsSchedulerLifecycle {
    private RecruitsSchedulerLifecycle() {}
    public static void init() {
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST,
            (ServerStoppedEvent event) -> shutdown(event.getServer().isDedicatedServer()));
    }
    static void shutdown(boolean dedicated) {
        // An integrated server may be reopened in the same client JVM: its static scheduler must survive.
        if (!dedicated) return;
        try {
            var field = DelayedExecutor.class.getDeclaredField("scheduler");
            field.setAccessible(true);
            var scheduler = (ScheduledExecutorService) field.get(null);
            // All dimensions have been saved/closed. Native delayed callbacks must not access them again.
            scheduler.shutdownNow();
            LogUtils.getLogger().info("Stopped native Recruits delayed scheduler after dedicated-server save");
        } catch (ReflectiveOperationException | RuntimeException incompatible) {
            LogUtils.getLogger().error("Cannot stop pinned Recruits delayed scheduler", incompatible);
        }
    }
}
