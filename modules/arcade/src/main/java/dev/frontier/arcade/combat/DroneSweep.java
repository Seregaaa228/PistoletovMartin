package dev.frontier.arcade.combat;

import com.atsuishio.superbwarfare.entity.OBBEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import java.util.function.Predicate;

/** Nearest server hit along one bounded segment, including SBW's rotated vehicle parts. */
public final class DroneSweep {
    // Conservative rotation-independent radius of every pinned SBW/DSM
    // vehicle OBB, including Kirov (largest authored radius: 41.666 blocks).
    // The wider query only admits OBB entities; ordinary targets retain their
    // small path query. It reads the loaded entity index and never loads chunks.
    public static final double MAX_VEHICLE_REACH = 48;
    private DroneSweep() { }
    public static boolean loadedPath(Entity drone, Vec3 from, Vec3 to) {
        double distance = from.distanceToSqr(to);
        if (!Double.isFinite(distance) || distance > WarheadRules.MAX_SWEEP * WarheadRules.MAX_SWEEP) return false;
        int minX = (int)Math.floor(Math.min(from.x, to.x)) >> 4, maxX = (int)Math.floor(Math.max(from.x, to.x)) >> 4;
        int minZ = (int)Math.floor(Math.min(from.z, to.z)) >> 4, maxZ = (int)Math.floor(Math.max(from.z, to.z)) >> 4;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
            if (!drone.level().hasChunk(x, z)) return false;
        return true;
    }
    /** Each target is tested as a still snapshot of its current pose. */
    public static HitResult trace(Entity drone, Vec3 from, Vec3 to, Predicate<Entity> target) {
        return trace(drone, from, to, target, false);
    }
    /**
     * @param targetFrame test each target in its own moving frame: the drone path starts offset by the
     * target's displacement over its last server tick and ends at the drone's end point, against the
     * target's current pose. Both snapshots of a jet (3-5 blocks a tick) can otherwise straddle a slow
     * drone, and a hovering drone, which has no own path, is then still struck by a passing vehicle.
     */
    public static HitResult trace(Entity drone, Vec3 from, Vec3 to, Predicate<Entity> target, boolean targetFrame) {
        double own = from.distanceToSqr(to);
        boolean moving = WarheadRules.sweepLength(own);
        if (!(moving || targetFrame && WarheadRules.hoverLength(own)) || !loadedPath(drone, from, to)) return null;
        HitResult closest = moving ? drone.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, drone)) : null;
        if (closest != null && closest.getType() != HitResult.Type.BLOCK) closest = null;
        Vec3 end = closest != null ? closest.getLocation() : to;
        double reach = MAX_VEHICLE_REACH + (targetFrame ? WarheadRules.MAX_TARGET_STEP : 0);
        AABB ordinaryPath = new AABB(from, end).inflate(.25);
        AABB ordinaryReach = targetFrame ? ordinaryPath.inflate(WarheadRules.MAX_TARGET_STEP) : ordinaryPath;
        double best = Double.POSITIVE_INFINITY; // fraction along each target's relative path; all end at `end`
        for (Entity entity : drone.level().getEntities(drone, new AABB(from, end).inflate(reach),
                entity -> (entity instanceof OBBEntity || moving && entity.getBoundingBox().intersects(ordinaryReach))
                        && target.test(entity))) {
            Vec3 start = targetFrame ? from.add(step(entity)) : from;
            double path = start.distanceToSqr(end);
            // A still drone keeps its old contact rules for people and slow vehicles.
            if (!moving && !(entity instanceof OBBEntity && WarheadRules.hoverContact(path))) continue;
            Vec3 point = null;
            if (entity instanceof OBBEntity obb && !obb.getOBBs().isEmpty()) {
                for (var part : obb.getOBBs()) {
                    if (part.contains(start)) { point = start; break; }
                    var hit = part.inflate(.15).clip(new Vector3d(start.x, start.y, start.z), new Vector3d(end.x, end.y, end.z));
                    if (hit.isPresent()) {
                        var p = hit.get(); Vec3 candidate = new Vec3(p.x, p.y, p.z);
                        if (point == null || start.distanceToSqr(candidate) < start.distanceToSqr(point)) point = candidate;
                    }
                }
            } else {
                var box = entity.getBoundingBox().inflate(.15);
                if (!box.intersects(new AABB(start, end).inflate(.25))) continue;
                point = box.contains(start) ? start : box.clip(start, end).orElse(null);
            }
            if (point == null) continue;
            double fraction = path > 1e-12 ? Math.sqrt(start.distanceToSqr(point) / path) : 0;
            if (fraction <= best) { closest = new EntityHitResult(entity, point); best = fraction; }
        }
        return closest;
    }
    /** The target's last tick of movement; teleports, placement and invalid motion stand still. */
    static Vec3 step(Entity entity) {
        double dx = entity.getX() - entity.xo, dy = entity.getY() - entity.yo, dz = entity.getZ() - entity.zo;
        return WarheadRules.targetStep(dx, dy, dz) ? new Vec3(dx, dy, dz) : Vec3.ZERO;
    }
}
