package dev.frontier.aviation.control.diagnostics;

import java.util.Arrays;

/**
 * Fixed-memory distribution of non-negative durations in nanoseconds.
 *
 * <p>Percentiles return the upper bound of the bucket containing the
 * nearest-rank sample. Exact minimum and maximum values are retained.</p>
 */
public final class FlightTimingHistogram {
    private static final long[] UPPER_BOUNDS = createUpperBounds();

    private final long[] buckets = new long[UPPER_BOUNDS.length];
    private long count;
    private long sumNanos;
    private long minNanos = Long.MAX_VALUE;
    private long maxNanos;

    private static long[] createUpperBounds() {
        // Zero has its own exact bucket. Powers of two then span the positive
        // long range, with Long.MAX_VALUE closing the final bucket.
        long[] bounds = new long[65];
        bounds[0] = 0;
        for (int i = 0; i <= 62; i++) {
            bounds[i + 1] = 1L << i;
        }
        bounds[64] = Long.MAX_VALUE;
        return bounds;
    }

    /** Records one duration. Negative values are invalid and are rejected. */
    public void record(long durationNanos) {
        if (durationNanos < 0) {
            throw new IllegalArgumentException("durationNanos must be non-negative");
        }
        int bucket = bucketFor(durationNanos);
        buckets[bucket] = incrementSaturated(buckets[bucket]);
        count = incrementSaturated(count);
        sumNanos = addSaturated(sumNanos, durationNanos);
        minNanos = Math.min(minNanos, durationNanos);
        maxNanos = Math.max(maxNanos, durationNanos);
    }

    private static int bucketFor(long value) {
        int low = 0;
        int high = UPPER_BOUNDS.length - 1;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (value <= UPPER_BOUNDS[mid]) high = mid;
            else low = mid + 1;
        }
        return low;
    }

    private static long incrementSaturated(long value) {
        return value == Long.MAX_VALUE ? value : value + 1;
    }

    private static long addSaturated(long value, long addition) {
        return value > Long.MAX_VALUE - addition ? Long.MAX_VALUE : value + addition;
    }

    public long count() {
        return count;
    }

    public long sumNanos() {
        return sumNanos;
    }

    /** Returns -1 until at least one sample has been recorded. */
    public long minNanos() {
        return count == 0 ? -1 : minNanos;
    }

    /** Returns -1 until at least one sample has been recorded. */
    public long maxNanos() {
        return count == 0 ? -1 : maxNanos;
    }

    /**
     * Returns the histogram upper bound for the nearest-rank percentile.
     * The percentile must be between 0 and 100 inclusive; an empty histogram
     * returns -1.
     */
    public long percentileUpperBound(double percentile) {
        if (!Double.isFinite(percentile) || percentile < 0 || percentile > 100) {
            throw new IllegalArgumentException("percentile must be between 0 and 100");
        }
        if (count == 0) return -1;

        long rank = percentile == 0
                ? 1
                : Math.max(1, (long) Math.ceil((percentile / 100.0) * count));
        long seen = 0;
        for (int i = 0; i < buckets.length; i++) {
            seen = addSaturated(seen, buckets[i]);
            if (seen >= rank) return UPPER_BOUNDS[i];
        }
        return Long.MAX_VALUE;
    }

    /** Clears samples while retaining the histogram's fixed allocation. */
    public void reset() {
        Arrays.fill(buckets, 0);
        count = 0;
        sumNanos = 0;
        minNanos = Long.MAX_VALUE;
        maxNanos = 0;
    }
}
