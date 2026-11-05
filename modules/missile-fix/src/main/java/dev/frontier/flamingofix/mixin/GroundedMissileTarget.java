package dev.frontier.flamingofix.mixin;

import dev.sbw.deepstrike.game.MissileKind;
import dev.sbw.deepstrike.game.RoutePoint;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The map screens pick a target's Y from the client's terrain, which past the client's view
 * distance (a 700 m FP-5 shot) is not loaded: the missile then burst ~100 blocks in the air.
 * The server drops the final waypoint onto the surface from the target chunk itself.
 *
 * A cruise missile then glides down at ~8 degrees and must cross a 1-block box at the target;
 * passing 2 blocks high it lands 10-15 blocks long. A dive entry 60 blocks short and 45 up
 * makes the last leg ~37 degrees, so the same miss is 2-3 blocks.
 */
@Mixin(targets = "dev.sbw.deepstrike.game.GuidedMissileEntity", remap = false)
public abstract class GroundedMissileTarget {
    @Unique private static final double flamingo$DIVE_BACK = 60, flamingo$DIVE_UP = 45, flamingo$DIVE_MIN_RANGE = 150;

    @Shadow public abstract MissileKind kind();

    @ModifyVariable(method = {"setMission(Ljava/util/List;Z)V", "setPlannedRoute(Ljava/util/List;)V"},
            at = @At("HEAD"), argsOnly = true, remap = false, require = 2)
    private List<RoutePoint> flamingo$groundTarget(List<RoutePoint> route) {
        Entity self = (Entity) (Object) this;
        if (!(self.level() instanceof ServerLevel level) || route == null || route.isEmpty()) return route;
        RoutePoint last = route.get(route.size() - 1);
        if (!Double.isFinite(last.x()) || !Double.isFinite(last.z())) return route;
        int y = flamingo$surface(level, last.x(), last.z());
        var grounded = new ArrayList<>(route);
        RoutePoint target = y > level.getMinBuildHeight() ? new RoutePoint(last.x(), y, last.z(), last.speed()) : last;
        grounded.set(grounded.size() - 1, target);
        MissileKind kind = kind();
        if (kind == MissileKind.FP5 || kind == MissileKind.STORM_SHADOW || kind == MissileKind.JAGM) {
            Vec3 from = grounded.size() > 1 ? flamingo$point(grounded.get(grounded.size() - 2)) : self.position();
            double dx = target.x() - from.x, dz = target.z() - from.z, range = Math.sqrt(dx * dx + dz * dz);
            // Not when the route already ends in a steep dive (a waypoint right over the target).
            if (range > flamingo$DIVE_MIN_RANGE) {
                double ex = target.x() - dx / range * flamingo$DIVE_BACK, ez = target.z() - dz / range * flamingo$DIVE_BACK;
                double ey = Math.max(target.y() + flamingo$DIVE_UP, flamingo$surface(level, ex, ez) + 20);
                grounded.add(grounded.size() - 1, new RoutePoint(ex, ey, ez, target.speed()));
            }
        }
        return grounded;
    }

    @Unique
    private static int flamingo$surface(ServerLevel level, double x, double z) {
        int bx = Mth.floor(x), bz = Mth.floor(z);
        return level.getChunk(bx >> 4, bz >> 4).getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx & 15, bz & 15) + 1;
    }

    @Unique
    private static Vec3 flamingo$point(RoutePoint point) {
        return new Vec3(point.x(), point.y(), point.z());
    }
}
