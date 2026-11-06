package dev.frontier.aviation.control.nativebridge;

import dev.frontier.aviation.control.EnginePhase;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/** NBT migration policy, isolated from entity/world initialization for contract checks. */
public final class NativeFlightPersistenceData {
    public static final String KEY = "frontier_aviation:flight_controls";
    private static final float POWER_EPSILON = .0004f;
    public record Selection(float targetThrottle, EnginePhase ignition) {}
    private NativeFlightPersistenceData() {}

    public static boolean unknownVersion(CompoundTag data) {
        return data.contains("Version", Tag.TAG_INT) && data.getInt("Version") != 1;
    }
    public static Selection read(CompoundTag data, CompoundTag nativeTag, float idle, float maximum,
                                 float nativePower, boolean engineUsable) {
        float legacy = legacyTarget(nativeTag, nativePower, idle, maximum);
        EnginePhase legacyIgnition = Float.isFinite(nativePower) && nativePower > POWER_EPSILON && engineUsable
            ? EnginePhase.RUNNING : EnginePhase.OFF;
        if (!data.contains("Version", Tag.TAG_INT) || data.getInt("Version") != 1)
            return new Selection(legacy, legacyIgnition);
        float target = data.contains("TargetThrottle", Tag.TAG_ANY_NUMERIC) ? data.getFloat("TargetThrottle") : Float.NaN;
        if (!Float.isFinite(target) || target < 0 || target > 1) target = legacy;
        EnginePhase phase = legacyIgnition;
        if (data.contains("Ignition", Tag.TAG_STRING)) {
            phase = switch (data.getString("Ignition")) {
                case "RUNNING" -> engineUsable ? EnginePhase.RUNNING : EnginePhase.OFF;
                case "OFF", "STARTING", "STOPPING" -> EnginePhase.OFF;
                default -> legacyIgnition;
            };
        }
        return new Selection(target, phase);
    }
    public static Selection load(CompoundTag persistent, CompoundTag nativeTag, float idle, float maximum,
                                 float nativePower, boolean engineUsable) {
        CompoundTag data = persistent.getCompound(KEY);
        Selection result = read(data, nativeTag, idle, maximum, nativePower, engineUsable);
        if (!unknownVersion(data)) save(persistent, result.targetThrottle(), result.ignition());
        return result;
    }
    /** An older mod never rewrites a version it cannot interpret, including on later ticks. */
    public static void save(CompoundTag persistent, float targetThrottle, EnginePhase ignition) {
        if (persistent.get(KEY) instanceof CompoundTag previous) {
            Tag version = previous.get("Version");
            if (version instanceof IntTag value && value.getAsInt() != 1) return;
            // Only update the canonical three-field v1 compound in place.
            // Unknown versions stay intact; malformed/extended v1 is rewritten.
            if (previous.size() == 3 && version instanceof IntTag value && value.getAsInt() == 1
                    && previous.get("Ignition") instanceof StringTag phase
                    && previous.get("TargetThrottle") instanceof FloatTag throttle) {
                if (!phase.getAsString().equals(ignition.name())) previous.putString("Ignition", ignition.name());
                if (Float.floatToIntBits(throttle.getAsFloat()) != Float.floatToIntBits(targetThrottle))
                    previous.putFloat("TargetThrottle", targetThrottle);
                return;
            }
        }
        CompoundTag data = new CompoundTag();
        data.putInt("Version", 1);
        data.putString("Ignition", ignition.name());
        data.putFloat("TargetThrottle", targetThrottle);
        persistent.put(KEY, data);
    }
    private static float legacyTarget(CompoundTag nativeTag, float nativePower, float idle, float maximum) {
        double selected = nativeTag.contains("Su24Throttle", Tag.TAG_ANY_NUMERIC)
            ? nativeTag.getDouble("Su24Throttle") : Double.NaN;
        if (!Double.isFinite(selected) || selected < 0) selected = nativePower;
        if (!Double.isFinite(selected)) selected = idle;
        if (!Float.isFinite(idle) || !Float.isFinite(maximum) || maximum <= idle)
            throw new IllegalArgumentException("Invalid native idle/maximum range");
        return (float) Math.max(0, Math.min(1, (selected - idle) / (maximum - idle)));
    }
}
