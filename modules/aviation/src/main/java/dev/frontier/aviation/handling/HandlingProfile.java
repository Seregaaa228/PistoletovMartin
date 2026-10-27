package dev.frontier.aviation.handling;

/**
 * Declarative aerodynamic calibration layered on top of the native aircraft
 * integrator. The native engine remains responsible for movement integration.
 */
public record HandlingProfile(
    boolean nativeOnly,
    float lowSpeedDamping,
    float highSpeedDamping,
    float fullAuthoritySpeed,
    float maximumCorrectionPerTick
) {
    public static final HandlingProfile NATIVE =
        new HandlingProfile(true, 0, 0, 0, 0);

    /** Responsive high-speed handling with a small bounded sideslip correction. */
    public static final HandlingProfile FIGHTER =
        new HandlingProfile(false, .035f, .053f, .22f, .018f);

    /** More stable attack-aircraft handling at low speed and during banked turns. */
    public static final HandlingProfile ATTACK =
        new HandlingProfile(false, .045f, .059f, .18f, .016f);

    public HandlingProfile {
        finiteNonNegative(lowSpeedDamping, "lowSpeedDamping");
        finiteNonNegative(highSpeedDamping, "highSpeedDamping");
        if (lowSpeedDamping > 1 || highSpeedDamping > 1)
            throw new IllegalArgumentException("damping must be in [0,1]");
        finiteNonNegative(fullAuthoritySpeed, "fullAuthoritySpeed");
        finiteNonNegative(maximumCorrectionPerTick, "maximumCorrectionPerTick");
        if (nativeOnly && (lowSpeedDamping != 0 || highSpeedDamping != 0
            || fullAuthoritySpeed != 0 || maximumCorrectionPerTick != 0)) {
            throw new IllegalArgumentException("nativeOnly profile cannot add aerodynamic forces");
        }
    }

    private static void finiteNonNegative(float value, String name) {
        if (!Float.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(name + " must be finite and nonnegative");
        }
    }
}
