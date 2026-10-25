package dev.frontier.arcade.mixin;

import com.atsuishio.superbwarfare.compat.valkyrienskies.ValkyrienSkiesCompat;
import com.atsuishio.superbwarfare.tools.CustomExplosion;
import dev.frontier.arcade.combat.WarheadRules;
import dev.frontier.arcade.geranium.GeraniumEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.lavafrai.uncomplicatedfpv.entity.FpvDroneEntity;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;

/** SBW destroys blocks before ExplosionEvent.Detonate and in delayed waves. Budget every wave. */
@Mixin(value = CustomExplosion.class, remap = false)
public abstract class DroneExplosionBlocks {
    @Shadow @Final private Level level;
    @Unique private int frontier$blocksSpent;
    @Unique private static boolean frontier$limited(CustomExplosion blast) {
        Entity source = blast.getDirectSourceEntity();
        return source instanceof FpvDroneEntity || source instanceof GeraniumEntity;
    }
    @ModifyVariable(method = "processBlockList", at = @At("HEAD"), argsOnly = true, remap = false)
    private Collection<? extends BlockPos> frontier$budget(Collection<? extends BlockPos> original) {
        var blast = (CustomExplosion)(Object)this;
        if (!frontier$limited(blast) || level.isClientSide) return original;
        boolean fpv = blast.getDirectSourceEntity() instanceof FpvDroneEntity;
        double radius = fpv ? WarheadRules.FPV_BLOCK_RADIUS : WarheadRules.GERANIUM_BLOCK_RADIUS;
        int limit = fpv ? WarheadRules.FPV_BLOCK_LIMIT : WarheadRules.GERANIUM_BLOCK_LIMIT;
        Vec3 origin = new Vec3(blast.getX(), blast.getY(), blast.getZ());
        var result = new ArrayList<BlockPos>();
        for (BlockPos pos : original) {
            if (!level.hasChunkAt(pos) || pos.distToCenterSqr(origin) > radius * radius) continue;
            var block = level.getBlockState(pos);
            if (!block.isAir() && block.getDestroySpeed(level, pos) >= 0) result.add(pos.immutable());
        }
        result.sort(Comparator.comparingDouble(pos -> pos.distToCenterSqr(origin)));
        int remaining = Math.max(0, limit - frontier$blocksSpent);
        if (result.size() > remaining) result.subList(remaining, result.size()).clear();
        frontier$blocksSpent += result.size();
        return result;
    }
    @Redirect(method = "explode$lambda$2", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;m_8055_(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"), remap = false)
    private static BlockState frontier$loadedTerrain(Level world, BlockPos pos,
            BlockPos minimum, BlockPos maximum, int rays, RandomSource random, float size,
            double inner, double outer, Vec3 center, int minY, int maxY, CustomExplosion blast,
            ValkyrienSkiesCompat.ShipTransformCache cache, double reach) {
        if (frontier$limited(blast) && !world.hasChunkAt(pos)) return Blocks.BEDROCK.defaultBlockState();
        return world.getBlockState(pos);
    }
}
