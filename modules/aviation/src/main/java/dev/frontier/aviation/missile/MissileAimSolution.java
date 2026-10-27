package dev.frontier.aviation.missile;

import java.util.UUID;
import java.util.Optional;
import net.minecraft.world.phys.Vec3;

/**
 * Result of the shared muzzle-to-reticle calculation.
 *
 * <p>The point is deliberately independent of the current AGM role. An entity
 * identity can be carried by a future air-to-air entry point without changing
 * the point and direction calculation used by player launches.</p>
 */
public record MissileAimSolution(Vec3 muzzle, Vec3 target, Vec3 direction, Optional<UUID> targetEntity) {
    public MissileAimSolution {
        if (muzzle == null || target == null || direction == null || targetEntity == null
                || !finite(muzzle) || !finite(target) || !finite(direction)
                || direction.lengthSqr() == 0.0) {
            throw new IllegalArgumentException("non-finite or empty missile aim solution");
        }
    }

    public static Optional<MissileAimSolution> resolve(Vec3 muzzle, Vec3 target) {
        if (muzzle == null || target == null || !finite(muzzle) || !finite(target)) {
            return Optional.empty();
        }
        Vec3 delta = target.subtract(muzzle);
        double distance = Math.hypot(Math.hypot(delta.x, delta.y), delta.z);
        if (!Double.isFinite(distance) || distance <= 0) {
            return Optional.empty();
        }
        return Optional.of(new MissileAimSolution(muzzle, target,
            new Vec3(delta.x / distance, delta.y / distance, delta.z / distance), Optional.empty()));
    }

    public MissileAimSolution withTargetEntity(UUID entityId) {
        return new MissileAimSolution(muzzle, target, direction,
                entityId == null ? Optional.empty() : Optional.of(entityId));
    }

    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
}
