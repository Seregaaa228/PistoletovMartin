package dev.frontier.aviation.control;

import java.util.Objects;

public record FlightEnvironment(
    long tick, float speed, boolean onGround,
    boolean engineHealthy, boolean energyAvailable,
    boolean gearAvailable, boolean hoverAvailable, boolean nativeEmergency,
    AxisCaps caps,
    float idlePower, float maximumPower, float boostMaximumPower,
    float throttleIncrease, float throttleDecrease, float secondaryDecrease,
    int throttleRampUpTicks, int throttleRampDownTicks,
    float airborneMinimumPower, float secondaryMinimumPower,
    float airBrakePowerCap, float rotorSpool, float boostThrottleIncrease
) {
    /** Older callers describe an engine whose boost does not change its rise rate. */
    public FlightEnvironment(long tick, float speed, boolean onGround,
        boolean engineHealthy, boolean energyAvailable, boolean gearAvailable, boolean hoverAvailable, boolean nativeEmergency,
        AxisCaps caps, float idlePower, float maximumPower, float boostMaximumPower,
        float throttleIncrease, float throttleDecrease, float secondaryDecrease,
        int throttleRampUpTicks, int throttleRampDownTicks, float airborneMinimumPower,
        float secondaryMinimumPower, float airBrakePowerCap, float rotorSpool) {
        this(tick, speed, onGround, engineHealthy, energyAvailable, gearAvailable, hoverAvailable, nativeEmergency,
            caps, idlePower, maximumPower, boostMaximumPower, throttleIncrease, throttleDecrease, secondaryDecrease,
            throttleRampUpTicks, throttleRampDownTicks, airborneMinimumPower, secondaryMinimumPower,
            airBrakePowerCap, rotorSpool, throttleIncrease);
    }
    public FlightEnvironment {
        FlightState.finite(speed, "speed");
        Objects.requireNonNull(caps, "caps");
        FlightState.finite(idlePower, "idlePower");
        FlightState.finite(maximumPower, "maximumPower");
        FlightState.finite(boostMaximumPower, "boostMaximumPower");
        FlightState.finite(throttleIncrease, "throttleIncrease");
        FlightState.finite(boostThrottleIncrease, "boostThrottleIncrease");
        FlightState.finite(throttleDecrease, "throttleDecrease");
        FlightState.finite(secondaryDecrease, "secondaryDecrease");
        if (throttleRampUpTicks < 1 || throttleRampUpTicks > 10 || throttleRampDownTicks < 1 || throttleRampDownTicks > 10)
            throw new IllegalArgumentException("ramp ticks must be in [1,10]");
        FlightState.finite(airborneMinimumPower, "airborneMinimumPower");
        FlightState.finite(secondaryMinimumPower, "secondaryMinimumPower");
        FlightState.finite(airBrakePowerCap, "airBrakePowerCap");
        FlightState.unit(rotorSpool, "rotorSpool");
        if (speed < 0 || idlePower < 0 || maximumPower <= idlePower || boostMaximumPower < maximumPower
                || boostThrottleIncrease < throttleIncrease || throttleIncrease < 0 || throttleDecrease < 0 || secondaryDecrease < 0
                || airborneMinimumPower < 0 || secondaryMinimumPower < 0 || airBrakePowerCap < 0)
            throw new IllegalArgumentException("invalid native power environment");
    }

    public record AxisCaps(float pitchNegative, float pitchPositive, float yawNegative, float yawPositive, float rollNegative, float rollPositive) {
        public AxisCaps {
            nonnegative(pitchNegative, "pitchNegative"); nonnegative(pitchPositive, "pitchPositive");
            nonnegative(yawNegative, "yawNegative"); nonnegative(yawPositive, "yawPositive");
            nonnegative(rollNegative, "rollNegative"); nonnegative(rollPositive, "rollPositive");
        }
        private static void nonnegative(float value, String name) {
            FlightState.finite(value, name);
            if (value < 0) throw new IllegalArgumentException(name + " must be nonnegative");
        }
    }
}
