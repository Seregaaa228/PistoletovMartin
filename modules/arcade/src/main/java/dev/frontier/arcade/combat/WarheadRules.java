package dev.frontier.arcade.combat;

/** Explicit native payload allowlist and bounded impact work, independent of Minecraft bootstrap. */
public final class WarheadRules {
    public static final int FPV_BLOCK_LIMIT = 96, GERANIUM_BLOCK_LIMIT = 256;
    public static final double FPV_BLOCK_RADIUS = 3, GERANIUM_BLOCK_RADIUS = 6, MAX_SWEEP = 32;
    private WarheadRules() { }
    public static boolean explosivePayload(String id, int ammo) {
        return ammo > 0 && ("superbwarfare:rpg_rocket_standard".equals(id)
            || "superbwarfare:rpg_rocket_tbg".equals(id) || "superbwarfare:mortar_shell".equals(id));
    }
    public static boolean sweepLength(double distanceSquared) {
        return Double.isFinite(distanceSquared) && distanceSquared > .0025 && distanceSquared <= MAX_SWEEP * MAX_SWEEP;
    }
    /**
     * A target's own displacement in one server tick that the swept test follows. Jets fly
     * 3-5 blocks a tick (faster in a dive); a larger or invalid step is a teleport or a fresh
     * placement and counts as standing still.
     */
    public static final double MAX_TARGET_STEP = 16;
    /** A hovering drone is struck only by a vehicle closing faster than this (blocks a tick). */
    public static final double HOVER_CONTACT_STEP = .5;
    public static boolean targetStep(double dx, double dy, double dz) {
        double squared = dx * dx + dy * dy + dz * dz;
        return Double.isFinite(squared) && squared <= MAX_TARGET_STEP * MAX_TARGET_STEP;
    }
    /** Own displacement of a hovering (or nearly still) drone that may still be struck. */
    public static boolean hoverLength(double distanceSquared) {
        return Double.isFinite(distanceSquared) && distanceSquared >= 0 && distanceSquared <= .0025;
    }
    /** Relative closing path between a hovering drone and a vehicle, both bounded. */
    public static boolean hoverContact(double relativeSquared) {
        double limit = MAX_SWEEP + MAX_TARGET_STEP;
        return Double.isFinite(relativeSquared) && relativeSquared > HOVER_CONTACT_STEP * HOVER_CONTACT_STEP
            && relativeSquared <= limit * limit;
    }
}
