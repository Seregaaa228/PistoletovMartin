package dev.frontier.aviation.client;

/** Render-only, tick-based surface animation for one aircraft. Axes are normalized to [-1, 1]. */
public final class F16ControlSurfaces {
    public record Axes(float roll, float pitch, float yaw) {}

    private static final float RESPONSE = 0.45f;
    private Axes previous = new Axes(0, 0, 0);
    private Axes current = previous;
    private boolean initialized;
    private float lastRoll, lastPitch, lastYaw;

    /**
     * Local input drives each surface immediately. When a mouse impulse ends, use
     * the observed angular rate so the surface follows the continuing maneuver.
     * Native AI without synchronized rates uses measured angular displacement.
     */
    public void tick(Axes input, Axes rates, float roll, float pitch, float yaw) {
        float deltaRoll = initialized ? difference(roll, lastRoll) : 0;
        float deltaPitch = initialized ? difference(pitch, lastPitch) : 0;
        float deltaYaw = initialized ? difference(yaw, lastYaw) : 0;
        lastRoll = finite(roll); lastPitch = finite(pitch); lastYaw = finite(yaw);
        initialized = true;
        Axes motion = rates != null ? fromRates(rates.roll, rates.pitch, rates.yaw)
            : fromRates(deltaRoll, deltaPitch, deltaYaw);
        Axes target = input == null ? motion : combine(normalize(input), motion);
        previous = current;
        current = new Axes(approach(current.roll, target.roll), approach(current.pitch, target.pitch),
            approach(current.yaw, target.yaw));
    }

    public Axes at(float partialTick) {
        float t = Float.isFinite(partialTick) ? Math.max(0, Math.min(1, partialTick)) : 0;
        return new Axes(lerp(previous.roll, current.roll, t), lerp(previous.pitch, current.pitch, t),
            lerp(previous.yaw, current.yaw, t));
    }

    private static Axes fromRates(float roll, float pitch, float yaw) {
        return new Axes(clamp(roll / 1.5f), clamp(pitch / 0.8f), clamp(yaw / 0.8f));
    }
    private static Axes combine(Axes input, Axes motion) {
        return new Axes(preferInput(input.roll, motion.roll), preferInput(input.pitch, motion.pitch),
            preferInput(input.yaw, motion.yaw));
    }
    private static float preferInput(float input, float motion) {
        return Math.abs(input) > 0.01f ? input : motion;
    }
    private static Axes normalize(Axes axes) {
        return new Axes(clamp(axes.roll), clamp(axes.pitch), clamp(axes.yaw));
    }
    private static float difference(float value, float old) {
        if (!Float.isFinite(value)) return 0;
        float delta = (value - old) % 360;
        if (delta >= 180) delta -= 360;
        if (delta < -180) delta += 360;
        return delta;
    }
    private static float finite(float value) { return Float.isFinite(value) ? value : 0; }
    private static float clamp(float value) { return Math.max(-1, Math.min(1, finite(value))); }
    private static float approach(float from, float to) { return from + (to - from) * RESPONSE; }
    private static float lerp(float from, float to, float t) { return from + (to - from) * t; }
}
