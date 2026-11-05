package dev.frontier.flamingofix;

import dev.sbw.deepstrike.game.CruiseDynamics;
import dev.sbw.deepstrike.game.CruisePropulsion;
import dev.sbw.deepstrike.game.MissileData;
import dev.sbw.deepstrike.game.MissileKind;
import dev.sbw.deepstrike.game.RouteDynamics;
import dev.sbw.deepstrike.game.RoutePoint;
import dev.sbw.deepstrike.game.SimpleFlightPlan;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/**
 * Flies DeepStrike's own launcher pipeline offline: SimpleFlightPlan builds the path, the
 * missile follows it with RouteDynamics/CruiseDynamics from a standstill, 4 sub-steps a tick,
 * until the native terminal contact or the ground. Flat ground at y 0, optionally a 40-block
 * ridge halfway. Reports the miss, the lowest clearance before the dive, and any reversal.
 */
public final class MissileCruiseSim {
    private static int checks;
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label); checks++;
    }

    record Flight(double miss, double minClearance, double peak, boolean reversal, boolean groundEarly, int ticks) { }

    static Flight fly(MissileKind kind, double range, boolean ridge, boolean profiled) {
        MissileCruiseProfile.Surface surface = (x, z) -> ridge && Math.abs(z - range / 2) < 60 ? 40 : 0;
        Vec3 start = new Vec3(0.5, 2.5, 0.5);
        double elevation = Math.toRadians(kind == MissileKind.JAGM ? 20 : 35);
        Vec3 forward = new Vec3(0, Math.sin(elevation), Math.cos(elevation));
        Vec3 aim = new Vec3(0.5, surface.at(0.5, range) + 1, range);
        List<RoutePoint> mission = List.of(new RoutePoint(aim.x, aim.y, aim.z, kind.defaultSpeed));
        if (profiled) mission = MissileCruiseProfile.shape(start, forward, kind, mission, surface);
        if (profiled && Boolean.getBoolean("missileSim.debug")) System.out.println("  mission " + kind + " " + range + " " + ridge + ": " + mission);
        List<RoutePoint> route = SimpleFlightPlan.build(start, forward, kind, mission);
        int fuel = MissileData.calculateDefaultMaxFuel(kind, List.of());
        var propulsion = CruisePropulsion.legacy(kind, fuel, fuel, kind.maxRange, 0);
        var state = new RouteDynamics.State(new CruiseDynamics.State(start, Vec3.ZERO, propulsion), 0, 0);
        double minClearance = Double.MAX_VALUE, peak = start.y;
        boolean reversal = false, groundEarly = false;
        Vec3 end = null;
        int tick = 0;
        flight:
        for (; tick < 20 * 180; tick++) {
            for (int sub = 0; sub < 4; sub++) {
                var step = RouteDynamics.step(state, route, kind, forward);
                state = step.state();
                Vec3 p = state.motion().position(), v = state.motion().velocity();
                peak = Math.max(peak, p.y);
                double toTarget = Math.hypot(aim.x - p.x, aim.z - p.z);
                double clearance = p.y - surface.at(p.x, p.z);
                if (state.launchDistance() > 120 && toTarget > 120) minClearance = Math.min(minClearance, clearance);
                if (state.launchDistance() > 30 && v.z < -1) reversal = true;
                if (step.finished()) { end = p; break flight; }
                if (state.launchDistance() > 10 && clearance <= 0) { end = p; groundEarly = toTarget > 3; break flight; }
            }
        }
        double miss = end == null ? Double.NaN : Math.hypot(end.x - aim.x, end.z - aim.z);
        return new Flight(miss, minClearance, peak, reversal, groundEarly, tick);
    }

    public static void main(String[] args) {
        Object[][] cases = {
            {MissileKind.FP5, 300.0}, {MissileKind.FP5, 700.0}, {MissileKind.FP5, 1500.0}, {MissileKind.FP5, 2800.0},
            {MissileKind.STORM_SHADOW, 200.0}, {MissileKind.STORM_SHADOW, 500.0}, {MissileKind.STORM_SHADOW, 900.0},
            {MissileKind.JAGM, 150.0}, {MissileKind.JAGM, 330.0}};
        for (Object[] c : cases) {
            MissileKind kind = (MissileKind) c[0]; double range = (Double) c[1];
            for (boolean ridge : new boolean[] {false, true}) {
                Flight before = fly(kind, range, ridge, false), after = fly(kind, range, ridge, true);
                System.out.printf("%-12s %5.0f %-5s native: miss %6.1f clear %6.1f peak %6.1f rev %-5s early %-5s | profile: miss %6.1f clear %6.1f peak %6.1f rev %-5s early %-5s %4d t%n",
                        kind, range, ridge ? "ridge" : "flat", before.miss(), before.minClearance(), before.peak(), before.reversal(), before.groundEarly(),
                        after.miss(), after.minClearance(), after.peak(), after.reversal(), after.groundEarly(), after.ticks());
                check(!after.reversal(), kind + " " + range + " loops");
                // A 40-block wall halfway through a short shot is past these missiles' turn limits.
                boolean reachable = !ridge || kind == MissileKind.FP5 || range >= (kind == MissileKind.JAGM ? 330 : 900);
                if (reachable) {
                    check(!after.groundEarly(), kind + " " + range + (ridge ? " ridge" : "") + " hits the ground short");
                    check(after.miss() <= 1, kind + " " + range + (ridge ? " ridge" : "") + " misses by " + after.miss());
                    check(after.peak() >= 38, kind + " " + range + " does not cruise high: " + after.peak());
                }
            }
        }
        System.out.println("MissileCruiseSim: " + checks + " checks passed");
    }
}
