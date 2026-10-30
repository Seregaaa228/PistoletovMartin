package dev.frontier.aviation.control.client;

import dev.frontier.aviation.control.FlightControlModel;

public final class FlightRenderAnglesTest {
    public static void main(String[] args) {
        near(-180, midpoint(179, -179), "crossing positive boundary cannot render a full reverse spin");
        near(180, midpoint(-179, 179), "crossing negative boundary cannot render a full reverse spin");
        near(21, midpoint(20, 22), "ordinary interpolation unchanged");
        for (int previous = -720; previous <= 720; previous += 15) {
            for (int delta : new int[]{-20, -2, 0, 2, 20}) {
                float current = FlightControlModel.wrapDegrees(previous + delta);
                float aligned = FlightRenderAngles.previous(previous, current);
                near(delta, current - aligned, "native linear interpolation uses actual small angle delta");
                near(0, FlightControlModel.wrapDegrees(aligned - previous), "alignment preserves equivalent previous attitude");
            }
        }
        System.out.println("FlightRenderAnglesTest: wrap crossings and ordinary interpolation passed");
    }
    private static float midpoint(float previous, float current) {
        return (FlightRenderAngles.previous(previous, current) + current) / 2;
    }
    private static void near(float expected, float actual, String label) {
        if (!Float.isFinite(actual) || Math.abs(expected - actual) > .0001f)
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
    }
}
