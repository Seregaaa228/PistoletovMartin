package dev.frontier.aviation.control;

import java.util.Objects;
import java.util.UUID;

public record ControlLease(UUID vehicleId, UUID controllerId, long epoch) {
    public ControlLease {
        Objects.requireNonNull(vehicleId, "vehicleId");
        Objects.requireNonNull(controllerId, "controllerId");
        if (epoch <= 0) throw new IllegalArgumentException("epoch must be positive");
    }
}
