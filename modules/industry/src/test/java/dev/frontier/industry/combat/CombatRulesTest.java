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
        System.out.println("COMBAT_RULES_PASS " + checks + " checks");
    }
}
