package dev.frontier.aviation.benchmark;

import com.sun.management.ThreadMXBean;
import dev.frontier.aviation.control.EnginePhase;
import dev.frontier.aviation.control.nativebridge.NativeFlightPersistenceData;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import net.minecraft.nbt.CompoundTag;

/** Explicit microbenchmark of the real persistence writer; never a game FPS measurement. */
public final class FlightPersistenceBenchmark {
    private static volatile Object sink;
    private static final ThreadMXBean ALLOCATIONS = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    public static void main(String[] args) {
        if (!ALLOCATIONS.isThreadAllocatedMemorySupported()) throw new IllegalStateException("allocation counter unavailable");
        ALLOCATIONS.setThreadAllocatedMemoryEnabled(true);
        for (int aircraft : new int[] {1, 8, 16}) {
            for (boolean changing : new boolean[] {false, true}) {
                for (int warmup = 0; warmup < 4; warmup++) run(aircraft, changing, 100_000);
                double[] nanos = new double[7], bytes = new double[7];
                for (int sample = 0; sample < nanos.length; sample++) {
                    double[] result = run(aircraft, changing, 250_000);
                    nanos[sample] = result[0]; bytes[sample] = result[1];
                }
                Arrays.sort(nanos); Arrays.sort(bytes);
                System.out.printf(java.util.Locale.ROOT,
                    "FLIGHT_PERSISTENCE aircraft=%d scenario=%s nsPerSave=%.3f bytesPerSave=%.3f samples=%d%n",
                    aircraft, changing ? "changing" : "steady", nanos[3], bytes[3], nanos.length);
            }
        }
    }

    private static double[] run(int aircraft, boolean changing, int iterations) {
        CompoundTag[] persistent = new CompoundTag[aircraft];
        for (int i = 0; i < aircraft; i++) {
            persistent[i] = new CompoundTag();
            NativeFlightPersistenceData.save(persistent[i], .75f, EnginePhase.RUNNING);
        }
        long thread = Thread.currentThread().getId();
        long allocated = ALLOCATIONS.getThreadAllocatedBytes(thread), start = System.nanoTime();
        for (int tick = 0; tick < iterations; tick++) {
            float throttle = changing ? (tick % 100) / 100f : .75f;
            for (CompoundTag tag : persistent) NativeFlightPersistenceData.save(tag, throttle, EnginePhase.RUNNING);
        }
        long elapsed = System.nanoTime() - start;
        long bytes = ALLOCATIONS.getThreadAllocatedBytes(thread) - allocated;
        sink = persistent[aircraft - 1];
        long operations = (long) iterations * aircraft;
        return new double[] {(double) elapsed / operations, (double) bytes / operations};
    }
}
