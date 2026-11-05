package dev.frontier.flamingofix.mixin;

import dev.frontier.flamingofix.MissileTargetGround;
import dev.sbw.deepstrike.game.RoutePoint;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Ground the target before the launcher checks range, picks a ballistic plan or runs its
 * terrain route planner, so DeepStrike plans its own climb, cruise and dive onto the real surface.
 */
@Mixin(targets = "dev.sbw.deepstrike.game.LauncherBlockEntity", remap = false)
public abstract class LauncherGroundedTarget {
    @ModifyVariable(method = "fireInternal(Lnet/minecraft/server/level/ServerPlayer;Ljava/util/List;ZLjava/lang/String;Ljava/util/UUID;Z)Z",
            at = @At("HEAD"), argsOnly = true, remap = false, require = 1)
    private List<RoutePoint> flamingo$groundTarget(List<RoutePoint> route) {
        return ((BlockEntity) (Object) this).getLevel() instanceof ServerLevel level ? MissileTargetGround.ground(level, route) : route;
    }
}
