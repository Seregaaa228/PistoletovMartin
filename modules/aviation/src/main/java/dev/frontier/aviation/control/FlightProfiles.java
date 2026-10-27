package dev.frontier.aviation.control;

import java.util.Map;
import java.util.Optional;

public final class FlightProfiles {
    private static final FlightProfile FIGHTER = new FlightProfile("fighter", FlightKind.AIRCRAFT, 4, 2, 40);
    private static final FlightProfile PROPELLER = new FlightProfile("propeller", FlightKind.AIRCRAFT, 5, 3, 40);
    private static final FlightProfile ATTACK = new FlightProfile("attack", FlightKind.AIRCRAFT, 6, 3, 40);
    private static final FlightProfile AC130 = new FlightProfile("ac130", FlightKind.AIRCRAFT, 8, 4, 60);
    private static final FlightProfile AH6 = new FlightProfile("ah6", FlightKind.HELICOPTER, 5, 3, 40);
    private static final FlightProfile MI28 = new FlightProfile("mi28", FlightKind.HELICOPTER, 7, 4, 60);
    private static final Map<String, FlightProfile> PROFILES = Map.ofEntries(
        Map.entry("frontier_aviation:f16", FIGHTER),
        Map.entry("frontier_aviation:j10", FIGHTER),
        Map.entry("superbwarfare:j_16", FIGHTER),
        Map.entry("superbwarfare:kv_16", FIGHTER),
        Map.entry("superbwarfare:ju_87", PROPELLER),
        Map.entry("superbwarfare:tom_6", PROPELLER),
        Map.entry("frontier_aviation:su25", ATTACK),
        Map.entry("deepstrike:su24m", ATTACK),
        Map.entry("superbwarfare:a_10a", ATTACK),
        Map.entry("superbwarfare:ac_130h", AC130),
        Map.entry("superbwarfare:ah_6", AH6),
        Map.entry("superbwarfare:mi_28", MI28)
    );

    private FlightProfiles() {}

    public static Optional<FlightProfile> find(String entityTypeId) {
        return Optional.ofNullable(PROFILES.get(entityTypeId));
    }
}
