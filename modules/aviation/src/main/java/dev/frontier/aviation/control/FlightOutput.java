package dev.frontier.aviation.control;

import java.util.Objects;

public record FlightOutput(FlightState state, float nativePower, boolean propulsionEnabled, boolean brake, boolean secondaryBrake, boolean boost, ControlStatus status) {
    public FlightOutput {
        Objects.requireNonNull(state, "state");
        FlightState.finite(nativePower, "nativePower");
        Objects.requireNonNull(status, "status");
    }
}
