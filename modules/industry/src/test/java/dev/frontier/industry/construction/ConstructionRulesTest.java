package dev.frontier.industry.construction;

/** Standalone checks; no Minecraft runtime or test dependency. */
public final class ConstructionRulesTest {
    private static int checks;
    private static void check(String label, boolean result) {
        checks++;
        if (!result) throw new AssertionError(label);
    }
    public static void main(String[] args) {
        for (String property : new String[]{"open", "powered", "snowy", "waterlogged", "lit", "power", "distance", "persistent",
            "north", "south", "east", "west", "up", "down", "shape", "in_wall", "mounted"})
            check(property + " changes by itself and is not construction progress", ConstructionRules.selfChanging(property));
        for (String property : new String[]{"facing", "axis", "half", "type", "hinge", "rotation", "face", "line", "part"})
            check(property + " defines the building look", !ConstructionRules.selfChanging(property));
        check("quota stops at plan end", ConstructionRules.quota(9, 10, 300, 300, 64) == 1);
        check("no refund after first block", !ConstructionRules.refundable(true, 1) && ConstructionRules.refundable(true, 0));
        System.out.println("ConstructionRulesTest: " + checks + " checks passed");
    }
}
