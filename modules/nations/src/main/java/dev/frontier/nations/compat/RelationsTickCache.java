package dev.frontier.nations.compat;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Recruits Villages' diplomacy pass scores every pair of countries, and each score looks up
 * the villages, strength and claimed chunks of the same countries again; the village lookup
 * itself scans every faction. With ~60 countries one pass took 0.2-0.36 s of a server tick.
 * Nothing in the pass changes village membership, recruit counts or claims (it only sets
 * diplomatic status), so within one pass each answer is computed once per country, and
 * the border check of two countries whose claims are far apart is answered from their
 * bounding boxes instead of probing every chunk around every claim.
 * Server thread only; outside a pass every call goes straight through.
 */
public final class RelationsTickCache {
    private static boolean active;
    private static final Map<Object, List<?>> VILLAGES = new IdentityHashMap<>();
    private static final Map<Object, Integer> STRENGTH = new IdentityHashMap<>();
    private static final Map<Object, Set<Long>> CLAIMS = new IdentityHashMap<>();
    private static final Map<Object, int[]> BOXES = new IdentityHashMap<>();

    private RelationsTickCache() {}

    public static void begin() {
        clear();
        active = true;
    }

    public static void end() {
        active = false;
        clear();
    }

    private static void clear() {
        VILLAGES.clear();
        STRENGTH.clear();
        CLAIMS.clear();
        BOXES.clear();
    }

    /** A fresh copy each time, as the original returns a new list. */
    @SuppressWarnings("unchecked")
    public static <T> List<T> villages(Object faction, Function<Object, List<T>> lookup) {
        if (!active) return lookup.apply(faction);
        return new ArrayList<>((List<T>) VILLAGES.computeIfAbsent(faction, lookup::apply));
    }

    /** Null outside a pass or before the first computation for this country. */
    public static Integer strength(Object faction) { return active ? STRENGTH.get(faction) : null; }

    public static void rememberStrength(Object faction, int strength) {
        if (active) STRENGTH.put(faction, strength);
    }

    /** Callers only read the set (contains, isEmpty, iteration). */
    public static Set<Long> claims(Object faction) { return active ? CLAIMS.get(faction) : null; }

    public static void rememberClaims(Object faction, Set<Long> claims) {
        if (active) CLAIMS.put(faction, claims);
    }

    public static boolean active() { return active; }

    /** {minX, maxX, minZ, maxZ} of packed chunk positions (ChunkPos.asLong), or null for none. */
    public static int[] box(Object faction, Set<Long> chunks) {
        if (!active) return bounds(chunks);
        return BOXES.computeIfAbsent(faction, key -> bounds(chunks));
    }

    static int[] bounds(Set<Long> chunks) {
        if (chunks == null || chunks.isEmpty()) return null;
        int[] box = {Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE};
        for (long chunk : chunks) {
            int x = (int) chunk, z = (int) (chunk >>> 32);
            box[0] = Math.min(box[0], x);
            box[1] = Math.max(box[1], x);
            box[2] = Math.min(box[2], z);
            box[3] = Math.max(box[3], z);
        }
        return box;
    }

    /**
     * Two claims are adjacent only if some chunks lie within {@code gap} of each other on both
     * axes, which needs the boxes grown by {@code gap} to overlap: apart boxes mean not adjacent.
     */
    public static boolean apart(int[] a, int[] b, int gap) {
        return (long) a[1] + gap < b[0] || (long) b[1] + gap < a[0] || (long) a[3] + gap < b[2] || (long) b[3] + gap < a[2];
    }
}
