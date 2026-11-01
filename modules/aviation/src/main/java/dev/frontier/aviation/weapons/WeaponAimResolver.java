package dev.frontier.aviation.weapons;

import java.util.Optional;
import java.util.HashSet;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;

/**
 * Shared reticle and muzzle geometry for player and non-player weapon callers.
 *
 * <p>The caller supplies the native weapon muzzle and the reticle direction.
 * This class does not choose a lead solution or a native target UUID. It only
 * resolves the first server-visible block or entity along that ray.</p>
 */
public final class WeaponAimResolver {
    private static final double MAXIMUM_RANGE = 8192, SEGMENT_LENGTH = 8;
    private static final int MAXIMUM_CANDIDATES = 512;
    private WeaponAimResolver() {
    }

    public static double range(WeaponMount mount, GunData gun) {
        double nativeRange = gun.get(GunProp.SEEK_WEAPON_INFO).getSeekRange();
        return Double.isFinite(nativeRange) && nativeRange > 0
            ? Math.min(mount.maximumRange(), nativeRange) : mount.maximumRange();
    }

    public static double sector(GunData gun) {
        double angle = gun.get(GunProp.SEEK_WEAPON_INFO).getSeekAngle();
        return Double.isFinite(angle) && angle > 0 ? Math.min(180, angle) : 20;
    }

    public static Optional<WeaponAimResult> resolve(
            Level level, Entity shooter, Vec3 muzzle, Vec3 reticleDirection, double maximumRange) {
        if (level == null || shooter == null || !finite(muzzle) || !finite(reticleDirection)
                || shooter.level() != level || !Double.isFinite(maximumRange)
                || maximumRange <= 0.0 || maximumRange > MAXIMUM_RANGE) {
            return Optional.empty();
        }
        double directionLengthSquared = reticleDirection.lengthSqr();
        if (!Double.isFinite(directionLengthSquared) || directionLengthSquared < 1.0e-12) {
            return Optional.empty();
        }

        Vec3 direction = reticleDirection.scale(1.0 / Math.sqrt(directionLengthSquared));
        Vec3 rayEnd = muzzle.add(direction.scale(maximumRange));
        if (!finite(rayEnd)) return Optional.empty();
        var candidates = new HashSet<UUID>();
        var root = shooter.getRootVehicle();
        Vec3 from = muzzle;
        for (double travelled = 0; travelled < maximumRange;) {
            double nextDistance = Math.min(maximumRange, travelled + SEGMENT_LENGTH);
            Vec3 to = muzzle.add(direction.scale(nextDistance));
            if (!loadedSegment(level, from, to)) return Optional.empty();
            var blockHit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, shooter));
            boolean blocked = blockHit.getType() == HitResult.Type.BLOCK;
            Vec3 visibleEnd = blocked ? blockHit.getLocation() : to;
            double nearestDistance = from.distanceToSqr(visibleEnd);
            WeaponAimResult nearest = blocked ? WeaponAimResult.block(visibleEnd) : null;
            // Small boxes along the ray avoid an O(range^2) entity-section scan
            // on diagonal views. A candidate budget also bounds crowded scenes.
            for (Entity candidate : level.getEntities(shooter, new AABB(from, to).inflate(.3),
                    entity -> entity.isAlive() && entity.isPickable() && !entity.isSpectator()
                        && entity.getRootVehicle() != root)) {
                candidates.add(candidate.getUUID());
                if (candidates.size() > MAXIMUM_CANDIDATES) return Optional.empty();
                var bounds = candidate.getBoundingBox().inflate(.3);
                var hit = bounds.contains(from) ? Optional.of(from) : bounds.clip(from, visibleEnd);
                if (hit.isPresent() && (from.distanceToSqr(hit.get()) < nearestDistance
                        || nearest == null && from.distanceToSqr(hit.get()) == nearestDistance)) {
                    nearestDistance = from.distanceToSqr(hit.get());
                    nearest = WeaponAimResult.entity(hit.get(), candidate.getUUID());
                }
            }
            if (nearest != null) return Optional.of(nearest);
            from = to;
            travelled = nextDistance;
        }
        return Optional.of(WeaponAimResult.space(rayEnd));
    }

    /**
     * A sector/range check only. World visibility is verified separately with
     * validateProposal; a finite point in front of the aircraft is not a hit.
     */
    public static boolean canResolvePoint(
            Vec3 muzzle, Vec3 point, Vec3 reticleDirection, double maximumRange, double maximumAngleDegrees) {
        if (!finite(muzzle) || !finite(point) || !finite(reticleDirection)
                || !Double.isFinite(maximumRange) || maximumRange <= 0.0 || maximumRange > MAXIMUM_RANGE
                || !Double.isFinite(maximumAngleDegrees) || maximumAngleDegrees <= 0 || maximumAngleDegrees > 180) {
            return false;
        }
        Vec3 offset = point.subtract(muzzle);
        double distanceSquared = offset.lengthSqr();
        double directionLengthSquared = reticleDirection.lengthSqr();
        if (!Double.isFinite(distanceSquared) || !Double.isFinite(directionLengthSquared)
                || distanceSquared < 1.0e-12 || distanceSquared > maximumRange * maximumRange
                || directionLengthSquared < 1.0e-12) {
            return false;
        }
        double cosine = offset.dot(reticleDirection)
                / Math.sqrt(distanceSquared * directionLengthSquared);
        return Double.isFinite(cosine) && cosine >= Math.cos(Math.toRadians(maximumAngleDegrees));
    }

    public static Optional<WeaponAimResult> validateProposal(Level level, Entity shooter, Vec3 muzzle,
            Vec3 reticleDirection, Vec3 proposed, double maximumRange, double maximumAngleDegrees) {
        if (!canResolvePoint(muzzle, proposed, reticleDirection, maximumRange, maximumAngleDegrees))
            return Optional.empty();
        double distance = muzzle.distanceTo(proposed);
        var resolved = resolve(level, shooter, muzzle, proposed.subtract(muzzle), Math.min(maximumRange, distance + .75));
        if (resolved.isEmpty()) return Optional.empty();
        if (resolved.get().kind() == WeaponAimResult.Kind.SPACE) return Optional.of(WeaponAimResult.space(proposed));
        return resolved.get().point().distanceToSqr(proposed) <= .75 * .75 ? resolved : Optional.empty();
    }

    private static boolean loadedSegment(Level level, Vec3 from, Vec3 to) {
        BlockPos a = BlockPos.containing(from), b = BlockPos.containing(to);
        if (!level.getWorldBorder().isWithinBounds(a) || !level.getWorldBorder().isWithinBounds(b)) return false;
        // A segment is at most eight blocks and spans at most four chunk columns.
        for (int x = Math.min(a.getX(), b.getX()) >> 4; x <= (Math.max(a.getX(), b.getX()) >> 4); x++)
            for (int z = Math.min(a.getZ(), b.getZ()) >> 4; z <= (Math.max(a.getZ(), b.getZ()) >> 4); z++)
                if (!level.hasChunk(x, z)) return false;
        return true;
    }

    public static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.x)
                && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
}
