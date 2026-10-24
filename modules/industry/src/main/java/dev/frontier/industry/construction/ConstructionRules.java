package dev.frontier.industry.construction;

/** Tick arithmetic; independent from Minecraft and shared by live simulation/tests. */
public final class ConstructionRules {
    private ConstructionRules() {}
    public static int duration(String type) {
        return switch (type) {
            case "SUBSTATION" -> 400;
            case "DRONE_FACTORY", "EQUIPMENT_FACTORY" -> 600;
            case "VEHICLE_FACTORY", "RESEARCH_CENTER" -> 800;
            default -> 900;
        };
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
}
