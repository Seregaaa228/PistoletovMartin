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
    public static HitResult trace(Entity drone, Vec3 from, Vec3 to, Predicate<Entity> target) {
        if (!WarheadRules.sweepLength(from.distanceToSqr(to)) || !loadedPath(drone, from, to)) return null;
        HitResult closest = drone.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, drone));
        Vec3 end = closest.getType() == HitResult.Type.BLOCK ? closest.getLocation() : to;
        double distance = from.distanceToSqr(end);
        AABB ordinaryPath = new AABB(from, end).inflate(.25);
        for (Entity entity : drone.level().getEntities(drone, new AABB(from, end).inflate(MAX_VEHICLE_REACH),
                entity -> (entity instanceof OBBEntity || entity.getBoundingBox().intersects(ordinaryPath))
                        && target.test(entity))) {
            Vec3 point = null;
            if (entity instanceof OBBEntity obb && !obb.getOBBs().isEmpty()) {
                for (var part : obb.getOBBs()) {
                    if (part.contains(from)) { point = from; break; }
                    var hit = part.inflate(.15).clip(new Vector3d(from.x, from.y, from.z), new Vector3d(end.x, end.y, end.z));
                    if (hit.isPresent()) {
                        var p = hit.get(); Vec3 candidate = new Vec3(p.x, p.y, p.z);
                        if (point == null || from.distanceToSqr(candidate) < from.distanceToSqr(point)) point = candidate;
                    }
                }
            } else {
                var box = entity.getBoundingBox().inflate(.15);
                point = box.contains(from) ? from : box.clip(from, end).orElse(null);
            }
            if (point != null && from.distanceToSqr(point) <= distance) {
                closest = new EntityHitResult(entity, point); distance = from.distanceToSqr(point);
            }
        }
        return closest.getType() == HitResult.Type.MISS ? null : closest;
    }
}
