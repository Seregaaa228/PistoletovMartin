package dev.frontier.aviation.client;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Per-frame time budget for the client's main-thread task queue, where every server
 * packet is applied. Vanilla drains the whole queue each frame, so a burst of chunk
 * packets while flying fast at a long view distance (~20 at once) stalls one frame for
 * 50-180 ms. Tasks keep their order (a block update must never run before its chunk);
 * the rest simply wait for the next frame. A backlog that persists raises the budget
 * frame by frame, and a very long queue is drained at once, so the client never falls
 * behind the server.
 */
public final class ClientTaskBudget {
    static final long BASE_NANOS = 4_000_000L, STEP_NANOS = 1_000_000L, MAX_NANOS = 16_000_000L;
    /** Queue length at which the budget is dropped and everything runs (vanilla behaviour). */
    static final int CATCH_UP_BACKLOG = 1024;
    public static final long UNLIMITED = Long.MAX_VALUE;
    /** Off with -Dfrontier.clientTaskBudget=false; FlightFramePerformance flips it for A/B runs. */
    public static volatile boolean enabled = !"false".equals(System.getProperty("frontier.clientTaskBudget"));

    private int backloggedFrames;

    /** Budget for this frame, given the queue length before it runs. */
    public long budget(int pending) {
        if (pending >= CATCH_UP_BACKLOG) return UNLIMITED;
        return Math.min(MAX_NANOS, BASE_NANOS + backloggedFrames * STEP_NANOS);
    }

    /** After the frame's tasks ran: how many are left decides the next frame's budget. */
    public void finished(int remaining) {
        backloggedFrames = remaining > 0 ? backloggedFrames + 1 : 0;
    }

    int backloggedFrames() { return backloggedFrames; }

    /**
     * Runs queued tasks in order until the queue is empty or the budget is spent; at least
     * one task runs whenever any is ready. Returns the number of tasks run.
     */
    public static int run(BooleanSupplier pollTask, LongSupplier nanoClock, long budget) {
        long deadline = budget == UNLIMITED ? UNLIMITED : nanoClock.getAsLong() + budget;
        int ran = 0;
        while (pollTask.getAsBoolean()) {
            ran++;
            if (deadline != UNLIMITED && nanoClock.getAsLong() >= deadline) break;
        }
        return ran;
    }
}
