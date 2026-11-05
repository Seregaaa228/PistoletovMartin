package dev.frontier.flamingofix;

import dev.sbw.deepstrike.game.RoutePoint;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The map screens pick a target's Y from the client's terrain, which past the client's view
 * distance is not loaded (a 700 m FP-5 shot burst ~100 blocks in the air). The server drops the
 * final waypoint onto the surface read from the target chunk itself, loading it if needed.
 */
public final class MissileTargetGround {
    public static List<RoutePoint> ground(ServerLevel level, List<RoutePoint> route) {
        if (route == null || route.isEmpty()) return route;
        RoutePoint last = route.get(route.size() - 1);
        if (!Double.isFinite(last.x()) || !Double.isFinite(last.z())) return route;
        int x = Mth.floor(last.x()), z = Mth.floor(last.z());
        if (!level.getWorldBorder().isWithinBounds(x, z)) return route;
        int y = level.getChunk(x >> 4, z >> 4).getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1;
        if (y <= level.getMinBuildHeight() || Math.abs(y - last.y()) < 0.5) return route;
        var grounded = new ArrayList<>(route);
        grounded.set(grounded.size() - 1, new RoutePoint(last.x(), y, last.z(), last.speed()));
        return grounded;
    }

    private MissileTargetGround() {}
}
