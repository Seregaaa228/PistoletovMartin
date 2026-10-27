package dev.frontier.aviation.control.client;

import dev.frontier.aviation.control.*;

/** Pure client invariants; no Minecraft bootstrap or native physics. */
public final class FlightClientStateTest {
    private static int checks;
    public static void main(String[] args) {
        keyConflicts(); mouse(); interpolation(); epochsAndReplay(); authorityTransitions(); delayedReleaseAfterReacquire();
        System.out.println("FlightClientStateTest: " + checks + " checks passed");
    }
    private static void keyConflicts() {
        check(FlightKeyConflictPolicy.suppress(true, true, true, false), "selected pilot gear wins edit binding");
        check(FlightKeyConflictPolicy.suppress(true, true, false, true), "selected helicopter hover wins edit binding");
        check(!FlightKeyConflictPolicy.suppress(true, true, false, false), "unselected flight binding retains edit");
        check(!FlightKeyConflictPolicy.suppress(false, true, true, true), "inactive pilot input retains native edit");
        check(!FlightKeyConflictPolicy.suppress(true, false, true, true), "unbound keys do not conflict");
    }
    private static void mouse() {
        FlightMouseInput mouse = new FlightMouseInput();
        mouse.add(8, 4); mouse.add(8, 4);
        var axes = mouse.consume(.5, false);
        near(.5f, axes.horizontal(), "accumulate before sensitivity");
        near(.25f, axes.vertical(), "raw vertical");
        near(0, mouse.consume(.5, false).horizontal(), "consume exactly once");
        mouse.add(16, 16); mouse.clear();
        near(0, mouse.consume(.5, false).vertical(), "free camera clears input");
        mouse.add(0, 16); near(-.5f, mouse.consume(.5, true).vertical(), "inversion once");
        mouse.add(Double.NaN, 1); near(0, mouse.consume(.5, false).horizontal(), "invalid mouse ignored");
        mouse.add(100000, -100000);
        var clamped = mouse.consume(.5, false);
        near(1, clamped.horizontal(), "axis capped positive"); near(-1, clamped.vertical(), "axis capped negative");
    }
    private static void interpolation() {
        FlightState from = FlightPrediction.attitude(initial(), 10, 179, -179);
        FlightState to = FlightPrediction.attitude(initial(), 20, -179, 179);
        FlightState mid = FlightPrediction.interpolate(from, to, .5f);
        near(-180, mid.yaw(), "yaw shortest wrap"); near(-180, mid.roll(), "roll explicitly interpolated");
        near(15, mid.pitch(), "pitch interpolation");
        near(-179, FlightPrediction.interpolate(from, to, 2).yaw(), "observer interpolation bounded");
    }
    private static void epochsAndReplay() {
        FlightProfile profile = FlightProfiles.find("frontier_aviation:f16").orElseThrow();
        FlightPrediction prediction = new FlightPrediction();
        check(prediction.accept(profile, 10, telemetry(initial(), 0, -1)), "first epoch accepted");
        PilotCommand right = new PilotCommand(1, 0, 1, 0, 0, false, false, false, ModeRequest.KEEP, ModeRequest.KEEP, ModeRequest.KEEP);
        FlightState expected = initial();
        for (int tick = 1; tick <= 3; tick++) {
            expected = FlightControlModel.step(profile, expected, right, environment(tick)).state();
            prediction.step(profile, right, environment(tick));
        }
        check(prediction.accept(profile, 10, telemetry(initial(), 1, 0)), "new authority tick accepted");
        near(expected.yaw(), prediction.state().yaw(), "unacknowledged pure frames replay");
        check(!prediction.accept(profile, 9, telemetry(initial(), 20, 20)), "old epoch rejected");
        check(!prediction.accept(profile, 10, telemetry(initial(), 0, 20)), "old tick rejected");
        check(prediction.accept(profile, 11, telemetry(initial(), 0, -1)), "new epoch resets tick ordering");
        check(prediction.pendingFrames() == 0, "new epoch clears old input");
        near(0, prediction.state().yawRate(), "new owner keeps zero rate");
        for (int tick = 1; tick <= 100; tick++) prediction.step(profile, right, environment(tick));
        check(prediction.pendingFrames() == FlightPrediction.MAX_FRAMES, "history bounded");
        prediction.accept(profile, 11, telemetry(initial(), 101, 1));
        check(prediction.pendingFrames() == 0, "ack retires history");
        for (int tick = 102; tick <= 105; tick++) prediction.step(profile, PilotCommand.neutral(1), environment(tick));
        near(prediction.state().yaw(), prediction.displayed().yaw(), "single correction ends after four frames");
    }
    private static void authorityTransitions() {
        FlightSnapshotPolicy policy = new FlightSnapshotPolicy();
        FlightSnapshot normal = snapshot(7, 40, ControlStatus.OK, true);
        FlightSnapshot busy = snapshot(7, 40, ControlStatus.BUSY, false);
        check(policy.accept(normal, null) == FlightSnapshotPolicy.Decision.APPLY, "normal snapshot managed");
        check(policy.accept(busy, normal) == FlightSnapshotPolicy.Decision.RETIRE, "same-tick BUSY wins before duplicate tick rejection");
        check(policy.accept(normal, null) == FlightSnapshotPolicy.Decision.IGNORE, "retired epoch cannot resume after entry removal");
        check(policy.accept(snapshot(7, 99, ControlStatus.OK, true), null) == FlightSnapshotPolicy.Decision.IGNORE,
            "later stale server tick cannot revive retired epoch");
        check(policy.accept(snapshot(6, 100, ControlStatus.OK, true), null) == FlightSnapshotPolicy.Decision.IGNORE,
            "older epoch cannot resume retired control");
        FlightSnapshot acquired = snapshot(8, 41, ControlStatus.OK, true);
        check(policy.accept(acquired, null) == FlightSnapshotPolicy.Decision.APPLY, "new manual epoch can resume");
        check(policy.accept(busy, acquired) == FlightSnapshotPolicy.Decision.IGNORE, "old BUSY cannot revoke newer manual epoch");
        FlightSnapshot sameTickRelease = snapshot(8, 41, ControlStatus.RELEASED, false);
        check(policy.accept(sameTickRelease, acquired) == FlightSnapshotPolicy.Decision.APPLY,
            "same-tick release revokes the pilot before duplicate-tick filtering");
        check(policy.accept(snapshot(8, 40, ControlStatus.RELEASED, false), acquired) == FlightSnapshotPolicy.Decision.IGNORE,
            "older release cannot revoke newer accepted state");
        FlightSnapshot released = snapshot(8, 42, ControlStatus.RELEASED, false);
        check(policy.accept(released, acquired) == FlightSnapshotPolicy.Decision.APPLY, "ordinary dismount retains managed engine continuation");
        check(policy.accept(snapshot(8, 43, ControlStatus.OK, false), released) == FlightSnapshotPolicy.Decision.APPLY,
            "released session continues receiving snapshots");
        check(policy.accept(snapshot(8, 42, ControlStatus.BUSY, false), released) == FlightSnapshotPolicy.Decision.RETIRE,
            "retained no-lease session also retires on same-tick BUSY");
        policy.clear();
        check(policy.accept(normal, null) == FlightSnapshotPolicy.Decision.APPLY, "world cleanup clears tombstones");
    }
    private static void delayedReleaseAfterReacquire() {
        FlightSnapshotPolicy policy = new FlightSnapshotPolicy();
        FlightSnapshot first = snapshot(20, 100, ControlStatus.OK, true);
        FlightSnapshot release = snapshot(20, 100, ControlStatus.RELEASED, false);
        check(policy.accept(first, null) == FlightSnapshotPolicy.Decision.APPLY, "first lease accepted");
        check(policy.accept(release, first) == FlightSnapshotPolicy.Decision.APPLY, "same-tick release accepted");
        FlightSnapshot reacquired = snapshot(21, 101, ControlStatus.OK, true);
        check(policy.accept(reacquired, release) == FlightSnapshotPolicy.Decision.APPLY, "new epoch accepted after release");
        check(policy.accept(release, reacquired) == FlightSnapshotPolicy.Decision.IGNORE,
            "delayed old release cannot revoke reacquired epoch");
        check(policy.accept(snapshot(20, 102, ControlStatus.RELEASED, false), reacquired)
                == FlightSnapshotPolicy.Decision.IGNORE,
            "old epoch release remains stale even with a newer server tick");
    }
    private static FlightSnapshot snapshot(long epoch, long tick, ControlStatus status, boolean owner) {
        java.util.UUID vehicle = new java.util.UUID(0, 1), player = new java.util.UUID(0, 2);
        FlightTelemetry telemetry = new FlightTelemetry(initial(), 1, 1, false, false, false, status, tick, -1);
        return new FlightSnapshot("minecraft:overworld", 1, vehicle, owner ? player : null, epoch, telemetry);
    }
    private static FlightState initial() { return FlightControlModel.initial(0, 0, 0, 0, EnginePhase.OFF, true, false); }
    private static FlightTelemetry telemetry(FlightState state, long tick, long ack) {
        return new FlightTelemetry(state, 1, 1, false, false, false, ControlStatus.OK, tick, ack);
    }
    private static FlightEnvironment environment(long tick) {
        return new FlightEnvironment(tick, 1, false, true, true, true, false, false,
            new FlightEnvironment.AxisCaps(4, 4, 4, 4, 4, 4), .02f, 1, 3,
            .006f, .006f, .03f, 1, 1, 0, 0, .2f, 0);
    }
    private static void near(float expected, float actual, String label) {
        check(Math.abs(expected - actual) < .0001f, label + ": " + actual + " expected " + expected);
    }
    private static void check(boolean okay, String label) {
        checks++; if (!okay) throw new AssertionError(label);
    }
}
