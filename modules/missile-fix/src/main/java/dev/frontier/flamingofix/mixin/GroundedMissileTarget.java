package dev.frontier.flamingofix.mixin;

import dev.frontier.flamingofix.MissileTargetGround;
import dev.sbw.deepstrike.game.RoutePoint;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Launches that skip the launcher (aircraft stores, Spike) still get a target on the surface.
 * A launcher's own terrain-planned route is left as planned: its target was grounded before
 * planning (LauncherGroundedTarget), and moving only its last point broke the planned dive.
 */
@Mixin(targets = "dev.sbw.deepstrike.game.GuidedMissileEntity", remap = false)
public abstract class GroundedMissileTarget {
    @ModifyVariable(method = "setMission(Ljava/util/List;Z)V", at = @At("HEAD"), argsOnly = true, remap = false, require = 1)
    private List<RoutePoint> flamingo$groundTarget(List<RoutePoint> route) {
        return ((Entity) (Object) this).level() instanceof ServerLevel level ? MissileTargetGround.ground(level, route) : route;
    }
}
