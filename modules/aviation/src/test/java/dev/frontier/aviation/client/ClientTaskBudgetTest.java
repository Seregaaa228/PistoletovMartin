package dev.frontier.aviation.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Pure queue/clock simulation. Does not certify in-game frame times. */
public final class ClientTaskBudgetTest {
    private static int checks;

    /** A task queue whose tasks advance a fake clock by their cost. */
    private static final class Queue {
        final ArrayDeque<long[]> tasks = new ArrayDeque<>();
        final List<Long> order = new ArrayList<>();
        long now;
        long next;
        void add(int count, long costNanos) { for (int i = 0; i < count; i++) tasks.addLast(new long[] {next++, costNanos}); }
        boolean poll() {
            long[] task = tasks.pollFirst();
            if (task == null) return false;
            now += task[1];
            order.add(task[0]);
            return true;
        }
        /** One frame: returns the time the queue took. */
        long frame(ClientTaskBudget budget) {
            long start = now;
            ClientTaskBudget.run(this::poll, () -> now, budget.budget(tasks.size()));
            budget.finished(tasks.size());
            return now - start;
        }
    }

    public static void main(String[] args) {
        emptyQueueRunsNothing();
        burstIsSpreadInOrder();
        oneSlowTaskStillRuns();
        steadyTrafficNeverLags();
        persistentBacklogRaisesBudget();
        hugeBacklogDrainsAtOnce();
        System.out.println("ClientTaskBudgetTest: " + checks + " checks passed (pure queue schedule, no game frames)");
    }

    private static void emptyQueueRunsNothing() {
        Queue queue = new Queue();
        check(queue.frame(new ClientTaskBudget()) == 0, "empty queue costs nothing");
        check(queue.order.isEmpty(), "no task runs");
    }

    /** 20 chunk packets of 5 ms each: vanilla would spend 100 ms in one frame. */
    private static void burstIsSpreadInOrder() {
        Queue queue = new Queue();
        ClientTaskBudget budget = new ClientTaskBudget();
        queue.add(20, 5_000_000L);
        long worst = 0;
        int frames = 0;
        while (!queue.tasks.isEmpty()) {
            worst = Math.max(worst, queue.frame(budget));
            check(++frames < 20, "burst drains in fewer frames than tasks");
        }
        // A frame never exceeds the budget by more than the one task that crossed it.
        check(worst <= ClientTaskBudget.MAX_NANOS + 5_000_000L, "worst frame bounded: " + worst);
        check(worst < 100_000_000L / 3, "burst no longer lands in one frame");
        for (int i = 0; i < queue.order.size(); i++) check(queue.order.get(i) == i, "task order preserved");
        check(budget.backloggedFrames() == 0, "budget relaxes once drained");
    }

    private static void oneSlowTaskStillRuns() {
        Queue queue = new Queue();
        queue.add(1, 50_000_000L);
        queue.add(1, 1_000_000L);
        ClientTaskBudget budget = new ClientTaskBudget();
        queue.frame(budget);
        check(queue.order.size() == 1, "a task over budget runs, then the frame ends");
        queue.frame(budget);
        check(queue.order.size() == 2, "the next task runs next frame");
    }

    /** Normal play: a few small packets per frame always finish in their frame. */
    private static void steadyTrafficNeverLags() {
        Queue queue = new Queue();
        ClientTaskBudget budget = new ClientTaskBudget();
        for (int frame = 0; frame < 600; frame++) {
            queue.add(3, 300_000L);
            queue.frame(budget);
            check(queue.tasks.isEmpty(), "steady traffic drained each frame");
        }
    }

    private static void persistentBacklogRaisesBudget() {
        ClientTaskBudget budget = new ClientTaskBudget();
        long first = budget.budget(10);
        for (int i = 0; i < 6; i++) budget.finished(10);
        check(budget.budget(10) > first, "budget grows while a backlog persists");
        for (int i = 0; i < 100; i++) budget.finished(10);
        check(budget.budget(10) == ClientTaskBudget.MAX_NANOS, "growth is capped");
        budget.finished(0);
        check(budget.budget(10) == ClientTaskBudget.BASE_NANOS, "budget resets once caught up");

        // Arrivals faster than the base budget still drain: the budget catches up.
        Queue queue = new Queue();
        ClientTaskBudget adaptive = new ClientTaskBudget();
        for (int frame = 0; frame < 300; frame++) {
            queue.add(3, 2_000_000L);
            queue.frame(adaptive);
        }
        check(queue.tasks.size() < 10, "6 ms of work per frame never builds an endless backlog: " + queue.tasks.size());
    }

    private static void hugeBacklogDrainsAtOnce() {
        Queue queue = new Queue();
        queue.add(ClientTaskBudget.CATCH_UP_BACKLOG, 10_000L);
        queue.frame(new ClientTaskBudget());
        check(queue.tasks.isEmpty(), "a backlog at the catch-up limit drains in one frame");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
