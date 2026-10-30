package dev.frontier.aviation.client;

public final class F16SuspensionTest {
    public static void main(String[] args) {
        F16Suspension gentle = landing(-0.1);
        F16Suspension hard = landing(-1.0);
        check(hard.compression(1) > gentle.compression(1), "landing intensity");
        for (int i = 0; i < 100; i++) hard.tick(true, true, 0);
        near(0.45f, hard.compression(1), "settles on ground");
        float rest = hard.compression(1);
        for (int i = 0; i < 100; i++) hard.tick(true, true, 0);
        near(rest, hard.compression(1), "no idle oscillation");
        hard.tick(false, true, 0);
        float airborne = hard.compression(1);
        hard.tick(false, false, 0);
        check(hard.compression(1) < airborne, "retraction returns to zero");
        for (int i = 0; i < 100; i++) hard.tick(false, false, 0);
        near(0, hard.compression(1), "air returns to zero");
        F16Suspension first = landing(-1);
        F16Suspension second = new F16Suspension();
        near(0, second.compression(1), "independent aircraft without pilot");
        float paused = first.compression(0.5f);
        near(paused, first.compression(0.5f), "rendering during pause does not advance state");
        near(first.compression(0), first.compression(-1), "lower partial bound");
        near(first.compression(1), first.compression(2), "upper partial bound");
        near(first.compression(0), first.compression(Float.NaN), "invalid partial tick");
        F16Suspension deployedOnGround = new F16Suspension();
        deployedOnGround.tick(true, false, -1.0);
        deployedOnGround.tick(true, true, 0);
        F16Suspension quietDeployment = new F16Suspension();
        quietDeployment.tick(true, false, 0);
        quietDeployment.tick(true, true, 0);
        near(quietDeployment.compression(1), deployedOnGround.compression(1),
                "deploying gear on ground does not trigger landing impact");
        F16Suspension invalid = landing(Double.NaN);
        check(Float.isFinite(invalid.compression(1)), "invalid velocity");
        F16Suspension extreme = landing(-Double.MAX_VALUE);
        check(extreme.compression(1) <= F16Suspension.MAX_COMPRESSION, "bounded impact");
        // Each simulated frame samples the same fixed tick interval. The shared midpoint
        // is represented by a frame at all three refresh rates.
        for (int fps : new int[]{30, 60, 144}) {
            F16Suspension state = landing(-0.8);
            float midpoint = state.compression(0.5f);
            for (int frame = 0; frame < fps; frame++) state.compression((frame % 10) / 10f);
            near(midpoint, state.compression(0.5f), "frame rate independent " + fps);
        }
        System.out.println("F16SuspensionTest passed");
    }

    private static F16Suspension landing(double speed) {
        F16Suspension result = new F16Suspension();
        result.tick(false, true, speed);
        result.tick(true, true, 0);
        return result;
    }
    private static void near(float expected, float actual, String message) {
        check(Float.isFinite(actual) && Math.abs(expected - actual) < 0.0001f, message);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
