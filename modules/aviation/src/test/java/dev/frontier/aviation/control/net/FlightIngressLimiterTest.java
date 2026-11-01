package dev.frontier.aviation.control.net;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public final class FlightIngressLimiterTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        normalRateAndWindows();
        pendingAndReset();
        independentConnections();
        wraparoundClock();
        concurrentAdmission();
        System.out.println("FlightIngressLimiterTest: " + assertions + " assertions passed");
    }
    private static void normalRateAndWindows() {
        var limiter = new FlightIngressLimiter(32, 40, 1_000_000_000L);
        for (int i = 0; i < 25; i++) {
            check(limiter.tryAcquire(i * 40_000_000L) == FlightIngressLimiter.Decision.ACCEPT, "25/s accepted");
            limiter.completeTask();
        }
        for (int i = 25; i < 40; i++) {
            check(limiter.tryAcquire(999_999_999L) == FlightIngressLimiter.Decision.ACCEPT, "bounded burst accepted");
            limiter.completeTask();
        }
        check(limiter.tryAcquire(999_999_999L) == FlightIngressLimiter.Decision.RESET, "over rate signals once");
        limiter.completeReset();
        check(limiter.tryAcquire(999_999_999L) == FlightIngressLimiter.Decision.DROP, "same window drops after reset completes");
        check(limiter.tryAcquire(1_000_000_000L) == FlightIngressLimiter.Decision.ACCEPT, "window resets at exact boundary");
        limiter.completeTask();
        check(limiter.tryAcquire(6_000_000_000_000L) == FlightIngressLimiter.Decision.ACCEPT, "long idle opens window");
        limiter.completeTask();
    }
    private static void pendingAndReset() {
        var limiter = new FlightIngressLimiter(2, 40, 100);
        check(limiter.tryAcquire(0) == FlightIngressLimiter.Decision.ACCEPT, "reserve first task");
        check(limiter.tryAcquire(0) == FlightIngressLimiter.Decision.ACCEPT, "reserve second task");
        check(limiter.tryAcquire(0) == FlightIngressLimiter.Decision.RESET, "pending overflow signals reset");
        check(limiter.tryAcquire(0) == FlightIngressLimiter.Decision.DROP, "flood dropped");
        limiter.completeTask();
        check(limiter.tryAcquire(1) == FlightIngressLimiter.Decision.DROP, "reset pending blocks admission even with free slot");
        check(limiter.tryAcquire(100) == FlightIngressLimiter.Decision.DROP, "old pending reset blocks another window");
        limiter.completeReset();
        check(limiter.tryAcquire(100) == FlightIngressLimiter.Decision.ACCEPT, "admission resumes after reset in a fresh window");
        check(limiter.tryAcquire(101) == FlightIngressLimiter.Decision.RESET, "one reset allowed after previous finishes in new window");
        limiter.completeReset();
        limiter.completeTask();
        check(limiter.tryAcquire(102) == FlightIngressLimiter.Decision.DROP, "completed reset still blocks same-window admission");
        check(limiter.tryAcquire(200) == FlightIngressLimiter.Decision.ACCEPT, "admission resumes at next window");
        limiter.completeTask(); limiter.completeTask();
        throwsState(limiter::completeTask, "task completion underflow");
        throwsState(limiter::completeReset, "reset completion underflow");
    }
    private static void independentConnections() {
        var a = new FlightIngressLimiter(1, 1, 100);
        var b = new FlightIngressLimiter(1, 1, 100);
        check(a.tryAcquire(0) == FlightIngressLimiter.Decision.ACCEPT, "first connection admitted");
        check(a.tryAcquire(0) == FlightIngressLimiter.Decision.RESET, "first connection overloaded");
        check(b.tryAcquire(0) == FlightIngressLimiter.Decision.ACCEPT, "other connection independent");
        b.completeTask(); a.completeTask(); a.completeReset();
    }
    private static void wraparoundClock() {
        var limiter = new FlightIngressLimiter(1, 1, 100);
        long start = Long.MAX_VALUE - 50;
        check(limiter.tryAcquire(start) == FlightIngressLimiter.Decision.ACCEPT, "large timestamp accepted");
        limiter.completeTask();
        check(limiter.tryAcquire(start + 99) == FlightIngressLimiter.Decision.RESET, "no early reset across wrap");
        limiter.completeReset();
        check(limiter.tryAcquire(start + 100) == FlightIngressLimiter.Decision.ACCEPT, "window boundary across wrap");
        limiter.completeTask();
    }
    private static void concurrentAdmission() throws Exception {
        int threads = 96, cap = 8;
        var limiter = new FlightIngressLimiter(cap, 1000, 1000);
        var ready = new CountDownLatch(threads);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        var accepted = new AtomicInteger();
        var resets = new AtomicInteger();
        var drops = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    switch (limiter.tryAcquire(0)) {
                        case ACCEPT -> accepted.incrementAndGet();
                        case RESET -> resets.incrementAndGet();
                        case DROP -> drops.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally { done.countDown(); }
            }).start();
        }
        ready.await(); start.countDown(); done.await();
        check(accepted.get() == cap, "concurrent tasks do not exceed pending cap");
        check(resets.get() == 1 && drops.get() == threads - cap - 1, "one reset and all other excess dropped");
        limiter.completeReset();
        for (int i = 0; i < cap; i++) limiter.completeTask();
        check(limiter.tryAcquire(1) == FlightIngressLimiter.Decision.DROP, "completed reset closes current window");
        check(limiter.tryAcquire(1000) == FlightIngressLimiter.Decision.ACCEPT, "all concurrent slots released for next window");
        limiter.completeTask();
    }
    private static void throwsState(Runnable action, String message) {
        try { action.run(); } catch (IllegalStateException expected) { assertions++; return; }
        throw new AssertionError(message);
    }
    private static void check(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
}
