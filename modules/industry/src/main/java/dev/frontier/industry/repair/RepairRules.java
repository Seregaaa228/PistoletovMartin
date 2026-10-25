package dev.frontier.industry.repair;

/** Deterministic gates shared by the brain and the single site repair transaction. */
public final class RepairRules {
    public static final int PATH_RETRY_TICKS = 100, PATH_TIMEOUT_TICKS = 200;
    public static final double APPROACH_DISTANCE_SQUARED = 6.25;
    private RepairRules() {}

    public static boolean isRepairRole(String role) {
        return "factory_repair".equals(role) || "grid_repair".equals(role);
    }
    public static boolean mayWork(boolean authorized, boolean loaded, boolean requested, boolean damaged,
                                  boolean available, double approachDistanceSquared) {
        return authorized && loaded && requested && damaged && available && Double.isFinite(approachDistanceSquared)
            && approachDistanceSquared <= APPROACH_DISTANCE_SQUARED;
    }
    public static boolean pathAttemptDue(long previousAttempt, long now) {
        return previousAttempt == Long.MIN_VALUE || now < previousAttempt || now - previousAttempt >= PATH_RETRY_TICKS;
    }
}
