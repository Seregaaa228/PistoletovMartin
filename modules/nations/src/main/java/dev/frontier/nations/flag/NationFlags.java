package dev.frontier.nations.flag;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import javax.annotation.Nullable;

/**
 * Server-owned flag store keyed by the native Recruits faction string ID.
 * Native faction lifecycle (creation/disband, membership) stays with Recruits;
 * this only decorates a surviving faction with a flag and drops unknown IDs on load.
 */
public final class NationFlags extends SavedData {
    /** Saved-data key in the overworld storage. */
    public static final String KEY = "frontier_nation_flags";
    static final int MAX_ENTRIES = 1024;
    static final int MAX_ID_LENGTH = 128;

    private final Map<String, FlagDefinition> flags = new LinkedHashMap<>();
    private boolean dimensionsMerged;

    public static NationFlags get(MinecraftServer server) {
        return get(server.overworld());
    }

    public static NationFlags get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        NationFlags canonical = overworld.getDataStorage().computeIfAbsent(NationFlags::load, NationFlags::new, KEY);
        if (!canonical.dimensionsMerged) {
            for (ServerLevel dimension : level.getServer().getAllLevels()) {
                if (dimension == overworld) continue;
                NationFlags legacy = dimension.getDataStorage().get(NationFlags::load, KEY);
                if (legacy != null) canonical.mergeLegacy(legacy);
            }
            canonical.dimensionsMerged = true;
            canonical.setDirty();
        }
        return canonical;
    }

    @Nullable
    public synchronized FlagDefinition flag(String factionId) {
        return flags.get(factionId);
    }

    public synchronized Map<String, FlagDefinition> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(flags));
    }
    /** Only called after the native manager has completed loading all factions. */
    public synchronized void retainFactions(java.util.Set<String> ids) {
        if (flags.keySet().removeIf(id -> !ids.contains(id))) setDirty();
    }

    /** Stores or replaces a flag; {@code null} clears the entry for a disbanded faction. */
    public synchronized boolean set(String factionId, @Nullable FlagDefinition flag) {
        if (!validId(factionId) || flags.size() >= MAX_ENTRIES && !flags.containsKey(factionId) && flag != null) return false;
        if (flag == null) {
            if (flags.remove(factionId) != null) setDirty();
            return true;
        }
        if (!flag.equals(flags.get(factionId))) {
            flags.put(factionId, flag);
            setDirty();
        }
        return true;
    }

    public static NationFlags load(CompoundTag root) {
        NationFlags data = new NationFlags();
        ListTag entries = root.getList("Flags", Tag.TAG_COMPOUND);
        for (int index = 0; index < Math.min(entries.size(), MAX_ENTRIES); index++) {
            CompoundTag entry = entries.getCompound(index);
            String faction = entry.getString("Faction");
            if (!validId(faction)) continue;
            FlagDefinition flag = FlagDefinition.fromNbt(entry.getCompound("Flag"));
            if (flag != null) data.flags.putIfAbsent(faction, flag);
        }
        data.dimensionsMerged = root.getBoolean("DimensionsMerged");
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        ListTag entries = new ListTag();
        for (Map.Entry<String, FlagDefinition> entry : flags.entrySet()) {
            if (entries.size() >= MAX_ENTRIES) break;
            CompoundTag stored = new CompoundTag();
            stored.putString("Faction", entry.getKey());
            stored.put("Flag", entry.getValue().toNbt());
            entries.add(stored);
        }
        root.put("Flags", entries);
        root.putBoolean("DimensionsMerged", dimensionsMerged);
        root.putInt(FlagDefinition.NBT_VERSION, FlagCodec.FORMAT_VERSION);
        return root;
    }

    /** One-time import keeps existing canonical flags and leaves old files recoverable. */
    synchronized void mergeLegacy(NationFlags legacy) {
        for (Map.Entry<String, FlagDefinition> entry : legacy.snapshot().entrySet()) {
            if (!flags.containsKey(entry.getKey())) set(entry.getKey(), entry.getValue());
        }
    }

    private static boolean validId(String factionId) {
        return factionId != null && !factionId.isBlank() && factionId.length() <= MAX_ID_LENGTH;
    }
}
