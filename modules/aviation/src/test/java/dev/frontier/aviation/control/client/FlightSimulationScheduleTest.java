package dev.frontier.aviation.control.client;

import dev.frontier.aviation.control.*;
import dev.frontier.aviation.control.server.FlightAuthority;
import java.util.ArrayDeque;
import java.util.UUID;

/** Pure transport/simulation schedules. Does not certify native physics, rendering or game TPS. */
public final class FlightSimulationScheduleTest {
    private static final UUID OWNER = new UUID(0, 1), VEHICLE = new UUID(0, 2);
    private static int checks;

    public static void main(String[] args) {
        for (String id : new String[] {"frontier_aviation:f16", "superbwarfare:tom_6", "superbwarfare:ah_6"}) {
            FlightProfile profile = FlightProfiles.find(id).orElseThrow();
            pairedDeliveriesPreserveEveryFrame(profile);
            emptyTickDoesNotRepeatMouse(profile);
            overloadRemainsBounded(profile);
            for (int delay : new int[] {0, 2}) {
                for (int tps : new int[] {20, 15, 10}) measureClockMismatch(id, profile, tps, delay);
            }
        }
        independentClientSessions();
        System.out.println("FlightSimulationScheduleTest: " + checks
            + " checks passed (pure schedules; low-TPS aggregation is approximate, no game/render acceptance)");
    }

    private static void pairedDeliveriesPreserveEveryFrame(FlightProfile profile) {
        FlightAuthority authority = new FlightAuthority(VEHICLE, 1, profile.kind());
        ControlLease lease = authority.acquire(OWNER, 2, 0);
        FlightPrediction prediction = new FlightPrediction();
        FlightState server = initial(), expected = initial();
        prediction.accept(profile, lease.epoch(), telemetry(server, 4000, -1));
        ArrayDeque<PilotCommand> network = new ArrayDeque<>();
        for (int clientTick = 1; clientTick <= 160; clientTick++) {
            long now = clientTick * 50_000_000L;
            PilotCommand input = command(clientTick, profile);
            network.addLast(input);
            expected = FlightControlModel.step(profile, expected, input, environment(clientTick)).state();
            prediction.step(profile, input, environment(1000 + clientTick));

            // Every odd packet is held for the following tick; both arrive before
            // that tick's one server step. This is a reliable transport, not loss.
            long beforeReceipt = authority.acknowledged();
            if ((clientTick & 1) == 0) {
                check(network.size() == 2, "two packets arrive together");
                while (!network.isEmpty()) {
                    check(authority.submit(OWNER, lease, network.removeFirst(), now) == ControlStatus.OK,
                        "paired frame accepted");
                }
            }
            check(authority.acknowledged() == beforeReceipt, "receipt is not application");
            PilotCommand consumed = authority.consume(now);
            check(authority.acknowledged() == beforeReceipt, "consume is not application");
            server = FlightControlModel.step(profile, server, consumed, environment(4000 + clientTick)).state();
            authority.markApplied();
            long expectedAck = clientTick == 1 ? -1 : clientTick - 1;
            check(authority.acknowledged() == expectedAck, "FIFO acknowledges only one applied frame");
            if ((clientTick & 1) == 0) {
                prediction.accept(profile, lease.epoch(), telemetry(server, 4000 + clientTick, authority.acknowledged()));
                check(prediction.pendingFrames() == 1, "one unapplied input remains for replay");
            }
            sameState(expected, prediction.state(), "paired schedule prediction");
        }
        // The last queued command must be applied, not silently skipped because
        // a newer packet was already acknowledged at receipt time.
        server = FlightControlModel.step(profile, server, authority.consume(8_050_000_000L), environment(4161)).state();
        authority.markApplied();
        check(authority.acknowledged() == 160, "last queued frame applied");
        sameState(expected, server, "paired schedule final server trajectory");
        prediction.accept(profile, lease.epoch(), telemetry(server, 4161, authority.acknowledged()));
        check(prediction.pendingFrames() == 0, "final acknowledgement drains replay");
        sameState(server, prediction.state(), "final reconciliation");
    }

    private static void emptyTickDoesNotRepeatMouse(FlightProfile profile) {
        FlightAuthority authority = new FlightAuthority(VEHICLE, 1, profile.kind());
        ControlLease lease = authority.acquire(OWNER, 2, 0);
        FlightPrediction prediction = new FlightPrediction();
        FlightState server = initial();
        prediction.accept(profile, lease.epoch(), telemetry(server, 0, -1));
        PilotCommand impulse = new PilotCommand(1, 1, .75f, -.5f, .5f, false, false, false,
            ModeRequest.ENABLE, ModeRequest.DISABLE, ModeRequest.KEEP);
        check(authority.submit(OWNER, lease, impulse, 1) == ControlStatus.OK, "mouse impulse accepted");
        prediction.step(profile, impulse, environment(900));
        server = FlightControlModel.step(profile, server, authority.consume(2), environment(1)).state();
        authority.markApplied();
        prediction.accept(profile, lease.epoch(), telemetry(server, 1, authority.acknowledged()));
        PilotCommand held = new PilotCommand(1, 0,
            profile.kind() == FlightKind.HELICOPTER ? impulse.yawAxis() : 0,
            profile.kind() == FlightKind.HELICOPTER ? 0 : impulse.rollAxis(),
            impulse.throttleAxis(), false, false, false,
            ModeRequest.KEEP, ModeRequest.KEEP, ModeRequest.KEEP);
        FlightState expected = FlightControlModel.step(profile, server, held, environment(2)).state();
        PilotCommand empty = authority.consume(3);
        check(empty.equals(held), "empty tick retains keys and consumes mouse only once");
        server = FlightControlModel.step(profile, server, empty, environment(2)).state();
        authority.markApplied();
        check(authority.acknowledged() == 1, "autonomous held-input step does not invent an acknowledgement");
        prediction.accept(profile, lease.epoch(), telemetry(server, 2, authority.acknowledged()));
        sameState(expected, prediction.state(), "empty tick authoritative reconciliation");
        check(prediction.pendingFrames() == 0, "same acknowledgement does not revive old mouse input");
    }

    private static void overloadRemainsBounded(FlightProfile profile) {
        FlightAuthority authority = new FlightAuthority(VEHICLE, 1, profile.kind());
        ControlLease lease = authority.acquire(OWNER, 2, 0);
        FlightPrediction prediction = new FlightPrediction();
        prediction.accept(profile, lease.epoch(), telemetry(initial(), 0, -1));
        int frames = FlightAuthority.MAX_QUEUED_COMMANDS * 10;
        for (int frame = 1; frame <= frames; frame++) {
            PilotCommand input = command(frame, profile);
            prediction.step(profile, input, environment(frame));
            check(authority.submit(OWNER, lease, input, frame) == ControlStatus.OK, "bounded overload preserves control");
            check(authority.acknowledged() == -1, "queued and aggregated frames are not acknowledged before physics");
        }
        FlightState server = initial();
        for (int step = 1; step <= FlightAuthority.MAX_QUEUED_COMMANDS; step++) {
            PilotCommand applied = authority.consume(100 + step);
            long previousAck = authority.acknowledged();
            check(applied.sequence() > previousAck, "bounded drain applies the next queued aggregate");
            server = FlightControlModel.step(profile, server, applied, environment(step)).state();
            check(authority.acknowledged() == previousAck, "aggregate acknowledgement waits for application");
            authority.markApplied();
            check(authority.acknowledged() == applied.sequence(), "aggregate acknowledgement covers its applied endpoint");
        }
        check(authority.acknowledged() == frames, "bounded number of model steps drains the entire burst");
        check(authority.lease().equals(lease), "overload does not churn the control lease");
        check(server.enginePhase() == EnginePhase.STARTING, "queued engine edge survives overload");
        check(!server.gearDown(), "latest explicit gear edge survives aggregation");
        prediction.accept(profile, lease.epoch(), telemetry(server, 10, authority.acknowledged()));
        check(prediction.pendingFrames() == 0, "aggregate acknowledgement removes all represented replay frames");
        sameState(server, prediction.state(), "aggregate reconciliation uses authoritative result");
        check(angularDistance(prediction.displayed(), server) > .001f,
            "aggregation is visibly approximate; the test does not claim exact frame equivalence");
        authority.consume(200);
        authority.markApplied();
        check(authority.acknowledged() == frames, "empty queue does not invent a new aggregate acknowledgement");
    }

    private record Packet(int deliveryTick, PilotCommand command) {}
    private record Snapshot(int deliveryTick, FlightTelemetry telemetry) {}

    private static void measureClockMismatch(String id, FlightProfile profile, int serverTps, int delayTicks) {
        FlightAuthority authority = new FlightAuthority(VEHICLE, 1, profile.kind());
        ControlLease lease = authority.acquire(OWNER, 2, 0);
        FlightPrediction prediction = new FlightPrediction();
        FlightState server = initial();
        prediction.accept(profile, lease.epoch(), telemetry(server, 0, -1));
        ArrayDeque<Packet> network = new ArrayDeque<>();
        ArrayDeque<Snapshot> snapshots = new ArrayDeque<>();
        int budget = 0, serverTick = 0, accepted = 0, maxBacklog = 0, aggregatesApplied = 0;
        float maxAngularLead = 0, maxCorrection = 0;
        for (int clientTick = 1; clientTick <= 200; clientTick++) {
            long now = clientTick * 50_000_000L;
            PilotCommand input = command(clientTick, profile);
            prediction.step(profile, input, environment(1000 + clientTick));
            network.addLast(new Packet(clientTick + delayTicks, input));
            while (!network.isEmpty() && network.peekFirst().deliveryTick <= clientTick) {
                ControlStatus status = authority.submit(OWNER, lease, network.removeFirst().command, now);
                check(status == ControlStatus.OK, "sustained input does not lose control on overload");
                accepted++;
                int backlog = accepted - (int) Math.max(0, authority.acknowledged());
                // An aggregate represents several input frames; frame backlog is
                // not queue size. At this fixed input rate each server interval
                // admits at most ceil(20 / TPS) input frames.
                check(backlog <= FlightAuthority.MAX_QUEUED_COMMANDS * ((20 + serverTps - 1) / serverTps),
                    "bounded aggregation prevents unbounded input latency");
                maxBacklog = Math.max(maxBacklog, backlog);
            }
            budget += serverTps;
            if (budget >= 20) {
                budget -= 20;
                serverTick++;
                long previousAck = authority.acknowledged();
                PilotCommand applied = authority.consume(now);
                server = FlightControlModel.step(profile, server, applied, environment(serverTick)).state();
                authority.markApplied();
                if (authority.acknowledged() - Math.max(0, previousAck) > 1) aggregatesApplied++;
                if ((serverTick & 1) == 0) {
                    snapshots.addLast(new Snapshot(clientTick + delayTicks,
                        telemetry(server, serverTick, authority.acknowledged())));
                }
            }
            while (!snapshots.isEmpty() && snapshots.peekFirst().deliveryTick <= clientTick) {
                FlightState before = prediction.state();
                prediction.accept(profile, lease.epoch(), snapshots.removeFirst().telemetry);
                maxCorrection = Math.max(maxCorrection, angularDistance(before, prediction.state()));
            }
            maxAngularLead = Math.max(maxAngularLead, angularDistance(server, prediction.state()));
            check(Float.isFinite(maxAngularLead) && Float.isFinite(maxCorrection), "scheduled state remains finite");
            check(prediction.pendingFrames() <= FlightPrediction.MAX_FRAMES, "replay history remains bounded");
            check(authority.lease().equals(lease), "clock mismatch does not interrupt the lease");
        }
        if (serverTps == 20) {
            check(aggregatesApplied == 0, "matched clocks do not aggregate ordinary traffic");
            check(maxCorrection < .0001f, "matched clocks replay applied frames without trajectory corrections");
        } else {
            check(aggregatesApplied > 0, "slower server exercises approximate aggregation");
            check(maxCorrection > .01f, "test exposes low-TPS reconciliation instead of claiming exact equivalence");
        }
        // Stop producing input, deliver the transport tail, then allow at most
        // the bounded number of server steps. Every source frame must retire.
        while (!network.isEmpty()) {
            check(authority.submit(OWNER, lease, network.removeFirst().command, 10_100_000_000L) == ControlStatus.OK,
                "transport tail accepted");
        }
        for (int step = 1; step <= FlightAuthority.MAX_QUEUED_COMMANDS; step++) {
            serverTick++;
            server = FlightControlModel.step(profile, server, authority.consume(10_100_000_000L + step), environment(serverTick)).state();
            authority.markApplied();
        }
        check(authority.acknowledged() == 200, "sustained schedule eventually acknowledges all source frames");
        prediction.accept(profile, lease.epoch(), telemetry(server, serverTick, authority.acknowledged()));
        check(prediction.pendingFrames() == 0, "sustained schedule drains prediction history");
        sameState(server, prediction.state(), "schedule final authoritative convergence");
        System.out.printf(java.util.Locale.ROOT,
            "Schedule %s client=20Hz server=%dTPS delay=%dms/way maxUnacknowledgedFrames=%d maxAngleLead=%.3f maxReconcile=%.3f aggregatesApplied=%d%n",
            id, serverTps, delayTicks * 50, maxBacklog, maxAngularLead, maxCorrection, aggregatesApplied);
    }

    private static void independentClientSessions() {
        FlightProfile aircraft = FlightProfiles.find("frontier_aviation:f16").orElseThrow();
        FlightProfile helicopter = FlightProfiles.find("superbwarfare:ah_6").orElseThrow();
        UUID otherOwner = new UUID(0, 3), otherVehicle = new UUID(0, 4);
        FlightAuthority first = new FlightAuthority(VEHICLE, 1, aircraft.kind());
        FlightAuthority second = new FlightAuthority(otherVehicle, 1, helicopter.kind());
        ControlLease firstLease = first.acquire(OWNER, 2, 0), secondLease = second.acquire(otherOwner, 2, 0);
        FlightPrediction firstPrediction = new FlightPrediction(), secondPrediction = new FlightPrediction();
        FlightState firstServer = initial(), secondServer = initial(), secondReference = initial();
        firstPrediction.accept(aircraft, 2, telemetry(firstServer, 0, -1));
        secondPrediction.accept(helicopter, 2, telemetry(secondServer, 0, -1));
        int firstSequence = 0;
        for (int tick = 1; tick <= 120; tick++) {
            long now = tick * 50_000_000L;
            // One client overloads its queue while the other sends ordinary
            // traffic. Identical sequence and epoch numbers are vehicle scoped.
            for (int packet = 0; packet < 2; packet++) {
                PilotCommand input = command(++firstSequence, aircraft);
                firstPrediction.step(aircraft, input, environment(tick));
                check(first.submit(OWNER, firstLease, input, now) == ControlStatus.OK, "first client burst accepted");
            }
            PilotCommand otherInput = command(tick, helicopter);
            secondPrediction.step(helicopter, otherInput, environment(tick));
            secondReference = FlightControlModel.step(helicopter, secondReference, otherInput, environment(tick)).state();
            check(second.submit(otherOwner, secondLease, otherInput, now) == ControlStatus.OK, "second client frame accepted");
            check(first.submit(otherOwner, secondLease, otherInput, now) == ControlStatus.INVALID_LEASE,
                "second client cannot enter first vehicle queue");
            check(second.submit(OWNER, firstLease, command(firstSequence + 1, aircraft), now) == ControlStatus.INVALID_LEASE,
                "first client cannot enter second vehicle queue");
            firstServer = FlightControlModel.step(aircraft, firstServer, first.consume(now), environment(tick)).state();
            secondServer = FlightControlModel.step(helicopter, secondServer, second.consume(now), environment(tick)).state();
            first.markApplied(); second.markApplied();
            check(second.acknowledged() == tick, "second client acknowledgement unaffected by first queue");
            firstPrediction.accept(aircraft, firstLease.epoch(), telemetry(firstServer, tick, first.acknowledged()));
            secondPrediction.accept(helicopter, secondLease.epoch(), telemetry(secondServer, tick, second.acknowledged()));
            sameState(secondReference, secondServer, "independent second server trajectory");
            sameState(secondReference, secondPrediction.state(), "independent second prediction trajectory");
            check(secondPrediction.pendingFrames() == 0, "second replay history does not inherit first client's backlog");
            if (tick == 60) {
                check(first.release(firstLease), "first client releases its own session");
                firstLease = first.acquire(OWNER, 3, now);
                firstSequence = 0;
                firstPrediction.accept(aircraft, firstLease.epoch(), telemetry(firstServer, tick, -1));
                check(firstPrediction.pendingFrames() == 0, "first session handoff clears only its own prediction");
                check(second.lease().equals(secondLease) && second.acknowledged() == tick,
                    "first session handoff leaves second lease and acknowledgement intact");
            }
        }
    }

    private static PilotCommand command(int sequence, FlightProfile profile) {
        float direction = sequence <= 40 ? 1 : sequence <= 60 ? 0 : sequence <= 120 ? -1 : 0;
        float mouse = direction * .4f, keyboard = direction * .3f;
        boolean helicopter = profile.kind() == FlightKind.HELICOPTER;
        return new PilotCommand(sequence, mouse, helicopter ? keyboard : mouse, helicopter ? -mouse : keyboard,
            sequence < 80 ? .5f : -.5f, false, false, false,
            sequence == 1 ? ModeRequest.ENABLE : ModeRequest.KEEP,
            sequence == 30 ? ModeRequest.DISABLE : sequence == 130 ? ModeRequest.ENABLE : ModeRequest.KEEP,
            ModeRequest.KEEP);
    }

    private static FlightState initial() {
        return FlightControlModel.initial(0, 0, 0, 0, EnginePhase.OFF, true, false);
    }

    private static FlightEnvironment environment(long tick) {
        return new FlightEnvironment(tick, 1, false, true, true, true, true, false,
            new FlightEnvironment.AxisCaps(4, 4, 4, 4, 4, 4), .02f, 1, 3,
            .006f, .006f, .03f, 1, 1, 0, 0, .2f, 0);
    }

    private static FlightTelemetry telemetry(FlightState state, long tick, long acknowledged) {
        return new FlightTelemetry(state, 1, 1, false, false, false, ControlStatus.OK, tick, acknowledged);
    }

    private static float angularDistance(FlightState a, FlightState b) {
        return Math.max(Math.abs(a.pitch() - b.pitch()), Math.max(
            Math.abs(FlightControlModel.wrapDegrees(a.yaw() - b.yaw())),
            Math.abs(FlightControlModel.wrapDegrees(a.roll() - b.roll()))));
    }

    private static void sameState(FlightState expected, FlightState actual, String label) {
        check(angularDistance(expected, actual) < .0001f, label + " angles");
        check(Math.abs(expected.pitchRate() - actual.pitchRate()) < .0001f
            && Math.abs(expected.yawRate() - actual.yawRate()) < .0001f
            && Math.abs(expected.rollRate() - actual.rollRate()) < .0001f, label + " angular rates");
        check(Math.abs(expected.targetThrottle() - actual.targetThrottle()) < .0001f
            && Math.abs(expected.effectivePower() - actual.effectivePower()) < .0001f, label + " throttle and power");
        check(expected.enginePhase() == actual.enginePhase() && expected.phaseTicks() == actual.phaseTicks()
            && Math.abs(expected.spool() - actual.spool()) < .0001f, label + " engine transition");
        check(expected.gearDown() == actual.gearDown() && expected.hover() == actual.hover()
            && expected.throttleHoldTicks() == actual.throttleHoldTicks(), label + " held modes");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
