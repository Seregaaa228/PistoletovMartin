package dev.frontier.aviation.control.server;

import dev.frontier.aviation.control.*;
import dev.frontier.aviation.control.diagnostics.FlightDiagnostics;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.UUID;

/** Thread-confined lease state; no world or transport dependency. Epoch allocator belongs to service. */
public final class FlightAuthority {
    public static final long TIMEOUT_NANOS = 500_000_000L;
    public static final int MAX_QUEUED_COMMANDS = 3;
    private final UUID vehicleId;
    private final FlightKind kind;
    private final ArrayDeque<PilotCommand> pending = new ArrayDeque<>();
    private long epoch;
    private ControlLease lease;
    private long acknowledged = -1;
    private long receivedSequence = -1, consumedSequence = -1;
    private long receivedAt;
    private PilotCommand held = PilotCommand.neutral(0);

    public FlightAuthority(UUID vehicleId, long initialEpoch) {
        this(vehicleId, initialEpoch, FlightKind.AIRCRAFT);
    }
    public FlightAuthority(UUID vehicleId, long initialEpoch, FlightKind kind) {
        if (initialEpoch <= 0) throw new IllegalArgumentException("epoch");
        this.vehicleId = vehicleId;
        this.epoch = initialEpoch;
        this.kind = Objects.requireNonNull(kind, "kind");
    }
    public ControlLease lease() { return lease; }
    public long epoch() { return epoch; }
    public long acknowledged() { return acknowledged; }
    /** Called after the consumed command has advanced the model, never merely on packet receipt. */
    public void markApplied() { acknowledged = Math.max(acknowledged, consumedSequence); }
    public ControlLease acquire(UUID controller, long newEpoch, long now) {
        if (lease != null) return lease.controllerId().equals(controller) ? lease : null;
        if (newEpoch <= epoch) throw new IllegalArgumentException("epoch must advance");
        epoch = newEpoch;
        lease = new ControlLease(vehicleId, controller, epoch);
        acknowledged = -1;
        receivedSequence = consumedSequence = -1;
        held = PilotCommand.neutral(0);
        pending.clear();
        receivedAt = now;
        return lease;
    }
    /** On overload, merge into the newest queued step to bound latency without interrupting control. */
    public ControlStatus submit(UUID controller, ControlLease supplied, PilotCommand next, long now) {
        if (!matches(controller, supplied)) return ControlStatus.INVALID_LEASE;
        if (next == null || !valid(next)) return ControlStatus.INVALID_INPUT;
        if (next.sequence() <= receivedSequence) return ControlStatus.STALE_SEQUENCE;
        // A timed-out epoch may have discarded queued frames; never accept a later
        // sequence in that epoch, since its ACK would also cover those frames.
        if (timedOut(now)) return ControlStatus.TIMEOUT;
        boolean merged = pending.size() >= MAX_QUEUED_COMMANDS;
        if (merged) pending.addLast(merge(pending.removeLast(), next));
        else pending.addLast(next);
        if (FlightDiagnostics.ENABLED) FlightDiagnostics.submitted(merged, pending.size());
        receivedSequence = next.sequence();
        receivedAt = now;
        return ControlStatus.OK;
    }
    private PilotCommand merge(PilotCommand earlier, PilotCommand later) {
        float horizontal = kind == FlightKind.HELICOPTER
            ? sumAxis(earlier.rollAxis(), later.rollAxis()) : sumAxis(earlier.yawAxis(), later.yawAxis());
        return new PilotCommand(later.sequence(), sumAxis(earlier.pitchAxis(), later.pitchAxis()),
            kind == FlightKind.HELICOPTER ? later.yawAxis() : horizontal,
            kind == FlightKind.HELICOPTER ? horizontal : later.rollAxis(),
            later.throttleAxis(), later.brake(), later.secondaryBrake(), later.boost(),
            mergeMode(earlier.ignition(), later.ignition()), mergeMode(earlier.gear(), later.gear()),
            mergeMode(earlier.hover(), later.hover()));
    }
    private static float sumAxis(float earlier, float later) {
        return Math.max(-1, Math.min(1, earlier + later));
    }
    private static ModeRequest mergeMode(ModeRequest earlier, ModeRequest later) {
        return later == ModeRequest.KEEP ? earlier : later;
    }
    public boolean matches(UUID controller, ControlLease supplied) {
        return lease != null && lease.equals(supplied) && lease.controllerId().equals(controller);
    }
    public boolean timedOut(long now) { return lease != null && now - receivedAt >= TIMEOUT_NANOS; }
    /** Apply one received frame per model step; without one, only held controls persist. */
    public PilotCommand consume(long now) {
        consumedSequence = -1;
        if (lease == null || timedOut(now)) {
            clearInput();
            return held;
        }
        PilotCommand next = pending.pollFirst();
        if (FlightDiagnostics.ENABLED) FlightDiagnostics.dequeued(next != null);
        if (next == null) return held;
        consumedSequence = next.sequence();
        held = new PilotCommand(next.sequence(), 0,
            kind == FlightKind.HELICOPTER ? next.yawAxis() : 0,
            kind == FlightKind.HELICOPTER ? 0 : next.rollAxis(),
            next.throttleAxis(), next.brake(), next.secondaryBrake(), next.boost(),
            ModeRequest.KEEP, ModeRequest.KEEP, ModeRequest.KEEP);
        return next;
    }
    public PilotCommand neutralize() {
        consumedSequence = -1;
        clearInput(); return held;
    }
    public boolean release(ControlLease supplied) {
        if (lease == null || !lease.equals(supplied)) return false;
        lease = null;
        clearInput();
        return true;
    }
    private void clearInput() {
        pending.clear();
        held = held.withoutTransientInput();
        consumedSequence = -1;
    }
    public static boolean valid(PilotCommand command) {
        return command.sequence() >= 0 && axis(command.pitchAxis()) && axis(command.yawAxis())
            && axis(command.rollAxis()) && axis(command.throttleAxis()) && command.ignition() != null
            && command.gear() != null && command.hover() != null;
    }
    private static boolean axis(float value) { return Float.isFinite(value) && value >= -1 && value <= 1; }
}
