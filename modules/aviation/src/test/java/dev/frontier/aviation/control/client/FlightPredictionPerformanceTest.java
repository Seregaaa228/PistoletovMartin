package dev.frontier.aviation.control.client;

import dev.frontier.aviation.control.*;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Arrays;

/**
 * Bounded prediction benchmark and differential replay gate.
 *
 * This is a pure-model benchmark. Its timings are prediction work only and
 * must not be described as game FPS or server TPS.
 */
public final class FlightPredictionPerformanceTest {
    private static final int WARMUP = 10_000;
    private static final int ITERATIONS = 10_000;
    private static final int RUNS = 5;
    private static final int[] QUEUES = {0, 4, 16, 80};
    private static final String[] PROFILES = {"frontier_aviation:f16", "superbwarfare:ah_6"};
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static volatile Object BLACKHOLE;
    private static volatile long BLACKHOLE_LONG;

    public static void main(String[] args) {
        if (!THREADS.isCurrentThreadCpuTimeSupported()) {
            throw new AssertionError("current-thread CPU time is required");
        }
        FlightProfile fighter = FlightProfiles.find(PROFILES[0]).orElseThrow();
        FlightProfile heli = FlightProfiles.find(PROFILES[1]).orElseThrow();
        canonicalizationGate();
        commandSequenceGate(fighter);
        differentialReplay(fighter, heli);
        System.out.println("FlightPredictionPerformanceTest: differential replay passed");
        if (java.util.Arrays.asList(args).contains("--check-only")) return;
        for (FlightProfile profile : new FlightProfile[] {fighter, heli}) {
            for (int queue : QUEUES) {
                Scenario scenario = new Scenario(profile, queue, false);
                runScenario(scenario);
            }
            runScenario(new Scenario(profile, 16, true));
        }
    }

    private static void canonicalizationGate() {
        float[] angles = {-1080.0f, -721.0f, -540.0f, -360.0f, -181.0f, 181.0f, 360.0f, 540.0f, 721.0f, 1080.0f};
        for (float angle : angles) {
            FlightState source = new FlightState(-0.0f, angle, angle, 0, 0, 0, 0, 0,
                EnginePhase.OFF, 0, 0, true, false, 0);
            FlightState canonical = FlightPrediction.attitude(source, source.pitch(), source.yaw(), source.roll());
            check(Float.floatToIntBits(canonical.pitch()) == Float.floatToIntBits(source.pitch()),
                "signed zero pitch preserved for " + angle);
            check(Float.floatToIntBits(canonical.yaw()) == Float.floatToIntBits(FlightControlModel.wrapDegrees(angle))
                    && Float.floatToIntBits(canonical.roll()) == Float.floatToIntBits(FlightControlModel.wrapDegrees(angle)),
                "attitude canonicalizes finite angle " + angle);
            FlightState constrained = FlightAttitudeLimits.UNRESTRICTED.constrain(source);
            check(Float.floatToIntBits(constrained.yaw()) == Float.floatToIntBits(FlightControlModel.wrapDegrees(angle))
                    && Float.floatToIntBits(constrained.roll()) == Float.floatToIntBits(
                        FlightControlModel.wrapDegrees(Math.max(-180, Math.min(180, angle)))),
                "unrestricted constrain canonicalizes angle " + angle);
            check(constrained != source, "noncanonical state is not returned by constrain fast path " + angle);
        }
        FlightState signedZero = FlightPrediction.attitude(
            FlightControlModel.initial(-0.0f, -0.0f, -0.0f, 0, EnginePhase.OFF, true, false),
            -0.0f, -0.0f, -0.0f);
        check(Float.floatToIntBits(signedZero.pitch()) == Float.floatToIntBits(-0.0f)
                && Float.floatToIntBits(signedZero.yaw()) == Float.floatToIntBits(-0.0f)
                && Float.floatToIntBits(signedZero.roll()) == Float.floatToIntBits(-0.0f),
            "signed zero is preserved on all attitude axes");
    }

    private static void commandSequenceGate(FlightProfile profile) {
        FlightCommandBuffer commands = new FlightCommandBuffer();
        FlightPrediction prediction = new FlightPrediction();
        check(prediction.accept(profile, 1, telemetry(initialState(), 0, -1)), "sequence gate seed");
        long previous = 0;
        for (int tick = 1; tick <= 96; tick++) {
            PilotCommand command = commands.next();
            check(command.sequence() > previous, "runtime command sequence strictly increases");
            previous = command.sequence();
            prediction.step(profile, command, environment(tick, false));
            if (tick % 2 == 0) {
                prediction.accept(profile, 1, telemetry(prediction.state(), tick, command.sequence()));
            }
        }
        check(prediction.pendingFrames() == 0, "prefix ACK retires generated command prefix");
    }

    private static void differentialReplay(FlightProfile fighter, FlightProfile heli) {
        for (FlightProfile profile : new FlightProfile[] {fighter, heli}) {
            for (int queue : QUEUES) {
                for (boolean clamps : new boolean[] {false, true}) {
                    Scenario scenario = new Scenario(profile, queue, clamps);
                    FrozenPrediction frozen = new FrozenPrediction();
                    FlightPrediction optimized = new FlightPrediction();
                    seed(frozen, optimized, profile);
                    for (int tick = 1; tick <= 240; tick++) {
                        PilotCommand command = command(tick);
                        FlightEnvironment environment = environment(tick, clamps);
                        FlightAttitudeLimits limits = limits(tick, clamps);
                        FlightOutput frozenOutput = frozen.step(profile, command, environment, limits);
                        FlightOutput optimizedOutput = optimized.step(profile, command, environment, limits);
                        compareOutput(frozenOutput, optimizedOutput, scenario + " output tick=" + tick);
                        if (tick % 2 == 0) {
                            FlightState authoritative = frozen.state;
                            FlightTelemetry telemetry = telemetry(authoritative, tick, tick - 1L);
                            frozen.accept(profile, 1, telemetry);
                            optimized.accept(profile, 1, telemetry);
                        }
                        compare(frozen, optimized, scenario + " tick=" + tick);
                    }
                    // Stale tick, stale epoch, and a new epoch exercise the
                    // same handoff/reconciliation branches used by the client.
                    FlightTelemetry current = telemetry(frozen.state, 240, 239);
                    check(!optimized.accept(profile, 1, current), scenario + " stale tick");
                    check(!optimized.accept(profile, 0, current), scenario + " stale epoch");
                    FlightTelemetry newEpoch = telemetry(frozen.state, 1, -1);
                    check(optimized.accept(profile, 2, newEpoch), scenario + " new epoch");
                    check(optimized.pendingFrames() == 0, scenario + " new epoch clears queue");
                    captureDifferential(profile, clamps);
                }
            }
        }
    }

    private static void captureDifferential(FlightProfile profile, boolean clamps) {
        FrozenPrediction frozen = new FrozenPrediction();
        FlightPrediction optimized = new FlightPrediction();
        seed(frozen, optimized, profile);
        FlightOutput frozenOutput = frozen.step(profile, command(1), environment(1, clamps), limits(1, clamps));
        FlightOutput optimizedOutput = optimized.step(profile, command(1), environment(1, clamps), limits(1, clamps));
        compareOutput(frozenOutput, optimizedOutput, "capture output before");
        FlightState observed = new FlightState(
            Math.max(-12, Math.min(3, frozen.displayed().pitch())),
            frozen.displayed().yaw() + 360,
            frozen.displayed().roll() - 360,
            frozen.displayed().pitchRate(), frozen.displayed().yawRate(), frozen.displayed().rollRate(),
            frozen.displayed().targetThrottle(), frozen.displayed().effectivePower(),
            frozen.displayed().enginePhase(), frozen.displayed().phaseTicks(), frozen.displayed().spool(),
            frozen.displayed().gearDown(), frozen.displayed().hover(), frozen.displayed().throttleHoldTicks());
        frozen.capture(observed);
        optimized.capture(observed);
        compareState(frozen.state, optimized.state(), "capture state");
        compareState(frozen.displayed(), optimized.displayed(), "capture displayed");
    }

    private static void seed(FrozenPrediction frozen, FlightPrediction optimized, FlightProfile profile) {
        FlightTelemetry initial = telemetry(initialState(), 0, -1);
        check(frozen.accept(profile, 1, initial), "frozen seed");
        check(optimized.accept(profile, 1, initial), "optimized seed");
    }

    private static void runScenario(Scenario scenario) {
        int totalTicks = WARMUP + RUNS * ITERATIONS;
        FlightState[] serverTrajectory = serverTrajectory(scenario, totalTicks);
        FlightPrediction prediction = new FlightPrediction();
        seed(new FrozenPrediction(), prediction, scenario.profile);
        for (int tick = 1; tick <= WARMUP; tick++) {
            tick(prediction, scenario, serverTrajectory, tick);
        }
        double[] nanos = new double[RUNS], bytes = new double[RUNS];
        long minPending = Long.MAX_VALUE, maxPending = Long.MIN_VALUE, sumPending = 0, measuredTicks = 0;
        for (int run = 0; run < RUNS; run++) {
            long beforeBytes = allocatedBytes();
            long before = THREADS.getCurrentThreadCpuTime();
            for (int i = 0; i < ITERATIONS; i++) {
                int tick = WARMUP + run * ITERATIONS + i + 1;
                tick(prediction, scenario, serverTrajectory, tick);
                int pending = prediction.pendingFrames();
                minPending = Math.min(minPending, pending);
                maxPending = Math.max(maxPending, pending);
                sumPending += pending;
                measuredTicks++;
                BLACKHOLE_LONG ^= prediction.state().hashCode() ^ pending;
            }
            nanos[run] = THREADS.getCurrentThreadCpuTime() - before;
            bytes[run] = Math.max(0, allocatedBytes() - beforeBytes);
        }
        Arrays.sort(nanos);
        Arrays.sort(bytes);
        long expectedMax = Math.min(FlightPrediction.MAX_FRAMES, scenario.queue + 1L);
        check(minPending == scenario.queue && maxPending == expectedMax,
            scenario + " measured pending queue is not target cadence: " + minPending + ".." + maxPending
                + " expected " + scenario.queue + ".." + expectedMax);
        System.out.printf(Locale.ROOT, "benchmark profile=%s queue=%d clamps=%s warmup=%d ticks=%d runs=%d "
                + "pending[min=%d max=%d mean=%.2f] medianCpuNs/tick=%.1f medianAllocatedB/tick=%.1f%n",
            scenario.profile.id(), scenario.queue, scenario.clamps, WARMUP, ITERATIONS, RUNS,
            minPending, maxPending, sumPending / (double) measuredTicks,
            nanos[RUNS / 2] / ITERATIONS, bytes[RUNS / 2] / ITERATIONS);
    }

    private static void tick(FlightPrediction prediction, Scenario scenario, FlightState[] serverTrajectory, int tick) {
        PilotCommand command = command(tick);
        prediction.step(scenario.profile, command, environment(tick, scenario.clamps), limits(tick, scenario.clamps));
        if (tick % 2 == 0) {
            long acknowledged = tick - scenario.queue;
            FlightState authoritative = serverTrajectory[(int) Math.max(0, acknowledged)];
            prediction.accept(scenario.profile, 1, telemetry(authoritative, tick, Math.max(-1, acknowledged)));
        }
    }

    private static FlightState[] serverTrajectory(Scenario scenario, int totalTicks) {
        FlightState[] trajectory = new FlightState[totalTicks + 1];
        trajectory[0] = initialState();
        for (int tick = 1; tick <= totalTicks; tick++) {
            FlightOutput raw = FlightControlModel.step(scenario.profile, trajectory[tick - 1],
                command(tick), environment(tick, scenario.clamps));
            trajectory[tick] = FrozenPrediction.oldConstrain(raw.state(), limits(tick, scenario.clamps));
        }
        return trajectory;
    }

    private static long allocatedBytes() {
        if (THREADS instanceof com.sun.management.ThreadMXBean mx && mx.isThreadAllocatedMemorySupported()) {
            if (!mx.isThreadAllocatedMemoryEnabled()) mx.setThreadAllocatedMemoryEnabled(true);
            return mx.getThreadAllocatedBytes(Thread.currentThread().getId());
        }
        return 0;
    }

    private static void compare(FrozenPrediction frozen, FlightPrediction optimized, String label) {
        check(frozen.pendingFrames() == optimized.pendingFrames(), label + " pending");
        compareState(frozen.state, optimized.state(), label + " state");
        compareState(frozen.displayed(), optimized.displayed(), label + " displayed");
    }

    private static void compareOutput(FlightOutput expected, FlightOutput actual, String label) {
        check(expected.equals(actual), label + " " + expected + " != " + actual);
    }

    private static void compareState(FlightState expected, FlightState actual, String label) {
        if (expected == null || actual == null) {
            check(expected == actual, label + " null");
            return;
        }
        check(expected.equals(actual), label + " " + expected + " != " + actual);
    }

    private static PilotCommand command(long sequence) {
        float pitch = sequence % 31 < 12 ? .8f : sequence % 31 < 20 ? -.65f : 0;
        float roll = sequence % 23 < 9 ? .7f : sequence % 23 < 15 ? -.55f : 0;
        return new PilotCommand(sequence, pitch, .25f, roll, .1f, false, false, sequence % 37 == 0,
            ModeRequest.KEEP, ModeRequest.KEEP, ModeRequest.KEEP);
    }

    private static FlightAttitudeLimits limits(long tick, boolean clamps) {
        return clamps && tick % 11 < 5 ? new FlightAttitudeLimits(-12, 3, 35)
            : new FlightAttitudeLimits(-60, 60, 50);
    }

    private static FlightEnvironment environment(long tick, boolean clamps) {
        return new FlightEnvironment(tick, 1, false, true, true, true, true, false,
            new FlightEnvironment.AxisCaps(4, 4, 4, 4, 4, 4), .02f, 1, 3,
            .006f, .006f, .03f, 1, 1, 0, 0, .2f, clamps ? .15f : 0);
    }

    private static FlightState initialState() {
        return FlightControlModel.initial(0, 0, 0, 0, EnginePhase.OFF, true, false);
    }

    private static FlightTelemetry telemetry(FlightState state, long tick, long ack) {
        return new FlightTelemetry(state, 1, 1, false, false, false, ControlStatus.OK, tick, ack);
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private record Scenario(FlightProfile profile, int queue, boolean clamps) {
        @Override public String toString() {
            return "profile=" + profile.id() + " queue=" + queue + " clamps=" + clamps;
        }
    }

    /** Frozen copy of the pre-optimization queue/reconciliation behavior. */
    private static final class FrozenPrediction {
        private record Frame(PilotCommand command, FlightEnvironment environment, FlightAttitudeLimits limits) {}
        private final ArrayDeque<Frame> frames = new ArrayDeque<>();
        private FlightState state;
        private long epoch = -1, serverTick = -1;
        private float correctionPitch, correctionYaw, correctionRoll;
        private int correctionTicks;

        private int pendingFrames() { return frames.size(); }
        private boolean accept(FlightProfile profile, long newEpoch, FlightTelemetry telemetry) {
            if (newEpoch < epoch || newEpoch == epoch && telemetry.serverTick() <= serverTick) return false;
            FlightState displayed = displayed();
            boolean changedEpoch = epoch != newEpoch;
            if (changedEpoch) frames.clear();
            epoch = newEpoch; serverTick = telemetry.serverTick(); state = telemetry.state();
            long acknowledged = telemetry.acknowledgedSequence();
            frames.removeIf(frame -> frame.command.sequence() <= acknowledged);
            for (Frame frame : frames) state = oldConstrain(
                FlightControlModel.step(profile, state, frame.command, frame.environment).state(), frame.limits);
            if (displayed != null && !changedEpoch) {
                correctionPitch = difference(displayed.pitch(), state.pitch());
                correctionYaw = difference(displayed.yaw(), state.yaw());
                correctionRoll = difference(displayed.roll(), state.roll());
                correctionTicks = 4;
            } else {
                correctionPitch = correctionYaw = correctionRoll = 0;
                correctionTicks = 0;
            }
            return true;
        }
        private FlightOutput step(FlightProfile profile, PilotCommand command, FlightEnvironment environment, FlightAttitudeLimits limits) {
            FlightOutput output = FlightControlModel.step(profile, state, command, environment);
            state = oldConstrain(output.state(), limits);
            frames.addLast(new Frame(command, environment, limits));
            while (frames.size() > FlightPrediction.MAX_FRAMES) frames.removeFirst();
            if (correctionTicks > 0) {
                float scale = (correctionTicks - 1f) / correctionTicks;
                correctionPitch *= scale; correctionYaw *= scale; correctionRoll *= scale; correctionTicks--;
            }
            return new FlightOutput(oldAttitude(state, state.pitch() + correctionPitch,
                state.yaw() + correctionYaw, state.roll() + correctionRoll), output.nativePower(),
                output.propulsionEnabled(), output.brake(), output.secondaryBrake(), output.boost(), output.status());
        }
        private void capture(FlightState observed) {
            FlightState displayed = displayed();
            if (displayed != null) {
                if (Math.abs(observed.pitch() - displayed.pitch()) > .0001f) correctionPitch = 0;
                if (Math.abs(FlightControlModel.wrapDegrees(observed.yaw() - displayed.yaw())) > .0001f) correctionYaw = 0;
                if (Math.abs(FlightControlModel.wrapDegrees(observed.roll() - displayed.roll())) > .0001f) correctionRoll = 0;
            }
            state = oldAttitude(observed, observed.pitch() - correctionPitch,
                observed.yaw() - correctionYaw, observed.roll() - correctionRoll);
        }
        private FlightState displayed() {
            return state == null ? null : oldAttitude(state, state.pitch() + correctionPitch,
                state.yaw() + correctionYaw, state.roll() + correctionRoll);
        }
        private static float difference(float from, float to) {
            return Math.max(-45, Math.min(45, FlightControlModel.wrapDegrees(from - to)));
        }

        private static FlightState oldConstrain(FlightState source, FlightAttitudeLimits limits) {
            float pitch = Math.max(limits.minimumPitch(), Math.min(limits.maximumPitch(), source.pitch()));
            float roll = Math.max(-limits.maximumRoll(), Math.min(limits.maximumRoll(), source.roll()));
            return oldAttitude(source, pitch, source.yaw(), roll);
        }

        private static FlightState oldAttitude(FlightState source, float pitch, float yaw, float roll) {
            return new FlightState(pitch, FlightControlModel.wrapDegrees(yaw), FlightControlModel.wrapDegrees(roll),
                source.pitchRate(), source.yawRate(), source.rollRate(), source.targetThrottle(), source.effectivePower(),
                source.enginePhase(), source.phaseTicks(), source.spool(), source.gearDown(), source.hover(),
                source.throttleHoldTicks());
        }
    }
}
