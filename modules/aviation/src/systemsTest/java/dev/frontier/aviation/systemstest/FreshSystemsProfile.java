package dev.frontier.aviation.systemstest;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.fml.loading.FMLPaths;

/** Independent opt-in and fresh-world guard for destructive fixture operations. */
final class FreshSystemsProfile {
    private FreshSystemsProfile() {}

    static boolean authorized(MinecraftServer server) {
        if (!Boolean.getBoolean("frontier.aircraftSystemsTest") || !server.isSameThread()) return false;
        try {
            String id = System.getProperty("frontier.aircraftSystemsRunId", "");
            if (!UUID.fromString(id).toString().equals(id)) return false;
            Path profile = FMLPaths.GAMEDIR.get().toRealPath();
            Path manifest = profile.resolve("test-profile.json");
            if (!Files.isRegularFile(manifest) || Files.size(manifest) > 262144) return false;
            var marker = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
            if (!marker.has("runId") || !id.equals(marker.get("runId").getAsString())
                    || !marker.has("worldsCopied") || !marker.get("worldsCopied").isJsonPrimitive()
                    || !marker.get("worldsCopied").getAsJsonPrimitive().isBoolean()
                    || marker.get("worldsCopied").getAsBoolean()) return false;
            Path world = server.getWorldPath(LevelResource.ROOT).toRealPath();
            return world.startsWith(profile) && !world.equals(profile)
                && world.getFileName().toString().equals("aircraft_systems_" + id.substring(0, 8));
        } catch (Exception failure) {
            return false;
        }
    }
}
