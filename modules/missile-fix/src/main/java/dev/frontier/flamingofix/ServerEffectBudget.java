package dev.frontier.flamingofix;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;

/** Cosmetic packet budget; the server thread owns each level and its counter. */
public final class ServerEffectBudget {
    private static final Map<ServerLevel, Counter> LEVELS = new WeakHashMap<>();
    private static final class Counter { long tick = Long.MIN_VALUE; int packets; int particles; }
    public static int take(ServerLevel level, int requested) {
        Counter counter = LEVELS.computeIfAbsent(level, ignored -> new Counter());
        if (counter.tick != level.getGameTime()) {
            counter.tick = level.getGameTime(); counter.packets = counter.particles = 0;
        }
        if (counter.packets >= 8 || counter.particles >= 32) return 0;
        int count = Math.min(Math.max(requested, 1), Math.min(4, 32 - counter.particles));
        counter.packets++; counter.particles += count;
        return count;
    }
    private ServerEffectBudget() { }
}
