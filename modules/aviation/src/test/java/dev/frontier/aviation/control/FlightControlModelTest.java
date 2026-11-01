package dev.frontier.aviation.control;

import java.util.List;

/** Standalone Java 17 deterministic oracles; no game/bootstrap or assertions flag required. */
public final class FlightControlModelTest {
    private static int checks;
    private static final FlightEnvironment.AxisCaps CAPS = new FlightEnvironment.AxisCaps(4, 8, 6, 12, 10, 20);
    private static final List<String> IDS = List.of("frontier_aviation:f16", "superbwarfare:ju_87",
        "frontier_aviation:su25", "superbwarfare:ac_130h", "superbwarfare:ah_6", "superbwarfare:mi_28");

    public static void main(String[] args) {
        angularProfiles();
        angularCapsAndWrap();
        enginePhases();
        throttleAndBrakes();
        helicopterRampsAndFloors();
        modesAndIsolation();
        invalidInputs();
        System.out.println("FlightControlModelTest: " + checks + " checks passed");
    }

    private static void angularProfiles() {
        for (String id : IDS) {
            FlightProfile p = profile(id);
            FlightState s = initial(EnginePhase.OFF, 0);
            PilotCommand positive = command(1, 1, 1, 0, false, false, false, ModeRequest.KEEP, ModeRequest.KEEP, ModeRequest.KEEP);
            for (int tick = 1; tick <= p.responseTicks(); tick++) {
                s = step(p, s, positive, environment(false));
                near(8f * tick / p.responseTicks(), s.pitchRate(), id + " pitch rise " + tick);
                near(12f * tick / p.responseTicks(), s.yawRate(), id + " yaw rise " + tick);
                near(20f * tick / p.responseTicks(), s.rollRate(), id + " roll rise " + tick);
            }
            // A reversal uses the newly requested negative cap and cannot pass zero in one step.
            PilotCommand negative = command(-1, -1, -1, 0, false, false, false, ModeRequest.KEEP, ModeRequest.KEEP, ModeRequest.KEEP);
            for (int tick = 1; tick <= 2 * p.responseTicks(); tick++) {
                s = step(p, s, negative, environment(false));
                near(Math.max(0, 8f - 4f * tick / p.responseTicks()), s.pitchRate(), id + " reverse pitch " + tick);
                near(Math.max(0, 12f - 6f * tick / p.responseTicks()), s.yawRate(), id + " reverse yaw " + tick);
                near(Math.max(0, 20f - 10f * tick / p.responseTicks()), s.rollRate(), id + " reverse roll " + tick);
            }
            s = step(p, s, negative, environment(false));
            near(-4f / p.responseTicks(), s.pitchRate(), id + " negative starts after zero");
            s = withRates(s, -4, -6, -10);
            for (int tick = 1; tick <= p.dampingTicks(); tick++) {
                s = step(p, s, PilotCommand.neutral(0), environment(false));
                near(Math.min(0, -4f + 4f * tick / p.dampingTicks()), s.pitchRate(), id + " damping pitch");
                near(Math.min(0, -6f + 6f * tick / p.dampingTicks()), s.yawRate(), id + " damping yaw");
                near(Math.min(0, -10f + 10f * tick / p.dampingTicks()), s.rollRate(), id + " damping roll");
            }
            float pitch = s.pitch(), roll = s.roll();
            for (int i = 0; i < 30; i++) s = step(p, s, PilotCommand.neutral(0), environment(false));
            near(pitch, s.pitch(), id + " released pitch remains tilted");
            near(roll, s.roll(), id + " released roll remains tilted");
            FlightState highRate = new FlightState(25, 179, 179, 8, 12, 20, 0, 0, EnginePhase.OFF, 0, 0, true, false, 0);
            FlightEnvironment limited = change(environment(false), new FlightEnvironment.AxisCaps(1, 2, 1, 2, 1, 2), true, true, false);
            FlightState capped = step(p, highRate, positive, limited);
            near(2, capped.pitchRate(), id + " changed cap pitch immediate");
            near(2, capped.yawRate(), id + " changed cap yaw immediate");
            near(2, capped.rollRate(), id + " changed cap roll immediate");
            near(-179, capped.yaw(), id + " wrapped yaw");
            near(-179, capped.roll(), id + " wrapped roll");
            FlightState fraction = step(p, initial(EnginePhase.OFF, 0),
                command(.1f, .1f, .1f, 0, false, false, false, ModeRequest.KEEP, ModeRequest.KEEP, ModeRequest.KEEP), environment(false));
            near(.8f, fraction.pitchRate(), id + " fractional axis stops at requested rate");
            near(1.2f, fraction.yawRate(), id + " fractional yaw");
            near(2, fraction.rollRate(), id + " fractional roll");
        }
    }

    private static void angularCapsAndWrap() {
        near(-180, FlightControlModel.wrapDegrees(180), "180 wraps negative");
        near(-180, FlightControlModel.wrapDegrees(-180), "-180 stable");
        near(179, FlightControlModel.wrapDegrees(-181), "negative wrap");
        near(-179, FlightControlModel.wrapDegrees(541), "multiple turns");
        near(0, FlightControlModel.wrapDegrees(1080), "three turns");
        FlightProfile p = profile(IDS.get(0));
        FlightState s = new FlightState(25, 179, 179, 8, 12, 20, 0, 0, EnginePhase.OFF, 0, 0, true, false, 0);
        FlightEnvironment limited = change(environment(false), new FlightEnvironment.AxisCaps(1, 2, 1, 2, 1, 2), true, true, false);
        s = step(p, s, command(1, 1, 1, 0, false, false, false, ModeRequest.KEEP, ModeRequest.KEEP, ModeRequest.KEEP), limited);
        near(2, s.pitchRate(), "authority loss clamps stored rate");
        near(27, s.pitch(), "pitch integrates exactly once without wrap");
        near(-179, s.yaw(), "yaw integration wraps");
        near(-179, s.roll(), "roll integration wraps");
        FlightEnvironment zero = change(environment(false), new FlightEnvironment.AxisCaps(0, 0, 0, 0, 0, 0), true, true, false);
        FlightState stopped = step(p, s, PilotCommand.neutral(0), zero);
        near(0, stopped.pitchRate(), "zero authority rate");
        near(s.pitch(), stopped.pitch(), "zero authority attitude");
        FlightState emergency = step(p, s, command(1, 1, 1, 0, false, false, false, ModeRequest.KEEP, ModeRequest.KEEP, ModeRequest.KEEP),
            change(environment(false), CAPS, true, true, true));
        near(0, emergency.rollRate(), "emergency suppresses commanded rate");
        near(s.roll(), emergency.roll(), "emergency native attitude remains native responsibility");
    }

    private static void enginePhases() {
        for (String id : IDS) {
            FlightProfile p = profile(id);
            FlightEnvironment e = environment(p.kind() == FlightKind.HELICOPTER);
            FlightState s = initial(EnginePhase.OFF, 1);
            FlightOutput out = FlightControlModel.step(p, s, ignition(ModeRequest.ENABLE), e);
            equal(EnginePhase.STARTING, out.state().enginePhase(), id + " accepted start");
            near(0, out.state().phaseTicks(), id + " first start tick zero");
            near(e.idlePower(), out.nativePower(), id + " startup idle");
            near(0, out.state().effectivePower(), id + " startup no effective power");
            truth(!out.propulsionEnabled(), id + " startup propulsion gated");
            s = out.state();
            for (int elapsed = 1; elapsed < p.startupTicks(); elapsed++) {
                // Repeated ENABLE is idempotent and cannot restart elapsed startup.
                out = FlightControlModel.step(p, s, ignition(ModeRequest.ENABLE), e);
                s = out.state();
                equal(EnginePhase.STARTING, s.enginePhase(), id + " not prematurely running " + elapsed);
                near(elapsed, s.phaseTicks(), id + " elapsed startup ticks");
                near((float) elapsed / p.startupTicks(), s.spool(), id + " startup progress");
            }
            out = FlightControlModel.step(p, s, ignition(ModeRequest.ENABLE), e);
            equal(EnginePhase.RUNNING, out.state().enginePhase(), id + " exact startup duration");
            near(e.idlePower() + e.throttleIncrease(), out.nativePower(), id + " first running tick rise from idle");
            truth(out.propulsionEnabled(), id + " running enabled");
            out = FlightControlModel.step(p, out.state(), ignition(ModeRequest.DISABLE), e);
            equal(EnginePhase.STOPPING, out.state().enginePhase(), id + " observable stopping tick");
            near(0, out.nativePower(), id + " shutdown zeros native power");
            near(0, out.state().effectivePower(), id + " shutdown zeros propulsion");
            truth(!out.propulsionEnabled(), id + " shutdown disabled");
            out = FlightControlModel.step(p, out.state(), PilotCommand.neutral(0), e);
            equal(EnginePhase.OFF, out.state().enginePhase(), id + " no residual becomes off");
        }
        FlightProfile p = profile(IDS.get(0));
        FlightEnvironment noEnergy = change(environment(false), CAPS, true, false, false);
        FlightOutput failed = FlightControlModel.step(p, initial(EnginePhase.OFF, 1), ignition(ModeRequest.ENABLE), noEnergy);
        equal(ControlStatus.NO_ENERGY, failed.status(), "failed ignition reason");
        equal(EnginePhase.OFF, failed.state().enginePhase(), "failed ignition remains off");
        failed = FlightControlModel.step(p, failed.state(), PilotCommand.neutral(0), environment(false));
        equal(EnginePhase.OFF, failed.state().enginePhase(), "restored energy cannot retry consumed ignition");
        FlightOutput failure = FlightControlModel.step(p, running(.8f, .6f), PilotCommand.neutral(0), noEnergy);
        equal(ControlStatus.NO_ENERGY, failure.status(), "running energy failure");
        near(0, failure.nativePower(), "energy failure immediate");
        failure = FlightControlModel.step(p, running(.8f, .6f), PilotCommand.neutral(0), change(environment(false), CAPS, false, true, false));
        equal(ControlStatus.DAMAGED, failure.status(), "engine damage reason");
        near(0, failure.nativePower(), "damage failure immediate");
        FlightState starting = step(p, initial(EnginePhase.OFF, 1), ignition(ModeRequest.ENABLE), environment(false));
        for (int i = 1; i < p.startupTicks(); i++) starting = step(p, starting, PilotCommand.neutral(0), environment(false));
        equal(EnginePhase.STOPPING, step(p, starting, PilotCommand.neutral(0), noEnergy).enginePhase(), "completion checks energy again");

        FlightEnvironment residual = rotor(environment(true), .4f);
        FlightOutput stopping = FlightControlModel.step(profile("superbwarfare:ah_6"), running(1, .12f), ignition(ModeRequest.DISABLE), residual);
        for (int i = 0; i < 20; i++) stopping = FlightControlModel.step(profile("superbwarfare:ah_6"), stopping.state(), PilotCommand.neutral(0), residual);
        equal(EnginePhase.STOPPING, stopping.state().enginePhase(), "rotor residual keeps stopping");
        near(.4f, stopping.state().spool(), "rundown uses sampled native rotor");
        near(0, stopping.nativePower(), "residual rotor never restores requested power");
        stopping = FlightControlModel.step(profile("superbwarfare:ah_6"), stopping.state(), PilotCommand.neutral(0), rotor(environment(true), .0009f));
        equal(EnginePhase.OFF, stopping.state().enginePhase(), "residual threshold");

        for (EnginePhase phase : EnginePhase.values()) {
            FlightState restored = FlightControlModel.initial(17, 181, -181, .65f, phase, false, true);
            equal(phase == EnginePhase.RUNNING ? EnginePhase.RUNNING : EnginePhase.OFF, restored.enginePhase(), "initializer phase " + phase);
            near(.65f, restored.targetThrottle(), "initializer selected gas");
            near(0, restored.pitchRate(), "initializer no saved rates");
            near(0, restored.phaseTicks(), "initializer no saved progress");
            near(0, restored.throttleHoldTicks(), "initializer no held controls");
            near(-179, restored.yaw(), "initializer yaw wraps");
            truth(!restored.gearDown() && restored.hover(), "initializer preserves native modes");
        }
        FlightOutput idle = FlightControlModel.step(p, running(0, .02f), PilotCommand.neutral(0), environment(false));
        equal(EnginePhase.RUNNING, idle.state().enginePhase(), "zero target does not shut engine down");
        near(.02f, idle.nativePower(), "zero gas means idle");
    }

    private static void throttleAndBrakes() {
        FlightProfile p = profile(IDS.get(0));
        FlightEnvironment e = environment(false);
        FlightState s = step(p, initial(EnginePhase.OFF, 0), gas(1), e);
        near(.006f / .98f, s.targetThrottle(), "W native rate normalized");
        equal(EnginePhase.OFF, s.enginePhase(), "W never starts engine");
        near(0, s.effectivePower(), "off W no propulsion");
        s = step(p, s, gas(-1), e);
        near(0, s.targetThrottle(), "aircraft gas down exact native units");
        near(-1, s.throttleHoldTicks(), "direction change resets signed hold");
        s = step(p, s, PilotCommand.neutral(0), e);
        near(0, s.throttleHoldTicks(), "release resets hold");
        FlightOutput air = FlightControlModel.step(p, running(.8f, .8f), buttons(true, false, false), e);
        near(.8f, air.state().targetThrottle(), "air brake remembers gas");
        near(.25f, air.nativePower(), "air brake immediate power cap");
        truth(air.brake(), "native brake flag");
        FlightOutput restored = FlightControlModel.step(p, air.state(), PilotCommand.neutral(0), e);
        near(.256f, restored.nativePower(), "release gas rises smoothly");
        FlightOutput ground = FlightControlModel.step(p, running(.8f, .8f), buttons(true, false, false), ground(e));
        near(0, ground.nativePower(), "wheel brake immediate zero");
        near(.8f, ground.state().targetThrottle(), "wheel brake remembers gas");
        near(.006f, FlightControlModel.step(p, ground.state(), PilotCommand.neutral(0), ground(e)).nativePower(), "ground release native rise");
        near(.25f, FlightControlModel.step(p, running(.8f, .8f), buttons(false, true, false), e).nativePower(), "air secondary brake cap");
        near(0, FlightControlModel.step(p, running(.8f, .8f), buttons(false, true, false), ground(e)).nativePower(), "ground secondary brake cap");
        FlightState selected = running(.5f, .51f);
        FlightOutput boosted = FlightControlModel.step(p, selected, buttons(false, false, true), e);
        near(.516f, boosted.nativePower(), "boost power rises at native rate");
        near(.5f, boosted.state().targetThrottle(), "boost preserves selected target");
        FlightOutput normal = FlightControlModel.step(p, boosted.state(), PilotCommand.neutral(0), e);
        near(.51f, normal.nativePower(), "boost release normal target");
        near(.5f, normal.state().targetThrottle(), "boost release selection unchanged");
        FlightEnvironment unsupportedBoost = maximums(e, 1);
        near(.51f, FlightControlModel.step(p, selected, buttons(false, false, true), unsupportedBoost).nativePower(), "unsupported boost no multiplier");
        FlightOutput releasedBoost = FlightControlModel.step(p, running(1, 2), PilotCommand.neutral(0), e);
        near(1.994f, releasedBoost.nativePower(), "release from actual boosted power falls by native .006, not hard cut to one");
        near(1, releasedBoost.state().targetThrottle(), "release from boosted power retains full selected gas");
        truth(releasedBoost.nativePower() > 1, "boost residual remains above normal maximum while decaying");
        FlightState decaying = releasedBoost.state();
        for (int tick = 0; tick < 200; tick++) {
            float before = decaying.effectivePower();
            decaying = step(p, decaying, PilotCommand.neutral(0), e);
            near(Math.max(1, before - .006f), decaying.effectivePower(), "boost residual descends by at most native fall rate");
            near(1, decaying.targetThrottle(), "boost residual never erases selected gas");
        }
        near(1, decaying.effectivePower(), "boost residual converges to normal full power");
        near(1.994f, FlightControlModel.step(p, running(1, 2), buttons(false, false, true), unsupportedBoost).nativePower(),
            "native capability loss also falls smoothly");
    }

    private static void helicopterRampsAndFloors() {
        FlightProfile p = profile("superbwarfare:ah_6");
        FlightEnvironment e = environment(true);
        helicopterNativeRateOracles(p, e);
        FlightState s = initial(EnginePhase.OFF, 0);
        float accumulated = 0;
        for (int i = 1; i <= 12; i++) {
            s = step(p, s, gas(1), e);
            accumulated += .0007f * Math.min(i, 10);
            near(accumulated / .075f, s.targetThrottle(), "heli W ramp " + i);
            near(Math.min(i, 10), s.throttleHoldTicks(), "heli W signed hold cap");
        }
        s = step(p, s, gas(-1), ground(e));
        near((accumulated - .001f) / .075f, s.targetThrottle(), "heli direction reversal resets down ramp");
        near(-1, s.throttleHoldTicks(), "heli signed down start");
        s = running(1, .12f);
        for (int i = 1; i <= 300; i++) s = step(p, s, gas(-1), e);
        near((.058f - .045f) / .075f, s.targetThrottle(), "heli airborne S native floor");
        near(.058f, s.effectivePower(), "heli S actual native floor");
        near(-5, s.throttleHoldTicks(), "heli downward hold bounded five");
        FlightState belowFloor = running(0, .045f);
        belowFloor = step(p, belowFloor, gas(-1), e);
        near(0, belowFloor.targetThrottle(), "S does not raise loaded target below floor");
        FlightOutput horizontal = FlightControlModel.step(p, running(1, .12f), buttons(true, false, false), e);
        near(.12f, horizontal.nativePower(), "heli Space preserves collective");
        near(1, horizontal.state().targetThrottle(), "heli Space preserves target");
        near(.12f, FlightControlModel.step(p, running(1, .12f), buttons(true, false, false), ground(e)).nativePower(), "heli ground Space also horizontal only");
        s = running(1, .12f);
        for (int i = 0; i < 300; i++) s = step(p, s, buttons(false, true, false), e);
        near(1, s.targetThrottle(), "heli Shift never erases target");
        near(.035f, s.effectivePower(), "heli Shift native stronger downward floor");
        near(.0357f, step(p, s, PilotCommand.neutral(0), e).effectivePower(), "heli Shift release rises toward remembered gas");
        s = running(1, .12f);
        for (int i = 0; i < 300; i++) s = step(p, s, buttons(false, true, false), ground(e));
        near(0, s.effectivePower(), "heli ground Shift zero floor");
        near(1, s.targetThrottle(), "heli ground Shift remembers target");
        s = initial(EnginePhase.OFF, 1);
        for (int i = 0; i < 300; i++) s = step(p, s, gas(-1), ground(e));
        near(0, s.targetThrottle(), "heli ground S selection reaches zero");
        near(.045f, FlightControlModel.step(p, running(0, .045f), PilotCommand.neutral(0), e).nativePower(), "loaded idle target not forced to S floor");
    }

    private static void helicopterNativeRateOracles(FlightProfile p, FlightEnvironment e) {
        // Pinned native increment/decrement=1, expressed as absolute native units per tick.
        // These expected numbers are independent of the model's ramp-counter calculation.
        float[] upwardNativeDeltas = {.0007f, .0014f, .0021f, .0028f, .0035f, .0042f,
            .0049f, .0056f, .0063f, .007f, .007f};
        FlightState upward = running(0, .045f);
        for (int i = 0; i < upwardNativeDeltas.length; i++) {
            FlightState before = upward;
            upward = step(p, before, gas(1), e);
            near(upwardNativeDeltas[i], (upward.targetThrottle() - before.targetThrottle()) * .075f,
                "native W selection delta, tick " + (i + 1));
            near(upwardNativeDeltas[i], upward.effectivePower() - before.effectivePower(),
                "native W actual delta, tick " + (i + 1));
        }
        float[] downwardNativeDeltas = {.001f, .002f, .003f, .004f, .005f, .005f};
        FlightState downward = running(1, .12f), secondary = downward;
        for (int i = 0; i < downwardNativeDeltas.length; i++) {
            FlightState before = downward, beforeSecondary = secondary;
            downward = step(p, before, gas(-1), e);
            secondary = step(p, beforeSecondary, buttons(false, true, false), e);
            near(downwardNativeDeltas[i], (before.targetThrottle() - downward.targetThrottle()) * .075f,
                "native S selection delta, tick " + (i + 1));
            near(downwardNativeDeltas[i], before.effectivePower() - downward.effectivePower(),
                "native S actual delta, tick " + (i + 1));
            near(downwardNativeDeltas[i], beforeSecondary.effectivePower() - secondary.effectivePower(),
                "native Shift actual delta, tick " + (i + 1));
            near(1, secondary.targetThrottle(), "native Shift retains selection, tick " + (i + 1));
        }
        FlightState released = step(p, secondary, PilotCommand.neutral(0), e);
        near(.0007f, released.effectivePower() - secondary.effectivePower(), "released Shift restores at unheld base native rise");
        near(0, released.throttleHoldTicks(), "released native rate resets signed counter");
        FlightState reverse = step(p, upward, gas(-1), e);
        near(.001f, (upward.targetThrottle() - reverse.targetThrottle()) * .075f, "native W-to-S direction reset uses first down delta");
        near(-1, reverse.throttleHoldTicks(), "native W-to-S first signed count");
    }

    private static void modesAndIsolation() {
        FlightProfile p = profile("superbwarfare:ah_6");
        FlightState s = step(p, initial(EnginePhase.OFF, .4f),
            command(0, 0, 0, 0, false, false, false, ModeRequest.KEEP, ModeRequest.DISABLE, ModeRequest.ENABLE), environment(true));
        truth(s.hover() && !s.gearDown(), "idempotent requested modes");
        s = step(p, s, command(0, 1, 0, 0, false, false, false, ModeRequest.KEEP, ModeRequest.DISABLE, ModeRequest.ENABLE), environment(true));
        truth(s.hover(), "yaw alone retains hover");
        s = step(p, s, command(.01f, 0, 0, 0, false, false, false, ModeRequest.KEEP, ModeRequest.KEEP, ModeRequest.ENABLE), environment(true));
        truth(!s.hover(), "deliberate pitch cancels even same-tick hover request");
        s = step(p, s, command(0, 0, .01f, 0, false, false, false, ModeRequest.KEEP, ModeRequest.KEEP, ModeRequest.ENABLE), environment(true));
        truth(!s.hover(), "roll cancels hover");
        FlightOutput unsupported = FlightControlModel.step(profile(IDS.get(0)), initial(EnginePhase.OFF, 0),
            command(0, 0, 0, 0, false, false, false, ModeRequest.KEEP, ModeRequest.KEEP, ModeRequest.ENABLE), environment(false));
        equal(ControlStatus.UNSUPPORTED_MODE, unsupported.status(), "aircraft hover unsupported");
        truth(!unsupported.state().hover(), "unsupported hover unchanged");
        FlightState a = initial(EnginePhase.OFF, .2f), b = initial(EnginePhase.OFF, .7f);
        FlightState originalB = b;
        for (int i = 0; i < 20; i++) a = step(p, a, gas(1), environment(true));
        equal(originalB, b, "one vehicle input never mutates another");
        truth(a.targetThrottle() > .2f, "first vehicle consumes its own gas input");
        FlightOutput once = FlightControlModel.step(p, b, gas(-1), environment(true));
        equal(once, FlightControlModel.step(p, b, gas(-1), environment(true)), "same inputs deterministic");
    }

    private static void invalidInputs() {
        rejects(() -> FlightControlModel.wrapDegrees(Float.NaN), "NaN wrap");
        rejects(() -> FlightControlModel.wrapDegrees(Float.POSITIVE_INFINITY), "infinite wrap");
        rejects(() -> FlightControlModel.initial(Float.NaN, 0, 0, 0, EnginePhase.OFF, false, false), "NaN attitude");
        rejects(() -> initial(EnginePhase.OFF, 1.1f), "target outside range");
        rejects(() -> gas(Float.NaN), "NaN command");
        rejects(() -> gas(Float.POSITIVE_INFINITY), "infinite command");
        rejects(() -> gas(1.001f), "out of range axis");
        rejects(() -> new FlightEnvironment.AxisCaps(-1, 0, 0, 0, 0, 0), "negative caps");
        rejects(() -> new FlightEnvironment.AxisCaps(0, Float.NaN, 0, 0, 0, 0), "nonfinite caps");
        rejects(() -> new FlightState(0, 0, 0, 0, 0, 0, 0, 0, EnginePhase.OFF, 0, 0, false, false, -11), "hold below bound");
        rejects(() -> new FlightState(0, 0, 0, 0, 0, 0, 0, 0, EnginePhase.OFF, 0, 0, false, false, 11), "hold above bound");
        near(-10, new FlightState(0, 0, 0, 0, 0, 0, 0, 0, EnginePhase.OFF, 0, 0, false, false, -10).throttleHoldTicks(), "signed hold accepted");
        rejects(() -> FlightControlModel.step(new FlightProfile("bad", FlightKind.AIRCRAFT, 0, 1, 40), initial(EnginePhase.OFF, 0), PilotCommand.neutral(0), environment(false)), "model rejects zero response");
        FlightState untouched = initial(EnginePhase.OFF, .3f);
        rejects(() -> gas(-2), "rejected malformed command");
        near(.3f, untouched.targetThrottle(), "invalid DTO cannot mutate last valid state");
        FlightState negativeZero = step(profile(IDS.get(0)), untouched, gas(-0.0f), environment(false));
        near(0, negativeZero.throttleHoldTicks(), "negative zero is released input");
        float[] nativeValues = {1, .02f, 1, 3, .006f, .006f, .03f, 0, 0, .25f};
        for (int index = 0; index < nativeValues.length; index++) {
            float[] invalid = nativeValues.clone();
            invalid[index] = -1;
            rejects(() -> nativeEnvironment(invalid, 1, 1), "negative native field " + index);
        }
        float[] noRange = nativeValues.clone();
        noRange[2] = .02f;
        rejects(() -> nativeEnvironment(noRange, 1, 1), "normal maximum must exceed idle");
        float[] smallerBoost = nativeValues.clone();
        smallerBoost[3] = .9f;
        rejects(() -> nativeEnvironment(smallerBoost, 1, 1), "boost maximum cannot lower normal maximum");
        rejects(() -> nativeEnvironment(nativeValues, 0, 1), "zero up ramp");
        rejects(() -> nativeEnvironment(nativeValues, 1, 11), "down ramp exceeds signed state bounds");
        PilotCommand transientInput = command(1, -1, .5f, 1, true, true, true, ModeRequest.ENABLE, ModeRequest.DISABLE, ModeRequest.ENABLE);
        equal(PilotCommand.neutral(0), transientInput.withoutTransientInput(), "timeout helper clears axes buttons and pending modes");
    }

    private static FlightProfile profile(String id) { return FlightProfiles.find(id).orElseThrow(); }
    private static FlightState initial(EnginePhase phase, float target) { return FlightControlModel.initial(0, 0, 0, target, phase, true, false); }
    private static FlightState running(float target, float effective) {
        return new FlightState(0, 0, 0, 0, 0, 0, target, effective, EnginePhase.RUNNING, 40, 1, true, false, 0);
    }
    private static FlightState withRates(FlightState s, float p, float y, float r) {
        return new FlightState(s.pitch(), s.yaw(), s.roll(), p, y, r, s.targetThrottle(), s.effectivePower(), s.enginePhase(), s.phaseTicks(), s.spool(), s.gearDown(), s.hover(), s.throttleHoldTicks());
    }
    private static FlightState step(FlightProfile p, FlightState s, PilotCommand c, FlightEnvironment e) { return FlightControlModel.step(p, s, c, e).state(); }
    private static PilotCommand gas(float axis) { return command(0, 0, 0, axis, false, false, false, ModeRequest.KEEP, ModeRequest.KEEP, ModeRequest.KEEP); }
    private static PilotCommand ignition(ModeRequest mode) { return command(0, 0, 0, 0, false, false, false, mode, ModeRequest.KEEP, ModeRequest.KEEP); }
    private static PilotCommand buttons(boolean brake, boolean secondary, boolean boost) { return command(0, 0, 0, 0, brake, secondary, boost, ModeRequest.KEEP, ModeRequest.KEEP, ModeRequest.KEEP); }
    private static PilotCommand command(float p, float y, float r, float t, boolean brake, boolean secondary, boolean boost, ModeRequest ignition, ModeRequest gear, ModeRequest hover) {
        return new PilotCommand(0, p, y, r, t, brake, secondary, boost, ignition, gear, hover);
    }
    private static FlightEnvironment environment(boolean heli) {
        return new FlightEnvironment(1, 1, false, true, true, true, heli, false, CAPS,
            heli ? .045f : .02f, heli ? .12f : 1, heli ? .12f : 3,
            heli ? .0007f : .006f, heli ? .001f : .006f, heli ? .001f : .03f,
            heli ? 10 : 1, heli ? 5 : 1, heli ? .058f : 0, heli ? .035f : 0, heli ? .12f : .25f, 0);
    }
    private static FlightEnvironment nativeEnvironment(float[] v, int up, int down) {
        return new FlightEnvironment(0, v[0], false, true, true, true, true, false, CAPS,
            v[1], v[2], v[3], v[4], v[5], v[6], up, down, v[7], v[8], v[9], 0);
    }
    private static FlightEnvironment change(FlightEnvironment e, FlightEnvironment.AxisCaps caps, boolean healthy, boolean energy, boolean emergency) {
        return new FlightEnvironment(e.tick(), e.speed(), e.onGround(), healthy, energy, e.gearAvailable(), e.hoverAvailable(), emergency,
            caps, e.idlePower(), e.maximumPower(), e.boostMaximumPower(), e.throttleIncrease(), e.throttleDecrease(), e.secondaryDecrease(),
            e.throttleRampUpTicks(), e.throttleRampDownTicks(), e.airborneMinimumPower(), e.secondaryMinimumPower(), e.airBrakePowerCap(), e.rotorSpool());
    }
    private static FlightEnvironment ground(FlightEnvironment e) {
        return new FlightEnvironment(e.tick(), e.speed(), true, e.engineHealthy(), e.energyAvailable(), e.gearAvailable(), e.hoverAvailable(), e.nativeEmergency(),
            e.caps(), e.idlePower(), e.maximumPower(), e.boostMaximumPower(), e.throttleIncrease(), e.throttleDecrease(), e.secondaryDecrease(),
            e.throttleRampUpTicks(), e.throttleRampDownTicks(), e.airborneMinimumPower(), e.secondaryMinimumPower(), e.airBrakePowerCap(), e.rotorSpool());
    }
    private static FlightEnvironment rotor(FlightEnvironment e, float rotor) {
        return new FlightEnvironment(e.tick(), e.speed(), e.onGround(), e.engineHealthy(), e.energyAvailable(), e.gearAvailable(), e.hoverAvailable(), e.nativeEmergency(),
            e.caps(), e.idlePower(), e.maximumPower(), e.boostMaximumPower(), e.throttleIncrease(), e.throttleDecrease(), e.secondaryDecrease(),
            e.throttleRampUpTicks(), e.throttleRampDownTicks(), e.airborneMinimumPower(), e.secondaryMinimumPower(), e.airBrakePowerCap(), rotor);
    }
    private static FlightEnvironment maximums(FlightEnvironment e, float boost) {
        return new FlightEnvironment(e.tick(), e.speed(), e.onGround(), e.engineHealthy(), e.energyAvailable(), e.gearAvailable(), e.hoverAvailable(), e.nativeEmergency(),
            e.caps(), e.idlePower(), e.maximumPower(), boost, e.throttleIncrease(), e.throttleDecrease(), e.secondaryDecrease(),
            e.throttleRampUpTicks(), e.throttleRampDownTicks(), e.airborneMinimumPower(), e.secondaryMinimumPower(), e.airBrakePowerCap(), e.rotorSpool());
    }
    private static void near(float expected, float actual, String message) {
        checks++;
        if (!Float.isFinite(actual) || Math.abs(expected - actual) > .00002f)
            throw new AssertionError(message + ": expected " + expected + ", actual " + actual);
    }
    private static void truth(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual, String message) { checks++; if (!expected.equals(actual)) throw new AssertionError(message + ": expected " + expected + ", actual " + actual); }
    private static void rejects(Runnable operation, String message) {
        checks++;
        try { operation.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(message + ": expected IllegalArgumentException");
    }
}
