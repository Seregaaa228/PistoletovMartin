package dev.frontier.industry.engine;

/** Small deterministic rules shared by live simulation and boundary tests. */
public final class IndustryRules {
    public static final int INPUTS = 12, SLOTS = 24, REPAIR_TICKS = 1800;
    public static final int MAX_WORKERS = 64, MAX_TRANSFERS = 7, MAX_PRODUCTION = 12;
    public static final int LINK_RANGE = 128, INTERACT_RANGE = 12, REPAIR_RANGE = 8;
    public static final int GENERATION = 128, BUFFER = 32000, MAX_QUEUE = 16;
    private IndustryRules() {}

    public static boolean functional(int matching, int total, boolean controller, boolean loaded) {
        return loaded && controller && total > 0 && (long) matching * 4 >= (long) total * 3;
    }
    public static int transfer(int source, int target, int maximum) {
        return Math.max(0, Math.min(Math.min(source, maximum), BUFFER - target));
    }
    public static boolean linkAllowed(boolean sameOwner, boolean sameDimension, int sourceType,
                                      int targetType, double distanceSquared) {
        return sameOwner && sameDimension && Double.isFinite(distanceSquared)
            && distanceSquared <= (double) LINK_RANGE * LINK_RANGE
            && ((sourceType == 0 && targetType == 1) || (sourceType == 1 && targetType >= 2 && targetType <= 6));
    }
    public static boolean canHire(long activeWorkers) { return activeWorkers < MAX_WORKERS; }
    public static boolean canTransfer(long activeWorkers, long activeTransfers) {
        return canHire(activeWorkers) && activeTransfers < MAX_TRANSFERS;
    }
    public static int repairStep(int previous, boolean loaded, boolean crew, boolean supplied) {
        return Math.max(0, Math.min(REPAIR_TICKS, previous + (loaded && crew && supplied ? 1 : 0)));
    }
    public static boolean repairMayReplace(boolean matching, boolean air, boolean blockEntity) {
        return !matching && air && !blockEntity;
    }
    public static double distanceToBox(double x, double y, double z, int minX, int minY, int minZ,
                                       int maxX, int maxY, int maxZ) {
        double dx = Math.max(Math.max(minX - x, x - (maxX + 1)), 0);
        double dy = Math.max(Math.max(minY - y, y - (maxY + 1)), 0);
        double dz = Math.max(Math.max(minZ - z, z - (maxZ + 1)), 0);
        return dx * dx + dy * dy + dz * dz;
    }
}
