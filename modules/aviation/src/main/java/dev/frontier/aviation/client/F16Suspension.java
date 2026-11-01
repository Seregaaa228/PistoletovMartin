package dev.frontier.aviation.client;

/** Per-aircraft visual suspension state. Advance once per game tick, never per render frame. */
public final class F16Suspension {
    public static final float MAX_COMPRESSION = 4.5f;
    private static final float REST_COMPRESSION = 0.45f;
    private static final float MAX_DESCENT = 1.5f;
    private float previous;
    private float current;
    private float previousDescent;
    private float impact;
    private boolean initialized;
    private boolean wasGrounded;

    /** Vertical velocity is in blocks per tick; negative values mean descent. */
    public void tick(boolean grounded, boolean gearDown, double verticalVelocity) {
        previous = current;
        boolean supported = grounded && gearDown;
        if (initialized && grounded && !wasGrounded && gearDown) {
            impact = Math.min(MAX_COMPRESSION - REST_COMPRESSION, previousDescent * 4.0f);
        }
        if (!supported) {
            impact = 0;
        }
        float target = supported ? REST_COMPRESSION + impact : 0;
        current = clamp(current + (target - current) * (supported ? 0.45f : 0.3f), 0, MAX_COMPRESSION);
        impact *= 0.80f;
        previousDescent = Double.isFinite(verticalVelocity)
                ? (float) Math.min(MAX_DESCENT, Math.max(0, -verticalVelocity)) : 0;
        wasGrounded = grounded;
        initialized = true;
    }

    /** Interpolate tick states; invalid partial ticks select the previous state. */
    public float compression(float partialTick) {
        float partial = Float.isFinite(partialTick) ? clamp(partialTick, 0, 1) : 0;
        return previous + (current - previous) * partial;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
