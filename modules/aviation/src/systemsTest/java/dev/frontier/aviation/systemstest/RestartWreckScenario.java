package dev.frontier.aviation.systemstest;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

/** Two actual JVM runs: old production saves a wreck; new production loads and removes it. */
final class RestartWreckScenario {
    private static UUID watched;
    private static boolean initialized, loadedFromDisk;
    private static float loadedHealth;
    private final ServerLevel level;
    private final boolean seed;
    private final JsonObject result = new JsonObject(), saved;
    private VehicleEntity aircraft;
    private int ticks;

    RestartWreckScenario(ServerLevel level, BlockPos origin, boolean seed) {
        this.level = level;
        this.seed = seed;
        try {
            var file = FMLPaths.GAMEDIR.get().resolve("aircraft-restart-seed.json");
            if (seed) {
                var type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("frontier_aviation:f16"));
                if (type == null || !(type.create(level) instanceof VehicleEntity vehicle))
                    throw new IllegalStateException("native aircraft creation failed");
                aircraft = vehicle;
                aircraft.moveTo(origin.getX() + 16.5, origin.getY() + .05, origin.getZ() + 16.5, 0, 0);
                aircraft.setHealth(0); aircraft.setWreck(true); aircraft.setSympatheticDetonated(true);
                if (!level.addFreshEntity(aircraft)) throw new IllegalStateException("old wreck spawn failed");
                saved = new JsonObject();
                saved.addProperty("uuid", aircraft.getUUID().toString());
                saved.addProperty("runId", System.getProperty("frontier.aircraftSystemsRunId"));
                saved.addProperty("x", aircraft.getX()); saved.addProperty("z", aircraft.getZ());
                var marker = JsonParser.parseString(Files.readString(FMLPaths.GAMEDIR.get().resolve("test-profile.json"))).getAsJsonObject();
                saved.add("aviationSHA256", marker.getAsJsonObject("mods").get("frontier-light-aviation-1.1.0.jar"));
                Files.writeString(file, saved.toString());
            } else {
                saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if (!System.getProperty("frontier.aircraftSystemsRunId").equals(saved.get("runId").getAsString()))
                    throw new IllegalStateException("restart seed belongs to a different profile");
            }
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }

    static void observe(Entity entity) {
        if (entity.getServer() == null || !FreshSystemsProfile.authorized(entity.getServer())) return;
        if (!initialized) {
            initialized = true;
            try {
                var path = FMLPaths.GAMEDIR.get().resolve("aircraft-restart-seed.json");
                if (Files.isRegularFile(path) && Files.size(path) < 4096)
                    watched = UUID.fromString(JsonParser.parseString(Files.readString(path)).getAsJsonObject().get("uuid").getAsString());
            } catch (Exception failure) { throw new IllegalStateException("invalid restart seed", failure); }
        }
        if (entity.getUUID().equals(watched) && entity instanceof VehicleEntity vehicle) {
            loadedFromDisk = vehicle.isWreck() && vehicle.getSympatheticDetonated();
            loadedHealth = vehicle.getHealth();
        }
    }

    boolean tick() {
        if (++ticks < 10) return false;
        UUID id = UUID.fromString(saved.get("uuid").getAsString());
        if (seed) {
            boolean remains = level.getEntity(id) == aircraft && aircraft.isWreck() && aircraft.getHealth() <= 0;
            result.addProperty("oldWreckRemained", remains);
            result.addProperty("pass", remains);
        } else {
            var pos = BlockPos.containing(saved.get("x").getAsDouble(), level.getMinBuildHeight() + 4, saved.get("z").getAsDouble());
            boolean loaded = level.hasChunkAt(pos);
            boolean removed = level.getEntity(id) == null;
            int explosions = ExplosionObservations.count(id);
            result.addProperty("loadedFromDisk", loadedFromDisk);
            result.addProperty("loadedHealth", loadedHealth);
            result.addProperty("seedChunkLoaded", loaded);
            result.addProperty("removed", removed);
            result.addProperty("newExplosions", explosions);
            result.addProperty("pass", loaded && loadedFromDisk && loadedHealth <= 0 && removed && explosions == 0);
        }
        result.add("previousAviationSHA256", saved.get("aviationSHA256"));
        result.addProperty("uuid", id.toString());
        return true;
    }

    JsonObject result() { return result; }
}
