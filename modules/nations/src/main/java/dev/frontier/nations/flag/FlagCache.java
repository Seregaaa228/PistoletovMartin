package dev.frontier.nations.flag;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import javax.annotation.Nullable;

/**
 * Client-side copy of {@link NationFlags}. Filled by {@link FlagNet.FlagSync}:
 * full snapshot on login, single-entry updates after each accepted write.
 * The map sidebar and country labels read from here; it never writes back.
 */
@OnlyIn(Dist.CLIENT)
public final class FlagCache {
    private static final Map<String, FlagDefinition> FLAGS = new LinkedHashMap<>();

    private FlagCache() {}

    public static synchronized void apply(@Nullable Map<String, FlagDefinition> flags) {
        if (flags == null) return;
        for (Map.Entry<String, FlagDefinition> entry : flags.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) continue;
            FLAGS.put(entry.getKey(), entry.getValue());
            if (FLAGS.size() > NationFlags.MAX_ENTRIES) break;
        }
    }

    public static synchronized void replace(@Nullable Map<String, FlagDefinition> flags) {
        FLAGS.clear();
        apply(flags);
    }

    @Nullable
    public static synchronized FlagDefinition flag(String factionId) {
        return FLAGS.get(factionId);
    }

    public static synchronized Map<String, FlagDefinition> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(FLAGS));
    }

    static synchronized void clearForTests() {
        FLAGS.clear();
    }
}
