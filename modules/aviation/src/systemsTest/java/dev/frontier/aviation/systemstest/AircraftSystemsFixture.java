package dev.frontier.aviation.systemstest;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.nio.file.Files;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

/** Explicitly installed fixture. Does nothing without both opt-in and a fresh marked world. */
@Mod("frontier_aircraft_systems_test")
public final class AircraftSystemsFixture {
    private final JsonObject results = new JsonObject();
    private BooleanSupplier step;
    private Supplier<JsonObject> outcome;
    private String scenarioName;
    private int ticks;

    public AircraftSystemsFixture() {
        if (Boolean.getBoolean("frontier.aircraftSystemsTest")) MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent public void commands(RegisterCommandsEvent event) {
        var command = Commands.literal("aircraft_check").requires(source -> source.hasPermission(2))
            .then(Commands.literal("destruction").executes(context -> {
                var source = context.getSource();
                if (step != null || !FreshSystemsProfile.authorized(source.getServer())) return 0;
                try {
                var scenario = DestructionScenario.start(source.getLevel(),
                    net.minecraft.core.BlockPos.containing(source.getPosition()).offset(16, 0, 16), ExplosionObservations::count);
                begin("destruction", scenario::tick, scenario::result);
                source.sendSuccess(() -> Component.literal("Aircraft destruction scenario started"), false);
                } catch (RuntimeException | LinkageError error) { startFailure("destruction", error); }
                return 1;
            }))
            .then(Commands.literal("npc").executes(context -> {
                var source = context.getSource();
                if (step != null || !FreshSystemsProfile.authorized(source.getServer())) return 0;
                try {
                var scenario = NpcFlightScenario.start(source.getLevel(),
                    net.minecraft.core.BlockPos.containing(source.getPosition()));
                begin("npc", scenario::tick, scenario::result);
                source.sendSuccess(() -> Component.literal("Aircraft NPC scenario started"), false);
                } catch (RuntimeException | LinkageError error) { startFailure("npc", error); }
                return 1;
            }));
        int[] ranges = {80, 160, 240, 160};
        float[] pitches = {0, -8, 8, 0}, banks = {0, 0, 0, 45};
        double[] speeds = {0, 1.2, 1.2, 1.2};
        for (int index = 0; index < ranges.length; index++) {
            int preset = index;
            command.then(Commands.literal("aim" + index).executes(context -> {
                var source = context.getSource();
                if (step != null || !FreshSystemsProfile.authorized(source.getServer())) return 0;
                try {
                var scenario = AimScenario.start(source.getPlayerOrException(), ranges[preset], pitches[preset], banks[preset], speeds[preset]);
                begin("aim" + preset, scenario::tick, scenario::result);
                } catch (RuntimeException | LinkageError error) { startFailure("aim" + preset, error); }
                return 1;
            }));
        }
        for (String name : new String[]{"seed_wreck", "restart_wreck"}) {
            command.then(Commands.literal(name).executes(context -> {
                var source = context.getSource();
                if (step != null || !FreshSystemsProfile.authorized(source.getServer())) return 0;
                try {
                    var scenario = new RestartWreckScenario(source.getLevel(),
                        net.minecraft.core.BlockPos.containing(source.getPosition()), name.equals("seed_wreck"));
                    begin(name, scenario::tick, scenario::result);
                } catch (RuntimeException | LinkageError error) { startFailure(name, error); }
                return 1;
            }));
        }
        command.then(Commands.literal("gesture").executes(context -> {
            var source = context.getSource();
            if (step != null || !FreshSystemsProfile.authorized(source.getServer())) return 0;
            try {
                var scenario = new GestureScenario(source.getPlayerOrException());
                begin("gesture", scenario::tick, scenario::result);
            } catch (RuntimeException | LinkageError error) { startFailure("gesture", error); }
            return 1;
        }));
        command.then(Commands.literal("gear").executes(context -> {
            var source = context.getSource();
            if (step != null || !FreshSystemsProfile.authorized(source.getServer())) return 0;
            try {
                var scenario = new GearScenario(source.getPlayerOrException());
                begin("gear", scenario::tick, scenario::result);
            } catch (RuntimeException | LinkageError error) { startFailure("gear", error); }
            return 1;
        }));
        event.getDispatcher().register(command);
    }

    private void begin(String name, BooleanSupplier advance, Supplier<JsonObject> report) {
        scenarioName = name;
        step = advance;
        outcome = report;
        ticks = 0;
    }

    private void startFailure(String name, Throwable error) {
        scenarioName = name;
        ticks = 0;
        LogUtils.getLogger().error("AIRCRAFT_SYSTEMS setup failed for " + name, error);
        finish(failure(error.toString()));
    }

    @SubscribeEvent public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) ImpactObservations.sample();
        if (event.phase != TickEvent.Phase.END || step == null) return;
        if (!FreshSystemsProfile.authorized(event.getServer())) {
            finish(failure("fresh-profile guard no longer matches"));
            return;
        }
        try {
            if (++ticks > 400) finish(failure("scenario exceeded 400 server ticks"));
            else if (step.getAsBoolean()) finish(outcome.get());
        } catch (RuntimeException | LinkageError error) {
            LogUtils.getLogger().error("AIRCRAFT_SYSTEMS fixture failed", error);
            finish(failure(error.toString()));
        }
    }

    private JsonObject failure(String message) {
        var result = new JsonObject();
        result.addProperty("pass", false);
        result.addProperty("error", message);
        return result;
    }

    private void finish(JsonObject result) {
        result.addProperty("observedTicks", ticks);
        results.add(scenarioName, result);
        step = null;
        outcome = null;
        try {
            var profile = FMLPaths.GAMEDIR.get();
            var marker = JsonParser.parseString(Files.readString(profile.resolve("test-profile.json"))).getAsJsonObject();
            var report = new JsonObject();
            report.addProperty("runId", System.getProperty("frontier.aircraftSystemsRunId"));
            report.add("aviationSHA256", marker.getAsJsonObject("mods").get("frontier-light-aviation-1.1.0.jar"));
            report.add("scenarios", results);
            Files.writeString(profile.resolve("aircraft-systems-results.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
            LogUtils.getLogger().info("AIRCRAFT_SYSTEMS {} result={}", scenarioName, result);
        } catch (Exception error) {
            throw new IllegalStateException("cannot persist aircraft check result", error);
        }
    }

    @SubscribeEvent public void spawned(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            ProjectileObservations.observed(event.getEntity()); ImpactObservations.spawned(event.getEntity());
            RestartWreckScenario.observe(event.getEntity());
        }
    }

    @SubscribeEvent public void stopped(ServerStoppingEvent event) {
        ExplosionObservations.clear(); ProjectileObservations.clear(); ImpactObservations.clear();
    }
}
