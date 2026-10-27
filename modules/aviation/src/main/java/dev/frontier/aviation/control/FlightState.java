package dev.frontier.aviation.control;

import java.util.Objects;

public record FlightState(
    float pitch, float yaw, float roll,
    float pitchRate, float yawRate, float rollRate,
    float targetThrottle, float effectivePower,
    EnginePhase enginePhase, int phaseTicks, float spool,
    boolean gearDown, boolean hover, int throttleHoldTicks
) {
    public FlightState {
        finite(pitch, "pitch"); finite(yaw, "yaw"); finite(roll, "roll");
        finite(pitchRate, "pitchRate"); finite(yawRate, "yawRate"); finite(rollRate, "rollRate");
        unit(targetThrottle, "targetThrottle"); finite(effectivePower, "effectivePower");
        Objects.requireNonNull(enginePhase, "enginePhase");
        if (phaseTicks < 0) throw new IllegalArgumentException("phaseTicks must be nonnegative");
        if (throttleHoldTicks < -10 || throttleHoldTicks > 10) throw new IllegalArgumentException("throttleHoldTicks must be in [-10,10]");
        unit(spool, "spool");
    }
    static void finite(float value, String name) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
    }
    static void unit(float value, String name) {
        finite(value, name);
        if (value < 0 || value > 1) throw new IllegalArgumentException(name + " must be in [0,1]");
    }
}
