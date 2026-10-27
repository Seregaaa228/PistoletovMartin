package dev.frontier.aviation.control.client;

import dev.frontier.aviation.control.*;
import dev.frontier.aviation.control.diagnostics.FlightDiagnostics;
import java.util.ArrayDeque;

/** Bounded pure-model history: replay never calls the native integrator. */
public final class FlightPrediction {
    public static final int MAX_FRAMES = 80;
    private record Frame(PilotCommand command, FlightEnvironment environment, FlightAttitudeLimits limits) {}
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private FlightState state;
    private long epoch = -1, serverTick = -1;
    private float correctionPitch, correctionYaw, correctionRoll;
    private int correctionTicks;

    public FlightState state() { return state; }
    public int pendingFrames() { return frames.size(); }
    public long epoch() { return epoch; }
    public boolean accept(FlightProfile profile, long newEpoch, FlightTelemetry telemetry) {
        if (newEpoch < epoch || newEpoch == epoch && telemetry.serverTick() <= serverTick) return false;
        FlightState displayed = displayed();
        boolean changedEpoch = epoch != newEpoch;
        if (changedEpoch) frames.clear();
        epoch = newEpoch; serverTick = telemetry.serverTick(); state = telemetry.state();
        long acknowledged = telemetry.acknowledgedSequence();
        // ACK covers the completed prefix, including frames merged or superseded on the server.
        // World clocks can differ; comparing them would replay acknowledged input.
        frames.removeIf(frame -> frame.command.sequence() <= acknowledged);
        if (FlightDiagnostics.ENABLED) FlightDiagnostics.snapshot(frames.size());
        for (Frame frame : frames) {
            state = frame.limits.constrain(FlightControlModel.step(profile, state, frame.command, frame.environment).state());
        }
        if (displayed != null && !changedEpoch) {
            correctionPitch = difference(displayed.pitch(), state.pitch());
            correctionYaw = difference(displayed.yaw(), state.yaw());
            correctionRoll = difference(displayed.roll(), state.roll());
            correctionTicks = 4;
        } else { correctionPitch = correctionYaw = correctionRoll = 0; correctionTicks = 0; }
        return true;
    }
    public FlightOutput step(FlightProfile profile, PilotCommand command, FlightEnvironment environment) {
        return step(profile, command, environment, FlightAttitudeLimits.UNRESTRICTED);
    }
    public FlightOutput step(FlightProfile profile, PilotCommand command, FlightEnvironment environment, FlightAttitudeLimits limits) {
        FlightOutput output = FlightControlModel.step(profile, state, command, environment);
        state = limits.constrain(output.state());
        frames.addLast(new Frame(command, environment, limits));
        while (frames.size() > MAX_FRAMES) {
            frames.removeFirst();
            if (FlightDiagnostics.ENABLED) FlightDiagnostics.evictedPending();
        }
        if (correctionTicks > 0) {
            float scale = (correctionTicks - 1f) / correctionTicks;
            correctionPitch *= scale; correctionYaw *= scale; correctionRoll *= scale;
            correctionTicks--;
        }
        return new FlightOutput(displayed(), output.nativePower(), output.propulsionEnabled(),
            output.brake(), output.secondaryBrake(), output.boost(), output.status());
    }
    public FlightState displayed() {
        return state == null ? null : attitude(state, state.pitch() + correctionPitch,
            state.yaw() + correctionYaw, state.roll() + correctionRoll);
    }
    /** Record only native permitted changes, keeping the single visual correction separate. */
    public void capture(FlightState observed) {
        FlightState displayed = displayed();
        if (displayed != null) {
            // A native constraint or damage writer has final authority over its
            // axis. Subtracting a correction after that clamp invents an angle
            // outside the constraint and causes later frames to rebound into it.
            if (Math.abs(observed.pitch() - displayed.pitch()) > .0001f) correctionPitch = 0;
            if (Math.abs(FlightControlModel.wrapDegrees(observed.yaw() - displayed.yaw())) > .0001f) correctionYaw = 0;
            if (Math.abs(FlightControlModel.wrapDegrees(observed.roll() - displayed.roll())) > .0001f) correctionRoll = 0;
        }
        state = attitude(observed, observed.pitch() - correctionPitch,
            observed.yaw() - correctionYaw, observed.roll() - correctionRoll);
    }
    public void clearHistory() { frames.clear(); }
    private static float difference(float from, float to) {
        return Math.max(-45, Math.min(45, FlightControlModel.wrapDegrees(from - to)));
    }
    public static FlightState attitude(FlightState source, float pitch, float yaw, float roll) {
        return new FlightState(pitch, FlightControlModel.wrapDegrees(yaw), FlightControlModel.wrapDegrees(roll),
            source.pitchRate(), source.yawRate(), source.rollRate(), source.targetThrottle(), source.effectivePower(),
            source.enginePhase(), source.phaseTicks(), source.spool(), source.gearDown(), source.hover(), source.throttleHoldTicks());
    }
    public static FlightState interpolate(FlightState from, FlightState to, float fraction) {
        float t = Math.max(0, Math.min(1, fraction));
        return attitude(to, from.pitch() + FlightControlModel.wrapDegrees(to.pitch() - from.pitch()) * t,
            from.yaw() + FlightControlModel.wrapDegrees(to.yaw() - from.yaw()) * t,
            from.roll() + FlightControlModel.wrapDegrees(to.roll() - from.roll()) * t);
    }
}
