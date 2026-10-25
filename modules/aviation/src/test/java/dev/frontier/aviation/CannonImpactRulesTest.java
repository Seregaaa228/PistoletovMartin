package dev.frontier.aviation;

/** Material regression fixtures for the 35-damage F-16/J-10 native cannon. */
public final class CannonImpactRulesTest {
    public static void main(String[] args) {
        double shot = CannonImpactRules.work(35, .4);
        require(hits(2, 3, shot) == 1, "wood: one contact");
        require(hits(1.5, 6, shot) == 2, "stone: two contacts");
        require(hits(2, 6, shot) == 2, "brick: two contacts");
        require(hits(5, 6, shot) == 3, "iron: three contacts");
        require(Double.isInfinite(CannonImpactRules.toughness(-1, 3_600_000)), "bedrock never breaks");
        require(Double.isInfinite(CannonImpactRules.toughness(Double.NaN, 1)), "invalid hardness rejected");
        require(CannonImpactRules.work(Double.POSITIVE_INFINITY, 1) == 0, "invalid projectile ignored");
        require(CannonImpactRules.work(35, 0) == 0, "disabled material work");
        System.out.println("Cannon material fixtures: 8 PASS");
    }
    private static int hits(double hardness, double resistance, double shot) {
        return (int) Math.ceil(CannonImpactRules.toughness(hardness, resistance) / shot);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
