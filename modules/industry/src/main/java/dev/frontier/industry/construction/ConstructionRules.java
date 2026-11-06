package dev.frontier.industry.construction;

/** Tick arithmetic; independent from Minecraft and shared by live simulation/tests. */
public final class ConstructionRules {
    private ConstructionRules() {}
    public static int duration(String type) {
        return 300;
    }
    public static int quota(int cursor, int count, int tick, int duration, int budget) {
        if (count <= 0 || duration <= 0 || budget <= 0) return 0;
        int target = (int)Math.min(count, (long)count * Math.max(0, Math.min(tick, duration)) / duration);
        return Math.max(0, Math.min(budget, target - Math.max(0, cursor)));
    }
    public static int advance(int tick, int duration, boolean working) {
        return Math.max(0, Math.min(duration, tick + (working ? 1 : 0)));
    }
    public static boolean refundable(boolean paid, int cursor) { return paid && cursor == 0; }

    /**
     * Block-state properties that a placed block changes by itself or through ordinary use
     * (neighbour connections, weather, fluids, redstone, doors opened, leaf decay, growth).
     * They never decide whether a building block is "built"; the block itself and its
     * shape-defining properties (facing, axis, half, type, hinge, rotation, line...) do.
     */
    private static final java.util.Set<String> SELF_CHANGING = java.util.Set.of(
        "north", "south", "east", "west", "up", "down", "shape", "in_wall", "attached", "mounted",
        "snowy", "waterlogged", "level", "distance", "persistent", "bottom",
        "powered", "lit", "open", "power", "triggered", "enabled", "extended", "occupied", "locked",
        "has_book", "has_record", "signal_fire", "inverted", "mode", "delay", "note", "instrument", "disarmed",
        "age", "moisture", "stage", "tilt");
    public static boolean selfChanging(String property) { return SELF_CHANGING.contains(property); }
}
