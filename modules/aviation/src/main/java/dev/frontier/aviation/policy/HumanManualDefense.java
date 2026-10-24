package dev.frontier.aviation.policy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Field;
import java.util.Map;
import java.lang.reflect.Method;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** A scope exists only around authenticated manual packet/action calls, never owner-driven NPC ticks. */
public final class HumanManualDefense {
    private static final String PATRIOT = "dev.sbw.deepstrike.vehicle.PatriotTruckEntity";
    private static final String DOME = "dev.sbw.deepstrike.vehicle.IronDomeEntity";
    private static final String INTERCEPTOR = "dev.sbw.deepstrike.game.PatriotInterceptorEntity";
    private static final String OWNER = "frontierAviationHumanDefenseOwner";
    private static final String SOURCE = "frontierAviationHumanDefenseSource";
    private static final String TARGET = "frontierAviationHumanDefenseTarget";
    private static final ThreadLocal<Shot> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Validation> VALIDATING = new ThreadLocal<>();
    private record Validation(ServerLevel level, Entity station, Entity target, UUID owner) { }
    private record Shot(ServerPlayer player, Entity station, UUID target) { }
    private HumanManualDefense() { }

    public static boolean patriotFire(ServerPlayer player, Object candidate, UUID target, int warhead, boolean seated) {
        if (!(candidate instanceof Entity station)) return false;
        Shot previous = CURRENT.get();
        boolean manual = HumanPlayers.isHuman(player) && player.isAlive() && station.isAlive()
                && player.level() == station.level() && PATRIOT.equals(station.getClass().getName())
                && (!seated || player.getVehicle() == station && station.getPassengers().contains(player))
                && player.getUUID().equals(nativeOwner(station));
        if (manual) CURRENT.set(new Shot(player, station, target));
        else CURRENT.remove();
        try {
            ClassLoader loader = station.getClass().getClassLoader();
            Class<?> type = Class.forName(PATRIOT, false, loader);
            Class<?> network = Class.forName("dev.sbw.deepstrike.network.PatriotDefenseNetwork", false, loader);
            Method fire = network.getMethod("fireFromCommand", ServerPlayer.class, type, UUID.class, int.class);
            return Boolean.TRUE.equals(fire.invoke(null, player, station, target, warhead));
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Native Patriot manual fire failed", cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Pinned native Patriot fire API is unavailable", failure);
        } finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    public static void domeFire(Object candidate, UUID target, ServerPlayer player) {
        if (!(candidate instanceof Entity station)) return;
        Shot previous = CURRENT.get();
        if (humanStation(player, station, DOME)) CURRENT.set(new Shot(player, station, target));
        else CURRENT.remove();
        try {
            station.getClass().getMethod("fireManual", UUID.class, ServerPlayer.class).invoke(station, target, player);
        } catch (InvocationTargetException failure) {
            throw nativeFailure(failure);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Pinned native Iron Dome fire API is unavailable", failure);
        } finally {
            restore(previous);
        }
    }

    /** Entered only after CommandCenterData has checked its real session permission. */
    public static String commandAction(ServerPlayer player, CompoundTag session, String action, CompoundTag request) {
        Shot previous = CURRENT.get();
        CURRENT.remove();
        try {
            Class<?> commands = Class.forName("dev.sbw.deepstrike.game.CommandDefense", false,
                    HumanManualDefense.class.getClassLoader());
            if ("defense_fire".equals(action) && HumanPlayers.isHuman(player) && request.hasUUID("Target")) {
                Field selection = commands.getDeclaredField("selected");
                selection.setAccessible(true);
                Object selected = ((Map<?, ?>)selection.get(null)).get(player.getUUID());
                if (selected instanceof UUID id) {
                    Entity station = player.serverLevel().getEntity(id);
                    boolean recorded = false;
                    for (var raw : session.getList("Defense", 10)) {
                        CompoundTag record = (CompoundTag)raw;
                        if (record.hasUUID("Id") && id.equals(record.getUUID("Id"))
                                && player.level().dimension().location().toString().equals(record.getString("Dimension"))) {
                            recorded = true;
                            break;
                        }
                    }
                    if (recorded && station != null && (humanStation(player, station, PATRIOT)
                            || humanStation(player, station, DOME))) {
                        CURRENT.set(new Shot(player, station, request.getUUID("Target")));
                    }
                }
            }
            return (String)commands.getMethod("action", ServerPlayer.class, CompoundTag.class,
                    String.class, CompoundTag.class).invoke(null, player, session, action, request);
        } catch (InvocationTargetException failure) {
            throw nativeFailure(failure);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Pinned native command-defense API is unavailable", failure);
        } finally {
            restore(previous);
        }
    }

    private static boolean humanStation(ServerPlayer player, Entity station, String type) {
        return HumanPlayers.isHuman(player) && player.isAlive() && station.isAlive()
                && player.level() == station.level() && type.equals(station.getClass().getName())
                && player.getUUID().equals(nativeOwner(station));
    }

    private static RuntimeException nativeFailure(InvocationTargetException failure) {
        Throwable cause = failure.getCause();
        if (cause instanceof RuntimeException runtime) return runtime;
        if (cause instanceof Error error) throw error;
        return new IllegalStateException("Native manual-defense call failed", cause);
    }

    private static void restore(Shot previous) {
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }

    public static boolean friendly(Object contact) {
        Shot shot = CURRENT.get();
        if (shot != null && HumanPlayers.isHuman(shot.player()) && shot.player().isAlive()
                && shot.station().isAlive() && shot.player().level() == shot.station().level()
                && shot.target().equals(call(contact, "id"))) return false;
        return !Boolean.FALSE.equals(call(contact, "friendly"));
    }

    public static boolean addPatriot(ServerLevel level, Entity projectile) {
        Shot shot = CURRENT.get();
        if (shot != null && shot.player().level() == level && projectile.level() == level
                && INTERCEPTOR.equals(projectile.getClass().getName())
                && shot.station().getUUID().equals(call(projectile, "defenseSource"))
                && shot.target().equals(call(projectile, "targetId"))) {
            CompoundTag data = projectile.getPersistentData();
            data.putUUID(OWNER, shot.player().getUUID());
            data.putUUID(SOURCE, shot.station().getUUID());
            data.putUUID(TARGET, shot.target());
        }
        return level.addFreshEntity(projectile);
    }

    /** Null means this is an ordinary NPC/native projectile and the original policy must run. */
    public static Boolean validateMarkedPatriot(ServerLevel level, Entity projectile) {
        CompoundTag data = projectile.getPersistentData();
        if (!data.hasUUID(OWNER)) return null;
        if (!INTERCEPTOR.equals(projectile.getClass().getName()) || !data.hasUUID(SOURCE) || !data.hasUUID(TARGET)
                || !data.getUUID(SOURCE).equals(call(projectile, "defenseSource"))
                || !data.getUUID(TARGET).equals(call(projectile, "targetId"))) return false;
        Entity station = level.getEntity(data.getUUID(SOURCE));
        Entity target = level.getEntity(data.getUUID(TARGET));
        if (station == null || target == null || !station.isAlive() || !target.isAlive()
                || station.level() != level || target.level() != level
                || !data.getUUID(OWNER).equals(nativeOwner(station))) return false;
        if (DOME.equals(station.getClass().getName())) {
            return level.hasChunkAt(station.blockPosition()) && level.hasChunkAt(target.blockPosition());
        }
        if (!PATRIOT.equals(station.getClass().getName())) return false;
        try {
            Class<?> policy = Class.forName("dev.frontier.industry.combat.NativeCombatEvents", false,
                    station.getClass().getClassLoader());
            Method validation = policy.getDeclaredMethod("manualError", ServerLevel.class, Entity.class,
                    Entity.class, UUID.class);
            validation.setAccessible(true);
            // A separate scope survives only for this persisted, exactly attributed human shot.
            Validation previous = VALIDATING.get();
            VALIDATING.set(new Validation(level, station, target, data.getUUID(OWNER)));
            try {
                // Keep original own-station, loaded-target, own-linked-radar and connection checks.
                return "".equals(validation.invoke(null, level, station, target, data.getUUID(OWNER)));
            } finally {
                if (previous == null) VALIDATING.remove(); else VALIDATING.set(previous);
            }
        } catch (ReflectiveOperationException failure) {
            return false;
        }
    }

    /** No scope means the exact original NPC diplomacy decision, including a human seated nearby. */
    public static boolean manualHostile(ServerLevel level, Entity station, UUID owner, Entity target) {
        Validation validation = VALIDATING.get();
        if (validation != null && validation.level() == level && validation.station() == station
                && validation.target() == target && validation.owner().equals(owner)) return true;
        Shot shot = CURRENT.get();
        if (shot != null && shot.station() == station && shot.target().equals(target.getUUID())
                && shot.player().getUUID().equals(owner) && shot.player().level() == level
                && HumanPlayers.isHuman(shot.player()) && shot.player().isAlive()
                && owner.equals(nativeOwner(station))) return true;
        try {
            Class<?> policy = Class.forName("dev.frontier.industry.combat.CombatOwnership", false,
                    HumanManualDefense.class.getClassLoader());
            Method hostile = policy.getDeclaredMethod("hostile", ServerLevel.class, UUID.class, Entity.class);
            hostile.setAccessible(true);
            return Boolean.TRUE.equals(hostile.invoke(null, level, owner, target));
        } catch (ReflectiveOperationException unavailable) {
            return false;
        }
    }

    private static UUID nativeOwner(Entity station) {
        try {
            Class<?> policy = Class.forName("dev.frontier.industry.combat.ReadyWeapons", false,
                    station.getClass().getClassLoader());
            Method owner = policy.getDeclaredMethod("nativeOwner", Entity.class);
            owner.setAccessible(true);
            Object value = owner.invoke(null, station);
            return value instanceof UUID id ? id : null;
        } catch (ClassNotFoundException absent) {
            CompoundTag data = station.getPersistentData();
            if (data.hasUUID("DeepStrikeOwner")) return data.getUUID("DeepStrikeOwner");
            Object driver = call(station, "getLastDriverUUID");
            try { return driver instanceof String text && !text.isEmpty() ? UUID.fromString(text) : null; }
            catch (IllegalArgumentException invalid) { return null; }
        } catch (ReflectiveOperationException unavailable) {
            return null;
        }
    }

    private static Object call(Object receiver, String name) {
        try { return receiver.getClass().getMethod(name).invoke(receiver); }
        catch (ReflectiveOperationException unavailable) { return null; }
    }
}
