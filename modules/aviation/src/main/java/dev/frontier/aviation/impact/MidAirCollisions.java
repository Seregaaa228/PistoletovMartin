package dev.frontier.aviation.impact;

import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.init.ModDamageTypes;
import com.atsuishio.superbwarfare.init.ModSounds;
import com.atsuishio.superbwarfare.tools.OBB;
import dev.frontier.aviation.LightAviation;
import dev.frontier.aviation.aircraft.AircraftCatalog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Server-side swept airframe-vs-vehicle collisions for aircraft.
 *
 * Native vehicles with part OBBs are not hard-collidable ({@code canBeCollidedWith} is
 * {@code enableAABB()}, false for OBB vehicles), so vanilla movement lets two jets pass
 * through each other. The only native vehicle-vs-vehicle damage is
 * {@code VehicleMotionUtils.crushEntities}, run every fourth tick on an unswept end
 * position: at several blocks per tick two jets cross each other between samples.
 *
 * After every entity of the level has moved, each tracked aircraft sweeps its native OBBs
 * against nearby vehicles along the relative motion of the tick. A contact damages both
 * bodies by relative speed through the native vehicle-strike damage (native modifiers,
 * wreck transition and {@code AircraftTerminalDestruction} stay in charge of deaths).
 */
@Mod.EventBusSubscriber(modid = LightAviation.ID)
public final class MidAirCollisions {
    /** A parked aircraft does not initiate; anything moving into it still finds it. */
    private static final double PARKED = .05;
    private static final Map<ServerLevel, State> LEVELS = new WeakHashMap<>();
    public static volatile long pairChecks;
    public static volatile long strikes;

    private static final class State {
        final Set<VehicleEntity> aircraft = Collections.newSetFromMap(new IdentityHashMap<>());
        final Map<VehicleEntity, Vec3> last = new IdentityHashMap<>();
        final Map<Long, Long> cooldown = new HashMap<>();
        final Set<Long> checked = new HashSet<>();
        final Map<VehicleEntity, Body> bodies = new IdentityHashMap<>();
    }

    private record Body(List<SweptAircraftBox.Box> boxes, AABB bounds, Vec3 motion, boolean grounded, float mass) { }

    private MidAirCollisions() { }

    public static boolean isAircraft(VehicleEntity vehicle) {
        var id = EntityType.getKey(vehicle.getType());
        if (id != null && AircraftCatalog.find(id.toString()).isPresent()) return true;
        try {
            var type = vehicle.getVehicleType();
            return type == VehicleType.AIRPLANE || type == VehicleType.HELICOPTER;
        } catch (RuntimeException unavailableData) {
            return false;
        }
    }

    /** Lowest priority: an addition another mod cancels never becomes a tracked ghost. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void joined(EntityJoinLevelEvent event) {
        if (event.isCanceled()) return;
        if (event.getLevel() instanceof ServerLevel level && event.getEntity() instanceof VehicleEntity vehicle
                && isAircraft(vehicle)) {
            LEVELS.computeIfAbsent(level, ignored -> new State()).aircraft.add(vehicle);
        }
    }

    @SubscribeEvent
    public static void left(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof VehicleEntity vehicle)) return;
        var state = LEVELS.get(level);
        if (state == null) return;
        state.aircraft.remove(vehicle);
        state.last.remove(vehicle);
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        LEVELS.clear();
    }

    @SubscribeEvent
    public static void tick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;
        var state = LEVELS.get(level);
        if (state == null || state.aircraft.isEmpty()) return;
        long now = level.getGameTime();
        if ((now & 63) == 0) state.cooldown.values().removeIf(until -> until <= now);
        state.checked.clear();
        state.bodies.clear();
        var aircraft = new ArrayList<>(state.aircraft);
        try {
            for (var plane : aircraft) {
                if (plane.isRemoved()) {
                    state.aircraft.remove(plane);
                    state.last.remove(plane);
                    continue;
                }
                if (!active(level, plane)) continue;
                Vec3 motion = motion(state, plane);
                if (motion.lengthSqr() < PARKED * PARKED && plane.onGround()) continue;
                collide(level, state, plane, now);
            }
        } finally {
            for (var plane : aircraft) if (!plane.isRemoved()) state.last.put(plane, plane.position());
            state.bodies.clear();
        }
    }

    private static boolean active(ServerLevel level, VehicleEntity vehicle) {
        return !vehicle.isRemoved() && !vehicle.isWreck() && vehicle.getHealth() > 0
                && level.isPositionEntityTicking(vehicle.blockPosition());
    }

    private static void collide(ServerLevel level, State state, VehicleEntity plane, long now) {
        Body own = body(state, plane);
        AABB search = own.bounds().expandTowards(own.motion().scale(-1)).inflate(MidAirCollisionRules.PARTNER_REACH);
        for (var other : level.getEntitiesOfClass(VehicleEntity.class, search, other -> other != plane)) {
            long key = MidAirCollisionRules.pairKey(plane.getId(), other.getId());
            if (!state.checked.add(key)) continue;
            Long until = state.cooldown.get(key);
            if (until != null && until > now) continue;
            if (!active(level, other) || plane.getRootVehicle() == other.getRootVehicle()) continue;
            Body partner = body(state, other);
            if (MidAirCollisionRules.exempt(own.motion().length(), own.grounded(),
                    partner.motion().length(), partner.grounded())) continue;
            Vec3 relative = own.motion().subtract(partner.motion());
            // Impact never exceeds relative speed: skip the geometry when it cannot hurt.
            if (relative.length() <= MidAirCollisionRules.SAFE_IMPACT) continue;
            if (!own.bounds().expandTowards(relative.scale(-1)).intersects(partner.bounds())) continue;
            pairChecks++;
            var hit = earliest(own, partner, relative);
            if (hit != null) strike(level, state, key, now, plane, own, other, partner, relative, hit);
        }
    }

    private static SweptAircraftBox.Hit earliest(Body own, Body partner, Vec3 relative) {
        SweptAircraftBox.Hit earliest = null;
        for (var a : own.boxes()) {
            for (var b : partner.boxes()) {
                var hit = SweptAircraftPair.contact(a, b, -relative.x, -relative.y, -relative.z,
                        relative.x, relative.y, relative.z);
                if (hit != null && (earliest == null || hit.time() < earliest.time())) earliest = hit;
            }
        }
        return earliest;
    }

    private static void strike(ServerLevel level, State state, long key, long now, VehicleEntity plane, Body own,
            VehicleEntity other, Body partner, Vec3 relative, SweptAircraftBox.Hit hit) {
        double approach = -(relative.x * hit.nx() + relative.y * hit.ny() + relative.z * hit.nz());
        if (!MidAirCollisionRules.newContact(hit.time(), approach)) return;
        double impact = MidAirCollisionRules.impact(relative.length(), approach);
        float toPlane = MidAirCollisionRules.damage(impact, own.mass(), partner.mass());
        float toOther = MidAirCollisionRules.damage(impact, partner.mass(), own.mass());
        if (toPlane <= 0 && toOther <= 0) return;
        boolean hurt = hurt(level, plane, other, toPlane);
        hurt |= hurt(level, other, plane, toOther);
        if (!hurt) return;
        strikes++;
        state.cooldown.put(key, now + MidAirCollisionRules.PAIR_COOLDOWN_TICKS);
        level.playSound(null, plane, ModSounds.VEHICLE_STRIKE.get(), plane.getSoundSource(), 1, 1);
        // Survivors are pushed apart along the contact normal (from the partner toward the plane).
        double impulse = MidAirCollisionRules.impulse(approach, own.mass(), partner.mass());
        if (impulse <= 0) return;
        Vec3 normal = new Vec3(hit.nx(), hit.ny(), hit.nz());
        if (plane.getHealth() > 0) plane.setDeltaMovement(plane.getDeltaMovement()
                .add(normal.scale(impulse / MidAirCollisionRules.mass(own.mass()))));
        if (other.getHealth() > 0) other.setDeltaMovement(other.getDeltaMovement()
                .add(normal.scale(-impulse / MidAirCollisionRules.mass(partner.mass()))));
    }

    /** Native vehicle-strike damage credited to the partner's crew, or to the partner itself. */
    private static boolean hurt(ServerLevel level, VehicleEntity target, VehicleEntity other, float amount) {
        if (amount <= 0 || target.isRemoved() || target.getHealth() <= 0) return false;
        Entity crew = other.getFirstPassenger();
        Entity attacker = crew == null ? other : crew;
        float health = target.getHealth();
        target.hurt(ModDamageTypes.causeVehicleStrikeDamage(level.registryAccess(), other, attacker), amount);
        // Native hurt rejects damage between team-mates without friendly fire; a physical
        // collision still breaks both airframes, so retry without crew attribution.
        if (target.getHealth() >= health && attacker != other && target.getHealth() > 0) {
            target.hurt(ModDamageTypes.causeVehicleStrikeDamage(level.registryAccess(), other, other), amount);
        }
        return target.getHealth() < health;
    }

    private static Vec3 motion(State state, VehicleEntity vehicle) {
        Vec3 previous = state.last.get(vehicle);
        if (previous == null) previous = new Vec3(vehicle.xo, vehicle.yo, vehicle.zo);
        Vec3 motion = vehicle.position().subtract(previous);
        return MidAirCollisionRules.sweepable(motion.length()) ? motion : Vec3.ZERO;
    }

    private static Body body(State state, VehicleEntity vehicle) {
        var cached = state.bodies.get(vehicle);
        if (cached != null) return cached;
        Vec3 motion = state.aircraft.contains(vehicle) ? motion(state, vehicle) : motionFromOld(vehicle);
        // Test the final attitude of this tick, after every native and flight adjustment.
        vehicle.updateOBB();
        var boxes = new ArrayList<SweptAircraftBox.Box>();
        AABB bounds = null;
        for (var nativeBox : vehicle.getOBBs()) {
            var center = nativeBox.center;
            var extent = nativeBox.extents();
            var axes = nativeBox.getAxes();
            boxes.add(new SweptAircraftBox.Box(center.x, center.y, center.z, extent.x, extent.y, extent.z,
                    new double[] {axes[0].x, axes[0].y, axes[0].z, axes[1].x, axes[1].y, axes[1].z,
                            axes[2].x, axes[2].y, axes[2].z}));
            AABB world = OBB.getWorldAABB(nativeBox);
            bounds = bounds == null ? world : bounds.minmax(world);
        }
        if (boxes.isEmpty()) {
            // Vehicles without part OBBs collide with their ordinary entity box.
            AABB box = vehicle.getBoundingBox();
            Vec3 center = box.getCenter();
            boxes.add(new SweptAircraftBox.Box(center.x, center.y, center.z, box.getXsize() / 2,
                    box.getYsize() / 2, box.getZsize() / 2, new double[] {1, 0, 0, 0, 1, 0, 0, 0, 1}));
            bounds = box;
        }
        var body = new Body(List.copyOf(boxes), bounds, motion, vehicle.onGround(), vehicle.getMass());
        state.bodies.put(vehicle, body);
        return body;
    }

    private static Vec3 motionFromOld(VehicleEntity vehicle) {
        Vec3 motion = vehicle.position().subtract(vehicle.xo, vehicle.yo, vehicle.zo);
        return MidAirCollisionRules.sweepable(motion.length()) ? motion : Vec3.ZERO;
    }
}
