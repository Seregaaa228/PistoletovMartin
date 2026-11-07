package dev.frontier.arcade.artillery;

import com.atsuishio.superbwarfare.config.server.ExplosionConfig;
import com.atsuishio.superbwarfare.entity.projectile.CannonShellEntity;
import com.atsuishio.superbwarfare.entity.projectile.FastThrowableProjectile;
import dev.frontier.arcade.mixin.CannonShellTypeAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * A high-explosive round that strikes a building does not burst on the roof: like a delay fuze it goes on along its
 * flight through roof and floors, breaking them, and bursts in the first room it reaches, so the explosion wrecks the
 * inside. It stops at earth or thick masonry (four solid blocks in a row, the ground under a field) and at very hard
 * blocks, so a hit in the open still bursts at the surface. Armour-piercing shells keep SBW's own penetration.
 */
public final class ArtilleryPenetration {
    /** How far along its path a shell / a rocket goes on into a structure, in blocks. */
    private static final double SHELL_DEPTH = 12, ROCKET_DEPTH = 6;
    /** Floors a shell / a rocket goes through before bursting in the room below the last. */
    private static final int SHELL_FLOORS = 2, ROCKET_FLOORS = 1;
    /** This many solid blocks in a row is ground or a bunker wall: the round stops in front of them. */
    private static final int SOLID_RUN = 4;
    /** Blocks harder than this (obsidian 50, bedrock -1) are never broken and stop the round. */
    private static final float HARD = 30;
    private static final double STEP = 0.5;

    private ArtilleryPenetration() { }

    /** Burst point for a round that reached {@code contact}; breaks the floors it passes. */
    public static Vec3 burst(ServerLevel level, FastThrowableProjectile round, Vec3 contact) {
        if (round instanceof CannonShellEntity shell && ((CannonShellTypeAccess) shell).frontier$type() != CannonShellEntity.Type.HE)
            return contact;
        Vec3 flight = round.getDeltaMovement();
        if (flight.lengthSqr() < 1.0E-4) return contact;
        Vec3 dir = flight.normalize();
        boolean shellRound = round instanceof CannonShellEntity;
        double depth = shellRound ? SHELL_DEPTH : ROCKET_DEPTH;
        int floors = shellRound ? SHELL_FLOORS : ROCKET_FLOORS;
        boolean breakBlocks = ExplosionConfig.EXPLOSION_DESTROY.get();

        BlockPos[] passed = new BlockPos[(int) (depth / STEP) + 1];
        int passedCount = 0, kept = 0, run = 0, through = 0;
        boolean inSolid = false;
        Vec3 room = null;
        BlockPos last = null;
        for (double d = STEP; d <= depth; d += STEP) {
            Vec3 at = contact.add(dir.scale(d));
            BlockPos pos = BlockPos.containing(at);
            if (pos.equals(last)) continue;
            last = pos;
            if (!level.isLoaded(pos)) break;
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.getCollisionShape(level, pos).isEmpty()) {
                run = 0;
                if (inSolid) {
                    // Out the far side of a roof or floor into a room: a candidate burst point.
                    inSolid = false;
                    room = at;
                    kept = passedCount;
                    if (++through >= floors) break;
                }
                continue;
            }
            float hardness = state.getDestroySpeed(level, pos);
            if (hardness < 0 || hardness > HARD || ++run >= SOLID_RUN) break;
            inSolid = true;
            passed[passedCount++] = pos;
        }
        if (room == null) return contact;
        // Only the floors it actually went through break; a slab it stopped inside stays.
        if (breakBlocks) for (int i = 0; i < kept; i++) level.destroyBlock(passed[i], false);
        return room;
    }
}
