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

        // Su-25 / J-10 tyres: each jet spins its wheels with its own radii.
        F16WheelSpin su25 = new F16WheelSpin(6.0 / 16, 5.05 / 16);
        su25.tick(true, true, 0.25);
        near((float) (-0.25 / (6.0 / 16)), su25.angle(1, false), "own main radius");
        near((float) (-0.25 / (5.05 / 16)), su25.angle(1, true), "own nose radius");

        // Long taxiing rebases by whole turns without a jump in either wheel.
        F16WheelSpin far = new F16WheelSpin(5.0 / 16, 4.4 / 16);
        double travelled = 0;
        for (int i = 0; i < 400_000; i++) {
            far.tick(true, true, 15.5);
            travelled += 15.5;
        }
        near(phase(-travelled / (5.0 / 16)), phase(far.angle(1, false)), "main phase after rebase");
        near(phase(-travelled / (4.4 / 16)), phase(far.angle(1, true)), "nose phase after rebase");
        float before = far.angle(1, false);
        far.tick(true, true, 0.1);
        float step = (float) Math.IEEEremainder(far.angle(1, false) - before, Math.PI * 2);
        near((float) (-0.1 / (5.0 / 16)), step, "continuous after rebase");
        try {
            new F16WheelSpin(0, 1);
            throw new AssertionError("zero radius rejected");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        System.out.println("F16WheelSpinTest passed");
    }
    private static float phase(double angle) {
        double wrapped = angle % (Math.PI * 2);
        return (float) (wrapped < 0 ? wrapped + Math.PI * 2 : wrapped);
    }

    private static void near(float expected, float actual, String message) {
        if (!Float.isFinite(actual) || Math.abs(expected - actual) > 0.0001f) throw new AssertionError(message);
    }
}
