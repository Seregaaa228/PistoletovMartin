package dev.frontier.arcade.airdefense;

import java.lang.reflect.Method;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.scores.Team;

/**
 * Friend or foe for automatic fire, on the pack's single rule, Industry's {@code CombatOwnership}:
 * the same owner, scoreboard team, country, an ally or a treaty partner is a friend and is never
 * engaged; an enemy is a country our country has declared war on. Whether AUTO also fires at
 * neutrals is the server setting {@link AirDefenseConfig#AUTO_TARGETS}. Arcade does not compile
 * against Industry, so the calls are reflective; without Industry, owners and scoreboard teams
 * still decide friends, and "enemies only" holds fire.
 */
public final class SamIff {
    private static final String OWNERSHIP = "dev.frontier.industry.combat.CombatOwnership";
    private static volatile Method hostile, owner, friends;
    private static volatile boolean resolved;

    private SamIff() { }

    private static void resolve() {
        if (resolved) return;
        synchronized (SamIff.class) {
            if (resolved) return;
            try {
                Class<?> type = Class.forName(OWNERSHIP, false, SamIff.class.getClassLoader());
                Method h = type.getDeclaredMethod("hostileCached", ServerLevel.class, UUID.class, Entity.class);
                Method o = type.getDeclaredMethod("owner", Entity.class);
                Method f = type.getDeclaredMethod("sameTeamCached", ServerLevel.class, UUID.class, UUID.class);
                h.setAccessible(true);
                o.setAccessible(true);
                f.setAccessible(true);
                hostile = h;
                owner = o;
                friends = f;
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                hostile = null;
                owner = null;
                friends = null;
            }
            resolved = true;
        }
    }

    /**
     * Whether a site in AUTO may fire at this contact by itself. No owner: never. Living mobs
     * (phantoms) are left to the crew: a magazine of long-range rounds is not spent on them.
     */
    public static boolean autoTarget(ServerLevel level, UUID siteOwner, Entity target) {
        if (siteOwner == null || target == null || target instanceof Mob || friendly(level, siteOwner, target)) return false;
        return AirDefenseConfig.autoTargets() == AirDefenseConfig.AutoTargets.ALL_BUT_FRIENDS || hostile(level, siteOwner, target);
    }

    /** At war with the site's owner by Recruits diplomacy. */
    public static boolean hostile(ServerLevel level, UUID siteOwner, Entity target) {
        if (siteOwner == null || target == null) return false;
        resolve();
        Method method = hostile;
        if (method == null) return false;
        try {
            return Boolean.TRUE.equals(method.invoke(null, level, siteOwner, target));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return false;
        }
    }

    /** Owner of an entity by the same rule (pilot first), for the friendly/unknown colour on the scope. */
    public static UUID ownerOf(Entity entity) {
        if (entity instanceof Player player) return player.getUUID();
        // Bombs, missiles and shells belong to whoever fired them (a KAB from our own jet is ours).
        if (entity instanceof Projectile projectile && projectile.getOwner() != null && projectile.getOwner() != entity)
            return ownerOf(projectile.getOwner());
        resolve();
        Method method = owner;
        if (method == null) return fallbackOwner(entity);
        try {
            return method.invoke(null, entity) instanceof UUID id ? id : null;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return fallbackOwner(entity);
        }
    }

    /** Without Industry: whoever flies it, else the owner it was marked with. */
    private static UUID fallbackOwner(Entity entity) {
        if (entity.getControllingPassenger() instanceof Player pilot) return pilot.getUUID();
        var data = entity.getPersistentData();
        for (String key : new String[]{"frontierIndustryOwner", "frontierIndustryVehicleOwner", "DeepStrikeOwner"})
            if (data.hasUUID(key)) return data.getUUID(key);
        return droneController(entity);
    }

    private static final String SBW_DRONE = "com.atsuishio.superbwarfare.entity.vehicle.DroneEntity";
    private static volatile net.minecraft.network.syncher.EntityDataAccessor<?> droneController;
    private static volatile boolean droneResolved;

    /** SBW's remotely flown drone has no rider: the linking player's UUID is in its CONTROLLER data. */
    private static UUID droneController(Entity entity) {
        Class<?> drone = null;
        for (Class<?> type = entity.getClass(); type != null; type = type.getSuperclass())
            if (type.getName().equals(SBW_DRONE)) { drone = type; break; }
        if (drone == null) return null;
        if (!droneResolved) {
            try {
                if (drone.getField("CONTROLLER").get(null) instanceof net.minecraft.network.syncher.EntityDataAccessor<?> key) droneController = key;
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                droneController = null;
            }
            droneResolved = true;
        }
        if (droneController == null) return null;
        try {
            return entity.getEntityData().get(droneController) instanceof String id ? UUID.fromString(id) : null;
        } catch (RuntimeException unlinked) {
            return null;
        }
    }

    /** Same owner, team, country or ally: drawn green on the scope and never engaged automatically. */
    public static boolean friendly(ServerLevel level, UUID siteOwner, Entity target) {
        UUID other = ownerOf(target);
        if (siteOwner == null || other == null) return false;
        if (siteOwner.equals(other)) return true;
        Method method = friends;
        if (method != null) {
            try {
                return Boolean.TRUE.equals(method.invoke(null, level, siteOwner, other));
            } catch (ReflectiveOperationException | RuntimeException failure) {
                // fall through to the scoreboard
            }
        }
        // Without Industry: the same scoreboard team, online or not.
        Team a = team(level, siteOwner), b = team(level, other);
        return a != null && a.isAlliedTo(b);
    }

    /** A player's scoreboard team by UUID: online, else by the name in the profile cache. */
    private static Team team(ServerLevel level, UUID player) {
        var server = level.getServer();
        Player online = server.getPlayerList().getPlayer(player);
        if (online != null) return online.getTeam();
        var scoreboard = server.getScoreboard();
        var cache = server.getProfileCache();
        String name = cache == null ? null : cache.get(player).map(com.mojang.authlib.GameProfile::getName).orElse(null);
        Team team = name == null ? null : scoreboard.getPlayersTeam(name);
        return team != null ? team : scoreboard.getPlayersTeam(player.toString());
    }
}
