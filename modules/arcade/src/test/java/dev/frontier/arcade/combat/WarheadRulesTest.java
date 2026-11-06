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
        // Moving-target frame: a jet's 3-5 block tick (and a dive) is followed, a teleport is not.
        require(WarheadRules.targetStep(0, 0, 0), "parked vehicle");
        require(WarheadRules.targetStep(4.2, -.6, 1.1), "jet at full throttle");
        require(WarheadRules.targetStep(0, -WarheadRules.MAX_TARGET_STEP, 0), "exact step limit");
        for (double[] bad : new double[][]{{17, 0, 0}, {-55, 120, -36}, {Double.NaN, 0, 0}, {0, Double.POSITIVE_INFINITY, 0}})
            require(!WarheadRules.targetStep(bad[0], bad[1], bad[2]), "teleport/placement is not motion " + java.util.Arrays.toString(bad));
        for (double still : new double[]{0, .0001, .0025})
            require(WarheadRules.hoverLength(still) && !WarheadRules.sweepLength(still), "hovering drone has no own path " + still);
        for (double bad : new double[]{-1, .0026, Double.NaN})
            require(!WarheadRules.hoverLength(bad), "not hovering " + bad);
        require(WarheadRules.hoverContact(4 * 4), "passing jet strikes a hovering FPV");
        require(WarheadRules.hoverContact(.6 * .6), "fast vehicle strikes a hovering FPV");
        require(!WarheadRules.hoverContact(.3 * .3), "a slow vehicle or a launch from a moving truck does not");
        require(!WarheadRules.hoverContact(Double.NaN) && !WarheadRules.hoverContact(49 * 49), "bounded relative path");
        require(WarheadRules.GERANIUM_BLOCK_RADIUS > WarheadRules.FPV_BLOCK_RADIUS, "Geranium wider structural impact");
        require(WarheadRules.GERANIUM_BLOCK_LIMIT > WarheadRules.FPV_BLOCK_LIMIT, "Geranium larger limited destruction");
        System.out.println("WarheadRulesTest: " + checks + " checks passed");
    }
    private static void require(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
}
