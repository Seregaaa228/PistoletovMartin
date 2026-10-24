package dev.frontier.industry.combat;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Root must call init() once. No server tick hook is required: native entities own their ticks. */
public final class CombatEvents {
    private static boolean initialized;
    private CombatEvents() { }
    public static void init() { if (!initialized) { initialized = true; MinecraftForge.EVENT_BUS.register(CombatEvents.class); } }
    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        var root = Commands.literal("industry")
                .then(Commands.literal("launch").then(Commands.argument("coordinates", Vec3Argument.vec3())
                        .executes(context -> launch(context.getSource().getPlayerOrException(), Vec3Argument.getVec3(context, "coordinates"), 1))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, CombatRules.MAX_SALVO))
                                .executes(context -> launch(context.getSource().getPlayerOrException(), Vec3Argument.getVec3(context, "coordinates"), IntegerArgumentType.getInteger(context, "count"))))))
                .then(Commands.literal("defense")
                        .then(Commands.literal("active").executes(context -> defense(context.getSource().getPlayerOrException(), true)))
                        .then(Commands.literal("hold").executes(context -> defense(context.getSource().getPlayerOrException(), false))))
                .then(Commands.literal("operator")
                        .executes(context -> operator(context.getSource().getPlayerOrException(), false))
                        .then(Commands.literal("clear").executes(context -> operator(context.getSource().getPlayerOrException(), true))))
                .then(Commands.literal("ammo").executes(context -> ammo(context.getSource().getPlayerOrException())))
                .then(Commands.literal("military")
                        .then(Commands.literal("status").executes(context -> status(context.getSource().getPlayerOrException())))
                        .then(Commands.literal("pack").executes(context -> pack(context.getSource().getPlayerOrException())))
                        .then(Commands.literal("kit").requires(source -> source.hasPermission(2)).executes(context -> kit(context.getSource().getPlayerOrException()))));
        event.getDispatcher().register(root);
    }
    private static int launch(ServerPlayer player, net.minecraft.world.phys.Vec3 target, int count) {
        CombatOperations.LaunchResult result = CombatOperations.launch(player, target, count);
        player.sendSystemMessage(result.success() ? Component.translatable("combat.frontier_industry.launched", count) : CombatOperations.failureMessage(result.reason()));
        return result.success() ? count : 0;
    }
    private static LauncherEntity nearest(ServerPlayer player) {
        LauncherEntity found = CombatOperations.nearest(player, 8);
        if (found == null) player.sendSystemMessage(Component.translatable("combat.frontier_industry.station_missing"));
        return found;
    }
    private static int defense(ServerPlayer player, boolean active) {
        LauncherEntity station = nearest(player);
        if (station == null || station.kind() == LauncherEntity.Kind.BALLISTIC) return 0;
        station.setActive(active); player.sendSystemMessage(station.status()); return 1;
    }
    private static int operator(ServerPlayer player, boolean clear) {
        LauncherEntity station = nearest(player); if (station == null) return 0;
        if (clear) { station.releaseOperator(); player.sendSystemMessage(Component.translatable("combat.frontier_industry.operator_released")); return 1; }
        boolean assigned = station.assignNearest(player);
        player.sendSystemMessage(Component.translatable(assigned ? "combat.frontier_industry.operator_assigned" : "combat.frontier_industry.operator_missing")); return assigned ? 1 : 0;
    }
    private static int ammo(ServerPlayer player) {
        LauncherEntity station = nearest(player); if (station == null) return 0;
        int added = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) added += station.load(player.getInventory().getItem(slot));
        player.getInventory().setChanged(); player.sendSystemMessage(Component.translatable("combat.frontier_industry.loaded", added, station.ammunition())); return added;
    }
    private static int status(ServerPlayer player) { LauncherEntity station = nearest(player); if (station == null) return 0; player.sendSystemMessage(station.status()); return 1; }
    private static int pack(ServerPlayer player) { LauncherEntity station = nearest(player); return station != null && station.packUp(player) ? 1 : 0; }
    private static int kit(ServerPlayer player) {
        give(player, new ItemStack(CombatContent.MISSILE_LAUNCHER.get())); give(player, new ItemStack(CombatContent.S300_LAUNCHER.get()));
        give(player, new ItemStack(CombatContent.S400_LAUNCHER.get())); give(player, new ItemStack(CombatContent.PATRIOT_LAUNCHER.get()));
        give(player, new ItemStack(CombatContent.BALLISTIC_MISSILE.get(), 12)); give(player, new ItemStack(CombatContent.INTERCEPTOR_AMMO.get(), 32)); return 1;
    }
    private static void give(ServerPlayer player, ItemStack stack) { if (!player.getInventory().add(stack)) player.drop(stack, false); }
    @SubscribeEvent public static void cleanupOperator(LivingEvent.LivingTickEvent event) {
        LivingEntity living = event.getEntity();
        if (!(living.level() instanceof ServerLevel server) || living.tickCount % 20 != 0) return;
        var tag = living.getPersistentData();
        if (!tag.contains(CombatOwnership.STATION)) return;
        if (ReadyWeapons.validStation(living)) return;
        java.util.UUID stationId = CombatOwnership.parse(tag.getString(CombatOwnership.STATION));
        Entity station = stationId == null ? null : server.getEntity(stationId);
        if (!(station instanceof LauncherEntity launcher) || !living.getUUID().equals(launcher.operatorUUID()) || living.getVehicle() != launcher)
            ReadyWeapons.clearAssignment(living);
    }
}
