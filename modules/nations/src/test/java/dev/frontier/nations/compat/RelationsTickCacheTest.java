package dev.frontier.nations.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Pure cache lifecycle: no Recruits Villages world is loaded here. */
public final class RelationsTickCacheTest {
    private static int checks;

    public static void main(String[] args) {
        Object rome = new Object(), carthage = new Object();
        int[] lookups = {0};

        List<String> outside = RelationsTickCache.villages(rome, key -> { lookups[0]++; return new ArrayList<>(List.of("a")); });
        RelationsTickCache.villages(rome, key -> { lookups[0]++; return new ArrayList<>(List.of("a")); });
        check(lookups[0] == 2 && outside.equals(List.of("a")), "outside a pass every call looks up");
        check(RelationsTickCache.strength(rome) == null && RelationsTickCache.claims(rome) == null, "outside a pass nothing is cached");
        RelationsTickCache.rememberStrength(rome, 5);
        check(RelationsTickCache.strength(rome) == null, "outside a pass nothing is remembered");

        RelationsTickCache.begin();
        lookups[0] = 0;
        List<String> first = RelationsTickCache.villages(rome, key -> { lookups[0]++; return new ArrayList<>(List.of("a", "b")); });
        first.add("mutated by a caller");
        List<String> second = RelationsTickCache.villages(rome, key -> { lookups[0]++; return new ArrayList<>(); });
        check(lookups[0] == 1, "one lookup per country and pass");
        check(second.equals(List.of("a", "b")), "each caller gets its own copy: " + second);
        RelationsTickCache.villages(carthage, key -> { lookups[0]++; return new ArrayList<>(); });
        check(lookups[0] == 2, "another country is looked up on its own");

        check(RelationsTickCache.strength(rome) == null, "strength unknown until computed");
        RelationsTickCache.rememberStrength(rome, 12);
        check(RelationsTickCache.strength(rome) == 12 && RelationsTickCache.strength(carthage) == null, "strength per country");
        Set<Long> claims = Set.of(1L, 2L);
        RelationsTickCache.rememberClaims(carthage, claims);
        check(RelationsTickCache.claims(carthage) == claims, "claims per country");
        RelationsTickCache.end();

        check(RelationsTickCache.strength(rome) == null && RelationsTickCache.claims(carthage) == null, "a pass leaves nothing behind");
        RelationsTickCache.begin();
        check(RelationsTickCache.strength(rome) == null, "a new pass starts empty");
        RelationsTickCache.end();

        boxes();
        System.out.println("RelationsTickCacheTest: " + checks + " checks passed (pure cache lifecycle)");
    }

    /** ChunkPos.asLong packing: x in the low 32 bits, z in the high 32 bits. */
    private static long chunk(int x, int z) { return (long) x & 0xFFFFFFFFL | ((long) z & 0xFFFFFFFFL) << 32; }

    /** The same probe as Relations.claimsAdjacent: a chunk of the first within gap of the second. */
    private static boolean adjacent(Set<Long> first, Set<Long> second, int gap) {
        for (long c : first)
            for (int dx = -gap; dx <= gap; dx++)
                for (int dz = -gap; dz <= gap; dz++)
                    if (second.contains(chunk((int) c + dx, (int) (c >>> 32) + dz))) return true;
        return false;
    }

    private static void boxes() {
        int[] box = RelationsTickCache.bounds(Set.of(chunk(-3, 7), chunk(5, -2), chunk(0, 0)));
        check(box[0] == -3 && box[1] == 5 && box[2] == -2 && box[3] == 7, "box of negative and positive chunks");
        check(RelationsTickCache.bounds(Set.of()) == null, "no claims, no box");
        // A box test never says "apart" for countries that do border each other.
        java.util.Random random = new java.util.Random(7);
        int agreed = 0;
        for (int trial = 0; trial < 3000; trial++) {
            Set<Long> first = new java.util.HashSet<>(), second = new java.util.HashSet<>();
            int ox = random.nextInt(41) - 20, oz = random.nextInt(41) - 20, gap = random.nextInt(4);
            for (int i = 0; i < 1 + random.nextInt(6); i++) first.add(chunk(random.nextInt(9) - 4, random.nextInt(9) - 4));
            for (int i = 0; i < 1 + random.nextInt(6); i++) second.add(chunk(ox + random.nextInt(9) - 4, oz + random.nextInt(9) - 4));
            boolean apart = RelationsTickCache.apart(RelationsTickCache.bounds(first), RelationsTickCache.bounds(second), gap);
            if (apart && adjacent(first, second, gap)) throw new AssertionError("apart boxes for bordering claims, trial " + trial);
            if (apart) agreed++;
        }
        check(agreed > 1000, "most far-apart pairs are answered by their boxes: " + agreed);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
