package dev.frontier.industry.engine;

/** Run with javac/java; deliberately has no Minecraft/Gradle/JUnit runtime dependency. */
public final class IndustryRulesTest {
    private static int checks;
    private static void check(String label, boolean condition) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
    public static void main(String[] args) {
        check("75 percent exact boundary", IndustryRules.functional(75, 100, true, true));
        check("below 75 pauses", !IndustryRules.functional(74, 100, true, true));
        check("missing controller pauses regardless health", !IndustryRules.functional(100, 100, false, true));
        check("unloaded pauses regardless health", !IndustryRules.functional(100, 100, true, false));
        check("integer ratio does not round damaged structures up", !IndustryRules.functional(2, 3, true, true));
        check("no power creation from empty source", IndustryRules.transfer(0, 0, 128) == 0);
        int source = 128, first = IndustryRules.transfer(source, 0, 96); source -= first;
        int second = IndustryRules.transfer(source, 0, 96); source -= second;
        check("shared source cannot double spend", first == 96 && second == 32 && source == 0);
        check("full target backpressure", IndustryRules.transfer(500, IndustryRules.BUFFER, 128) == 0);
        check("target clamp", IndustryRules.transfer(500, IndustryRules.BUFFER - 3, 128) == 3);
        check("plant to substation 128 boundary", IndustryRules.linkAllowed(true, true, 0, 1, 128 * 128));
        check("out of range rejected", !IndustryRules.linkAllowed(true, true, 0, 1, 128 * 128 + 0.01));
        check("plant factory bypass rejected", !IndustryRules.linkAllowed(true, true, 0, 2, 1));
        check("reversed link rejected", !IndustryRules.linkAllowed(true, true, 2, 1, 1));
        check("foreign owner rejected", !IndustryRules.linkAllowed(false, true, 0, 1, 1));
        check("cross dimension rejected", !IndustryRules.linkAllowed(true, false, 0, 1, 1));
        check("nonfinite distance rejected", !IndustryRules.linkAllowed(true, true, 0, 1, Double.NaN));
        check("seventh transfer allowed", IndustryRules.canTransfer(6, 6));
        check("eighth transfer refused", !IndustryRules.canTransfer(7, 7));
        check("civilian cap independent of transfer cap", !IndustryRules.canTransfer(64, 0));
        check("63rd hire boundary", IndustryRules.canHire(63) && !IndustryRules.canHire(64));
        int repair = 0;
        for (int n = 0; n < 1799; n++) repair = IndustryRules.repairStep(repair, true, true, true);
        check("1799 ordinary ticks cannot finish", repair == 1799);
        check("missing crew pauses at boundary", IndustryRules.repairStep(repair, true, false, true) == 1799);
        check("unloaded pauses at boundary", IndustryRules.repairStep(repair, false, true, true) == 1799);
        check("missing kit pauses at boundary", IndustryRules.repairStep(repair, true, true, false) == 1799);
        repair = IndustryRules.repairStep(repair, true, true, true);
        check("1800th tick finishes", repair == 1800);
        check("repair progress cannot overflow", IndustryRules.repairStep(repair, true, true, true) == 1800);
        check("air can be restored", IndustryRules.repairMayReplace(false, true, false));
        check("foreign block preserved", !IndustryRules.repairMayReplace(false, false, false));
        check("container preserved", !IndustryRules.repairMayReplace(false, true, true));
        check("matched blocks not replaced", !IndustryRules.repairMayReplace(true, false, false));
        check("distance uses footprint", IndustryRules.distanceToBox(6, 2, 0, -3, 0, -3, 3, 4, 3) == 4);
        check("inside footprint has zero distance", IndustryRules.distanceToBox(0, 1, 0, -3, 0, -3, 3, 4, 3) == 0);
        check("repair exact8 boundary includes block extents", IndustryRules.distanceToBox(12, 1, 0, -3, 0, -3, 3, 4, 3) == 64);
        System.out.println("IndustryRulesTest: " + checks + " checks passed");
    }
}
