package dev.frontier.industry.integrationtests;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

/** Test-only mod. It is packaged separately and never included in the Industry release. */
@Mod(IntegrationChecksMod.ID)
public final class IntegrationChecksMod {
    public static final String ID = "frontier_integration_checks";
    private static final Logger LOGGER = LogUtils.getLogger();

    public IntegrationChecksMod() {
        MinecraftForge.EVENT_BUS.addListener(this::serverStarted);
        Pis1WorldFixture.init();
        Pis1DedicatedFixture.init();
        Pis1DroneWalkthroughFixture.init();
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> Pis1SmokeClient::init);
    }

    public static boolean authorized(MinecraftServer server) {
        if (!Boolean.getBoolean("frontier.pis1Smoke")) return false;
        String runId = System.getProperty("frontier.pis1RunId", "");
        if (runId.isBlank() || server == null) return false;
        Path gameDirectory = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
        Path manifest = gameDirectory.resolve("test-profile.json");
        try {
            if (!Files.isRegularFile(manifest) || Files.size(manifest) > 262144) return false;
            JsonObject profile = JsonParser.parseString(Files.readString(manifest, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!profile.has("runId") || !profile.get("runId").isJsonPrimitive()
                || !profile.get("runId").getAsJsonPrimitive().isString()
                || !runId.equals(profile.get("runId").getAsString())) return false;
            if (!profile.has("worldsCopied") || !profile.get("worldsCopied").isJsonPrimitive()
                || !profile.get("worldsCopied").getAsJsonPrimitive().isBoolean()
                || profile.get("worldsCopied").getAsBoolean()) return false;
            // A symlinked or custom external save must never turn this profile into
            // a route to a personal world, even if its manifest otherwise matches.
            Path world = server.getWorldPath(LevelResource.ROOT).toRealPath();
            return world.startsWith(gameDirectory.toRealPath());
        } catch (IOException | com.google.gson.JsonParseException | IllegalArgumentException | IllegalStateException error) {
            LOGGER.error("PIS-1 test profile guard rejected the launch: {}", error.toString());
            return false;
        }
    }

    private void serverStarted(ServerStartedEvent event) {
        if (!Boolean.getBoolean("frontier.pis1Smoke")) return;
        MinecraftServer server = event.getServer();
        if (!authorized(server)) {
            LOGGER.error("PIS-1 native checks refused: use an isolated test profile with worldsCopied=false and matching frontier.pis1RunId");
            return;
        }
        if (Boolean.getBoolean("frontier.pis1BenchmarkOnly")) {
            LOGGER.info("PIS-1 baseline benchmark: native integration suites are disabled for this run");
            return;
        }
        Map<String, Boolean> checks = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();
        run("energy-jade", EnergyRuntimeChecks::run, checks, errors);
        run("repair", RepairRuntimeChecks::run, checks, errors);
        run("catalog", CatalogRuntimeChecks::run, checks, errors);
        boolean passed = !checks.isEmpty() && checks.values().stream().allMatch(Boolean::booleanValue) && errors.isEmpty();
        JsonObject report = new JsonObject();
        report.addProperty("runId", System.getProperty("frontier.pis1RunId"));
        report.addProperty("minecraft", "1.20.1");
        report.addProperty("forge", "47.4.0");
        report.addProperty("java", System.getProperty("java.version"));
        report.addProperty("completedAt", Instant.now().toString());
        report.addProperty("passed", passed);
        var gson = new GsonBuilder().setPrettyPrinting().create();
        report.add("checks", gson.toJsonTree(checks));
        report.add("errors", gson.toJsonTree(errors));
        Path output = FMLPaths.GAMEDIR.get().resolve("pis1-native-checks.json");
        try {
            Files.writeString(output, gson.toJson(report) + "\n", StandardCharsets.UTF_8);
        } catch (IOException error) {
            LOGGER.error("PIS-1 native checks FAIL: cannot save report {}", output, error);
            return;
        }
        LOGGER.info("PIS-1 native checks {}: {}/{} passed; report {}", passed ? "PASS" : "FAIL",
            checks.values().stream().filter(Boolean::booleanValue).count(), checks.size(), output);
        checks.forEach((name, ok) -> {
            if (!ok) LOGGER.error("PIS-1 native check FAIL: {}", name);
        });
    }

    private static void run(String suite, Supplier<Map<String, Boolean>> task,
                            Map<String, Boolean> checks, Map<String, String> errors) {
        try {
            Map<String, Boolean> result = task.get();
            if (result.isEmpty()) checks.put(suite + ": nonempty checks", false);
            result.forEach((name, passed) -> checks.put(suite + ": " + name, passed));
        } catch (RuntimeException | LinkageError | AssertionError error) {
            checks.put(suite + ": completed without exception", false);
            errors.put(suite, error.toString());
            LOGGER.error("PIS-1 native suite failed: {}", suite, error);
        }
    }
}
