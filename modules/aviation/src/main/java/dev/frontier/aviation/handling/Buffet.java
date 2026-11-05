package dev.frontier.aviation.handling;

/**
 * Visual-only airframe buffet: near the stall, at a high angle of attack, in the bumpy
 * air close to the ground at speed, and a light rumble on the take-off roll. It never
 * feeds the flight model, so it cannot desynchronise the server and client prediction.
 */
public final class Buffet {
    private Buffet() {}

    /** Most camera pitch / roll shake (degrees) at full intensity. */
    public static final float PITCH_AMPLITUDE = .35f, ROLL_AMPLITUDE = .5f;
    /** Ground turbulence fades out above this height (blocks). */
    public static final float TURBULENCE_HEIGHT = 14;

    public record Shake(float pitch, float roll) {}

    /**
     * @param speed      airspeed in blocks/tick
     * @param stallSpeed stall speed in blocks/tick (0: no stall model, no aerodynamic buffet)
     * @param aoa        angle between the nose and the flight path, degrees
     * @param clearance  height of the airframe above the ground, blocks
     * @return 0 (smooth) .. 1 (heavy buffet)
     */
    public static float intensity(float speed, float stallSpeed, float aoa, float clearance, boolean onGround) {
        if (!Float.isFinite(speed) || speed < 0) return 0;
        if (onGround) return .25f * clamp(speed / 3f);
        if (!(stallSpeed > 0)) return 0;
        float nearStall = clamp((1.3f * stallSpeed - speed) / (.3f * stallSpeed));
        float highAoa = Float.isFinite(aoa) ? clamp((aoa - 8) / 12) * clamp(speed / stallSpeed) : 0;
        float low = Float.isFinite(clearance) ? clamp(1 - clearance / TURBULENCE_HEIGHT) : 0;
        float turbulence = .5f * low * clamp((speed - stallSpeed) / (2 * stallSpeed));
        return clamp(Math.max(nearStall, highAoa) + turbulence);
    }

    /** Smooth pseudo-random shake at a few hertz; {@code seed} decorrelates aircraft. */
    public static Shake shake(double timeTicks, int seed, float intensity, float strength) {
        float scale = clamp(intensity) * Math.max(0, Float.isFinite(strength) ? strength : 0);
        if (scale == 0 || !Double.isFinite(timeTicks)) return new Shake(0, 0);
        double t = timeTicks / 20.0, phase = (seed & 1023) * 0.618;
        // Incommensurate frequencies (Hz) never line up into a visible repeating pattern.
        double p = .5 * Math.sin(2 * Math.PI * 7.3 * t + phase) + .3 * Math.sin(2 * Math.PI * 11.9 * t + 2.1 * phase)
            + .2 * Math.sin(2 * Math.PI * 3.7 * t + 0.7 * phase);
        double r = .5 * Math.sin(2 * Math.PI * 6.1 * t + 1.3 * phase) + .3 * Math.sin(2 * Math.PI * 9.7 * t + 0.4 * phase)
            + .2 * Math.sin(2 * Math.PI * 4.3 * t + 2.9 * phase);
        return new Shake((float) (p * PITCH_AMPLITUDE * scale), (float) (r * ROLL_AMPLITUDE * scale));
    }

    private static float clamp(float value) { return Math.max(0, Math.min(1, value)); }
}
