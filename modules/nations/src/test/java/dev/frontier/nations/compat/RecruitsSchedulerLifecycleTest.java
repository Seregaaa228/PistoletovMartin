package dev.frontier.nations.compat;

import com.talhanation.recruits.util.DelayedExecutor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Uses the actual pinned native scheduler that prevented the dedicated JVM from exiting. */
public final class RecruitsSchedulerLifecycleTest {
    public static void main(String[] args) throws Exception {
        var field=DelayedExecutor.class.getDeclaredField("scheduler");field.setAccessible(true);
        var scheduler=(ScheduledExecutorService)field.get(null);
        try {
            var worker=new AtomicReference<Thread>();var first=new CountDownLatch(1);
            DelayedExecutor.runLater(()->{worker.set(Thread.currentThread());first.countDown();},0);
            check(first.await(3,TimeUnit.SECONDS),"native delayed task runs");
            check(!worker.get().isDaemon(),"reproduces pinned native non-daemon worker");
            RecruitsSchedulerLifecycle.shutdown(false);
            check(!scheduler.isShutdown(),"integrated server retains native scheduler");
            var second=new CountDownLatch(1);DelayedExecutor.runLater(second::countDown,0);
            check(second.await(3,TimeUnit.SECONDS),"next integrated session can still schedule work");
            RecruitsSchedulerLifecycle.shutdown(true);
            check(scheduler.isShutdown(),"dedicated stop shuts native scheduler");
            check(scheduler.awaitTermination(3,TimeUnit.SECONDS),"native worker cannot keep dedicated JVM alive");
        } finally {scheduler.shutdownNow();}
        System.out.println("RecruitsSchedulerLifecycleTest: 6 checks passed");
    }
    private static void check(boolean pass,String name){if(!pass)throw new AssertionError(name);}
}
