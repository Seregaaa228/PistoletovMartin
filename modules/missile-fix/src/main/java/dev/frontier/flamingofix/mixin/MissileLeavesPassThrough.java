package dev.frontier.flamingofix.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** A missile goes through foliage; trunks, buildings and the ground still stop it. */
@Mixin(targets = "dev.sbw.deepstrike.game.FlightCollision", remap = false)
public abstract class MissileLeavesPassThrough {
    @Redirect(method = "sweepBlocks(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;"
            + "Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;)"
            + "Ldev/sbw/deepstrike/game/FlightCollision$SweepResult;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;m_60742_("
                    + "Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;"),
            require = 1, remap = false)
    private static VoxelShape flamingo$throughLeaves(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.is(BlockTags.LEAVES) ? Shapes.empty() : state.getCollisionShape(level, pos, context);
    }
}
