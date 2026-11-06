package dev.frontier.aviation.client;

/** Pure interpolation checks for the F-16 visual air-brake state. */
public final class F16AirBrakeSmoothingTest {
    private static int checks;

    public static void main(String[] args) {
        F16AirBrakeSmoothing smoothing = new F16AirBrakeSmoothing();
        near(0, smoothing.at(0.5f), "starts closed");
        smoothing.tick(true);
        check(smoothing.value() > 0 && smoothing.value() < 1, "deployment is smoothed");
        check(smoothing.at(-1) == 0, "partial tick is clamped low");
        check(smoothing.at(2) == smoothing.value(), "partial tick is clamped high");
        for (int i = 0; i < 40; i++) smoothing.tick(true);
        near(1, smoothing.value(), "deployment settles open");
        smoothing.tick(false);
        check(smoothing.value() < 1, "retraction starts immediately");
        for (int i = 0; i < 40; i++) smoothing.tick(false);
        near(0, smoothing.value(), "retraction settles closed");
        System.out.println("F16AirBrakeSmoothingTest: " + checks + " checks passed");
    }

    private static void near(float expected, float actual, String label) {
        check(Math.abs(expected - actual) < 0.0001f, label + ": " + actual);
    }

    private static void check(boolean okay, String label) {
        checks++;
        if (!okay) throw new AssertionError(label);
    }
}
