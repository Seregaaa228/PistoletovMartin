package dev.frontier.aviation.bench;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.frontier.aviation.AircraftProfile;
import dev.frontier.aviation.LightAviation;
import dev.frontier.aviation.control.diagnostics.FlightPerformanceEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Opt-in flight benchmark (-Dfrontier.flightBench=x,y,z,yaw on both sides): seats the
 * caller in a fresh, fuelled, invulnerable F-16 at a fixed point so every run flies the
 * same route, and brackets the measured window for the server tick histogram. Never
 * registered in normal play.
 */
@Mod.EventBusSubscriber(modid = LightAviation.ID)
public final class FlightBenchCommand {
    public static final boolean ENABLED = System.getProperty("frontier.flightBench") != null;

    private FlightBenchCommand() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        if (!ENABLED) return;
        var yaw = Commands.argument("yaw", FloatArgumentType.floatArg(-180, 180))
            .executes(context -> start(context.getSource(),
                IntegerArgumentType.getInteger(context, "x"), IntegerArgumentType.getInteger(context, "y"),
                IntegerArgumentType.getInteger(context, "z"), FloatArgumentType.getFloat(context, "yaw")));
        var point = Commands.argument("x", IntegerArgumentType.integer())
            .then(Commands.argument("y", IntegerArgumentType.integer(-64, 400))
                .then(Commands.argument("z", IntegerArgumentType.integer()).then(yaw)));
        event.getDispatcher().register(Commands.literal("frontier_bench")
            .requires(FlightBenchCommand::allowed)
            .then(Commands.literal("start").then(point))
            .then(Commands.literal("hold").then(Commands.argument("x", IntegerArgumentType.integer())
                .then(Commands.argument("y", IntegerArgumentType.integer(-64, 400))
                    .then(Commands.argument("z", IntegerArgumentType.integer()).executes(context -> hold(context.getSource(),
                        IntegerArgumentType.getInteger(context, "x"), IntegerArgumentType.getInteger(context, "y"),
                        IntegerArgumentType.getInteger(context, "z")))))))
            .then(Commands.literal("measure").executes(context -> {
                FlightPerformanceEvents.reset();
                return 1;
            }))
            .then(Commands.literal("finish").executes(context -> {
                FlightPerformanceEvents.dumpAndReset("bench");
                return 1;
            })));
    }

    private static boolean allowed(CommandSourceStack source) {
        if (source.hasPermission(2)) return true;
        return source.getPlayer() != null && source.getServer().isSingleplayerOwner(source.getPlayer().getGameProfile());
    }

    /** Parks the caller as a spectator at a point, e.g. while a LOD mod builds around it. */
    private static int hold(CommandSourceStack source, int x, int y, int z) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
        player.teleportTo(player.serverLevel(), x + .5, y, z + .5, 0, 0);
        return 1;
    }

    private static int start(CommandSourceStack source, int x, int y, int z, float yaw) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        var level = player.serverLevel();
        VehicleEntity plane = (VehicleEntity) AircraftProfile.F16.entity.get().create(level);
        if (plane == null) throw new IllegalStateException("F-16 creation failed");
        plane.moveTo(x + .5, y, z + .5, yaw, 0);
        plane.setEnergy(plane.getMaxEnergy());
        plane.setInvulnerable(true);
        if (player.isPassenger()) player.stopRiding();
        player.teleportTo(level, x + .5, y, z + .5, yaw, 0);
        if (!level.addFreshEntity(plane) || !player.startRiding(plane, true))
            throw new IllegalStateException("bench seat failed");
        source.sendSuccess(() -> Component.literal("flight bench: seated at " + x + " " + y + " " + z), false);
        return 1;
    }
}
