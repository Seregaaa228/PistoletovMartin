package dev.frontier.aviation.control;

import java.util.Objects;

public record FlightProfile(String id, FlightKind kind, int responseTicks, int dampingTicks, int startupTicks) {
    public FlightProfile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        if (responseTicks <= 0 || dampingTicks <= 0 || startupTicks <= 0) {
            throw new IllegalArgumentException("profile ticks must be positive");
        }
    }
}
