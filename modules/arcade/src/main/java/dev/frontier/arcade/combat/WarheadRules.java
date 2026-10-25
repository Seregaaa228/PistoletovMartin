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
}
