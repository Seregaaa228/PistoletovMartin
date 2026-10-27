package dev.frontier.aviation.control.server;

import dev.frontier.aviation.control.*;
import java.util.UUID;

public final class FlightAuthorityTest {
    private static int assertions;
    public static void main(String[] args) {
        frameQueue(FlightKind.AIRCRAFT);
        frameQueue(FlightKind.HELICOPTER);
        overloadMerge(FlightKind.AIRCRAFT);
        overloadMerge(FlightKind.HELICOPTER);
        slowServer(10);
        slowServer(15);
        timeoutAndLease();
        System.out.println("FlightAuthorityTest: " + assertions + " assertions passed");
    }
    private static void frameQueue(FlightKind kind) {
        UUID owner = UUID.randomUUID();
        FlightAuthority authority = new FlightAuthority(UUID.randomUUID(), 1, kind);
        ControlLease lease = authority.acquire(owner, 2, 0);
        PilotCommand first = command(1, .4f, .3f, -.2f, .5f, ModeRequest.ENABLE, ModeRequest.DISABLE);
        PilotCommand second = command(2, -.1f, -.6f, .7f, -.5f, ModeRequest.DISABLE, ModeRequest.ENABLE);
        check(authority.submit(owner, lease, first, 1) == ControlStatus.OK, "first frame accepted");
        check(authority.submit(owner, lease, second, 2) == ControlStatus.OK, "second frame accepted");
        check(authority.acknowledged() == -1, "receipt does not acknowledge");
        check(authority.consume(3).equals(first), kind + " first of two packets applied in full");
        check(authority.acknowledged() == -1, "consume does not acknowledge before physics");
        check(authority.submit(owner, lease, command(3, 0, 0, 0, 0, ModeRequest.KEEP, ModeRequest.KEEP), 3)
            == ControlStatus.OK, "later receipt between consume and applied");
        authority.markApplied();
        check(authority.acknowledged() == 1, "only first simulated frame acknowledged");
        check(authority.consume(4).equals(second), kind + " second frame and modes applied in order");
        authority.markApplied();
        check(authority.acknowledged() == 2, "second simulated frame acknowledged");
        PilotCommand third = authority.consume(5);
        check(third.sequence() == 3 && third.pitchAxis() == 0, "later frame remains queued");
        authority.markApplied();
        check(authority.acknowledged() == 3, "later frame acknowledged only after application");
        // Reestablish nonzero held controls without any mouse movement.
        PilotCommand heldFrame = kind == FlightKind.AIRCRAFT
            ? command(4, 0, 0, .7f, -.5f, ModeRequest.KEEP, ModeRequest.KEEP)
            : command(4, 0, -.6f, 0, -.5f, ModeRequest.KEEP, ModeRequest.KEEP);
        authority.submit(owner, lease, heldFrame, 6);
        authority.consume(7); authority.markApplied();
        PilotCommand empty = authority.consume(8);
        check(empty.pitchAxis() == 0, "mouse pitch is never repeated");
        check((kind == FlightKind.AIRCRAFT ? empty.yawAxis() : empty.rollAxis()) == 0,
            "mouse horizontal is never repeated");
        check((kind == FlightKind.AIRCRAFT ? empty.rollAxis() : empty.yawAxis())
            == (kind == FlightKind.AIRCRAFT ? second.rollAxis() : second.yawAxis()), "keyboard axis remains held");
        check(empty.throttleAxis() == -.5f && empty.brake() && empty.secondaryBrake() && empty.boost(), "held controls persist");
        check(empty.ignition() == ModeRequest.KEEP && empty.gear() == ModeRequest.KEEP, "actions occur once");
        authority.markApplied();
        check(authority.acknowledged() == 4, "empty tick cannot acknowledge a new frame");
        check(authority.consume(9).equals(empty), "zero packets keeps only held input");
        check(authority.submit(owner, lease, second, 10) == ControlStatus.STALE_SEQUENCE, "replayed sequence rejected");
    }
    private static void overloadMerge(FlightKind kind) {
        UUID owner = UUID.randomUUID();
        FlightAuthority authority = new FlightAuthority(UUID.randomUUID(), 1, kind);
        ControlLease lease = authority.acquire(owner, 2, 0);
        for (int i = 1; i <= FlightAuthority.MAX_QUEUED_COMMANDS; i++)
            check(authority.submit(owner, lease, PilotCommand.neutral(i), i) == ControlStatus.OK, "queue accepts within bound");
        PilotCommand positive = command(4, 1, kind == FlightKind.AIRCRAFT ? 1 : -.3f,
            kind == FlightKind.HELICOPTER ? 1 : .3f, 1, ModeRequest.ENABLE, ModeRequest.ENABLE);
        PilotCommand overflow = new PilotCommand(5, 1, kind == FlightKind.AIRCRAFT ? 1 : .8f,
            kind == FlightKind.HELICOPTER ? 1 : -.8f, -1, false, false, false,
            ModeRequest.KEEP, ModeRequest.DISABLE, ModeRequest.KEEP);
        authority.submit(owner, lease, positive, 4);
        check(authority.submit(owner, lease, overflow, 5) == ControlStatus.OK, "overload accepted");
        check(authority.acknowledged() == -1, "receipt alone never acknowledges");
        check(authority.consume(6).sequence() == 1, "oldest frame remains first"); authority.markApplied();
        check(authority.acknowledged() == 1, "ack is oldest applied frame");
        check(authority.consume(7).sequence() == 2, "second frame remains ordered"); authority.markApplied();
        PilotCommand merged = authority.consume(8);
        check(merged.sequence() == 5 && merged.pitchAxis() == 1, "merged sequence and saturated pitch");
        check((kind == FlightKind.AIRCRAFT ? merged.yawAxis() : merged.rollAxis()) == 1, "saturated mouse horizontal");
        check((kind == FlightKind.AIRCRAFT ? merged.rollAxis() : merged.yawAxis()) == (kind == FlightKind.AIRCRAFT ? -.8f : .8f), "latest keyboard axis");
        check(merged.throttleAxis() == -1 && !merged.brake() && !merged.secondaryBrake() && !merged.boost()
            && merged.ignition() == ModeRequest.ENABLE
            && merged.gear() == ModeRequest.DISABLE, "latest held controls and latest non-KEEP modes");
        authority.markApplied();
        check(authority.acknowledged() == 5, "merged sequence acknowledged only after its approximate step");
        PilotCommand empty = authority.consume(9);
        check(empty.pitchAxis() == 0 && (kind == FlightKind.AIRCRAFT ? empty.yawAxis() : empty.rollAxis()) == 0,
            "saturated input leaves no unacknowledged residual");
        check(empty.ignition() == ModeRequest.KEEP && empty.gear() == ModeRequest.KEEP, "merged actions occur once");
        // Direction reversal cancels what remains after saturation; previously clipped movement is not replayed.
        authority.submit(owner, lease, PilotCommand.neutral(6), 10);
        authority.submit(owner, lease, PilotCommand.neutral(7), 11);
        authority.submit(owner, lease, command(8, .75f, .75f, .75f, 0, ModeRequest.ENABLE, ModeRequest.ENABLE), 12);
        authority.submit(owner, lease, command(9, -.75f, -.75f, -.75f, 0, ModeRequest.DISABLE, ModeRequest.KEEP), 13);
        authority.consume(14); authority.markApplied();
        authority.consume(15); authority.markApplied();
        PilotCommand reversed = authority.consume(16);
        check(reversed.pitchAxis() == 0 && (kind == FlightKind.AIRCRAFT ? reversed.yawAxis() : reversed.rollAxis()) == 0,
            "opposing relative input cancels");
        check(reversed.ignition() == ModeRequest.DISABLE && reversed.gear() == ModeRequest.ENABLE,
            "latest non-KEEP requests survive opposite commands");
        authority.submit(owner, lease, PilotCommand.neutral(10), 17);
        authority.submit(owner, lease, PilotCommand.neutral(11), 18);
        authority.submit(owner, lease, command(12, 1, 1, 1, 0, ModeRequest.KEEP, ModeRequest.KEEP), 19);
        authority.submit(owner, lease, command(13, 1, 1, 1, 0, ModeRequest.KEEP, ModeRequest.KEEP), 20);
        authority.submit(owner, lease, command(14, -1, -1, -1, 0, ModeRequest.KEEP, ModeRequest.KEEP), 21);
        authority.consume(22); authority.markApplied();
        authority.consume(23); authority.markApplied();
        PilotCommand clippedThenReversed = authority.consume(24);
        check(clippedThenReversed.pitchAxis() == 0
            && (kind == FlightKind.AIRCRAFT ? clippedThenReversed.yawAxis() : clippedThenReversed.rollAxis()) == 0,
            "reversal cancels bounded value; clipped overflow is discarded");
        authority.markApplied();
        check(authority.consume(25).pitchAxis() == 0 && authority.acknowledged() == 14,
            "clipped overflow cannot reappear after acknowledgement");
    }
    private static void slowServer(int tps) {
        UUID owner = UUID.randomUUID();
        FlightAuthority authority = new FlightAuthority(UUID.randomUUID(), 1, FlightKind.AIRCRAFT);
        ControlLease lease = authority.acquire(owner, 2, 0);
        int steps = 0;
        for (int frame = 1; frame <= 200; frame++) {
            float axis = ((frame / 20) & 1) == 0 ? .25f : -.25f;
            check(authority.submit(owner, lease, command(frame, axis, axis, axis, axis,
                ModeRequest.KEEP, ModeRequest.KEEP), frame * 50_000_000L) == ControlStatus.OK,
                "slow server never rejects an input frame");
            int dueSteps = frame * tps / 20;
            if (dueSteps > steps) {
                PilotCommand applied = authority.consume(frame * 50_000_000L);
                check(applied.sequence() <= frame && applied.sequence() >= Math.max(1, frame - 6),
                    "bounded queue prevents growing input lag at " + tps + " TPS");
                authority.markApplied();
                steps++;
            }
        }
        for (int i = 0; i < FlightAuthority.MAX_QUEUED_COMMANDS; i++) {
            authority.consume(10_000_000_001L + i); authority.markApplied();
        }
        check(authority.acknowledged() == 200, "last overloaded frame is covered by applied merged step");
        check(authority.lease().equals(lease), "slow server retains control lease");
    }
    private static void timeoutAndLease() {
        UUID owner = UUID.randomUUID(), other = UUID.randomUUID(), craft = UUID.randomUUID();
        FlightAuthority authority = new FlightAuthority(craft, 1);
        ControlLease lease = authority.acquire(owner, 2, 0);
        check(authority.acquire(other, 3, 0) == null, "competing owner rejected");
        check(authority.submit(other, lease, command(1, 1, 1, 1, 1, ModeRequest.ENABLE, ModeRequest.ENABLE), 1)
            == ControlStatus.INVALID_LEASE, "spoofed owner rejected");
        authority.submit(owner, lease, command(1, 1, 1, 1, 1, ModeRequest.ENABLE, ModeRequest.ENABLE), 1);
        authority.submit(owner, lease, command(2, -1, -1, -1, -1, ModeRequest.DISABLE, ModeRequest.DISABLE), 2);
        authority.consume(3); authority.markApplied();
        PilotCommand timedOut = authority.consume(500_000_002L);
        authority.markApplied();
        check(timedOut.pitchAxis() == 0 && timedOut.yawAxis() == 0 && timedOut.rollAxis() == 0
            && timedOut.throttleAxis() == 0 && !timedOut.brake(), "timeout clears queue and held controls");
        check(authority.acknowledged() == 1, "timeout cannot acknowledge a discarded frame");
        check(authority.lease().equals(lease), "timeout retains lease until service releases it");
        check(authority.submit(owner, lease, PilotCommand.neutral(3), 500_000_003L) == ControlStatus.TIMEOUT,
            "timed-out epoch cannot accept a sequence that would acknowledge discarded input");
        check(authority.acknowledged() == 1, "rejected late input cannot advance acknowledgement");
        check(authority.release(lease), "release accepted");
        ControlLease reacquired = authority.acquire(owner, 3, 500_000_003L);
        check(reacquired.epoch() == 3 && !reacquired.equals(lease), "reacquire after timeout advances epoch");
        check(authority.submit(owner, lease, PilotCommand.neutral(3), 500_000_004L) == ControlStatus.INVALID_LEASE,
            "old commands cannot enter reacquired epoch");
        check(authority.submit(owner, reacquired, PilotCommand.neutral(0), 500_000_004L) == ControlStatus.OK,
            "new epoch accepts fresh sequence");
        check(authority.release(reacquired), "reacquired lease releases");
        ControlLease successor = authority.acquire(other, 4, 500_000_003L);
        check(successor.epoch() == 4 && authority.acknowledged() == -1, "new epoch resets acknowledgement");
        check(!authority.release(lease), "old lease cannot release successor");
        check(authority.consume(500_000_004L).equals(PilotCommand.neutral(0)), "handoff clears old input");
    }
    private static PilotCommand command(long sequence, float pitch, float yaw, float roll, float throttle,
                                        ModeRequest ignition, ModeRequest gear) {
        return new PilotCommand(sequence, pitch, yaw, roll, throttle, true, true, true,
            ignition, gear, ModeRequest.KEEP);
    }
    private static void check(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
}
