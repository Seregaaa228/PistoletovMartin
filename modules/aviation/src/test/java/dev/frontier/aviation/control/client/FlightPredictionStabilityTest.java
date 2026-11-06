package dev.frontier.aviation.control.client;

import dev.frontier.aviation.control.*;

/** Reconciliation trajectories with independent client and server simulation clocks. */
public final class FlightPredictionStabilityTest {
    private static int checks;

    public static void main(String[] args) {
        acknowledgedFrameUsesSequence();
        delayedSnapshotsPreserveSteering();
        nativeConstraintsConsumeOnlyTheirAxisCorrection();
        replayPreservesNativeClampsAndReversal();
        System.out.println("FlightPredictionStabilityTest: " + checks + " checks passed");
    }

    private static void replayPreservesNativeClampsAndReversal() {
        FlightProfile profile = FlightProfiles.find("frontier_aviation:f16").orElseThrow();
        FlightPrediction prediction = new FlightPrediction();
        FlightState[] server = new FlightState[41];
        server[0] = FlightPrediction.attitude(initial(), 3, 0, 49);
        prediction.accept(profile, 1, telemetry(server[0], 0, -1));
        for (int tick = 1; tick <= 40; tick++) {
            // The later ground constraint differs from the airborne constraints
            // of earlier frames still awaiting acknowledgement.
            FlightAttitudeLimits limits = tick <= 10
                ? new FlightAttitudeLimits(-60, 60, 50) : new FlightAttitudeLimits(-12, 3, 50);
            float direction = tick <= 12 ? 1 : tick <= 28 ? -1 : 0;
            PilotCommand input = new PilotCommand(tick, direction, 0, direction, 0, false, false, false,
                ModeRequest.KEEP, ModeRequest.KEEP, ModeRequest.KEEP);
            FlightState raw = FlightControlModel.step(profile, server[tick - 1], input, environment(tick)).state();
            // Independent equivalent of native apply/capture, including preserved rates.
            server[tick] = FlightPrediction.attitude(raw,
                Math.max(limits.minimumPitch(), Math.min(limits.maximumPitch(), raw.pitch())), raw.yaw(),
                Math.max(-50, Math.min(50, raw.roll())));
            prediction.step(profile, input, environment(1000 + tick), limits);
            if (tick > 2 && tick % 2 == 0) {
                prediction.accept(profile, 1, telemetry(server[tick - 2], tick - 2, tick - 2));
            }
            near(server[tick].pitch(), prediction.state().pitch(), "projected pitch reversal at " + tick);
            near(server[tick].roll(), prediction.state().roll(), "projected bank reversal at " + tick);
            near(server[tick].pitch(), prediction.displayed().pitch(), "displayed pitch reversal at " + tick);
            near(server[tick].roll(), prediction.displayed().roll(), "displayed bank reversal at " + tick);
            near(server[tick].pitchRate(), prediction.state().pitchRate(), "pitch clamp preserves rate at " + tick);
            near(server[tick].rollRate(), prediction.state().rollRate(), "bank clamp preserves rate at " + tick);
        }
        FlightState edge = FlightPrediction.attitude(initial(), 120, 179, -180);
        near(edge.roll(), FlightAttitudeLimits.UNRESTRICTED.constrain(edge).roll(), "unrestricted wrap endpoint");
        near(edge.pitch(), FlightAttitudeLimits.UNRESTRICTED.constrain(edge).pitch(), "unrestricted pitch");
    }

    private static void nativeConstraintsConsumeOnlyTheirAxisCorrection() {
        FlightProfile profile = FlightProfiles.find("superbwarfare:tom_6").orElseThrow();
        FlightPrediction prediction = new FlightPrediction();
        prediction.accept(profile, 1, telemetry(initial(), 1, -1));
        prediction.accept(profile, 1, telemetry(FlightPrediction.attitude(initial(), 20, 20, 20), 2, -1));
        FlightState displayed = prediction.step(profile, PilotCommand.neutral(1), environment(3)).state();
        near(5, displayed.pitch(), "first smoothing step before native constraint");
        // Tom6 ground contact reduces permitted pitch to +3. Yaw/roll can be
        // represented a full revolution apart without any physical angle change.
        FlightState observed = new FlightState(3, displayed.yaw() + 360, displayed.roll() - 360,
            displayed.pitchRate(), displayed.yawRate(), displayed.rollRate(), displayed.targetThrottle(),
            displayed.effectivePower(), displayed.enginePhase(), displayed.phaseTicks(), displayed.spool(),
            displayed.gearDown(), displayed.hover(), displayed.throttleHoldTicks());
        prediction.capture(observed);
        near(3, prediction.state().pitch(), "native pitch clamp cannot retain hidden smoothing offset");
        FlightState next = prediction.step(profile, PilotCommand.neutral(2), environment(4)).state();
        near(3, next.pitch(), "neutral pitch cannot rebound into the native constraint");
        near(10, next.yaw(), "wrap-equivalent yaw capture preserves smoothing");
        near(10, next.roll(), "wrap-equivalent roll capture preserves smoothing");
    }

    private static void acknowledgedFrameUsesSequence() {
        FlightProfile profile = FlightProfiles.find("frontier_aviation:f16").orElseThrow();
        FlightPrediction prediction = new FlightPrediction();
        FlightState initial = initial();
        prediction.accept(profile, 1, telemetry(initial, 40, -1));
        FlightState applied = prediction.step(profile, command(1, 1, 0), environment(1041)).state();
        prediction.accept(profile, 1, telemetry(applied, 41, 1));
        near(applied.yaw(), prediction.state().yaw(),
            "acknowledged yaw must not be simulated twice when client clock leads");
        check(prediction.pendingFrames() == 0, "acknowledgement retires frame without comparing clocks");
    }

    private static void delayedSnapshotsPreserveSteering() {
        for (String id : new String[] {"frontier_aviation:f16", "superbwarfare:tom_6", "superbwarfare:ah_6"}) {
            FlightProfile profile = FlightProfiles.find(id).orElseThrow();
            for (int clockOffset : new int[] {-1000, 0, 1000}) {
                for (int axis = 0; axis < 2; axis++) {
                    FlightPrediction prediction = new FlightPrediction();
                    FlightState[] server = new FlightState[241];
                    server[0] = initial();
                    prediction.accept(profile, 1, telemetry(server[0], 2000, -1));
                    for (int tick = 1; tick <= 240; tick++) {
                        // Hold, release, reverse, then release: mouse yaw and keyboard bank paths.
                        float direction = tick <= 60 ? 1 : tick <= 80 ? 0 : tick <= 180 ? -1 : 0;
                        PilotCommand input = command(tick, axis == 0 ? direction : 0, axis == 1 ? direction : 0);
                        server[tick] = FlightControlModel.step(profile, server[tick - 1], input, environment(2000 + tick)).state();
                        prediction.step(profile, input, environment(2000 + tick + clockOffset));
                        // Reliable snapshots arrive two simulation frames later, every second tick.
                        if (tick > 2 && tick % 2 == 0) {
                            int acknowledged = tick - 2;
                            prediction.accept(profile, 1, telemetry(server[acknowledged], 2000 + acknowledged, acknowledged));
                            check(prediction.pendingFrames() == 2, id + " retains only the two unacknowledged steps");
                        }
                        near(server[tick].yaw(), prediction.state().yaw(), id + " yaw trajectory at " + tick);
                        near(server[tick].roll(), prediction.state().roll(), id + " roll trajectory at " + tick);
                        near(server[tick].yaw(), prediction.displayed().yaw(), id + " displayed yaw at " + tick);
                        near(server[tick].roll(), prediction.displayed().roll(), id + " displayed roll at " + tick);
                    }
                }
            }
        }
    }

    private static PilotCommand command(long sequence, float yaw, float roll) {
        return new PilotCommand(sequence, 0, yaw, roll, 0, false, false, false,
            ModeRequest.KEEP, ModeRequest.KEEP, ModeRequest.KEEP);
    }

    private static FlightState initial() {
        return FlightControlModel.initial(0, 0, 0, 0, EnginePhase.OFF, true, false);
    }

    private static FlightTelemetry telemetry(FlightState state, long tick, long acknowledged) {
        return new FlightTelemetry(state, 1, 1, false, false, false, ControlStatus.OK, tick, acknowledged);
    }

    private static FlightEnvironment environment(long tick) {
        return new FlightEnvironment(tick, 1, false, true, true, true, true, false,
            new FlightEnvironment.AxisCaps(4, 4, 4, 4, 4, 4), .02f, 1, 3,
            .006f, .006f, .03f, 1, 1, 0, 0, .2f, 0);
    }

    private static void near(float expected, float actual, String label) {
        check(Math.abs(FlightControlModel.wrapDegrees(actual - expected)) < .0001f,
            label + ": " + actual + " expected " + expected);
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
}
