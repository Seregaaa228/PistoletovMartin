package dev.frontier.flamingofix;

import dev.sbw.deepstrike.game.FlightDynamics;
import dev.sbw.deepstrike.game.MissileKind;
import dev.sbw.deepstrike.game.RoutePoint;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/**
 * A cruise missile's altitude profile for DeepStrike's route planner (SimpleFlightPlan), which
 * otherwise just eases between the waypoints' own heights: with the target on the ground the
 * missile skimmed the terrain all the way and flew into any hill (see MissileCruiseSim).
 *
 * Climb to a cruise height (the highest terrain under the route plus {@link #CLEARANCE}), hold
 * it, and start the descent where the missile can still make it: the planner's last leg leaves
 * level and eases down as t^2 (2 - t), whose steepest curvature is 4 dH / D^2 at its start, so
 * D >= sqrt(4 dH R) for a turn radius R = v^2 / a at the dive speed (80 % of cruise, not below
 * the missile's minimum). Terrain under that descent raises the cruise height until it clears.
 * An FP-5 leaves its launcher up an 80-block rail first, so its profile starts at the rail's end.
 */
public final class MissileCruiseProfile {
    public static final double CLEARANCE = 40, DIVE_MARGIN = 6, MIN_DROP = 10, SAMPLE = 8, MARGIN = 1.15,
            END_ROOM = 40, CLIMB = 2;

    public interface Surface { double at(double x, double z); }

    public static boolean applies(MissileKind kind) {
        return kind == MissileKind.FP5 || kind == MissileKind.STORM_SHADOW || kind == MissileKind.JAGM;
    }

    /** The mission reshaped, or the same list when there is no room to climb and dive. */
    public static List<RoutePoint> shape(Vec3 launch, Vec3 forward, MissileKind kind, List<RoutePoint> mission, Surface surface) {
        if (!applies(kind) || mission.isEmpty()) return mission;
        Vec3 start = launch;
        if (kind == MissileKind.FP5) {
            // SimpleFlightPlan's rail: 6.5 out along the launcher, then 80 forward and 32 up.
            Vec3 heading = new Vec3(forward.x, 0, forward.z);
            heading = heading.lengthSqr() < 1e-8 ? new Vec3(0, 0, 1) : heading.normalize();
            start = launch.add(forward.normalize().scale(6.5)).add(heading.scale(80)).add(0, 32, 0);
        }
        RoutePoint target = mission.get(mission.size() - 1);
        List<Vec3> path = new ArrayList<>();
        path.add(start);
        for (RoutePoint point : mission) path.add(new Vec3(point.x(), point.y(), point.z()));

        double highest = target.y();
        for (int i = 1; i < path.size(); i++) {
            Vec3 a = path.get(i - 1), b = path.get(i);
            double length = horizontal(a, b);
            for (double s = 0; s <= length; s += SAMPLE) {
                double t = length < 1e-6 ? 1 : s / length;
                highest = Math.max(highest, surface.at(a.x + (b.x - a.x) * t, a.z + (b.z - a.z) * t));
            }
        }

        Vec3 lastFrom = path.get(path.size() - 2), aim = path.get(path.size() - 1);
        double lastLeg = horizontal(lastFrom, aim);
        double room = lastLeg - END_ROOM;
        if (room <= 0) return mission;
        double cruiseSpeed = Math.max(kind.minSpeed, Math.min(kind.defaultSpeed, target.speed()));
        double diveSpeed = Math.max(kind.minSpeed, cruiseSpeed * 0.8);
        double metres = diveSpeed / 3.6;
        double radius = metres * metres / FlightDynamics.lateralAcceleration(kind, false);
        double dx = aim.x - lastFrom.x, dz = aim.z - lastFrom.z;
        double ux = dx / lastLeg, uz = dz / lastLeg;

        // The longest (gentlest) descent whose climb (at most 1:1) and descent both clear the
        // terrain; past an obstacle the dive starts later and steeper, and DeepStrike's path
        // follower slows the missile for it. With no such descent, the comfortable one.
        double total = horizontal(start, aim);
        double comfortable = Math.min(room, Math.sqrt(4 * Math.max(highest + CLEARANCE - target.y(), 0) * radius) * MARGIN);
        double cruise = Double.NaN, distance = comfortable;
        for (double d = comfortable; d >= Math.min(comfortable, END_ROOM); d -= SAMPLE) {
            double climbRoom = total - d;
            double height = Math.min(highest + CLEARANCE, start.y + Math.max(MIN_DROP, climbRoom / CLIMB * 2));
            if (clears(surface, start, aim, d, climbRoom, height, target.y(), ux, uz)) { cruise = height; distance = d; break; }
        }
        if (Double.isNaN(cruise)) {
            cruise = Math.min(highest + CLEARANCE, start.y + Math.max(MIN_DROP, (total - comfortable) / CLIMB));
            distance = comfortable;
        }
        if (cruise - target.y() < MIN_DROP) return mission;

        List<RoutePoint> shaped = new ArrayList<>();
        for (int i = 0; i < mission.size() - 1; i++) {
            RoutePoint way = mission.get(i);
            shaped.add(new RoutePoint(way.x(), Math.max(way.y(), cruise), way.z(), way.speed()));
        }
        shaped.add(new RoutePoint(aim.x - ux * distance, cruise, aim.z - uz * distance, diveSpeed));
        shaped.add(new RoutePoint(target.x(), target.y(), target.z(), diveSpeed));
        return shaped;
    }

    /** Climb t^2 (3 - 2t) to the descent point, then descend t^2 (2 - t) onto the aim, over the terrain. */
    private static boolean clears(Surface surface, Vec3 start, Vec3 aim, double distance, double climbRoom, double cruise,
            double floor, double ux, double uz) {
        double total = climbRoom + distance;
        // The launch itself and the last stretch onto the aim are at the ground by design.
        for (double s = END_ROOM; s < total - END_ROOM; s += SAMPLE) {
            double y;
            if (s < climbRoom) {
                double t = s / climbRoom;
                y = start.y + (cruise - start.y) * t * t * (3 - 2 * t);
            } else {
                double t = (s - climbRoom) / distance;
                y = cruise - (cruise - floor) * t * t * (2 - t);
            }
            double back = total - s;
            if (surface.at(aim.x - ux * back, aim.z - uz * back) + DIVE_MARGIN > y) return false;
        }
        return true;
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        double dx = b.x - a.x, dz = b.z - a.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private MissileCruiseProfile() {}
}
