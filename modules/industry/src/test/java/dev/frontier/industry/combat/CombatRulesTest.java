package dev.frontier.industry.combat;

import java.util.Arrays;
import java.util.UUID;

/** No Minecraft bootstrap required; compile/run directly with Java 17. */
public final class CombatRulesTest {
    private static int checks;
    private static void check(boolean condition, String name) { checks++; if (!condition) throw new AssertionError(name); }
    public static void main(String[] args) {
        int[] stocks = {1, 1, 1};
        check(Arrays.equals(CombatRules.allocate(stocks, 3), new int[]{1, 1, 1}), "Three launchers contribute one shot on a salvo");
        check(Arrays.equals(stocks, new int[]{1, 1, 1}), "Planning never consumes stock");
        check(Arrays.equals(CombatRules.allocate(new int[]{6}, 3), new int[]{3}), "One loaded launcher can launch three tubes");
        check(CombatRules.allocate(new int[]{1, 0, 1}, 3) == null, "Insufficient total stock rejects full salvo");
        check(CombatRules.allocate(new int[]{64}, 4) == null && CombatRules.allocate(new int[]{64}, 0) == null, "Count bounds");
        check(CombatRules.allocate(new int[]{}, 1) == null, "No launcher cannot fire");
        check(CombatRules.allocate(new int[]{-1, 0}, 1) == null, "Corrupt negative stock cannot fire");
        UUID owner = UUID.randomUUID(), enemy = UUID.randomUUID();
        check(!CombatRules.hostile(owner, owner, false), "Owner never hostile");
        check(!CombatRules.hostile(owner, enemy, true), "Team member never hostile");
        check(!CombatRules.hostile(owner, null, false), "Unknown ownership fails closed");
        check(CombatRules.hostile(owner, enemy, false), "Different non-allied owner hostile");
        for (int distance = 0; distance <= 2048; distance++) {
            int duration = CombatRules.flightTicks(distance);
            check(duration >= 45 && duration < CombatRules.MAX_FLIGHT_TICKS, "Flight always bounded");
            check(CombatRules.arcOffset(0, distance) == 0 && CombatRules.arcOffset(1, distance) == 0, "Arc ends at target");
            check(CombatRules.arcOffset(0.5, distance) >= 24 && CombatRules.arcOffset(0.5, distance) <= 112, "Apex is bounded");
        }
        check(!CombatRules.sameSnapshot(new int[]{3, 2}, new int[]{2, 2}), "Snapshot detects ammo mutation");
        check(!CombatRules.lowIrDetected(65 * 65, 128, 0F), "Low-IR target unseen beyond half range");
        check(CombatRules.lowIrDetected(64 * 64, 128, 0F), "Low-IR target seen inside half range on a good roll");
        check(!CombatRules.lowIrDetected(10 * 10, 128, CombatRules.LOW_IR_DETECTION_CHANCE), "Low-IR target missed on a bad roll");
        // A glide bomb crossing an S-300 (range 128, scan every 5 ticks) at 2.5 blocks/tick. An
        // ordinary tagged target is acquired on the first scan in range; the bomb only sometimes.
        java.util.Random random = new java.util.Random(20261101L);
        int runs = 20000, acquired = 0;
        for (int run = 0; run < runs; run++) {
            for (double distance = 300; distance >= 0; distance -= 12.5) {
                if (distance <= 128 && CombatRules.lowIrDetected(distance * distance, 128, random.nextFloat())) { acquired++; break; }
            }
        }
        double rate = acquired / (double) runs;
        check(rate > 0.3 && rate < 0.8, "Low-IR crossing acquisition is reduced, not zero: " + rate);
        System.out.println("LOW_IR_CROSSING_ACQUISITION baseline=1.0 glideBomb=" + rate);
        System.out.println("COMBAT_RULES_PASS " + checks + " checks");
    }
}
