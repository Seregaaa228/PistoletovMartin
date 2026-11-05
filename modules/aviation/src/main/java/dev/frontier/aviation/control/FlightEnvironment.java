package dev.frontier.aviation.control;

import dev.frontier.aviation.handling.FlightDynamics;
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
    float airBrakePowerCap, float rotorSpool, float boostThrottleIncrease, float stallSpeed,
    float groundClearance, FlightDynamics dynamics
) {
    /** Height callers do not sample: always treated as clear of the ground. */
    public static final float CLEAR = 1000;
    /** Callers without ground sampling or per-jet dynamics: the original control model. */
    public FlightEnvironment(long tick, float speed, boolean onGround,
        boolean engineHealthy, boolean energyAvailable, boolean gearAvailable, boolean hoverAvailable, boolean nativeEmergency,
        AxisCaps caps, float idlePower, float maximumPower, float boostMaximumPower,
        float throttleIncrease, float throttleDecrease, float secondaryDecrease,
        int throttleRampUpTicks, int throttleRampDownTicks, float airborneMinimumPower,
        float secondaryMinimumPower, float airBrakePowerCap, float rotorSpool, float boostThrottleIncrease, float stallSpeed) {
        this(tick, speed, onGround, engineHealthy, energyAvailable, gearAvailable, hoverAvailable, nativeEmergency,
            caps, idlePower, maximumPower, boostMaximumPower, throttleIncrease, throttleDecrease, secondaryDecrease,
            throttleRampUpTicks, throttleRampDownTicks, airborneMinimumPower, secondaryMinimumPower,
            airBrakePowerCap, rotorSpool, boostThrottleIncrease, stallSpeed, CLEAR, FlightDynamics.NONE);
    }
    /** Callers without a stall model (stall speed 0: the nose never drops by itself). */
    public FlightEnvironment(long tick, float speed, boolean onGround,
        boolean engineHealthy, boolean energyAvailable, boolean gearAvailable, boolean hoverAvailable, boolean nativeEmergency,
        AxisCaps caps, float idlePower, float maximumPower, float boostMaximumPower,
        float throttleIncrease, float throttleDecrease, float secondaryDecrease,
        int throttleRampUpTicks, int throttleRampDownTicks, float airborneMinimumPower,
        float secondaryMinimumPower, float airBrakePowerCap, float rotorSpool, float boostThrottleIncrease) {
        this(tick, speed, onGround, engineHealthy, energyAvailable, gearAvailable, hoverAvailable, nativeEmergency,
            caps, idlePower, maximumPower, boostMaximumPower, throttleIncrease, throttleDecrease, secondaryDecrease,
            throttleRampUpTicks, throttleRampDownTicks, airborneMinimumPower, secondaryMinimumPower,
            airBrakePowerCap, rotorSpool, boostThrottleIncrease, 0);
    }
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
            airBrakePowerCap, rotorSpool, throttleIncrease, 0);
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
        FlightState.finite(stallSpeed, "stallSpeed");
        FlightState.finite(groundClearance, "groundClearance");
        Objects.requireNonNull(dynamics, "dynamics");
        if (groundClearance < 0) throw new IllegalArgumentException("groundClearance must be nonnegative");
        if (speed < 0 || idlePower < 0 || maximumPower <= idlePower || boostMaximumPower < maximumPower
                || boostThrottleIncrease < throttleIncrease || throttleIncrease < 0 || throttleDecrease < 0 || secondaryDecrease < 0
                || airborneMinimumPower < 0 || secondaryMinimumPower < 0 || airBrakePowerCap < 0 || stallSpeed < 0)
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
