package dev.frontier.aviation.client;

/** Client-only visual interpolation for the F-16 air-brake pose. */
public final class F16AirBrakeSmoothing {
    private float previous;
    private float current;

    public void tick(boolean deployed) {
        previous = current;
        float target = deployed ? 1.0f : 0.0f;
        current += (target - current) * 0.35f;
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
