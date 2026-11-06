package dev.frontier.aviation.control;

import dev.frontier.aviation.handling.FlightDynamics;
import java.util.Objects;

/** One deterministic 20 Hz control tick. Native forces and energy remain outside this class. */
public final class FlightControlModel {
    private FlightControlModel() {}

    public static FlightState initial(float pitch, float yaw, float roll, float targetThrottle,
                                      EnginePhase phase, boolean gearDown, boolean hover) {
        Objects.requireNonNull(phase, "phase");
        // Startup progress and rundown are transient; only a completed running selection survives load.
        EnginePhase restored = phase == EnginePhase.RUNNING ? phase : EnginePhase.OFF;
        return new FlightState(pitch, wrapDegrees(yaw), wrapDegrees(roll), 0, 0, 0,
            targetThrottle, 0, restored, 0, restored == EnginePhase.RUNNING ? 1 : 0,
            gearDown, hover, 0);
    }

    public static float wrapDegrees(float angle) {
        FlightState.finite(angle, "angle");
        float wrapped = angle % 360;
        if (wrapped >= 180) wrapped -= 360;
        if (wrapped < -180) wrapped += 360;
        return wrapped;
    }

    public static FlightOutput step(FlightProfile profile, FlightState previous,
                                   PilotCommand command, FlightEnvironment environment) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(environment, "environment");
        boolean helicopter = profile.kind() == FlightKind.HELICOPTER;
        ControlStatus status = ControlStatus.OK;
        boolean gear = previous.gearDown(), hover = previous.hover();
        if (command.gear() != ModeRequest.KEEP) {
            if (environment.gearAvailable()) gear = command.gear() == ModeRequest.ENABLE;
            else status = ControlStatus.UNSUPPORTED_MODE;
        }
        if (command.hover() != ModeRequest.KEEP) {
            if (helicopter && environment.hoverAvailable()) hover = command.hover() == ModeRequest.ENABLE;
            else status = ControlStatus.UNSUPPORTED_MODE;
        }
        if (command.pitchAxis() != 0 || command.rollAxis() != 0) hover = false;

        FlightDynamics dynamics = environment.dynamics();
        boolean inertial = !helicopter && dynamics.active();
        // A wheel hop off a block edge at taxi speed is still the ground phase: no stall
        // nose-drop and no pitch/bank from the stick until the gear is back down.
        boolean hop = taxiHop(helicopter, environment);
        boolean groundPhase = inertial && (environment.onGround() || hop);
        float pitchAxis = hop ? 0 : command.pitchAxis();
        float rollAxis = groundPhase ? 0 : command.rollAxis();
        // On the ground A/D steer the nose wheel together with the mouse.
        float yawAxis = groundPhase ? clamp(command.yawAxis() + command.rollAxis(), -1, 1) : command.yawAxis();

        FlightEnvironment.AxisCaps caps = environment.caps();
        float pitchRate = environment.nativeEmergency() ? 0 : rate(previous.pitchRate(), pitchAxis,
            caps.pitchNegative(), caps.pitchPositive(), profile, dynamics, inertial);
        pitchRate = stall(pitchRate, previous.pitch(), helicopter, environment);
        float yawRate = environment.nativeEmergency() ? 0 : rate(previous.yawRate(), yawAxis,
            caps.yawNegative(), caps.yawPositive(), profile, dynamics, inertial);
        float rollRate = environment.nativeEmergency() ? 0 : rate(previous.rollRate(), rollAxis,
            caps.rollNegative(), caps.rollPositive(), profile, dynamics, inertial);
        float turnRate = inertial && !groundPhase && !environment.nativeEmergency()
            ? turn(previous.pitch(), previous.roll(), rollRate - previous.rollRate(), environment) : 0;

        // Shift is a temporary native collective command, not a replacement remembered selection.
        boolean secondaryDown = helicopter && command.secondaryBrake();
        float throttleAxis = secondaryDown ? 0 : command.throttleAxis();
        int direction = secondaryDown ? -1 : throttleAxis > 0 ? 1 : throttleAxis < 0 ? -1 : 0;
        int rampTicks = direction > 0 ? environment.throttleRampUpTicks() : environment.throttleRampDownTicks();
        int held = direction == 0 ? 0 : direction * Math.min(rampTicks,
            Integer.signum(previous.throttleHoldTicks()) == direction ? Math.abs(previous.throttleHoldTicks()) + 1 : 1);
        // Native helicopter deltas are multiplied by the held-tick count (1..10 / 1..5),
        // not a normalized fraction of that count. Released target restoration uses the base rate.
        float ramp = direction == 0 ? 1 : Math.abs(held);
        float target = previous.targetThrottle();
        float range = environment.maximumPower() - environment.idlePower();
        float throttleIncrease = command.boost() ? environment.boostThrottleIncrease() : environment.throttleIncrease();
        if (throttleAxis > 0) {
            target = clamp(target + throttleAxis * throttleIncrease * ramp / range, 0, 1);
        } else if (throttleAxis < 0) {
            float next = clamp(target + throttleAxis * environment.throttleDecrease() * ramp / range, 0, 1);
            if (helicopter && !environment.onGround()) {
                float floor = clamp((environment.airborneMinimumPower() - environment.idlePower()) / range, 0, 1);
                next = Math.max(next, Math.min(target, floor));
            }
            target = next;
        }

        EnginePhase phase = previous.enginePhase();
        int phaseTicks = previous.phaseTicks();
        boolean enteredStarting = false, enteredStopping = false;
        if (command.ignition() == ModeRequest.DISABLE && phase != EnginePhase.OFF && phase != EnginePhase.STOPPING) {
            phase = EnginePhase.STOPPING;
            phaseTicks = 0;
            enteredStopping = true;
        } else if (command.ignition() == ModeRequest.ENABLE && (phase == EnginePhase.OFF || phase == EnginePhase.STOPPING)) {
            if (!environment.engineHealthy()) status = ControlStatus.DAMAGED;
            else if (!environment.energyAvailable()) status = ControlStatus.NO_ENERGY;
            else {
                phase = EnginePhase.STARTING;
                phaseTicks = 0;
                enteredStarting = true;
            }
        }
        if ((phase == EnginePhase.STARTING || phase == EnginePhase.RUNNING)
                && (!environment.engineHealthy() || !environment.energyAvailable())) {
            status = !environment.engineHealthy() ? ControlStatus.DAMAGED : ControlStatus.NO_ENERGY;
            phase = EnginePhase.STOPPING;
            phaseTicks = 0;
            enteredStopping = true;
        }
        if (phase == EnginePhase.STARTING && !enteredStarting) {
            phaseTicks = Math.min(profile.startupTicks(), increment(phaseTicks));
            if (phaseTicks == profile.startupTicks()) phase = EnginePhase.RUNNING;
        } else if (phase == EnginePhase.STOPPING && !enteredStopping) {
            phaseTicks = increment(phaseTicks);
            if (environment.rotorSpool() < .001f) {
                phase = EnginePhase.OFF;
                phaseTicks = 0;
            }
        }

        float nativePower = 0, effective = 0;
        if (phase == EnginePhase.STARTING) nativePower = environment.idlePower();
        if (phase == EnginePhase.RUNNING) {
            float maximum = command.boost() ? environment.boostMaximumPower() : environment.maximumPower();
            float requested = environment.idlePower() + target * (maximum - environment.idlePower());
            float actual = previous.enginePhase() == EnginePhase.RUNNING ? Math.max(0, previous.effectivePower()) : environment.idlePower();
            if (secondaryDown) {
                float floor = environment.onGround() ? 0 : environment.secondaryMinimumPower();
                requested = Math.max(Math.min(actual, floor), actual - environment.secondaryDecrease() * ramp);
            }
            float rise = throttleIncrease * (direction > 0 ? ramp : 1);
            float fall = (secondaryDown ? environment.secondaryDecrease() : environment.throttleDecrease())
                * (direction < 0 ? ramp : 1);
            nativePower = approach(actual, requested, requested >= actual ? rise : fall);
            // A reduced boost ceiling is a target, not an instantaneous power cut.
            // Existing over-ceiling power decays at the supplied native fall rate.
            nativePower = clamp(nativePower, 0, Math.max(maximum, actual));
            if (!helicopter && (command.brake() || command.secondaryBrake())) {
                nativePower = Math.min(nativePower, environment.onGround() ? 0 : environment.airBrakePowerCap());
            }
            effective = nativePower;
        }
        float spool = switch (phase) {
            case OFF -> 0;
            case STARTING -> (float) phaseTicks / profile.startupTicks();
            case RUNNING -> 1;
            case STOPPING -> environment.rotorSpool();
        };
        FlightState state = new FlightState(previous.pitch() + pitchRate,
            wrapDegrees(turnRate == 0 ? previous.yaw() + yawRate : previous.yaw() + yawRate + turnRate),
            wrapDegrees(previous.roll() + rollRate),
            pitchRate, yawRate, rollRate, target, effective, phase, phaseTicks, spool, gear, hover, held);
        return new FlightOutput(state, nativePower, phase == EnginePhase.RUNNING,
            command.brake(), command.secondaryBrake(), command.boost(), status);
    }

    /** Nose-down pitch rate (degrees/tick) of a fully stalled jet at zero airspeed. */
    static final float STALL_DROP = 2.2f;

    /**
     * Below its stall speed a jet cannot hold its nose up: the pull-up authority fades with
     * the airspeed and the nose falls (positive pitch is nose down) until speed returns.
     * Without this a zoom climb ends hanging vertically on thrust at ~0 km/h.
     */
    static float stall(float pitchRate, float pitch, boolean helicopter, FlightEnvironment environment) {
        float stall = environment.stallSpeed();
        if (helicopter || stall <= 0 || environment.onGround() || taxiHop(false, environment)) return pitchRate;
        float speed = environment.speed();
        // Falls fastest nose-high; stops pushing once the nose points well down.
        float noseHigh = clamp((70 - pitch) / 70, 0, 1);
        // As speed bleeds off toward the stall the nose grows heavy (per-jet dynamics only).
        float sink = environment.dynamics().noseSink() * clamp((SINK_ONSET * stall - speed) / ((SINK_ONSET - 1) * stall), 0, 1) * noseHigh;
        // Without a sink the original arithmetic is kept exactly (no -0 + 0 sign change).
        if (speed >= stall) return sink == 0 ? pitchRate : pitchRate + sink;
        float lift = speed / stall;
        if (pitchRate < 0) pitchRate *= lift;
        float drop = STALL_DROP * (1 - lift) * noseHigh;
        return sink == 0 ? pitchRate + drop : pitchRate + drop + sink;
    }

    /** The nose starts to feel heavy below this multiple of the stall speed. */
    static final float SINK_ONSET = 1.6f;
    /** Gear within this many blocks of the ground counts as a wheel hop, not flight. */
    static final float HOP_CLEARANCE = 1.6f;
    /** A hop only happens below this fraction of the stall speed (taxiing, not a take-off). */
    static final float HOP_SPEED = .75f;

    /** Airborne for a moment over a block edge while taxiing (per-jet dynamics only). */
    static boolean taxiHop(boolean helicopter, FlightEnvironment environment) {
        return !helicopter && environment.dynamics().active() && !environment.onGround()
            && environment.groundClearance() < HOP_CLEARANCE && environment.speed() < HOP_SPEED * environment.stallSpeed();
    }

    /**
     * Yaw that follows the bank (a banked jet turns by itself, fading in with airspeed) and
     * a touch of adverse yaw against the roll while a roll starts (and back as it stops), so
     * a complete roll leaves the heading where it was.
     */
    static float turn(float pitch, float roll, float rollAcceleration, FlightEnvironment environment) {
        FlightDynamics dynamics = environment.dynamics();
        double level = Math.max(0, Math.cos(Math.toRadians(pitch)));
        float stall = environment.stallSpeed();
        float weight = stall > 0 ? clamp(environment.speed() / (1.5f * stall), 0, 1) : 1;
        return (float) ((dynamics.bankTurn() * Math.sin(Math.toRadians(roll)) * weight
            - dynamics.adverseYaw() * rollAcceleration) * level);
    }

    private static float rate(float current, float axis, float negative, float positive, FlightProfile profile,
                              FlightDynamics dynamics, boolean inertial) {
        if (!inertial) return rate(current, axis, negative, positive, profile);
        // Inertial axis: the rate eases toward the request (and back to zero on release)
        // instead of ramping linearly, limited to the profile's angular acceleration.
        current = clamp(current, -negative, positive);
        float cap = axis == 0 ? (current < 0 ? negative : positive) : axis < 0 ? negative : positive;
        float desired = axis * cap;
        float gain = axis == 0 ? dynamics.release() : dynamics.response();
        float maxStep = cap / (axis == 0 ? profile.dampingTicks() : profile.responseTicks());
        float next = current + clamp((desired - current) * gain, -maxStep, maxStep);
        if (Math.abs(desired - next) <= RATE_SNAP) next = desired;
        return clamp(next, -negative, positive);
    }

    /** Below this rate difference (deg/tick) an inertial axis has arrived. */
    static final float RATE_SNAP = .01f;

    private static float rate(float current, float axis, float negative, float positive, FlightProfile profile) {
        current = clamp(current, -negative, positive);
        float cap = axis == 0 ? (current < 0 ? negative : positive) : axis < 0 ? negative : positive;
        float desired = axis * cap;
        if (axis != 0 && current != 0 && Math.signum(current) != Math.signum(axis)) desired = 0;
        return clamp(approach(current, desired, cap / (axis == 0 ? profile.dampingTicks() : profile.responseTicks())), -negative, positive);
    }

    private static float approach(float current, float target, float delta) {
        // Float storage must not add an extra response/reversal tick at a rounding residue.
        float tolerance = 16 * Math.ulp(Math.max(Math.max(Math.abs(current), Math.abs(target)), delta));
        if (delta > 0 && Math.abs(target - current) <= delta + tolerance) return target;
        return current < target ? Math.min(current + delta, target) : Math.max(current - delta, target);
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static int increment(int value) { return value == Integer.MAX_VALUE ? value : value + 1; }

}
