package dev.frontier.aviation.aircraft;

import dev.frontier.aviation.control.FlightKind;
import dev.frontier.aviation.control.FlightProfile;
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
    private static final FlightProfile PROPELLER = new FlightProfile("propeller", FlightKind.AIRCRAFT, 5, 3, 40);
    private static final FlightProfile ATTACK = new FlightProfile("attack", FlightKind.AIRCRAFT, 6, 3, 40);
    private static final FlightProfile AC130 = new FlightProfile("ac130", FlightKind.AIRCRAFT, 8, 4, 60);
    private static final FlightProfile AH6 = new FlightProfile("ah6", FlightKind.HELICOPTER, 5, 3, 40);
    private static final FlightProfile MI28 = new FlightProfile("mi28", FlightKind.HELICOPTER, 7, 4, 60);
    private static final Map<String, AircraftDefinition> DEFINITIONS = index(List.of(
        new AircraftDefinition("frontier_aviation:su25", ATTACK, HandlingProfile.ATTACK,
            WeaponLoadout.CANNON_ONLY, true, new AircraftModel(8, 6.5f,
                List.of(new AircraftModel.Exhaust(1.03, 2.35, -6.96),
                    new AircraftModel.Exhaust(-1.03, 2.35, -6.96)), 1.2f)),
        new AircraftDefinition("frontier_aviation:f16", FIGHTER, HandlingProfile.FIGHTER,
            WeaponLoadout.F16, true, new AircraftModel(8, 6.5f,
                List.of(new AircraftModel.Exhaust(0, 2.46, -7.18)), 1.5f)),
        new AircraftDefinition("frontier_aviation:j10", FIGHTER, HandlingProfile.FIGHTER,
            WeaponLoadout.CANNON_ONLY, true, new AircraftModel(8, 6.5f,
                List.of(new AircraftModel.Exhaust(0, 2.47, -7.36)), 1.5f)),
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
