package dev.frontier.arcade.combat;

public final class WarheadRulesTest {
    private static int checks;
    public static void main(String[] args) {
        for (String id : new String[]{"superbwarfare:rpg_rocket_standard", "superbwarfare:rpg_rocket_tbg", "superbwarfare:mortar_shell"}) {
            require(WarheadRules.explosivePayload(id, 1), "loaded native payload " + id);
            require(!WarheadRules.explosivePayload(id, 0), "empty payload " + id);
            require(!WarheadRules.explosivePayload(id, -1), "invalid ammo " + id);
        }
        for (String id : new String[]{"minecraft:tnt", "superbwarfare:c4_bomb", "foreign:rpg_rocket_standard", "rpg_rocket_standard", "", null})
            require(!WarheadRules.explosivePayload(id, 1), "never infer a payload from a suffix: " + id);
        for (double bad : new double[]{0, -.1, .0025, Double.NaN, Double.POSITIVE_INFINITY, 1024.0001})
            require(!WarheadRules.sweepLength(bad), "invalid/idle/teleport displacement " + bad);
        require(WarheadRules.sweepLength(4), "cross an entity in a 2m server tick");
        require(WarheadRules.sweepLength(1024), "exact finite work limit");
        require(WarheadRules.GERANIUM_BLOCK_RADIUS > WarheadRules.FPV_BLOCK_RADIUS, "Geranium wider structural impact");
        require(WarheadRules.GERANIUM_BLOCK_LIMIT > WarheadRules.FPV_BLOCK_LIMIT, "Geranium larger limited destruction");
        System.out.println("WarheadRulesTest: " + checks + " checks passed");
    }
    private static void require(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
}
