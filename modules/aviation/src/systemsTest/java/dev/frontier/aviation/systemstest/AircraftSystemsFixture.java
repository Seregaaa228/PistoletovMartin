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
    private int limit = 400;

    public AircraftSystemsFixture() {
        if (!Boolean.getBoolean("frontier.aircraftSystemsTest")) return;
        MinecraftForge.EVENT_BUS.register(this);
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient())
            MinecraftForge.EVENT_BUS.register(new TabletClientHook());
            MinecraftForge.EVENT_BUS.register(new FlightTraceScenario.ClientRecorder());
            MinecraftForge.EVENT_BUS.register(new FireFpsScenario.FrameRecorder());
            MinecraftForge.EVENT_BUS.register(new ObserverRollScenario.Recorder());
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
            MinecraftForge.EVENT_BUS.register(new SeekClientRecorder());
        }
    }

    @SubscribeEvent public void livingHurt(net.minecraftforge.event.entity.living.LivingHurtEvent event) {
        TesterFix.livingHurt(event);
    }

    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public void detonate(net.minecraftforge.event.level.ExplosionEvent.Detonate event) {
        TesterFix.detonate(event);
    }

    /** Tester-fix scenarios: one command each, with its own tick budget. */
    private void testerFix(com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> command,
                           String name, int budget,
                           java.util.function.Function<net.minecraft.server.level.ServerPlayer, TesterFix.Scenario> make) {
        command.then(Commands.literal(name).executes(context -> {
            var source = context.getSource();
            if (step != null || !FreshSystemsProfile.authorized(source.getServer())) return 0;
            try {
                var scenario = make.apply(source.getPlayerOrException());
                begin(name, scenario::tick, scenario::result);
                limit = budget;
            } catch (RuntimeException | LinkageError | com.mojang.brigadier.exceptions.CommandSyntaxException error) {
                startFailure(name, error);
            }
            return 1;
        }));
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
            }))
            .then(Commands.literal("air_to_air").executes(context -> {
                var source = context.getSource();
                if (step != null || !FreshSystemsProfile.authorized(source.getServer())) return 0;
                try {
                    var scenario = F16AirToAirOrdnanceScenario.start(source.getPlayerOrException());
                    begin("air_to_air", scenario::tick, scenario::result);
                } catch (RuntimeException | LinkageError error) { startFailure("air_to_air", error); }
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
        command.then(Commands.literal("moving_ground_homing").executes(context -> {
            var source = context.getSource();
            if (step != null || !FreshSystemsProfile.authorized(source.getServer())) return 0;
            try {
                var scenario = F16MovingGroundHomingScenario.start(source.getPlayerOrException());
                begin("moving_ground_homing", scenario::tick, scenario::result);
            } catch (RuntimeException | LinkageError error) { startFailure("moving_ground_homing", error); }
            return 1;
        }));
        command.then(Commands.literal("ground_homing").executes(context -> {
            var source = context.getSource();
            if (step != null || !FreshSystemsProfile.authorized(source.getServer())) return 0;
            try {
                var scenario = new F16GroundHomingScenario(source.getPlayerOrException());
                begin("ground_homing", scenario::tick, scenario::result);
            } catch (RuntimeException | LinkageError error) { startFailure("ground_homing", error); }
            return 1;
        }));
        command.then(Commands.literal("creative_tab").executes(context -> {
            var source = context.getSource();
            if (step != null || !FreshSystemsProfile.authorized(source.getServer())) return 0;
            try {
                var scenario = new CreativeTabScenario();
                begin("creative_tab", scenario::tick, scenario::result);
            } catch (RuntimeException | LinkageError error) { startFailure("creative_tab", error); }
            return 1;
        }));
        for (var entry : java.util.Map.of("cannon_range", false, "cannon_range_unloaded", true).entrySet()) {
            command.then(Commands.literal(entry.getKey()).executes(context -> {
                var source = context.getSource();
                if (step != null || !FreshSystemsProfile.authorized(source.getServer())) return 0;
                try {
                    // 240: loaded but outside the simulation distance; 360: beyond the view distance.
                    var scenario = new CannonRangeScenario(source.getPlayerOrException(), entry.getValue() ? 360 : 240, entry.getValue());
                    begin(entry.getKey(), scenario::tick, scenario::result);
                    limit = 900;
                } catch (RuntimeException | LinkageError error) { startFailure(entry.getKey(), error); }
                return 1;
            }));
        }
        command.then(Commands.literal("fire_fps").executes(context -> {
            var source = context.getSource();
            if (step != null || !FreshSystemsProfile.authorized(source.getServer())) return 0;
            try {
                var scenario = new FireFpsScenario(source.getPlayerOrException());
                begin("fire_fps", scenario::tick, scenario::result);
                limit = FireFpsScenario.TIMEOUT_TICKS;
            } catch (RuntimeException | LinkageError error) { startFailure("fire_fps", error); }
            return 1;
        }));
        command.then(Commands.literal("observer_roll").executes(context -> {
            var source = context.getSource();
            if (step != null || !FreshSystemsProfile.authorized(source.getServer())) return 0;
            try {
                var scenario = new ObserverRollScenario(source.getPlayerOrException());
                begin("observer_roll", scenario::tick, scenario::result);
            } catch (RuntimeException | LinkageError error) { startFailure("observer_roll", error); }
            return 1;
        }));
        command.then(Commands.literal("cockpit_views").executes(context -> {
            var source = context.getSource();
            if (step != null || !FreshSystemsProfile.authorized(source.getServer())) return 0;
            try {
                var scenario = new CockpitViewScenario(source.getPlayerOrException());
                begin("cockpit_views", scenario::tick, scenario::result);
                limit = CockpitViewScenario.TIMEOUT_TICKS;
            } catch (RuntimeException | LinkageError error) { startFailure("cockpit_views", error); }
            return 1;
        }));
        command.then(Commands.literal("net_flight").executes(context -> {
            var source = context.getSource();
            if (step != null || !FreshSystemsProfile.authorized(source.getServer())) return 0;
            try {
                var scenario = new NetFlightScenario(source.getPlayerOrException());
                begin("net_flight", scenario::tick, scenario::result);
                limit = NetFlightScenario.TIMEOUT_TICKS;
            } catch (RuntimeException | LinkageError error) { startFailure("net_flight", error); }
            return 1;
        }));
        command.then(Commands.literal("deploy").executes(context -> {
            var source = context.getSource();
            if (step != null || !FreshSystemsProfile.authorized(source.getServer())) return 0;
            try {
                var scenario = new DeployScenario(source.getPlayerOrException());
                begin("deploy", scenario::tick, scenario::result);
            } catch (RuntimeException | LinkageError error) { startFailure("deploy", error); }
            return 1;
        }));
        command.then(Commands.literal("kab_envelope").executes(context -> {
            var source = context.getSource();
            if (step != null || !FreshSystemsProfile.authorized(source.getServer())) return 0;
            try {
                var scenario = new KabEnvelopeScenario(source.getPlayerOrException());
                begin("kab_envelope", scenario::tick, scenario::result);
                limit = KabEnvelopeScenario.TIMEOUT_TICKS;
            } catch (RuntimeException | LinkageError error) { startFailure("kab_envelope", error); }
            return 1;
        }));
        for (var entry : java.util.Map.of("tablet", dev.frontier.aviation.AircraftProfile.SU25,
                "tablet_f16", dev.frontier.aviation.AircraftProfile.F16).entrySet()) {
            command.then(Commands.literal(entry.getKey()).executes(context -> {
                var source = context.getSource();
                if (step != null || !FreshSystemsProfile.authorized(source.getServer())) return 0;
                try {
                    var scenario = new TabletStrikeScenario(source.getPlayerOrException(), entry.getValue());
                    begin(entry.getKey(), scenario::tick, scenario::result);
                    limit = TabletStrikeScenario.TIMEOUT_TICKS;
                } catch (RuntimeException | LinkageError error) { startFailure(entry.getKey(), error); }
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
        command.then(Commands.literal("air_lock").executes(context -> {
            var source = context.getSource();
            if (step != null || !FreshSystemsProfile.authorized(source.getServer())) return 0;
            try {
                var scenario = new AirLockScenario(source.getPlayerOrException());
                begin("air_lock", scenario::tick, scenario::result);
            } catch (RuntimeException | LinkageError error) { startFailure("air_lock", error); }
            return 1;
        }));
        command.then(Commands.literal("cannon_cadence").executes(context -> {
            var source = context.getSource();
            if (step != null || !FreshSystemsProfile.authorized(source.getServer())) return 0;
            try {
                var scenario = new CannonCadenceScenario(source.getPlayerOrException());
                begin("cannon_cadence", scenario::tick, scenario::result);
            } catch (RuntimeException | LinkageError error) { startFailure("cannon_cadence", error); }
            return 1;
        }));
        for (var entry : java.util.Map.of("gear", dev.frontier.aviation.AircraftProfile.F16,
                "gear_su25", dev.frontier.aviation.AircraftProfile.SU25,
                "gear_j10", dev.frontier.aviation.AircraftProfile.J10).entrySet()) {
            command.then(Commands.literal(entry.getKey()).executes(context -> {
                var source = context.getSource();
                if (step != null || !FreshSystemsProfile.authorized(source.getServer())) return 0;
                try {
                    var scenario = new GearScenario(source.getPlayerOrException(), entry.getValue());
                    begin(entry.getKey(), scenario::tick, scenario::result);
                } catch (RuntimeException | LinkageError error) { startFailure(entry.getKey(), error); }
                return 1;
            }));
        }
        for (var entry : java.util.Map.of("trace_su25", dev.frontier.aviation.AircraftProfile.SU25,
                "trace_f16", dev.frontier.aviation.AircraftProfile.F16,
                "trace_j10", dev.frontier.aviation.AircraftProfile.J10).entrySet()) {
            command.then(Commands.literal(entry.getKey()).executes(context -> {
                var source = context.getSource();
                if (step != null || !FreshSystemsProfile.authorized(source.getServer())) return 0;
                try {
                    var scenario = new FlightTraceScenario(source.getPlayerOrException(), entry.getValue());
                    begin(entry.getKey(), scenario::tick, scenario::result);
                    limit = FlightTraceScenario.TIMEOUT_TICKS;
                } catch (RuntimeException | LinkageError error) { startFailure(entry.getKey(), error); }
                return 1;
            }));
        }
        for (var entry : java.util.Map.of("maneuver_su25", dev.frontier.aviation.AircraftProfile.SU25,
                "maneuver_f16", dev.frontier.aviation.AircraftProfile.F16,
                "maneuver_j10", dev.frontier.aviation.AircraftProfile.J10).entrySet()) {
            command.then(Commands.literal(entry.getKey()).executes(context -> {
                var source = context.getSource();
                if (step != null || !FreshSystemsProfile.authorized(source.getServer())) return 0;
                try {
                    var scenario = new ManeuverTraceScenario(source.getPlayerOrException(), entry.getValue());
                    begin(entry.getKey(), scenario::tick, scenario::result);
                    limit = ManeuverTraceScenario.TIMEOUT_TICKS;
                } catch (RuntimeException | LinkageError error) { startFailure(entry.getKey(), error); }
                return 1;
            }));
        }
        command.then(Commands.literal("visual_loadout").executes(context -> {
            var source = context.getSource();
            if (step != null || !FreshSystemsProfile.authorized(source.getServer())) return 0;
            try {
                var scenario = new F16VisualLoadoutScenario(source.getPlayerOrException());
                begin("visual_loadout", scenario::tick, scenario::result);
            } catch (RuntimeException | LinkageError error) { startFailure("visual_loadout", error); }
            return 1;
        }));
        testerFix(command, "tf_cannon_f16", 3000, p -> new TesterFix.CannonTargets(p, dev.frontier.aviation.AircraftProfile.F16, -1200, 0));
        testerFix(command, "tf_cannon_su25", 3000, p -> new TesterFix.CannonTargets(p, dev.frontier.aviation.AircraftProfile.SU25, -1200, 300));
        testerFix(command, "tf_cannon_j10", 3000, p -> new TesterFix.CannonTargets(p, dev.frontier.aviation.AircraftProfile.J10, -1200, 600));
        testerFix(command, "tf_kab", 9500, p -> new TesterFix.KabCraters(p, false));
        testerFix(command, "tf_kab_1500", 9500, p -> new TesterFix.KabCraters(p, true));
        testerFix(command, "tf_midair", 2200, TesterFix.MidAir::new);
        testerFix(command, "tf_fpv", 1400, TesterFix.FpvJets::new);
        testerFix(command, "tf_seek", 800, TesterFix.SeekSelf::new);
        testerFix(command, "tf_worker", 1000, TesterFix.WorkerLook::new);
        // Air-to-air missile against a manoeuvring vulnerable target: real client lock + click.
        var F16 = dev.frontier.aviation.AircraftProfile.F16;
        var J10 = dev.frontier.aviation.AircraftProfile.J10;
        var SU25 = dev.frontier.aviation.AircraftProfile.SU25;
        testerFix(command, "aam_f16_jet", 900, p -> new AamHitScenario(p, F16, "aamissile", "frontier_aviation:f16", 2.5));
        testerFix(command, "aam_f16_light_heli", 900, p -> new AamHitScenario(p, F16, "light_aamissile", "superbwarfare:mi_28", 1.0));
        testerFix(command, "aam_j10_jet", 900, p -> new AamHitScenario(p, J10, "aamissile", "frontier_aviation:su25", 2.5));
        testerFix(command, "aam_j10_light_drone", 900, p -> new AamHitScenario(p, J10, "light_aamissile", "frontier_arcade_vehicles:geranium_2_drone", 2.0));
        testerFix(command, "aam_su25_light_jet", 900, p -> new AamHitScenario(p, SU25, "light_aamissile", "frontier_aviation:j10", 2.5));
        event.getDispatcher().register(command);
    }

    private void begin(String name, BooleanSupplier advance, Supplier<JsonObject> report) {
        scenarioName = name;
        step = advance;
        outcome = report;
        ticks = 0;
        limit = 400;
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
            if (++ticks > limit) finish(failure("scenario exceeded " + limit + " server ticks"));
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
