package dev.frontier.aviation;

import dev.frontier.aviation.control.EnginePhase;
import dev.frontier.aviation.control.nativebridge.NativeFlightPersistenceData;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.Arrays;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

/** Migration vectors use actual CompoundTag/NBT serialization, without entities or a game. */
public final class NativeFlightPersistenceContractTest {
    private static final String KEY = NativeFlightPersistenceData.KEY;
    public static void main(String[] args) throws Exception {
        legacyRange(.02f, 1);
        legacyRange(.045f, .12f);
        su24SelectionDoesNotIgnite();
        corruptVersionOneFallsBackPerField();
        futureVersionRoundTrip();
        explicitStatesAndUsability();
        repeatedSaveMatchesCanonicalWriter();
        System.out.println("Native flight persistence contracts passed");
    }
    private static void legacyRange(float idle, float max) {
        float[] powers = {0, idle, idle + (max - idle) / 2, max, max * 2};
        float[] targets = {0, 0, .5f, 1, 1};
        for (int i = 0; i < powers.length; i++) {
            var nativeTag = nativePower(powers[i]); var persistent = new CompoundTag();
            var selected = NativeFlightPersistenceData.load(persistent, nativeTag, idle, max, powers[i], true);
            near(targets[i], selected.targetThrottle(), "legacy idle-to-max normalization " + powers[i]);
            phase(powers[i] > .0004f ? EnginePhase.RUNNING : EnginePhase.OFF, selected.ignition(), "legacy native Power ignition");
            near(powers[i], nativeTag.getFloat("Power"), "old native Power not rewritten");
            near(targets[i], persistent.getCompound(KEY).getFloat("TargetThrottle"), "migrated target stored");
        }
    }
    private static void su24SelectionDoesNotIgnite() {
        CompoundTag nativeTag = nativePower(0); nativeTag.putDouble("Su24Throttle", .51);
        var selected = NativeFlightPersistenceData.load(new CompoundTag(), nativeTag, .02f, 1, 0, true);
        near(.5f, selected.targetThrottle(), "Su24 idle-to-max target");
        phase(EnginePhase.OFF, selected.ignition(), "positive Su24Throttle alone cannot ignite");
        nativeTag.putDouble("Su24Throttle", Double.NaN);
        selected = NativeFlightPersistenceData.read(new CompoundTag(), nativeTag, .02f, 1, .51f, true);
        near(.5f, selected.targetThrottle(), "nonfinite Su24Throttle falls back to native Power");
        nativeTag.putDouble("Su24Throttle", -1);
        selected = NativeFlightPersistenceData.read(new CompoundTag(), nativeTag, .02f, 1, .51f, true);
        near(.5f, selected.targetThrottle(), "negative native sentinel falls back to Power");
    }
    private static void corruptVersionOneFallsBackPerField() {
        CompoundTag persistent = versionOne("invalid-phase", Float.NaN);
        var selected = NativeFlightPersistenceData.load(persistent, nativePower(.51f), .02f, 1, .51f, true);
        near(.5f, selected.targetThrottle(), "corrupt v1 target uses legacy native selection");
        phase(EnginePhase.RUNNING, selected.ignition(), "corrupt v1 ignition uses healthy native positive Power");
        persistent = versionOne("OFF", 2);
        selected = NativeFlightPersistenceData.load(persistent, nativePower(.51f), .02f, 1, .51f, true);
        near(.5f, selected.targetThrottle(), "out-of-range saved target falls back");
        phase(EnginePhase.OFF, selected.ignition(), "valid saved OFF survives corrupt target");
        persistent = versionOne("RUNNING", .75f);
        persistent.getCompound(KEY).remove("TargetThrottle");
        selected = NativeFlightPersistenceData.load(persistent, nativePower(.51f), .02f, 1, .51f, true);
        near(.5f, selected.targetThrottle(), "missing target falls back instead of assuming zero");
        persistent = versionOne("RUNNING", .75f);
        persistent.getCompound(KEY).putString("TargetThrottle", "not-a-number");
        selected = NativeFlightPersistenceData.load(persistent, nativePower(.51f), .02f, 1, .51f, true);
        near(.5f, selected.targetThrottle(), "wrong-typed target falls back");
    }
    private static void futureVersionRoundTrip() throws Exception {
        CompoundTag future = new CompoundTag(); future.putInt("Version", 2);
        future.putString("Ignition", "FUTURE_STATE"); future.putDouble("TargetThrottle", .123456789012345);
        future.putByteArray("FuturePayload", new byte[]{0, -1, 12, 42});
        CompoundTag nested = new CompoundTag(); nested.putLong("Unknown", Long.MAX_VALUE); future.put("Extra", nested);
        CompoundTag persistent = new CompoundTag(); persistent.put(KEY, future); persistent.putString("OtherMod", "untouched");
        byte[] before = bytes(persistent);
        NativeFlightPersistenceData.load(persistent, nativePower(.51f), .02f, 1, .51f, true);
        check(Arrays.equals(before, bytes(persistent)), "load must preserve all unknown v2 NBT bytes");
        NativeFlightPersistenceData.save(persistent, .9f, EnginePhase.RUNNING);
        check(Arrays.equals(before, bytes(persistent)), "subsequent save must preserve unknown v2 NBT bytes");
        check(persistent.getCompound(KEY) == future, "unknown future compound must not be replaced");
    }
    private static void explicitStatesAndUsability() {
        CompoundTag persistent = versionOne("RUNNING", 0);
        var selected = NativeFlightPersistenceData.load(persistent, nativePower(.02f), .02f, 1, .02f, true);
        phase(EnginePhase.RUNNING, selected.ignition(), "RUNNING at native idle stays running");
        near(0, selected.targetThrottle(), "saved normalized zero is native idle");
        NativeFlightPersistenceData.save(persistent, selected.targetThrottle(), selected.ignition());
        phase(EnginePhase.RUNNING, NativeFlightPersistenceData.read(persistent.getCompound(KEY), nativePower(.02f), .02f, 1, .02f, true).ignition(), "RUNNING idle save/read round trip");
        for (String transientPhase : new String[]{"STARTING", "STOPPING"}) {
            persistent = versionOne(transientPhase, .75f);
            selected = NativeFlightPersistenceData.load(persistent, nativePower(.51f), .02f, 1, .51f, true);
            phase(EnginePhase.OFF, selected.ignition(), transientPhase + " cannot resume on load");
            near(.75f, selected.targetThrottle(), transientPhase + " retains selected gas");
        }
        selected = NativeFlightPersistenceData.load(versionOne("RUNNING", .4f), nativePower(.51f), .02f, 1, .51f, false);
        phase(EnginePhase.OFF, selected.ignition(), "unusable native engine cannot restore RUNNING");
        selected = NativeFlightPersistenceData.load(new CompoundTag(), nativePower(.51f), .02f, 1, .51f, false);
        phase(EnginePhase.OFF, selected.ignition(), "legacy engine requires health and energy");
    }
    private static CompoundTag nativePower(float value) { CompoundTag tag = new CompoundTag(); tag.putFloat("Power", value); return tag; }
    private static void repeatedSaveMatchesCanonicalWriter() throws Exception {
        CompoundTag actual = new CompoundTag(), expected = new CompoundTag();
        java.util.Random random = new java.util.Random(20261027L);
        for (int tick = 0; tick < 10_000; tick++) {
            float target = switch (tick % 19) {
                case 0 -> -0f; case 1 -> Float.NaN; case 2 -> Float.POSITIVE_INFINITY;
                default -> (tick % 5 == 0) ? random.nextFloat() : .75f;
            };
            EnginePhase phase = EnginePhase.values()[(tick / 100) % EnginePhase.values().length];
            if (tick % 73 == 0) {
                actual.getCompound(KEY).putString("ExtraV1", "discarded-by-canonical-writer");
                expected.getCompound(KEY).putString("ExtraV1", "discarded-by-canonical-writer");
            }
            if (tick % 97 == 0) {
                actual.getCompound(KEY).putDouble("TargetThrottle", target);
                expected.getCompound(KEY).putDouble("TargetThrottle", target);
            }
            NativeFlightPersistenceData.save(actual, target, phase);
            CompoundTag canonical = new CompoundTag();
            canonical.putInt("Version", 1); canonical.putString("Ignition", phase.name());
            canonical.putFloat("TargetThrottle", target); expected.put(KEY, canonical);
            check(Arrays.equals(bytes(expected), bytes(actual)), "unchanged save must match canonical NBT at " + tick);
        }
    }
    private static CompoundTag versionOne(String phase, float target) {
        CompoundTag persistent = new CompoundTag(); CompoundTag data = new CompoundTag();
        data.putInt("Version", 1); data.putString("Ignition", phase); data.putFloat("TargetThrottle", target); persistent.put(KEY, data); return persistent;
    }
    private static byte[] bytes(CompoundTag tag) throws Exception {
        var buffer = new ByteArrayOutputStream(); try (var output = new DataOutputStream(buffer)) { NbtIo.write(tag, output); } return buffer.toByteArray();
    }
    private static void near(float expected, float actual, String label) { check(Math.abs(expected - actual) < .00001f, label + ": " + actual); }
    private static void phase(EnginePhase expected, EnginePhase actual, String label) { check(expected == actual, label + ": " + actual); }
    private static void check(boolean success, String label) { if (!success) throw new AssertionError(label); }
}
