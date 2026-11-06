package dev.frontier.aviation.control;

import java.util.Objects;

public record PilotCommand(
    long sequence, float pitchAxis, float yawAxis, float rollAxis, float throttleAxis,
    boolean brake, boolean secondaryBrake, boolean boost,
    ModeRequest ignition, ModeRequest gear, ModeRequest hover
) {
    public PilotCommand {
        if (sequence < 0) throw new IllegalArgumentException("sequence must be nonnegative");
        checkAxis(pitchAxis, "pitchAxis");
        checkAxis(yawAxis, "yawAxis");
        checkAxis(rollAxis, "rollAxis");
        checkAxis(throttleAxis, "throttleAxis");
        Objects.requireNonNull(ignition, "ignition");
        Objects.requireNonNull(gear, "gear");
        Objects.requireNonNull(hover, "hover");
    }

    private static void checkAxis(float value, String name) {
        if (!Float.isFinite(value) || value < -1.0f || value > 1.0f) {
            throw new IllegalArgumentException(name + " must be finite and in [-1,1]");
        }
    }

    public static PilotCommand neutral(long sequence) {
        return new PilotCommand(sequence, 0, 0, 0, 0, false, false, false,
            ModeRequest.KEEP, ModeRequest.KEEP, ModeRequest.KEEP);
    }

    public PilotCommand withoutTransientInput() {
        return new PilotCommand(sequence, 0, 0, 0, 0, false, false, false,
            ModeRequest.KEEP, ModeRequest.KEEP, ModeRequest.KEEP);
    }
}
