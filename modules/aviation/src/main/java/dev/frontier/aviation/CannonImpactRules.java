package dev.frontier.aviation;

/** Deterministic material work; a cannon hit can remove only the contacted block. */
public final class CannonImpactRules {
    /**
     * Blast resistance is an explosion property, so a shell sees only this much of it.
     * Engineer's Decor rebar concrete (hardness 1, resistance 2000: the Industry factory
     * warehouse walls) otherwise needed ~215 contacts within 5 s, i.e. it was immune; it
     * now takes three, like an iron block. Ordinary stone, brick and deepslate (6) are
     * unchanged, and obsidian-class blocks stay out through the hardness limit.
     */
    public static final double MAX_RESISTANCE = 24;

    private CannonImpactRules() {}

    public static double toughness(double hardness, double blastResistance) {
        if (!Double.isFinite(hardness) || !Double.isFinite(blastResistance) || hardness < 0) return Double.POSITIVE_INFINITY;
        return Math.max(1, hardness * 4 + Math.min(MAX_RESISTANCE, Math.max(0, blastResistance)) * 1.5);
    }

    public static double work(double projectileDamage, double scale) {
        return Double.isFinite(projectileDamage) && Double.isFinite(scale)
                ? Math.max(0, Math.min(200, projectileDamage * scale)) : 0;
    }
}
