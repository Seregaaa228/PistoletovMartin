package dev.frontier.aviation.control.client;

/** Raw cursor input, consumed once at 20 Hz. No native smoothing/bank transform. */
public final class FlightMouseInput {
    private double x, y;
    public record Axes(float horizontal, float vertical) {}
    public void add(double dx, double dy) {
        if (!Double.isFinite(dx) || !Double.isFinite(dy)) return;
        x = Math.max(-4096, Math.min(4096, x + dx));
        y = Math.max(-4096, Math.min(4096, y + dy));
    }
    public Axes consume(double sensitivity, boolean inverted) {
        // Minecraft stores this option in [0,1]. Keep the pure helper safe for
        // callers/tests too, so an invalid value cannot reverse or amplify input.
        if (!Double.isFinite(sensitivity)) sensitivity = 0.5;
        sensitivity = Math.max(0, Math.min(1, sensitivity));
        double scale = Math.pow(sensitivity * .6 + .2, 3) * 8 / 32;
        Axes result = new Axes(axis(x * scale), axis(y * scale * (inverted ? -1 : 1)));
        clear();
        return result;
    }
    public void clear() { x = y = 0; }
    private static float axis(double value) { return (float) Math.max(-1, Math.min(1, value)); }
}
