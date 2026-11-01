package dev.frontier.aviation.control;

import java.util.Objects;

public record FlightTelemetry(FlightState state, float speed, float maximumPower, boolean brake, boolean secondaryBrake, boolean boost, ControlStatus status, long serverTick, long acknowledgedSequence) {
    public FlightTelemetry {
        Objects.requireNonNull(state, "state");
        FlightState.finite(speed, "speed");
        FlightState.finite(maximumPower, "maximumPower");
        Objects.requireNonNull(status, "status");
        if (acknowledgedSequence < -1) throw new IllegalArgumentException("acknowledgedSequence must be >= -1");
    }
}
