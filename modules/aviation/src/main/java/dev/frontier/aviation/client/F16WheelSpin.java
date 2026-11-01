package dev.frontier.aviation.client;

/** Tick-based visual wheel angle driven by signed ground travel, in blocks. */
public final class F16WheelSpin {
    private static final double MAIN_RADIUS = 11.52 / 32.0;
    private static final double NOSE_RADIUS = 9.28 / 32.0;
    private static final double TAU = Math.PI * 2;
    private double previousDistance;
    private double distance;

    public void tick(boolean grounded, boolean gearDown, double signedTravel) {
        previousDistance = distance;
        if (grounded && gearDown && Double.isFinite(signedTravel)) {
            // One tick cannot plausibly cover hundreds of blocks. Reject teleports.
            if (Math.abs(signedTravel) <= 16) distance += signedTravel;
        }
        // Preserve the same interpolation interval while bounding accumulated precision loss.
        if (Math.abs(distance) > 10000) {
            // The two radii differ; leave distance unwrapped to preserve both wheel phases.
            // Rebase only at an exact common circumference: 36 nose turns = 29 main turns.
            double offset = Math.floor(distance / (TAU * MAIN_RADIUS * 29)) * (TAU * MAIN_RADIUS * 29);
            previousDistance -= offset;
            distance -= offset;
        }
    }

    public float angle(float partialTick, boolean nose) {
        float partial = Float.isFinite(partialTick) ? Math.max(0, Math.min(1, partialTick)) : 0;
        double interpolated = previousDistance + (distance - previousDistance) * partial;
        // SBW wheel bones use the opposite X rotation to the signed forward
        // displacement measured in world coordinates.
        return (float) ((-interpolated / (nose ? NOSE_RADIUS : MAIN_RADIUS)) % TAU);
    }
}
