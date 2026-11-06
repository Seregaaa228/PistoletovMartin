package dev.frontier.aviation.control.diagnostics;

public final class FlightTimingHistogramTest {
    public static void main(String[] args) {
        FlightTimingHistogram histogram = new FlightTimingHistogram();
        check(histogram.count() == 0, "empty count");
        check(histogram.sumNanos() == 0, "empty sum");
        check(histogram.minNanos() == -1 && histogram.maxNanos() == -1, "empty extrema");
        check(histogram.percentileUpperBound(50) == -1, "empty percentile");

        histogram.record(0);
        histogram.record(1);
        histogram.record(2);
        histogram.record(3);
        histogram.record(4);
        check(histogram.count() == 5, "count");
        check(histogram.sumNanos() == 10, "sum");
        check(histogram.minNanos() == 0 && histogram.maxNanos() == 4, "exact extrema");
        check(histogram.percentileUpperBound(50) == 2, "p50 upper bound");
        check(histogram.percentileUpperBound(95) == 4, "p95 upper bound");
        check(histogram.percentileUpperBound(99) == 4, "p99 upper bound");

        histogram.reset();
        histogram.record(Long.MAX_VALUE);
        check(histogram.count() == 1, "reset count");
        check(histogram.sumNanos() == Long.MAX_VALUE, "maximum duration sum");
        check(histogram.minNanos() == Long.MAX_VALUE && histogram.maxNanos() == Long.MAX_VALUE,
                "maximum duration extrema");
        check(histogram.percentileUpperBound(50) == Long.MAX_VALUE, "maximum duration percentile");

        histogram.reset();
        histogram.record(Long.MAX_VALUE - 1);
        histogram.record(2);
        check(histogram.sumNanos() == Long.MAX_VALUE, "saturated sum");
        check(histogram.minNanos() == 2 && histogram.maxNanos() == Long.MAX_VALUE - 1,
                "extrema after saturation");

        expectIllegalArgument(() -> histogram.record(-1), "negative duration");
        expectIllegalArgument(() -> histogram.percentileUpperBound(Double.NaN), "NaN percentile");
        expectIllegalArgument(() -> histogram.percentileUpperBound(-0.1), "negative percentile");
        expectIllegalArgument(() -> histogram.percentileUpperBound(100.1), "percentile over 100");
        System.out.println("FlightTimingHistogramTest PASS");
    }

    private static void expectIllegalArgument(Runnable action, String label) {
        try {
            action.run();
            throw new AssertionError(label + " was accepted");
        } catch (IllegalArgumentException expected) {
            // Expected validation.
        }
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
