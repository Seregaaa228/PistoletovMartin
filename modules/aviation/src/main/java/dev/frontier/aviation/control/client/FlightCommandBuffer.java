package dev.frontier.aviation.control.client;

import dev.frontier.aviation.control.ModeRequest;
import dev.frontier.aviation.control.PilotCommand;
import java.util.Objects;

/** Input intent survives tick gaps; each simulated flight frame receives a distinct sequence. */
public final class FlightCommandBuffer {
    private PilotCommand intent = PilotCommand.neutral(0);
    private long sequence;

    public void update(PilotCommand next) {
        Objects.requireNonNull(next, "next");
        intent = new PilotCommand(sequence, next.pitchAxis(), next.yawAxis(), next.rollAxis(), next.throttleAxis(),
            next.brake(), next.secondaryBrake(), next.boost(),
            merge(intent.ignition(), next.ignition()), merge(intent.gear(), next.gear()), merge(intent.hover(), next.hover()));
    }

    public PilotCommand next() {
        long nextSequence = Math.incrementExact(sequence);
        PilotCommand frame = new PilotCommand(nextSequence, intent.pitchAxis(), intent.yawAxis(), intent.rollAxis(), intent.throttleAxis(),
            intent.brake(), intent.secondaryBrake(), intent.boost(), intent.ignition(), intent.gear(), intent.hover());
        sequence = nextSequence;
        intent = new PilotCommand(sequence, intent.pitchAxis(), intent.yawAxis(), intent.rollAxis(), intent.throttleAxis(),
            intent.brake(), intent.secondaryBrake(), intent.boost(), ModeRequest.KEEP, ModeRequest.KEEP, ModeRequest.KEEP);
        return frame;
    }

    public PilotCommand visualIntent() { return intent; }

    public void clearTransient() { intent = PilotCommand.neutral(sequence); }

    public void reset() {
        sequence = 0;
        intent = PilotCommand.neutral(0);
    }

    private static ModeRequest merge(ModeRequest pending, ModeRequest next) {
        return next == ModeRequest.KEEP ? pending : next;
    }
}
