package dev.frontier.aviation.weapons;

/** Delayed-fuze run of the three КАБ classes (radii 16, 41, 60) through map materials. */
public final class KabPenetrationTest {
    private static final double AIR = 0, STONE = 1.5, CONCRETE = 1.8, DEEPSLATE = 3.5, IRON = 5, OBSIDIAN = 50;

    public static void main(String[] args) {
        check(Math.abs(KabPenetration.maxTravel(16) - 5.6) < 1.0E-9, "KAB-500 fuze travel");
        check(Math.abs(KabPenetration.maxTravel(41) - 14.35) < 1.0E-9, "KAB-1500 fuze travel");
        check(KabPenetration.maxTravel(60) == 20, "KAB-3000 fuze travel capped");
        check(KabPenetration.maxTravel(Double.NaN) == 0 && KabPenetration.depth(Double.NaN, i -> STONE) == 0, "invalid radius");

        // Ground: stone is free-running for the whole fuze travel; deepslate and iron stop sooner.
        check(KabPenetration.depth(16, i -> STONE) == 5.5, "KAB-500 into stone");
        check(KabPenetration.depth(16, i -> DEEPSLATE) == 4.0, "KAB-500 into deepslate");
        check(KabPenetration.depth(16, i -> IRON) == 2.5, "KAB-500 into iron blocks");
        check(KabPenetration.depth(41, i -> STONE) == 14.0, "KAB-1500 into stone");
        check(KabPenetration.depth(60, i -> STONE) == 20.0, "KAB-3000 into stone");

        // A building: a concrete roof, a 4-block room, a floor: the burst is inside, not on the roof.
        double house = KabPenetration.depth(16, i -> i < 2 ? CONCRETE : i < 10 ? AIR : CONCRETE);
        check(house == 5.5 && KabPenetration.bursts(house), "KAB-500 bursts below the roof");
        // Tree canopy over a roof and a water surface: the store does not dud on the first touch.
        check(KabPenetration.depth(16, i -> i < 4 ? 0.2 : i < 6 ? AIR : STONE) == 5.5, "through leaves");
        check(KabPenetration.depth(16, i -> KabPenetration.FLUID_RESISTANCE) == 5.5, "through water");

        // Hard stops: obsidian-class and unbreakable or unloaded blocks end the run in front of them.
        check(KabPenetration.depth(60, i -> OBSIDIAN) == 0 && !KabPenetration.bursts(0), "obsidian face: no penetrator");
        check(KabPenetration.depth(60, i -> i < 6 ? STONE : OBSIDIAN) == 3.0, "stops at obsidian");
        check(KabPenetration.depth(60, i -> i < 4 ? STONE : -1) == 2.0, "stops at bedrock");
        check(KabPenetration.depth(60, i -> i < 3 ? STONE : Double.NaN) == 1.5, "stops at unloaded terrain");
        check(!KabPenetration.bursts(KabPenetration.depth(16, i -> i < 1 ? STONE : OBSIDIAN)), "too shallow to burst");

        // Bounded: the penetrator is half the radius, never more than 20 blocks of travel.
        for (float radius : new float[]{16, 41, 60}) {
            check(KabPenetration.burstRadius(radius) == radius / 2, "half-radius charge");
            check(KabPenetration.depth(radius, i -> AIR) <= KabPenetration.MAX_TRAVEL, "travel bounded");
        }
        int[] samples = {0};
        KabPenetration.depth(60, i -> { samples[0]++; return AIR; });
        check(samples[0] == 40, "at most 40 samples per impact");
        System.out.println("KabPenetrationTest passed");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
