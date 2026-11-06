package dev.frontier.aviation.performancetest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.frontier.aviation.control.diagnostics.FlightTimingHistogram;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import com.mojang.brigadier.arguments.StringArgumentType;

/** Guarded timing probe only; it never creates entities or submits flight input. */
@Mod("frontier_flight_performance_test")
public final class FlightPerformanceProbe {
    private String phase;

    public FlightPerformanceProbe() {
        if (Boolean.getBoolean("frontier.flightBenchmark") && authorizedProfile())
            MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("flight_benchmark")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("begin").then(Commands.argument("label", StringArgumentType.word()).executes(context -> {
                MinecraftServer server = context.getSource().getServer();
                if (!authorizedWorld(server)) throw new IllegalStateException("fresh test world required");
                String label = StringArgumentType.getString(context, "label");
                if (!label.matches("[a-zA-Z0-9_-]{1,40}")) throw new IllegalArgumentException("benchmark label");
                histogram("dev.frontier.aviation.control.diagnostics.FlightPerformanceEvents", "WHOLE_SERVER_TICK").reset();
                phase = label;
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> FlightPerformanceProbeClient.begin(label));
                context.getSource().sendSuccess(() -> Component.literal("FLIGHT_BENCHMARK BEGIN " + label), false);
                return 1;
            })))
            .then(Commands.literal("end").executes(context -> {
                if (phase == null || !authorizedWorld(context.getSource().getServer()))
                    throw new IllegalStateException("no active fresh-world phase");
                String label = phase; phase = null;
                report("server", label, histogram("dev.frontier.aviation.control.diagnostics.FlightPerformanceEvents", "WHOLE_SERVER_TICK"));
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> FlightPerformanceProbeClient.end(label));
                context.getSource().sendSuccess(() -> Component.literal("FLIGHT_BENCHMARK END " + label), false);
                return 1;
            })));
    }

    private static boolean authorizedProfile() {
        try {
            String id = System.getProperty("frontier.flightBenchmarkRunId", "");
            if (!UUID.fromString(id).toString().equals(id)) return false;
            JsonObject manifest = manifest();
            return id.equals(manifest.get("runId").getAsString()) && !manifest.get("worldsCopied").getAsBoolean();
        } catch (Exception failure) { return false; }
    }

    private static boolean authorizedWorld(MinecraftServer server) {
        try {
            Path profile = FMLPaths.GAMEDIR.get().toRealPath();
            Path world = server.getWorldPath(LevelResource.ROOT).toRealPath();
            return authorizedProfile() && world.startsWith(profile) && !world.equals(profile)
                && !profile.getFileName().toString().equals("instance");
        } catch (Exception failure) { return false; }
    }

    private static JsonObject manifest() throws Exception {
        Path path = FMLPaths.GAMEDIR.get().resolve("test-profile.json");
        if (Files.size(path) > 262_144) throw new IllegalStateException("test manifest too large");
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    static FlightTimingHistogram histogram(String owner, String name) {
        try { return (FlightTimingHistogram) field(owner, name).get(null); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    static Field field(String owner, String name) throws ReflectiveOperationException {
        Field field = Class.forName(owner).getDeclaredField(name); field.setAccessible(true); return field;
    }

    static void report(String side, String label, FlightTimingHistogram samples) {
        try {
            JsonObject record = new JsonObject(); JsonObject manifest = manifest();
            record.addProperty("runId", manifest.get("runId").getAsString());
            record.addProperty("aviationSha256", manifest.getAsJsonObject("mods").get("frontier-light-aviation-1.1.0.jar").getAsString());
            record.addProperty("side", side); record.addProperty("label", label);
            record.addProperty("samples", samples.count()); record.addProperty("sumNanos", samples.sumNanos());
            record.addProperty("meanNanos", samples.count() == 0 ? -1 : (double) samples.sumNanos() / samples.count());
            record.addProperty("minNanos", samples.minNanos()); record.addProperty("maxNanos", samples.maxNanos());
            record.addProperty("p50Upper", samples.percentileUpperBound(50));
            record.addProperty("p95Upper", samples.percentileUpperBound(95));
            record.addProperty("p99Upper", samples.percentileUpperBound(99));
            Files.writeString(FMLPaths.GAMEDIR.get().resolve("flight-benchmark-" + side + ".jsonl"), record + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            System.out.println("FLIGHT_BENCHMARK " + record);
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
