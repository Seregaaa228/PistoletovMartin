package dev.frontier.aviation.control.client;

import dev.frontier.aviation.control.*;
import dev.frontier.aviation.control.server.FlightAuthority;
import dev.frontier.aviation.control.diagnostics.FlightDiagnostics;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/** Deterministic pure-model witnesses, not a native game or network acceptance test. */
public final class FlightPredictionEvidenceTest {
    private static int checks;
    public static void main(String[] args) {
        FlightProfile profile = FlightProfiles.find("frontier_aviation:f16").orElseThrow();
        evictedHistory(profile);
        clippedReversal(profile);
        System.out.println("FlightPredictionEvidenceTest: " + checks + " checks passed");
    }

    private static void evictedHistory(FlightProfile profile) {
        FlightPrediction prediction = new FlightPrediction();
        FlightState initial = initial(), reference = initial(), ackState = initial();
        check(prediction.accept(profile, 1, telemetry(initial, 0, -1)), "initial snapshot accepted");
        int total = 100, ack = 5;
        for (int sequence = 1; sequence <= total; sequence++) {
            PilotCommand command = command(sequence, sequence <= 20 ? 1 : 0);
            reference = FlightControlModel.step(profile, reference, command, environment(sequence)).state();
            if (sequence == ack) ackState = reference;
            prediction.step(profile, command, environment(sequence));
            check(prediction.pendingFrames() <= FlightPrediction.MAX_FRAMES, "history bounded at every step");
        }
        check(prediction.pendingFrames() == 80, "100 outstanding frames retain exactly 80");
        if (FlightDiagnostics.ENABLED) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            PrintStream original = System.out;
            try (PrintStream captured = new PrintStream(output, true, StandardCharsets.UTF_8)) {
                System.setOut(captured);
                FlightDiagnostics.dumpClient();
            } finally {
                System.setOut(original);
            }
            String dump = output.toString(StandardCharsets.UTF_8).trim();
            check(dump.contains("side=client") && dump.matches(".*\\bevictedPending=20\\b.*"),
                "diagnostic dump records the 20 actually evicted pending frames: " + dump);
            System.out.println("Captured diagnostic: " + dump);
        }
        check(prediction.accept(profile, 1, telemetry(ackState, 1, ack)), "delayed snapshot accepted");
        check(prediction.pendingFrames() == 80, "ACK 5 precedes first retained sequence 21");
        float gap = Math.abs(reference.pitch() - prediction.state().pitch());
        check(gap > .01f, "evicted unacknowledged inputs can change replay trajectory");
        System.out.printf(Locale.ROOT,
            "History witness: submitted=%d retained=%d evicted=20 ACK=%d; sequential pitch=%.6f replay pitch=%.6f difference=%.6f degrees%n",
            total, prediction.pendingFrames(), ack, reference.pitch(), prediction.state().pitch(), gap);
    }

    private static void clippedReversal(FlightProfile profile) {
        UUID owner = new UUID(0, 1);
        FlightAuthority authority = new FlightAuthority(new UUID(0, 2), 1, profile.kind());
        ControlLease lease = authority.acquire(owner, 2, 0);
        FlightState sequential = initial(), merged = initial();
        float[] inputs = {0, 0, 1, 1, -1};
        for (int i = 0; i < inputs.length; i++) {
            PilotCommand command = command(i + 1, inputs[i]);
            sequential = FlightControlModel.step(profile, sequential, command, environment(i + 1)).state();
            check(authority.submit(owner, lease, command, i + 1) == ControlStatus.OK, "burst accepted");
            check(authority.acknowledged() == -1, "receipt does not acknowledge");
        }
        PilotCommand last = null;
        for (int i = 1; i <= FlightAuthority.MAX_QUEUED_COMMANDS; i++) {
            last = authority.consume(10 + i);
            merged = FlightControlModel.step(profile, merged, last, environment(i)).state();
            authority.markApplied();
        }
        check(last.sequence() == 5 && last.pitchAxis() == 0, "clipped 1 + 1 - 1 yields zero aggregate");
        check(authority.acknowledged() == 5, "last aggregate acknowledges endpoint after application");
        float gap = Math.abs(sequential.pitch() - merged.pitch());
        check(gap > .01f, "merged reversal differs from sequential model steps");
        System.out.printf(Locale.ROOT,
            "Queue witness: submitted=5 queue capacity=%d applied steps=3 aggregate pitch=%.1f ACK=%d; sequential pitch=%.6f merged pitch=%.6f difference=%.6f degrees%n",
            FlightAuthority.MAX_QUEUED_COMMANDS, last.pitchAxis(), authority.acknowledged(), sequential.pitch(), merged.pitch(), gap);
    }

    private static PilotCommand command(int sequence, float pitch) {
        return new PilotCommand(sequence, pitch, 0, 0, 0, false, false, false,
            ModeRequest.KEEP, ModeRequest.KEEP, ModeRequest.KEEP);
    }
    private static FlightState initial() {
        return FlightControlModel.initial(0, 0, 0, 0, EnginePhase.OFF, true, false);
    }
    private static FlightEnvironment environment(long tick) {
        return new FlightEnvironment(tick, 1, false, true, true, true, true, false,
            new FlightEnvironment.AxisCaps(4, 4, 4, 4, 4, 4), .02f, 1, 3,
            .006f, .006f, .03f, 1, 1, 0, 0, .2f, 0);
    }
    private static FlightTelemetry telemetry(FlightState state, long tick, long ack) {
        return new FlightTelemetry(state, 1, 1, false, false, false, ControlStatus.OK, tick, ack);
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
