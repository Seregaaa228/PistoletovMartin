package dev.frontier.arcade.airdefense;

import java.lang.reflect.Method;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Friend or foe for automatic fire: the pack's single rule, Industry's {@code CombatOwnership}
 * (same owner, scoreboard team, country, alliance or treaty is never a target; a target needs our
 * country's explicit ENEMY order). Arcade does not compile against Industry, so the call is
 * reflective and fails closed: if the rule is unavailable, automatic fire holds.
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

    /** Hostile to the site's owner: the only targets automatic fire may choose. */
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
        resolve();
        Method method = owner;
        if (method == null) return null;
        try {
            return method.invoke(null, entity) instanceof UUID id ? id : null;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    /** Same owner, team, country or ally: drawn green on the scope and never engaged automatically. */
    public static boolean friendly(ServerLevel level, UUID siteOwner, Entity target) {
        UUID other = ownerOf(target);
        if (siteOwner == null || other == null) return false;
        if (siteOwner.equals(other)) return true;
        Method method = friends;
        if (method == null) return false;
        try {
            return Boolean.TRUE.equals(method.invoke(null, level, siteOwner, other));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return false;
        }
    }
}
