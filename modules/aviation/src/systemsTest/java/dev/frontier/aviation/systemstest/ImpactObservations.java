package dev.frontier.aviation.systemstest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

/** Test-only traces of real native projectiles and their native block contacts. */
public final class ImpactObservations {
    static final class Trace {
        final Entity entity;
        final UUID owner;
        final String type;
        final Vec3 spawn;
        final Vec3 initialVelocity;
        final long born;
        final Vec3 expected;
        Vec3 hit;
        UUID hitEntity;
        double maximumY;
        boolean direct;
        Trace(Entity entity, UUID owner, String type) {
            this.entity = entity; this.owner = owner; this.type = type;
            spawn = entity.position(); maximumY = spawn.y;
            initialVelocity = entity.getDeltaMovement(); born = entity.level().getGameTime();
            expected = EXPECTED.get(owner);
        }
    }
    private static final Map<UUID, Trace> TRACES = new LinkedHashMap<>();
    private static final Map<UUID, Vec3> EXPECTED = new LinkedHashMap<>();
    private ImpactObservations() {}

    static void spawned(Entity entity) {
        if (!(entity instanceof Projectile projectile) || entity.getServer() == null
                || !FreshSystemsProfile.authorized(entity.getServer())) return;
        var type = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        if (type == null || (!type.toString().equals("superbwarfare:agm_65")
                && !type.toString().equals("superbwarfare:wire_guide_missile")
                && !type.toString().equals("superbwarfare:small_cannon_shell"))) return;
        var owner = projectile.getOwner();
        if (owner == null) return;
        if (TRACES.size() >= 256) throw new IllegalStateException("fixture projectile trace budget exceeded");
        TRACES.put(entity.getUUID(), new Trace(entity, owner.getUUID(), type.toString()));
    }

    public static void impact(Entity projectile, BlockHitResult hit) {
        Trace trace = TRACES.get(projectile.getUUID());
        if (trace != null && trace.hit == null) trace.hit = hit.getLocation();
    }

    public static void impactEntity(Entity projectile, EntityHitResult hit) {
        Trace trace = TRACES.get(projectile.getUUID());
        if (trace != null) trace.hitEntity = hit.getEntity().getUUID();
    }

    static void sample() {
        for (Trace trace : TRACES.values()) {
            trace.maximumY = Math.max(trace.maximumY, trace.entity.getY());
            trace.direct |= trace.entity.getPersistentData().getBoolean("frontierAircraftDirectAim");
        }
    }

    static List<Trace> owned(UUID owner, UUID vehicle) {
        return TRACES.values().stream().filter(trace -> owner.equals(trace.owner) || vehicle.equals(trace.owner)).toList();
    }
    static void expect(UUID owner, UUID vehicle, Vec3 point) { EXPECTED.put(owner, point); EXPECTED.put(vehicle, point); }
    static void clear() { TRACES.clear(); EXPECTED.clear(); }
}
