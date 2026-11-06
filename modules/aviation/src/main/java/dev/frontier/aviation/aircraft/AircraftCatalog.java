package dev.frontier.aviation.aircraft;

import dev.frontier.aviation.control.FlightKind;
import dev.frontier.aviation.control.FlightProfile;
import dev.frontier.aviation.handling.FlightDynamics;
import dev.frontier.aviation.handling.HandlingProfile;
import dev.frontier.aviation.weapons.WeaponLoadout;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Single source for aircraft capabilities on both physical sides. No world or registry access. */
public final class AircraftCatalog {
    private static final FlightProfile FIGHTER = new FlightProfile("fighter", FlightKind.AIRCRAFT, 4, 2, 40);
    private static final FlightProfile AGILE_FIGHTER = new FlightProfile("agile_fighter", FlightKind.AIRCRAFT, 3, 2, 35);
    private static final FlightProfile PROPELLER = new FlightProfile("propeller", FlightKind.AIRCRAFT, 5, 3, 40);
    private static final FlightProfile ATTACK = new FlightProfile("attack", FlightKind.AIRCRAFT, 6, 3, 40);
    private static final FlightProfile AC130 = new FlightProfile("ac130", FlightKind.AIRCRAFT, 8, 4, 60);
    private static final FlightProfile AH6 = new FlightProfile("ah6", FlightKind.HELICOPTER, 5, 3, 40);
    private static final FlightProfile MI28 = new FlightProfile("mi28", FlightKind.HELICOPTER, 7, 4, 60);
    private static final Map<String, AircraftDefinition> DEFINITIONS = index(List.of(
        new AircraftDefinition("frontier_aviation:su25", ATTACK, HandlingProfile.ATTACK,
            WeaponLoadout.SU25, true, new AircraftModel(8, 6.5f,
                List.of(new AircraftModel.Exhaust(0.71, 2.29, -3.51),
                    new AircraftModel.Exhaust(-0.71, 2.29, -3.51)), 1.2f)),
        new AircraftDefinition("frontier_aviation:f16", AGILE_FIGHTER, HandlingProfile.FIGHTER,
            WeaponLoadout.F16, true, new AircraftModel(8, 6.5f,
                List.of(new AircraftModel.Exhaust(0, 2.46, -6.11)), 1.5f)),
        new AircraftDefinition("frontier_aviation:j10", FIGHTER, HandlingProfile.FIGHTER,
            WeaponLoadout.J10, true, new AircraftModel(8, 6.5f,
                List.of(new AircraftModel.Exhaust(0, 2.55, -7.4)), 1.5f)),
        nativeAircraft("superbwarfare:j_16", FIGHTER),
        nativeAircraft("superbwarfare:kv_16", FIGHTER),
        nativeAircraft("superbwarfare:ju_87", PROPELLER),
        nativeAircraft("superbwarfare:tom_6", PROPELLER),
        nativeAircraft("deepstrike:su24m", ATTACK),
        nativeAircraft("superbwarfare:a_10a", ATTACK),
        nativeAircraft("superbwarfare:ac_130h", AC130),
        nativeAircraft("superbwarfare:ah_6", AH6),
        nativeAircraft("superbwarfare:mi_28", MI28)
    ));
    /**
     * Our three jets fly differently: the Su-25 is a slow, heavy, forgiving attack jet;
     * the F-16 the fastest and most agile; the J-10 between them. Top speeds at full
     * throttle / with boost: Su-25 ~190 / ~260, J-10 ~240 / ~320, F-16 ~260 / ~340 km/h.
     * Native aircraft keep the native engine (power 1, boost 3) and no stall.
     */
    private static final Map<String, AircraftPerformance> PERFORMANCE = Map.of(
        "frontier_aviation:su25", new AircraftPerformance(0.82f, 2.0f, 0.75f, 0.8f, 0.75f, 90),
        "frontier_aviation:j10", new AircraftPerformance(1.46f, 3.3f, 1.2f, 1.05f, 1.1f, 110),
        "frontier_aviation:f16", new AircraftPerformance(1.8f, 3.9f, 1.4f, 1.15f, 1.35f, 115));
    /**
     * Control feel of the same three jets (inertia, coordinated turn, nose-wheel steering,
     * brakes, buffet): Su-25 heavy and steady, F-16 crisp, J-10 between. Native: none.
     */
    private static final Map<String, FlightDynamics> DYNAMICS = Map.of(
        "frontier_aviation:su25", FlightDynamics.SU25,
        "frontier_aviation:j10", FlightDynamics.J10,
        "frontier_aviation:f16", FlightDynamics.F16);
    private static final List<AircraftDefinition> ALL = List.copyOf(DEFINITIONS.values());
    private static final List<AircraftDefinition> MODELS = ALL.stream()
        .filter(AircraftDefinition::registersModel).toList();

    private AircraftCatalog() {}

    private static AircraftDefinition nativeAircraft(String id, FlightProfile profile) {
        return new AircraftDefinition(id, profile, HandlingProfile.NATIVE, WeaponLoadout.NONE, false, null);
    }

    public static Optional<AircraftDefinition> find(String entityTypeId) {
        return Optional.ofNullable(DEFINITIONS.get(entityTypeId));
    }

    public static Optional<AircraftPerformance> performance(String entityTypeId) {
        return Optional.ofNullable(PERFORMANCE.get(entityTypeId));
    }

    /** Per-jet control dynamics; {@link FlightDynamics#NONE} keeps the original model. */
    public static FlightDynamics dynamics(String entityTypeId) {
        return DYNAMICS.getOrDefault(entityTypeId, FlightDynamics.NONE);
    }

    public static List<AircraftDefinition> all() { return ALL; }
    public static List<AircraftDefinition> models() { return MODELS; }

    static Map<String, AircraftDefinition> index(Collection<AircraftDefinition> definitions) {
        Map<String, AircraftDefinition> index = new LinkedHashMap<>();
        for (AircraftDefinition definition : definitions) {
            if (index.putIfAbsent(definition.typeId(), definition) != null)
                throw new IllegalArgumentException("duplicate aircraft definition: " + definition.typeId());
        }
        return Collections.unmodifiableMap(index);
    }
}
