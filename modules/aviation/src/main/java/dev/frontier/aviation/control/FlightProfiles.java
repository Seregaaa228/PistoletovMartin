package dev.frontier.aviation.control;

import dev.frontier.aviation.aircraft.AircraftCatalog;
import java.util.Optional;

public final class FlightProfiles {
    private FlightProfiles() {}

    public static Optional<FlightProfile> find(String entityTypeId) {
        return AircraftCatalog.find(entityTypeId).map(definition -> definition.flight());
    }
}
