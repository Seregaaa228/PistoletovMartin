package dev.frontier.aviation.aircraft;

/**
 * Engine and handling of one of our jets on top of the native SBW aircraft engine.
 * Native thrust is 0.047 * power * SpeedRate against v * (0.0016 + 0.001768 * v^2) of drag
 * (v in blocks/tick), so the top speed in level flight follows from {@code maxPower}.
 *
 * @param maxPower    engine power at full throttle (native jets: 1)
 * @param boostPower  power with afterburner/boost held (native jets: 3)
 * @param throttleRate multiplier of how fast power follows the throttle
 * @param pitchRate   multiplier of the pitch rate
 * @param rollRate    multiplier of the roll rate
 * @param stallKmh    below this airspeed the nose drops and cannot be pulled further up
 */
public record AircraftPerformance(float maxPower, float boostPower, float throttleRate,
                                  float pitchRate, float rollRate, float stallKmh) {
    public AircraftPerformance {
        if (!(maxPower > 0.02f) || boostPower < maxPower || throttleRate <= 0 || pitchRate <= 0 || rollRate <= 0 || stallKmh < 0)
            throw new IllegalArgumentException("invalid aircraft performance");
    }

    /** Stall speed in blocks per tick. */
    public float stallSpeed() { return stallKmh / 72f; }
}
