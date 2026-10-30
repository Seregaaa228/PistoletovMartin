package dev.frontier.aviation;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo;
import dev.frontier.aviation.control.*;
import dev.frontier.aviation.control.nativebridge.NativeFlightAdapter;
import java.io.InputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

/** Executes native EngineInfo capability sampling and the model; checks the actual entity sample bytecode. */
public final class NativeFlightBoostContractTest {
    public static void main(String[] args) throws Exception {
        sampleDoesNotDependOnMaskedInput();
        EngineInfo.Aircraft aircraft = engine(EngineInfo.Aircraft.class);
        var air = NativeFlightAdapter.throttleCapabilities(aircraft, false);
        var ground = NativeFlightAdapter.throttleCapabilities(aircraft, true);
        near(.006f * aircraft.getIncrement(), air.normalIncrease(), "native aircraft normal rise");
        near(air.normalIncrease() * 1.6f, air.boostIncrease(), "native aircraft boosted rise");
        near(3, air.boostMaximumPower(), "airborne capability survives masked sprint=false");
        near(air.boostIncrease(), ground.normalIncrease(), "ground has native1.6 factor already");
        near(ground.normalIncrease(), ground.boostIncrease(), "ground Ctrl cannot double1.6");
        currentCommandControlsFirstTickAndTransitions(aircraft);
        subtypeDispatch();
        unchangedOtherEngines(engine(EngineInfo.Tom6.class), 2.2f, .02f);
        unchangedOtherEngines(engine(EngineInfo.Helicopter.class), .12f, .0007f);
        rejectsInvalidBoostRate();
        System.out.println("Native flight boost capability contracts passed");
    }
    /** Native constructors register default sounds. Seed only this test's native increment without
     * bootstrapping a registry, world, client, server, or game; the real native getters still run. */
    private static <T extends EngineInfo> T engine(Class<T> type) throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        var singleton = unsafeType.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
        T engine = type.cast(unsafeType.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), type));
        engine.setIncrement(2); engine.setDecrement(1); engine.setEnergyCostRate(10);
        if (engine instanceof EngineInfo.Helicopter helicopter) {
            helicopter.setPitchSpeed(1); helicopter.setYawSpeed(1); helicopter.setRollSpeed(1);
            helicopter.setLiftSpeed(1); helicopter.setClampPitch(89); helicopter.setClampRoll(90);
        }
        return engine;
    }
    private static void currentCommandControlsFirstTickAndTransitions(EngineInfo.Aircraft aircraft) {
        var caps = NativeFlightAdapter.throttleCapabilities(aircraft, false);
        FlightProfile profile = FlightProfiles.find("frontier_aviation:su25").orElseThrow();
        FlightEnvironment env = sampled(aircraft, false, 100);
        FlightState state = new FlightState(0, 0, 0, 0, 0, 0, .5f, .5f, EnginePhase.RUNNING, 0, 1, false, false, 0);
        PilotCommand firstCtrl = command(0, true, 1);
        FlightOutput first = FlightControlModel.step(profile, state, firstCtrl, env);
        near(.5f + caps.boostIncrease(), first.nativePower(), "first Ctrl tick uses boosted actual rise");
        near(.5f + caps.boostIncrease() / .98f, first.state().targetThrottle(), "first Ctrl W tick uses boosted target rise");
        // A weapon/decoy bitmap edge masks native sprint again. The real sample has no flag dependency,
        // and the same native capability must continue to use this tick's accepted Ctrl command.
        FlightOutput edge = FlightControlModel.step(profile, first.state(), command(1, true, 0), sampled(aircraft, false, 100));
        near(first.nativePower() + caps.boostIncrease(), edge.nativePower(), "weapon/key edge preserves boosted rise");
        FlightOutput ordinary = FlightControlModel.step(profile, state, command(2, false, 1), env);
        near(.5f + caps.normalIncrease(), ordinary.nativePower(), "current unboosted command uses normal rate");
        near(.5f + caps.normalIncrease() / .98f, ordinary.state().targetThrottle(), "normal W rise remains unchanged");
        FlightState aboveNormal = new FlightState(0, 0, 0, 0, 0, 0, 1, 1.5f, EnginePhase.RUNNING, 0, 1, false, false, 0);
        FlightOutput released = FlightControlModel.step(profile, aboveNormal, command(3, false, 0), env);
        near(1.5f - env.throttleDecrease(), released.nativePower(), "boost release retains native base decrement");
        FlightOutput grounded = FlightControlModel.step(profile, state, command(4, true, 1), sampled(aircraft, true, 100));
        near(.5f + caps.boostIncrease(), grounded.nativePower(), "grounded current Ctrl uses one1.6 factor");
    }
    private static void unchangedOtherEngines(EngineInfo info, float maximum, float coefficient) {
        var caps = NativeFlightAdapter.throttleCapabilities(info, false);
        float increment = info instanceof EngineInfo.Tom6 tom ? tom.getIncrement() : ((EngineInfo.Helicopter) info).getIncrement();
        near(coefficient * increment, caps.normalIncrease(), "other native engine base coefficient");
        near(caps.normalIncrease(), caps.boostIncrease(), "no invented aircraft factor for Tom6/heli");
        near(maximum, caps.boostMaximumPower(), "native engine boosted maximum");
    }
    private static FlightEnvironment sampled(EngineInfo info, boolean onGround, int energy) {
        return NativeFlightAdapter.sampleNative(info, new NativeFlightAdapter.NativeReadings(
            0, 1, 1, onGround, !(info instanceof EngineInfo.Aircraft), false,
            0, 0, .12f, false, .5f, false, 100, energy));
    }
    private static void subtypeDispatch() throws Exception {
        EngineInfo.Tom6 tom = engine(EngineInfo.Tom6.class);
        EngineInfo.Aircraft aircraft = engine(EngineInfo.Aircraft.class);
        EngineInfo.Helicopter helicopter = engine(EngineInfo.Helicopter.class);
        near(.04f, sampled(tom, false, 100).throttleIncrease(), "actual sample Tom branch uses .02*increment");
        near(2.2f, sampled(tom, false, 100).boostMaximumPower(), "actual sample Tom branch uses native2.2 cap");
        near(4f, sampled(tom, false, 100).caps().pitchPositive(), "Tom reaches its own4 pitch cap");
        near(16f / 3f, sampled(tom, false, 100).caps().rollPositive(), "Tom uses its own roll envelope");
        if (sampled(tom, false, 7).energyAvailable()) throw new AssertionError("Tom must account for two native5-unit energy sites");
        if (!sampled(aircraft, false, 7).energyAvailable()) throw new AssertionError("aircraft has one native5-unit energy site");
        near(.12f, sampled(helicopter, false, 100).maximumPower(), "actual heli path remains heli");
        near(120, NativeFlightAdapter.permittedPitch(tom, false, 130), "Tom airborne120 pitch bound");
        near(-12, NativeFlightAdapter.permittedPitch(tom, true, -30), "Tom ground negative pitch bound");
        near(3, NativeFlightAdapter.permittedPitch(tom, true, 20), "Tom ground positive pitch bound");
        near(150, NativeFlightAdapter.permittedRoll(tom, 150), "Tom does not inherit aircraft roll clamp");
        near(89, NativeFlightAdapter.permittedPitch(aircraft, false, 130), "ordinary native aircraft pitch clamp remains");
        near(90, NativeFlightAdapter.permittedRoll(aircraft, 150), "ordinary native aircraft roll clamp remains");
        if (NativeFlightAdapter.startSound(tom) != null) throw new AssertionError("Tom has no native startup sound site");
    }
    private static FlightEnvironment environment(NativeFlightAdapter.ThrottleCapabilities caps) {
        return new FlightEnvironment(0, 1, false, true, true, true, false, false,
            new FlightEnvironment.AxisCaps(1, 1, 1, 1, 1, 1), .02f, 1, caps.boostMaximumPower(),
            caps.normalIncrease(), .006f, .03f, 1, 1, .025f, .025f, .25f, 0, caps.boostIncrease());
    }
    private static PilotCommand command(long sequence, boolean boost, float throttle) {
        return new PilotCommand(sequence, 0, 0, 0, throttle, false, false, boost, ModeRequest.KEEP, ModeRequest.KEEP, ModeRequest.KEEP);
    }
    private static void rejectsInvalidBoostRate() {
        try { environment(new NativeFlightAdapter.ThrottleCapabilities(.006f, Float.NaN, 3)); throw new AssertionError("NaN boost rate accepted"); }
        catch (IllegalArgumentException expected) { }
        try { environment(new NativeFlightAdapter.ThrottleCapabilities(.006f, -.1f, 3)); throw new AssertionError("negative boost rate accepted"); }
        catch (IllegalArgumentException expected) { }
    }
    private static void sampleDoesNotDependOnMaskedInput() throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = NativeFlightBoostContractTest.class.getClassLoader().getResourceAsStream(
                "dev/frontier/aviation/control/nativebridge/NativeFlightAdapter.class")) {
            if (in == null) throw new AssertionError("compiled native adapter unavailable");
            new ClassReader(in).accept(node, 0);
        }
        MethodNode sample = node.methods.stream().filter(m -> m.name.equals("sample")).findFirst().orElseThrow();
        int delegation = 0;
        for (var instruction : sample.instructions) if (instruction instanceof MethodInsnNode call) {
            if (call.name.equals("sprintInputDown")) throw new AssertionError("actual sample still reads stale/masked native sprint flag");
            if (call.name.equals("sampleNative")) delegation++;
        }
        if (delegation != 1) throw new AssertionError("entity sample must delegate once to the exercised native policy");
        MethodNode soundPolicy = node.methods.stream().filter(m -> m.name.equals("startSound")).findFirst().orElseThrow();
        var executable = new java.util.ArrayList<AbstractInsnNode>();
        for (var instruction : soundPolicy.instructions) if (instruction.getOpcode() >= 0) executable.add(instruction);
        if (!(executable.get(1) instanceof TypeInsnNode type) || !type.desc.endsWith("EngineInfo$Tom6")
                || executable.get(3).getOpcode() != org.objectweb.asm.Opcodes.ACONST_NULL
                || executable.get(4).getOpcode() != org.objectweb.asm.Opcodes.ARETURN)
            throw new AssertionError("Tom start sound must return before inherited aircraft/heli sound access");
        MethodNode policy = node.methods.stream().filter(m -> m.name.equals("sampleNative")).findFirst().orElseThrow();
        int capability = 0, normal = 0, boosted = 0, maximum = 0, environments = 0;
        for (var instruction : policy.instructions) if (instruction instanceof MethodInsnNode call) {
            if (call.name.equals("sprintInputDown")) throw new AssertionError("sample policy reads masked input");
            if (call.name.equals("throttleCapabilities")) capability++;
            if (call.name.equals("normalIncrease")) normal++;
            if (call.name.equals("boostIncrease")) boosted++;
            if (call.name.equals("boostMaximumPower")) maximum++;
            if (call.owner.equals("dev/frontier/aviation/control/FlightEnvironment") && call.name.equals("<init>")) environments++;
        }
        if (capability != 1 || normal != 1 || boosted != 1 || maximum != 1 || environments != 1)
            throw new AssertionError("actual sample must build its environment from the exercised native capability path");
    }
    private static void near(float expected, float actual, String label) {
        if (!Float.isFinite(expected) || !Float.isFinite(actual) || Math.abs(expected - actual) > .00001f) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
    }
}
