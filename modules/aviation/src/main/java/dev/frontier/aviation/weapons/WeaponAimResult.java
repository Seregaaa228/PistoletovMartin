package dev.frontier.aviation.weapons;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;

/** Server-side result of a weapon reticle ray. */
public record WeaponAimResult(Vec3 point, Optional<UUID> entity, Kind kind) {
    public enum Kind { BLOCK, ENTITY, SPACE }
    public WeaponAimResult {
        if (point == null || entity == null || kind == null || (kind == Kind.ENTITY) != entity.isPresent()
                || !Double.isFinite(point.x) || !Double.isFinite(point.y)
                || !Double.isFinite(point.z)) {
            throw new IllegalArgumentException("invalid weapon aim result");
        }
    }

    public static WeaponAimResult block(Vec3 point) {
        return new WeaponAimResult(point, Optional.empty(), Kind.BLOCK);
    }

    public static WeaponAimResult space(Vec3 point) {
        return new WeaponAimResult(point, Optional.empty(), Kind.SPACE);
    }

    public static WeaponAimResult entity(Vec3 point, UUID entityId) {
        return new WeaponAimResult(point, Optional.of(entityId), Kind.ENTITY);
    }
}
