package dev.frontier.aviation.control;

import java.util.Objects;
import java.util.UUID;

public record FlightSnapshot(String dimensionId, int entityId, UUID vehicleId, UUID controllerId, long epoch, FlightTelemetry telemetry) {
    public FlightSnapshot {
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(vehicleId, "vehicleId");
        Objects.requireNonNull(telemetry, "telemetry");
        if (epoch <= 0) throw new IllegalArgumentException("epoch must be positive");
    }
}
