package dev.frontier.aviation.control.diagnostics;

/**
 * Opt-in aggregate counters only. Each side is accessed on its own game thread.
 * Server values combine all aircraft; client values cover one ClientLevel lifecycle.
 * Depth is sampled after submit, so depth0 is always zero. An abrupt termination
 * loses the current aggregate; lifecycleEnd denotes a dump, not a complete test.
 */
public final class FlightDiagnostics {
    public static final boolean ENABLED = Boolean.getBoolean("frontier.flightDiagnostics");
    private static final Counters SERVER = ENABLED ? new Counters() : null;
    private static final Counters CLIENT = ENABLED ? new Counters() : null;
    private FlightDiagnostics() {}

    private static final class Counters {
        long accepted, merged, dequeued, empty, snapshots, pendingSum, pendingMax, evictedPending;
        final long[] depths = new long[4];
    }
    private static long increment(long value) { return value == Long.MAX_VALUE ? value : value + 1; }
    private static long add(long value, int amount) {
        return value > Long.MAX_VALUE - amount ? Long.MAX_VALUE : value + amount;
    }
    public static void submitted(boolean merged, int depth) {
        if (!ENABLED) return;
        SERVER.accepted = increment(SERVER.accepted);
        if (merged) SERVER.merged = increment(SERVER.merged);
        if (depth >= 0 && depth < SERVER.depths.length) SERVER.depths[depth] = increment(SERVER.depths[depth]);
    }
    public static void dequeued(boolean hasFrame) {
        if (!ENABLED) return;
        if (hasFrame) SERVER.dequeued = increment(SERVER.dequeued);
        else SERVER.empty = increment(SERVER.empty);
    }
    public static void snapshot(int pending) {
        if (!ENABLED) return;
        CLIENT.snapshots = increment(CLIENT.snapshots);
        CLIENT.pendingSum = add(CLIENT.pendingSum, pending);
        CLIENT.pendingMax = Math.max(CLIENT.pendingMax, pending);
    }
    public static void evictedPending() {
        if (ENABLED) CLIENT.evictedPending = increment(CLIENT.evictedPending);
    }
    /** Dump once per lifecycle and clear; abrupt termination can lose the current aggregate. */
    public static void dumpServer() {
        if (!ENABLED) return;
        System.out.println("[flight-diagnostics] side=server lifecycleEnd=true accepted=" + SERVER.accepted
            + " merged=" + SERVER.merged + " dequeued=" + SERVER.dequeued + " empty=" + SERVER.empty
            + " depth0=" + SERVER.depths[0] + " depth1=" + SERVER.depths[1]
            + " depth2=" + SERVER.depths[2] + " depth3=" + SERVER.depths[3]);
        SERVER.accepted = SERVER.merged = SERVER.dequeued = SERVER.empty = 0;
        java.util.Arrays.fill(SERVER.depths, 0);
    }
    public static void dumpClient() {
        if (!ENABLED) return;
        System.out.println("[flight-diagnostics] side=client lifecycleEnd=true localPredictionSnapshots=" + CLIENT.snapshots
            + " pendingSum=" + CLIENT.pendingSum + " pendingMax=" + CLIENT.pendingMax
            + " evictedPending=" + CLIENT.evictedPending);
        CLIENT.snapshots = CLIENT.pendingSum = CLIENT.pendingMax = CLIENT.evictedPending = 0;
    }
}
