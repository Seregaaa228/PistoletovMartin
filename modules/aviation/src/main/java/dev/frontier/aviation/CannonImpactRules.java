package dev.frontier.aviation;

/** Deterministic material work; a cannon hit can remove only the contacted block. */
public final class CannonImpactRules {
    private CannonImpactRules() {}

    public static double toughness(double hardness, double blastResistance) {
        if (!Double.isFinite(hardness) || !Double.isFinite(blastResistance) || hardness < 0) return Double.POSITIVE_INFINITY;
        return Math.max(1, hardness * 4 + Math.max(0, blastResistance) * 1.5);
    }

    public static double work(double projectileDamage, double scale) {
        return Double.isFinite(projectileDamage) && Double.isFinite(scale)
                ? Math.max(0, Math.min(200, projectileDamage * scale)) : 0;
    }
}
