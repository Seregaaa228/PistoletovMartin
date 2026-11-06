package dev.frontier.flamingofix;

import dev.sbw.deepstrike.game.ActiveFlights;
import dev.sbw.deepstrike.game.PatriotInterceptorEntity;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * Engagement bookkeeping for the accepted native air-defense loop
 * ({@code dev.frontier.industry.combat.NativeCombatEvents}, shipped as an exact preserved
 * .class) and DeepStrike's Iron Dome coordinator. Both are patched only from outside by
 * mixins; this class holds the shared logic. Server thread only.
 */
public final class NativeDefenseEngagements {
    private static final String GAME = "dev.sbw.deepstrike.";
    private static final Set<String> ROSTER_TYPES = Set.of(
            GAME + "game.PatriotInterceptorEntity",
            GAME + "vehicle.PatriotTruckEntity",
            GAME + "vehicle.IronDomeEntity",
            GAME + "vehicle.AnMpq65Entity");

    // Only the entity types NativeCombatEvents.tick() acts on, per level, in join order.
    private static final Map<ServerLevel, LinkedHashSet<Entity>> ROSTER = new HashMap<>();
    private static MinecraftServer rosterServer;

    private static Method nativeOwner, sameTeam;
    private static boolean ownershipResolved;
    private static Field engagementInterceptors;
    private static boolean engagementResolved;

    private NativeDefenseEngagements() { }

    /** Called from NativeCombatEvents.join (EntityJoinLevelEvent, HIGHEST) for every join. */
    public static void track(Entity entity, ServerLevel level) {
        if (!ROSTER_TYPES.contains(entity.getClass().getName())) return;
        roster(level).add(entity);
    }

    /** Replaces "copy every loaded entity": supplies only roster entities still in this level. */
    public static void feed(ServerLevel level, Consumer<? super Entity> sink) {
        LinkedHashSet<Entity> roster = roster(level);
        // Removed/unloaded entities, and joins another listener cancelled, are never in the level.
        roster.removeIf(entity -> entity.isRemoved() || !entity.isAddedToWorld() || entity.level() != level);
        for (Entity entity : new ArrayList<>(roster)) sink.accept(entity);
    }

    private static LinkedHashSet<Entity> roster(ServerLevel level) {
        MinecraftServer server = level.getServer();
        if (rosterServer != server) { ROSTER.clear(); rosterServer = server; }
        return ROSTER.computeIfAbsent(level, ignored -> new LinkedHashSet<>());
    }

    /**
     * Owner-aware replacement for NativeCombatEvents.engaged(level, target): only a live
     * interceptor launched by this station or by a station of the same side counts as
     * "already engaged". A foreign interceptor (e.g. an enemy hand-firing at its own jet)
     * must not make this side hold fire.
     */
    public static boolean engagedBySide(ServerLevel level, UUID target, Entity station) {
        for (Entity entity : roster(level)) {
            if (entity instanceof PatriotInterceptorEntity interceptor && interceptor.isAlive()
                    && target.equals(interceptor.targetId()) && sameSide(level, station, interceptor)) return true;
        }
        return false;
    }

    /**
     * Whether DeepStrike's per-target Iron Dome engagement record still blocks a new manual
     * shot from {@code battery}. The record is only cleared when the target dies, and the
     * native follow-up wave needs automatic defense that NativeCombatEvents switches off, so
     * after a miss the record would block every later shot at that target forever. It blocks
     * only while one of its interceptors is still flying for this side.
     */
    public static boolean blocksManualShot(ServerLevel level, Entity battery, Object engagement) {
        if (engagement == null) return false;
        List<?> interceptors = interceptors(engagement);
        if (interceptors == null) return true; // Unknown layout: keep the native decision.
        ActiveFlights flights = null;
        for (Object raw : interceptors) {
            if (!(raw instanceof UUID id)) continue;
            Entity entity = level.getEntity(id);
            if (entity == null) {
                if (flights == null) flights = ActiveFlights.get(level);
                if (flights.hasActiveFlight(id)) return true; // In flight but unloaded: owner unknown.
                continue;
            }
            if (!entity.isAlive()) continue;
            if (entity instanceof PatriotInterceptorEntity interceptor && !sameSide(level, battery, interceptor)) continue;
            return true;
        }
        return false;
    }

    static boolean sameSide(ServerLevel level, Entity station, PatriotInterceptorEntity interceptor) {
        UUID sourceId = interceptor.defenseSource();
        if (sourceId == null) return false;
        if (sourceId.equals(station.getUUID())) return true;
        Entity source = level.getEntity(sourceId);
        if (source == null) return false; // NativeCombatEvents discards interceptors of unloaded stations.
        UUID mine = owner(station), theirs = owner(source);
        if (mine == null || theirs == null) return false;
        if (mine.equals(theirs)) return true;
        resolveOwnership();
        if (sameTeam == null) return false;
        try {
            return Boolean.TRUE.equals(sameTeam.invoke(null, level, mine, theirs));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return false;
        }
    }

    private static UUID owner(Entity station) {
        resolveOwnership();
        if (nativeOwner != null) {
            try {
                Object value = nativeOwner.invoke(null, station);
                return value instanceof UUID id ? id : null;
            } catch (ReflectiveOperationException | RuntimeException failure) {
                // Fall through to the native tag.
            }
        }
        CompoundTag data = station.getPersistentData();
        return data.hasUUID("DeepStrikeOwner") ? data.getUUID("DeepStrikeOwner") : null;
    }

    private static void resolveOwnership() {
        if (ownershipResolved) return;
        ownershipResolved = true;
        ClassLoader loader = NativeDefenseEngagements.class.getClassLoader();
        try {
            Method owner = Class.forName("dev.frontier.industry.combat.ReadyWeapons", false, loader)
                    .getDeclaredMethod("nativeOwner", Entity.class);
            owner.setAccessible(true);
            nativeOwner = owner;
        } catch (ReflectiveOperationException | RuntimeException absent) {
            nativeOwner = null;
        }
        try {
            // Existing cached IFF verdict owned by CombatOwnership (20-tick relation cache).
            Method team = Class.forName("dev.frontier.industry.combat.CombatOwnership", false, loader)
                    .getDeclaredMethod("sameTeamCached", ServerLevel.class, UUID.class, UUID.class);
            team.setAccessible(true);
            sameTeam = team;
        } catch (ReflectiveOperationException | RuntimeException absent) {
            sameTeam = null;
        }
    }

    private static List<?> interceptors(Object engagement) {
        if (!engagementResolved) {
            engagementResolved = true;
            try {
                Field field = engagement.getClass().getDeclaredField("interceptors");
                field.setAccessible(true);
                engagementInterceptors = field;
            } catch (ReflectiveOperationException | RuntimeException absent) {
                engagementInterceptors = null;
            }
        }
        if (engagementInterceptors == null) return null;
        try {
            return engagementInterceptors.get(engagement) instanceof List<?> list ? list : null;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }
}
