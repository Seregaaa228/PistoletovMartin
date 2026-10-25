package dev.frontier.industry.acceptance;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

/** Test mod is separately built and requires both explicit opt-in and a fresh profile marker. */
@Mod("frontier_combat_checks")
public final class CombatChecks {
    public CombatChecks() {
        if (!enabled()) return;
        MinecraftForge.EVENT_BUS.register(new CombatServerChecks());
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> CombatClientChecks::register);
    }
    public static boolean enabled() {
        return OptIn.ENABLED;
    }
    private static final class OptIn {
        private static final boolean ENABLED=readMarker();
    }
    private static boolean readMarker() {
        if (!Boolean.getBoolean("frontier.combatAcceptance")) return false;
        try {
            var marker = JsonParser.parseString(Files.readString(FMLPaths.GAMEDIR.get().resolve("test-profile.json"))).getAsJsonObject();
            return !marker.get("worldsCopied").getAsBoolean()
                    && marker.get("runId").getAsString().equals(System.getProperty("frontier.combatRunId"));
        } catch (Exception invalid) { return false; }
    }
    public static void write(String name, Object value) {
        try { Files.writeString(FMLPaths.GAMEDIR.get().resolve(name), new GsonBuilder().setPrettyPrinting().create().toJson(value)); }
        catch (Exception failed) { throw new IllegalStateException(failed); }
    }
}
