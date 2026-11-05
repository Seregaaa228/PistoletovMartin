package dev.frontier.flamingofix.mixin;

import dev.sbw.deepstrike.game.RoutePoint;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The map screens pick a target's Y from the client's terrain, which past the client's view
 * distance (a 700 m FP-5 shot) is not loaded: the missile then burst ~100 blocks in the air.
 * The server drops the final waypoint onto the surface from the target chunk itself.
 */
@Mixin(targets = "dev.sbw.deepstrike.game.GuidedMissileEntity", remap = false)
public abstract class GroundedMissileTarget {
    @ModifyVariable(method = {"setMission(Ljava/util/List;Z)V", "setPlannedRoute(Ljava/util/List;)V"},
            at = @At("HEAD"), argsOnly = true, remap = false, require = 2)
    private List<RoutePoint> flamingo$groundTarget(List<RoutePoint> route) {
        if (!(((Entity) (Object) this).level() instanceof ServerLevel level) || route == null || route.isEmpty()) return route;
        RoutePoint last = route.get(route.size() - 1);
        if (!Double.isFinite(last.x()) || !Double.isFinite(last.z())) return route;
        int x = Mth.floor(last.x()), z = Mth.floor(last.z());
        int y = level.getChunk(x >> 4, z >> 4).getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1;
        if (y <= level.getMinBuildHeight() || Math.abs(y - last.y()) < 0.5) return route;
        var grounded = new ArrayList<>(route);
        grounded.set(grounded.size() - 1, new RoutePoint(last.x(), y, last.z(), last.speed()));
        return grounded;
    }
}
