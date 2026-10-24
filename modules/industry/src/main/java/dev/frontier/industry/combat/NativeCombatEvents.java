package dev.frontier.industry.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.UUID;

/** Policies around actual DSM launch APIs. No replacement projectile, explosion or visual effect is created here. */
@Mod.EventBusSubscriber(modid = CombatContent.MOD_ID)
public final class NativeCombatEvents {
    private static final String GAME = "dev.sbw.deepstrike.game.";
    private NativeCombatEvents() { }
    static boolean ballistic(Entity target) throws ReflectiveOperationException {
        if (!target.getClass().getName().equals(GAME + "GuidedMissileEntity")) return target instanceof BallisticMissileEntity;
        if (ReadyWeapons.call(target, "kind").toString().equals("PEACEKEEPER")) return true;
        var field = target.getClass().getDeclaredField("ballistic"); field.setAccessible(true); return field.getBoolean(target);
    }
    static boolean allowed(Entity source, Entity target) throws ReflectiveOperationException {
        if (!(source.level() instanceof ServerLevel server) || !ReadyWeapons.nativeDefense(source) || target == null || !target.isAlive()
                || ReadyWeapons.crew(source) == null || !server.hasChunkAt(source.blockPosition()) || !server.hasChunkAt(target.blockPosition())) return false;
        if (ReadyWeapons.patriot(source) && !ReadyWeapons.radarReady(source)) return false;
        if (!ReadyWeapons.patriot(source) && ballistic(target)) return false;
        return CombatOwnership.hostile(server, ReadyWeapons.nativeOwner(source), target);
    }
    private static boolean validInterceptor(ServerLevel level, Entity interceptor) throws ReflectiveOperationException {
        UUID source = (UUID) ReadyWeapons.call(interceptor, "defenseSource");
        UUID target = (UUID) ReadyWeapons.call(interceptor, "targetId");
        if (source == null || target == null) return false;
        Entity station = level.getEntity(source), candidate = level.getEntity(target);
        return station != null && station.isAlive() && allowed(station, candidate);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void join(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !ReadyWeapons.available()) return;
        Entity entity = event.getEntity();
        try {
            if (entity.getClass().getName().equals(GAME + "PatriotInterceptorEntity") && !validInterceptor(level, entity)) event.setCanceled(true);
            if (ReadyWeapons.dome(entity)) ReadyWeapons.invoke(entity, "setAutoDefense", new Class<?>[]{boolean.class}, false);
            if (entity.getClass().getName().equals(GAME + "GuidedMissileEntity")) {
                var field = entity.getClass().getDeclaredField("launchMaster"); field.setAccessible(true); Object raw = field.get(entity);
                if (raw instanceof BlockPos pos && level.hasChunkAt(pos)) {
                    var block = level.getBlockEntity(pos);
                    if (ReadyWeapons.nativeLauncher(block) && (ReadyWeapons.crew(block) == null || block.getPersistentData().getBoolean("frontierNativeDefenseHold"))) event.setCanceled(true);
                }
            }
        } catch (ReflectiveOperationException | LinkageError incompatible) {
            if (entity.getClass().getName().equals(GAME + "PatriotInterceptorEntity")) event.setCanceled(true);
        }
    }
    @SubscribeEvent
    public static void tick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.START || !(event.level instanceof ServerLevel level) || !ReadyWeapons.available()) return;
        java.util.ArrayList<Entity> loaded = new java.util.ArrayList<>(); level.getAllEntities().forEach(loaded::add);
        for (Entity entity : loaded) {
            try {
                if (entity.getClass().getName().equals(GAME + "PatriotInterceptorEntity")) {
                    if (!validInterceptor(level, entity)) entity.discard();
                } else if (ReadyWeapons.nativeDefense(entity)) {
                    if (ReadyWeapons.dome(entity)) ReadyWeapons.invoke(entity, "setAutoDefense", new Class<?>[]{boolean.class}, false);
                    if (level.getGameTime() % 10 == 0) defend(level, entity);
                }
            } catch (ReflectiveOperationException | LinkageError incompatible) { /* Optional API is fail-closed. */ }
        }
        if (level.getGameTime() % 5 == 0) for (ServerPlayer player : level.players()) {
            try { advanceOrder(player); } catch (ReflectiveOperationException | LinkageError incompatible) { player.getPersistentData().remove(ReadyWeapons.PENDING); }
        }
    }
    private static void defend(ServerLevel level, Entity station) throws ReflectiveOperationException {
        if (!station.isAlive() || !station.getPersistentData().getBoolean(ReadyWeapons.DUTY) || ReadyWeapons.crew(station) == null
                || !ReadyWeapons.patriot(station) && !ReadyWeapons.dome(station)) return;
        Object raw = ReadyWeapons.patriot(station)
                ? ReadyWeapons.invoke(station, "scan", new Class<?>[]{ServerLevel.class}, level)
                : ReadyWeapons.invoke(station, "scanContacts", new Class<?>[]{ServerLevel.class}, level);
        if (!(raw instanceof List<?> contacts)) return;
        for (Object contact : contacts) {
            UUID id = (UUID) ReadyWeapons.call(contact, "id"); Entity target = level.getEntity(id);
            if (target == null || !allowed(station, target) || engaged(level, id)) continue;
            int type = ReadyWeapons.patriot(station) && (ballistic(target) || ((Number) ReadyWeapons.call(station, "readyPac2")).intValue() == 0) ? 1 : 0;
            int ammunition = ((Number) ReadyWeapons.call(station, ReadyWeapons.patriot(station) ? type == 0 ? "readyPac2" : "readyPac3" : "getReadyTamir")).intValue();
            if (ammunition <= 0) continue;
            ServerPlayer player = controller(level, station); if (player == null) return;
            if (ReadyWeapons.patriot(station)) {
                Object fired = ReadyWeapons.staticCall("dev.sbw.deepstrike.network.PatriotDefenseNetwork", "fireFromCommand",
                        new Class<?>[]{ServerPlayer.class, station.getClass(), UUID.class, int.class}, player, station, id, type);
                if (Boolean.TRUE.equals(fired)) return;
            } else {
                ReadyWeapons.invoke(station, "fireManual", new Class<?>[]{UUID.class, ServerPlayer.class}, id, player); return;
            }
        }
    }
    /** Server-only context for an already owned and staffed native defense; never joins a player list or receives items. */
    static ServerPlayer controller(ServerLevel level, Entity station) throws ReflectiveOperationException {
        UUID owner = ReadyWeapons.nativeOwner(station);
        if (!station.isAlive() || owner == null || CombatOwnership.country(level, owner) == null || ReadyWeapons.crew(station) == null
                || !level.hasChunkAt(station.blockPosition()) || !ReadyWeapons.radarReady(station)) return null;
        ServerPlayer actual = level.getServer().getPlayerList().getPlayer(owner);
        if (actual != null && actual.serverLevel() == level) return actual;
        var controller = net.minecraftforge.common.util.FakePlayerFactory.get(level, new com.mojang.authlib.GameProfile(owner, "[FrontierPVO]"));
        // Forge supplies its own no-op network handler. Do not replace a missing connection or invoke a client-only API.
        if (controller.connection == null) return null;
        controller.setPos(station.position()); return controller;
    }
    private static boolean engaged(ServerLevel level, UUID target) throws ReflectiveOperationException {
        for (Entity entity : level.getAllEntities()) if (entity.isAlive() && entity.getClass().getName().equals(GAME + "PatriotInterceptorEntity")
                && target.equals(ReadyWeapons.call(entity, "targetId"))) return true;
        return false;
    }
    private static void advanceOrder(ServerPlayer player) throws ReflectiveOperationException {
        CompoundTag playerData = player.getPersistentData(); if (!playerData.hasUUID(ReadyWeapons.PENDING)) return;
        Object center = ReadyWeapons.staticCall(GAME + "CommandCenterData", "get", new Class<?>[]{ServerLevel.class}, player.serverLevel());
        CompoundTag session = (CompoundTag) ReadyWeapons.invoke(center, "session", new Class<?>[]{ServerPlayer.class}, player);
        if (session == null) {
            ReadyWeapons.invoke(center, "login", new Class<?>[]{ServerPlayer.class, String.class}, player, player.getGameProfile().getName());
            session = (CompoundTag) ReadyWeapons.invoke(center, "session", new Class<?>[]{ServerPlayer.class}, player);
            if (session == null) { playerData.remove(ReadyWeapons.PENDING); return; }
        }
        CompoundTag task = null;
        for (var tag : session.getList("Tasks", 10)) if (tag instanceof CompoundTag entry && entry.hasUUID("Id") && entry.getUUID("Id").equals(playerData.getUUID(ReadyWeapons.PENDING))) task = entry;
        if (task == null) { playerData.remove(ReadyWeapons.PENDING); return; }
        boolean crewReady = true;
        for (var tag : task.getList("Assignments", 10)) {
            CompoundTag assignment = (CompoundTag) tag; UUID unitId = assignment.getUUID("Unit"); Object station = null;
            for (var unitTag : session.getList("Units", 10)) {
                CompoundTag unit = (CompoundTag) unitTag;
                if (unit.hasUUID("Id") && unitId.equals(unit.getUUID("Id"))) {
                    // Only current-dimension, already loaded units can advance an order.
                    if (!player.serverLevel().dimension().location().toString().equals(unit.getString("Dimension"))) continue;
                    BlockPos pos = BlockPos.of(unit.getLong("Position"));
                    if (player.serverLevel().hasChunkAt(pos)) station = player.serverLevel().getBlockEntity(pos);
                }
            }
            if (!ReadyWeapons.nativeLauncher(station) || ReadyWeapons.crew(station) == null || !ReadyWeapons.owned(player, station)) crewReady = false;
        }
        CompoundTag payload = new CompoundTag(); payload.putUUID("Task", playerData.getUUID(ReadyWeapons.PENDING));
        if (!crewReady) { ReadyWeapons.nativeAction(center, player, "disarm", payload); playerData.remove(ReadyWeapons.PENDING); return; }
        String state = task.getString("State");
        if (state.equals("DUTY")) {
            String error = ReadyWeapons.nativeAction(center, player, "launch", payload);
            if (error.isEmpty()) playerData.remove(ReadyWeapons.PENDING);
        } else if (!state.equals("PREPARING")) playerData.remove(ReadyWeapons.PENDING);
    }
}
