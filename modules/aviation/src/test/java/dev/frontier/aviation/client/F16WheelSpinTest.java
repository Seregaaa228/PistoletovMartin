package dev.frontier.aviation.client;

public final class F16WheelSpinTest {
    public static void main(String[] args) {
        F16WheelSpin spin = new F16WheelSpin();
        spin.tick(true, true, 0.18);
        near(-0.25f, spin.angle(0.5f, false), "interpolated main angle");
        near((float) (-0.18 / 0.29), spin.angle(1, true), "nose radius");
        spin.tick(false, true, 10);
        near(-0.5f, spin.angle(1, false), "airborne wheels stop");
        spin.tick(true, false, 10);
        near(-0.5f, spin.angle(1, false), "retracted wheels stop");
        spin.tick(true, true, -0.18);
        near(0, spin.angle(1, false), "reverse rolling");
        spin.tick(true, true, -0.18);
        near(0.5f, spin.angle(1, false), "backward rolling has opposite rotation");
        spin.tick(true, true, 0.18);
        spin.tick(true, true, Double.NaN);
        spin.tick(true, true, 100);
        near(0, spin.angle(1, false), "invalid input and teleports ignored");
        near(0, new F16WheelSpin().angle(1, false), "independent plane");
        System.out.println("F16WheelSpinTest passed");
    }
    private static void near(float expected, float actual, String message) {
        if (!Float.isFinite(actual) || Math.abs(expected - actual) > 0.0001f) throw new AssertionError(message);
    }
}
