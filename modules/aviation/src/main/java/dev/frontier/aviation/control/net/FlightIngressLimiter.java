package dev.frontier.aviation.control.net;

/**
 * Per-connection admission control, usable before scheduling work on the server thread.
 * Times must come from one monotonic clock, such as System.nanoTime().
 */
public final class FlightIngressLimiter {
    public enum Decision { ACCEPT, RESET, DROP }

    private final int maxPending, maxPerWindow;
    private final long windowNanos;
    private int pending, acceptedInWindow;
    private long windowStart;
    private boolean windowStarted, resetSignalled, resetPending;

    public FlightIngressLimiter(int maxPending, int maxPerWindow, long windowNanos) {
        if (maxPending <= 0 || maxPerWindow <= 0 || windowNanos <= 0)
            throw new IllegalArgumentException("limits must be positive");
        this.maxPending = maxPending;
        this.maxPerWindow = maxPerWindow;
        this.windowNanos = windowNanos;
    }

    /**
     * ACCEPT reserves one pending task; call completeTask even if enqueueing fails.
     * RESET reserves one reset task; call completeReset even if enqueueing fails.
     * DROP reserves nothing. After signalling a reset, all packets are dropped
     * for the rest of that window, and while the reset remains pending even if
     * the window has elapsed. At most one reset is pending across all windows.
     */
    public synchronized Decision tryAcquire(long now) {
        if (!windowStarted || now - windowStart >= windowNanos) {
            windowStarted = true;
            windowStart = now;
            acceptedInWindow = 0;
            resetSignalled = false;
        }
        if (resetPending || resetSignalled) return Decision.DROP;
        if (acceptedInWindow < maxPerWindow && pending < maxPending) {
            acceptedInWindow++;
            pending++;
            return Decision.ACCEPT;
        }
        resetSignalled = true;
        resetPending = true;
        return Decision.RESET;
    }

    public synchronized void completeTask() {
        if (pending == 0) throw new IllegalStateException("no pending task");
        pending--;
    }

    public synchronized void completeReset() {
        if (!resetPending) throw new IllegalStateException("no pending reset");
        resetPending = false;
    }
}
