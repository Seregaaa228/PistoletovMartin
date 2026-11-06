package dev.frontier.aviation.client;

/** Client-only visual interpolation of a two-state pose (air brake, canopy). */
public final class F16AirBrakeSmoothing {
    private final float rate;
    private float previous;
    private float current;

    public F16AirBrakeSmoothing() { this(0.35f); }

    /** rate: fraction of the remaining travel covered per tick. */
    public F16AirBrakeSmoothing(float rate) { this.rate = rate; }

    public void tick(boolean deployed) {
        previous = current;
        float target = deployed ? 1.0f : 0.0f;
        current += (target - current) * rate;
        if (Math.abs(target - current) < 0.001f) current = target;
    }

    public float at(float partialTick) {
        float partial = Math.max(0.0f, Math.min(1.0f, partialTick));
        return previous + (current - previous) * partial;
    }

    public float value() {
        return current;
    }
}
