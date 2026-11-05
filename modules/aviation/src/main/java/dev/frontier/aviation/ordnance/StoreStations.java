package dev.frontier.aviation.ordnance;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Physical store stations of each owned airframe and the allocator that fills them.
 *
 * Positions are the attach points under the real pylons of the shipped geo models
 * (pylon/rail bottom), in the renderer frame: x = -vehicle x (+x is the right wing),
 * y up from the ground, z = -vehicle z (nose towards -Z), metres. A store hangs with
 * its top {@link #STORE_CLEARANCE} below that point. tools/check_aircraft_store_fit.py
 * parses this file and checks every station and store against the airframe.
 */
public final class StoreStations {
    /** The payload packet carries three bits per station. */
    public static final int MAX_STATIONS = 6;

    public enum Store {
        /** A DeepStrike tablet rack store: Storm Shadow or a КАБ. */
        STORM_SHADOW,
        /** AGM-65, 9M100 large AAM, wire-guided AGM. */
        UNDERWING,
        /** 9M336 light AAM. */
        LIGHT_AIR_TO_AIR
    }

    // Visual kinds in the payload bits: 0 empty, 1 tablet rack store, 2..5 SBW missiles.
    public static final int EMPTY = 0, RACK = 1, AGM_65 = 2, LARGE_AAM = 3, LIGHT_AAM = 4, WIRE_AGM = 5;
    /** Native gun names by visual kind (2..5); every owned airframe uses the same names. */
    public static final List<String> GUNS = List.of("Missile", "AAMissile", "LightAAMissile", "MediumAGMissile");

    // Hung store models measured by tools/check_aircraft_store_fit.py --describe:
    // top of the store above its model origin where it meets the pylon, and the aft
    // shift of the origin that centres the store under the attach point. Order:
    // agm_65, ru_9m100_missile, ru_9m336_missile, wire_guide_missile (SBW projectile
    // models, origin at the exhaust), storm_shadow (DeepStrike, without wings/fins,
    // after the renderer's -90 degree turn), kab_500 at scale 1, 1.25, 1.5.
    public static final double[] STORE_TOP = {0.1905, 0.1963, 0.1013, 0.3438, 0.4875, 0.28125, 0.3516, 0.4219};
    public static final double[] STORE_CENTER_Z = {1.86134, 1.25737, 0.85467, 1.03125, 0.75, 0.1875, 0.2344, 0.2812};
    public static final double STORE_CLEARANCE = 0.015;

    /** Index into STORE_TOP / STORE_CENTER_Z: SBW kind 2..5, Storm Shadow, КАБ variant 0..2. */
    public static int model(int kind, int kabVariant) {
        if (kind >= AGM_65 && kind <= WIRE_AGM) return kind - AGM_65;
        return kabVariant >= 0 ? 5 + Math.min(2, kabVariant) : 4;
    }

    /** Model origin of a hung store, renderer frame. */
    public static double[] hang(Station station, int model) {
        return new double[]{station.x(), station.y() - STORE_TOP[model] - STORE_CLEARANCE,
            station.z() + STORE_CENTER_Z[model]};
    }

    /** The same point in the vehicle frame (SBW "Vehicle" transform): launch point of the store. */
    public static double[] launch(Station station, int model) {
        double[] p = hang(station, model);
        return new double[]{-p[0], p[1], -p[2]};
    }

    /** A station: attach point (renderer frame), accepted stores, DeepStrike rack or -1. */
    public record Station(double x, double y, double z, int stores, int rack) {
        public boolean takes(Store store) { return (stores & 1 << store.ordinal()) != 0; }
    }

    public static final class Layout {
        private final String id;
        private final List<Station> stations;
        private final int[] racks;
        private final Map<Store, int[]> fill;
        private final Map<Store, int[]> fire;

        private Layout(String id, List<Station> stations, Map<Store, int[]> fill, Map<Store, int[]> fire) {
            if (stations.isEmpty() || stations.size() > MAX_STATIONS) throw new IllegalArgumentException("station count");
            this.id = id;
            this.stations = List.copyOf(stations);
            this.racks = this.stations.stream().filter(s -> s.rack() >= 0).mapToInt(s -> this.stations.indexOf(s)).toArray();
            this.fill = Map.copyOf(fill);
            this.fire = Map.copyOf(fire);
            for (var store : Store.values()) for (int station : order(store))
                if (!this.stations.get(station).takes(store)) throw new IllegalArgumentException(id + " order");
        }

        public String id() { return id; }
        public List<Station> stations() { return stations; }
        public int size() { return stations.size(); }
        /** DeepStrike racks this airframe uses (1 or 2); DeepStrike's own entity always has two. */
        public int racks() { return racks.length; }
        /** Station index holding DeepStrike rack {@code rack}, or -1. */
        public int rackStation(int rack) { return rack >= 0 && rack < racks.length ? racks[rack] : -1; }
        /** Stations a store class fills in, preferred first (only stations that take it). */
        public int[] order(Store store) { return fill.getOrDefault(store, new int[0]).clone(); }
        /** Stations a missile class fires from, first to last. */
        public int[] fireOrder(Store store) { return fire.getOrDefault(store, order(store)).clone(); }

        /**
         * Find a collision-free assignment. Fixed stores represent native occupied
         * stations, whose position cannot be changed by the allocator. Restrictive
         * stores are assigned first so that mixed loads do not waste special stations.
         */
        public Optional<List<Store>> assign(Store[] fixed, List<Store> stores) {
            int count = stations.size();
            if (fixed.length != count || stores.size() > count) return Optional.empty();
            Store[] result = fixed.clone();
            for (int i = 0; i < count; i++)
                if (result[i] != null && !stations.get(i).takes(result[i])) return Optional.empty();
            Store[] pending = stores.toArray(Store[]::new);
            if (Arrays.stream(pending).anyMatch(Objects::isNull)) return Optional.empty();
            Arrays.sort(pending, Comparator.comparingInt(s -> order(s).length));
            if (!assign(result, pending, 0)) return Optional.empty();
            // List.of does not allow empty stations, so expose an unmodifiable copy.
            return Optional.of(Collections.unmodifiableList(Arrays.asList(result.clone())));
        }

        private boolean assign(Store[] result, Store[] pending, int index) {
            if (index == pending.length) return true;
            Store store = pending[index];
            for (int station : order(store)) {
                if (result[station] != null) continue;
                result[station] = store;
                if (assign(result, pending, index + 1)) return true;
                result[station] = null;
            }
            return false;
        }
    }

    private static Station station(double x, double y, double z, Store... stores) {
        int mask = 0;
        for (Store store : stores) mask |= 1 << store.ordinal();
        return new Station(x, y, z, mask, -1);
    }

    /** Racks go to the tablet-capable stations in table order. */
    private static Layout layout(String id, List<Station> stations, Map<Store, int[]> fill, Map<Store, int[]> fire) {
        Station[] numbered = stations.toArray(Station[]::new);
        int rack = 0;
        for (int i = 0; i < numbered.length; i++) if (numbered[i].takes(Store.STORM_SHADOW))
            numbered[i] = new Station(numbered[i].x(), numbered[i].y(), numbered[i].z(), numbered[i].stores(), rack++);
        if (rack < 1 || rack > 2) throw new IllegalArgumentException(id + " needs one or two tablet racks");
        return new Layout(id, List.of(numbered), fill, fire);
    }

    private static final Store STORM = Store.STORM_SHADOW, WING = Store.UNDERWING, LIGHT = Store.LIGHT_AIR_TO_AIR;

    // Inner pylons (pylon_l/r, rail bottom y 2.2) take every store; outer pylons
    // (pylon_outer_*) the missiles; wingtip rails (f16_tip_pylon_*, rail bottom
    // 37.24 px, centre x 74.7 px) the light AAM. Indices and kinds are the saved
    // and synced F-16 station order.
    public static final Layout F16 = layout("f16", List.of(
        station(-2.1, 2.2, -0.2, STORM, WING, LIGHT),
        station(2.1, 2.2, -0.2, STORM, WING, LIGHT),
        station(-3.4, 2.2, -0.2, WING, LIGHT),
        station(3.4, 2.2, -0.2, WING, LIGHT),
        station(-4.669, 2.3275, 1.36, LIGHT),
        station(4.669, 2.3275, 1.36, LIGHT)),
        Map.of(STORM, new int[]{0, 1}, WING, new int[]{2, 3, 0, 1}, LIGHT, new int[]{4, 5, 0, 1, 2, 3}),
        Map.of(LIGHT, new int[]{4, 5, 2, 3, 0, 1}));

    // Su-25: tablet stores and AG missiles on the functional inner pylons (pylon_l/r);
    // AG missiles under the wing pylons at 3.61 m (hung 2 cm below the thicker wing), ahead of
    // the flaps; light AAMs on the outermost pylons at 5.10 m (bottom 37.745 px).
    public static final Layout SU25 = layout("su25", List.of(
        station(-2.1, 2.2, -0.2, STORM, WING),
        station(2.1, 2.2, -0.2, STORM, WING),
        station(-3.61, 2.4041, -0.4, WING),
        station(3.61, 2.4041, -0.4, WING),
        station(-5.1, 2.3591, 0.6675, LIGHT),
        station(5.1, 2.3591, 0.6675, LIGHT)),
        Map.of(STORM, new int[]{0, 1}, WING, new int[]{2, 3, 0, 1}, LIGHT, new int[]{4, 5}),
        Map.of());

    // J-10: two tablet stores do not fit under its wings, so it carries one on the
    // belly centreline, aft of the nose-gear doors (hung at 1.70 m under the rounded belly);
    // a missile (wire-guided AGM or large AAM) on each functional inner pylon and a
    // light AAM on each outer wing pylon at 3.76 m (bottom 31.6 px).
    public static final Layout J10 = layout("j10", List.of(
        station(0, 1.70, 2.2, STORM),
        station(-2.1, 2.2, -0.2, WING, LIGHT),
        station(2.1, 2.2, -0.2, WING, LIGHT),
        station(-3.76, 1.975, 3.2577, LIGHT),
        station(3.76, 1.975, 3.2577, LIGHT)),
        Map.of(STORM, new int[]{0}, WING, new int[]{1, 2}, LIGHT, new int[]{3, 4, 1, 2}),
        Map.of());

    private static final Map<String, Layout> BY_TYPE = Map.of(
        "frontier_aviation:su25", SU25, "frontier_aviation:f16", F16, "frontier_aviation:j10", J10);

    private StoreStations() {}

    /** Layout of an owned airframe by entity type ID; empty for DeepStrike's Su-24M and native aircraft. */
    public static Optional<Layout> forType(String entityTypeId) {
        return Optional.ofNullable(BY_TYPE.get(entityTypeId));
    }
}
