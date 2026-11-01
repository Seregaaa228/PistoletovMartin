package dev.frontier.aviation.control.net;

import dev.frontier.aviation.control.*;
import io.netty.buffer.Unpooled;
import java.lang.management.ManagementFactory;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;

/** Bounded transport benchmark; reports codec ns/packet, never game FPS/TPS. */
public final class FlightCodecBenchmark {
    private static final int WARMUP = 20_000;
    private static final int ITERATIONS = 50_000;
    private static final int SAMPLES = 5;
    private static volatile Object BLACK_HOLE;

    public static void main(String[] args) {
        boolean baseline = args.length == 0 || "--baseline".equals(args[0]);
        boolean optimized = args.length == 0 || "--optimized".equals(args[0]);
        if (!baseline && !optimized) {
            throw new IllegalArgumentException("expected --baseline, --optimized, or no argument");
        }

        var allocated = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!allocated.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException("allocation counter unavailable");
        }
        allocated.setThreadAllocatedMemoryEnabled(true);
        Workload workload = workload();
        for (int packets : new int[]{1, 8, 16}) {
            if (baseline) {
                run("baseline commands", packets, () -> baselineCommands(workload, packets), allocated);
                run("baseline snapshots", packets, () -> baselineSnapshots(workload, packets), allocated);
            }
            if (optimized) {
                run("optimized commands", packets, () -> optimizedCommands(workload, packets), allocated);
                run("optimized snapshots", packets, () -> optimizedSnapshots(workload, packets), allocated);
            }
        }
    }

    private static Workload workload() {
        UUID vehicle = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        UUID owner = UUID.fromString("87654321-4321-8765-cba9-876543210fed");
        PilotCommand[] commands = new PilotCommand[16];
        for (int index = 0; index < commands.length; index++) {
            commands[index] = new PilotCommand(42 + index, -.5f, .25f, -.75f, .8f,
                true, false, true, ModeRequest.ENABLE, ModeRequest.DISABLE, ModeRequest.KEEP);
        }
        var snapshot = new FlightSnapshot("minecraft:overworld", 127, vehicle, owner, 17,
            new FlightTelemetry(new FlightState(34.5f, -179.5f, 173.25f, 1.2f, -.5f, .75f,
                .63f, .077f, EnginePhase.RUNNING, 0, .8f, true, false, 3),
                2.5f, .12f, true, false, true, ControlStatus.TIMEOUT, 810, 42));
        return new Workload(vehicle, commands, new FlightWireCodec.Reply(ControlStatus.OK, snapshot));
    }

    private static void baselineCommands(Workload workload, int packets) {
        for (int index = 0; index < packets; index++) {
            var request = commandRequest(workload, index);
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer(256));
            try {
                buffer.writeByteArray(FlightWireCodec.encodeRequest(request));
                BLACK_HOLE = FlightWireCodec.decodeRequest(buffer.readByteArray(FlightWireCodec.MAX_BYTES));
                checkConsumed(buffer);
            } finally {
                buffer.release();
            }
        }
    }

    private static void baselineSnapshots(Workload workload, int packets) {
        for (int index = 0; index < packets; index++) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer(1024));
            try {
                buffer.writeByteArray(FlightWireCodec.encodeReply(workload.reply));
                BLACK_HOLE = FlightWireCodec.decodeReply(buffer.readByteArray(FlightWireCodec.MAX_BYTES));
                checkConsumed(buffer);
            } finally {
                buffer.release();
            }
        }
    }

    private static void optimizedCommands(Workload workload, int packets) {
        for (int index = 0; index < packets; index++) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer(256));
            try {
                FlightBufferCodec.encodeRequest(buffer, commandRequest(workload, index));
                BLACK_HOLE = FlightBufferCodec.decodeRequest(buffer);
            } finally {
                buffer.release();
            }
        }
    }

    private static void optimizedSnapshots(Workload workload, int packets) {
        for (int index = 0; index < packets; index++) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer(1024));
            try {
                FlightBufferCodec.encodeReply(buffer, workload.reply);
                BLACK_HOLE = FlightBufferCodec.decodeReply(buffer);
            } finally {
                buffer.release();
            }
        }
    }

    private static FlightWireCodec.Request commandRequest(Workload workload, int index) {
        return new FlightWireCodec.Request(FlightWireCodec.Operation.COMMAND, null, 0,
            workload.vehicle, 17, workload.commands[index]);
    }

    private static void checkConsumed(FriendlyByteBuf buffer) {
        if (buffer.isReadable()) {
            throw new IllegalStateException("baseline envelope left readable bytes");
        }
    }

    private static void run(String name, int packets, Runnable work,
            com.sun.management.ThreadMXBean allocated) {
        for (int index = 0; index < WARMUP; index++) {
            work.run();
        }
        double[] nanos = new double[SAMPLES];
        double[] bytes = new double[SAMPLES];
        long threadId = Thread.currentThread().getId();
        for (int sample = 0; sample < SAMPLES; sample++) {
            long beforeBytes = allocated.getThreadAllocatedBytes(threadId);
            long start = System.nanoTime();
            for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                work.run();
            }
            nanos[sample] = (double) (System.nanoTime() - start) / (ITERATIONS * packets);
            bytes[sample] = (double) (allocated.getThreadAllocatedBytes(threadId) - beforeBytes)
                / (ITERATIONS * packets);
        }
        System.out.printf("%s packets=%d ns/packet=%.1f allocatedBytes/packet=%.1f%n",
            name, packets, median(nanos), median(bytes));
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private record Workload(UUID vehicle, PilotCommand[] commands, FlightWireCodec.Reply reply) {
    }
}
