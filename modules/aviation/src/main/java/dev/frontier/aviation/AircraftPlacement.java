package dev.frontier.aviation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Where a tablet aircraft (DeepStrike Su24mEntity: Su-24M, Su-25, F-16, J-10) is put
 * by its deploy item. DeepStrike ignores the clicked point (17 blocks ahead of the
 * player instead), searches ground relative to the player's feet and wants an empty
 * 18x25x7 area with every wheel within 0.15 of one level, so it often refuses perfectly
 * usable ground. Here the aircraft stands on the ground at the clicked point, facing
 * the player's look; it is only pushed forward along that line far enough to clear the
 * player or an obstacle, and lifted onto higher ground only when nothing else fits.
 */
public final class AircraftPlacement {
    private static final int MAX_LIFT = 3, MAX_PUSH = 24;

    private AircraftPlacement() {}

    /** Feet position, or null when no spot along the look line is clear. */
    public static Vec3 find(UseOnContext context, Entity entity, float yaw) {
        // First on the ground itself (a one-block bump at most), moving forward along the
        // look line past the player or an obstacle; only then lift onto higher ground.
        Vec3 spot = search(context, entity, yaw, 1);
        return spot != null ? spot : search(context, entity, yaw, MAX_LIFT);
    }

    private static Vec3 search(UseOnContext context, Entity entity, float yaw, int maxLift) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        Vec3 click = context.getClickLocation();
        BlockPos clicked = context.getClickedPos();
        int top = context.getClickedFace() == Direction.UP ? clicked.getY() : clicked.getY() + 1;
        double angle = Math.toRadians(yaw);
        Vec3 forward = new Vec3(-Math.sin(angle), 0, Math.cos(angle));
        Vec3 at = new Vec3(click.x, 0, click.z);
        for (int push = 0; push <= MAX_PUSH; push++, at = at.add(forward)) {
            Double floor = ground(level, at, top);
            if (floor == null) continue;
            top = (int) Math.floor(floor);
            for (int lift = 0; lift <= maxLift; lift++) {
                Vec3 feet = new Vec3(at.x, floor + lift + 0.02, at.z);
                AABB box = entity.getType().getDimensions().makeBoundingBox(feet);
                if (player != null && box.intersects(player.getBoundingBox().inflate(1.0))) break;
                if (level.noCollision(entity, box)) return feet;
            }
        }
        return null;
    }

    /** Top of the first solid block under (x, z), searching a few blocks around {@code top}. */
    private static Double ground(Level level, Vec3 at, int top) {
        for (int y = top + 2; y >= top - 6; y--) {
            BlockPos pos = BlockPos.containing(at.x, y, at.z);
            var shape = level.getBlockState(pos).getCollisionShape(level, pos);
            if (!shape.isEmpty()) return y + shape.max(Direction.Axis.Y);
        }
        return null;
    }
}
