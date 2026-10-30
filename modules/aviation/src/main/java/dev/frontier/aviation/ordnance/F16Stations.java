package dev.frontier.aviation.ordnance;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Physical F-16 stations, ordered inner pair, outer pair, wingtip pair. */
public final class F16Stations {
    public static final int COUNT = 6;

    public enum Store {
        STORM_SHADOW(0b000011),
        UNDERWING(0b001111),
        LIGHT_AIR_TO_AIR(0b111111);

        final int stations;
        Store(int stations) { this.stations = stations; }
    }

    private F16Stations() {}

    /**
     * Find a collision-free assignment. Fixed stores represent native occupied
     * stations, whose position cannot be changed by the allocator. Restrictive
     * stores are assigned first so that mixed loads do not waste wingtip rails.
     */
    public static Optional<List<Store>> assign(Store[] fixed, List<Store> stores) {
        if (fixed.length != COUNT || stores.size() > COUNT) return Optional.empty();
        Store[] result = fixed.clone();
        for (int i = 0; i < COUNT; i++) {
            if (result[i] != null && (result[i].stations & (1 << i)) == 0) return Optional.empty();
        }
        Store[] pending = stores.toArray(Store[]::new);
        if (Arrays.stream(pending).anyMatch(java.util.Objects::isNull)) return Optional.empty();
        Arrays.sort(pending, java.util.Comparator.comparingInt(s -> Integer.bitCount(s.stations)));
        if (!assign(result, pending, 0)) return Optional.empty();
        // List.of does not allow empty stations, so expose an unmodifiable copy.
        return Optional.of(java.util.Collections.unmodifiableList(Arrays.asList(result.clone())));
    }

    private static boolean assign(Store[] result, Store[] pending, int index) {
        if (index == pending.length) return true;
        Store store = pending[index];
        // Prefer wingtip rails for light missiles, preserving general-purpose pylons.
        for (int step = 0; step < COUNT; step++) {
            int station = store == Store.LIGHT_AIR_TO_AIR ? (step + 4) % COUNT : step;
            if (result[station] != null || (store.stations & (1 << station)) == 0) continue;
            result[station] = store;
            if (assign(result, pending, index + 1)) return true;
            result[station] = null;
        }
        return false;
    }
}
