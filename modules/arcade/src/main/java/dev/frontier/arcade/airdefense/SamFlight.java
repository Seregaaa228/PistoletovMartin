package dev.frontier.arcade.airdefense;

import net.minecraft.world.phys.Vec3;

/**
 * Interceptor kinematics and guidance, free of world access so the server and the client
 * (which flies its copy between position updates) step the very same model.
 *
 * <p>Guidance is lead pursuit: the missile turns toward where the target will be when the
 * missile gets there, at most {@link #maxTurn} per tick. Against a straight flyer that is a
 * collision course; a target that changes direction late leaves the lead point swinging faster
 * than a tired missile can turn, which is the pilot's way out.</p>
 */
public final class SamFlight {
    private SamFlight() { }

    /** Mutable flight state; {@code dir} is a unit vector. */
    public static final class State {
        public Vec3 pos, dir;
        public double speed;
        public int age;

        public State(Vec3 pos, Vec3 dir, double speed) {
            this.pos = pos;
            this.dir = dir.normalize();
            this.speed = speed;
        }

        public Vec3 velocity() { return dir.scale(speed); }
    }

    /** Speed after one more tick: linear acceleration while the motor burns, then drag. */
    public static double nextSpeed(SamMissile missile, int age, double speed) {
        if (missile.burning(age)) {
            int burn = missile.verticalTicks + missile.boostTicks;
            return Math.min(missile.maxSpeed, speed + missile.maxSpeed / Math.max(1, burn) * 1.15);
        }
        return speed * (1 - missile.drag);
    }

    /** Largest heading change (radians) this tick; lateral acceleration scales with speed squared. */
    public static double maxTurn(SamMissile missile, double speed) {
        if (speed <= 1e-6) return 0;
        double ratio = Math.min(1, speed / missile.maxSpeed);
        // Thrust vectoring keeps a little authority while slow off the rail.
        double lateral = missile.maxLateral * Math.max(ratio * ratio, 0.25);
        return Math.min(Math.toRadians(35), lateral / speed);
    }

    /** Where to aim: the target's position after the missile's time to go (iterated). */
    public static Vec3 lead(Vec3 from, double speed, Vec3 target, Vec3 targetVelocity) {
        Vec3 aim = target;
        double v = Math.max(speed, 1);
        for (int i = 0; i < 3; i++) {
            double time = Math.min(200, aim.distanceTo(from) / v);
            aim = target.add(targetVelocity.scale(time));
        }
        return aim;
    }

    /** Turns unit vector {@code from} toward {@code to} by at most {@code limit} radians. */
    public static Vec3 turn(Vec3 from, Vec3 to, double limit) {
        Vec3 goal = to.lengthSqr() < 1e-12 ? from : to.normalize();
        double cos = Math.max(-1, Math.min(1, from.dot(goal)));
        double angle = Math.acos(cos);
        if (angle <= limit || angle < 1e-9) return goal;
        Vec3 axis = from.cross(goal);
        if (axis.lengthSqr() < 1e-12) {
            // Exactly opposite: pick any perpendicular.
            axis = Math.abs(from.y) < 0.9 ? from.cross(new Vec3(0, 1, 0)) : from.cross(new Vec3(1, 0, 0));
        }
        axis = axis.normalize();
        // Rodrigues rotation of `from` about `axis` by `limit`.
        double c = Math.cos(limit), s = Math.sin(limit);
        return from.scale(c).add(axis.cross(from).scale(s)).add(axis.scale(axis.dot(from) * (1 - c))).normalize();
    }

    /** Ticks to go below which the terminal error steers the missile off the true aim point. */
    public static final double END_GAME_TICKS = 12;

    /** Terminal miss distance for a standard normal magnitude {@code gaussianAbs} and target acceleration. */
    public static double terminalError(SamMissile missile, double gaussianAbs, double targetAcceleration) {
        double a = Double.isFinite(targetAcceleration) ? Math.min(2, Math.max(0, targetAcceleration)) : 0;
        return missile.sigma * Math.abs(gaussianAbs) + missile.maneuverPenalty * a;
    }

    public static void step(State state, SamMissile missile, Vec3 target, Vec3 targetVelocity) {
        step(state, missile, target, targetVelocity, null);
    }

    /**
     * One tick. {@code target}/{@code targetVelocity} may be null (no guidance: fly straight).
     * The vertical-launch phase climbs straight up regardless of the target. {@code endGameOffset},
     * if given, is added to the aim point in the end game (the terminal guidance error).
     */
    public static void step(State state, SamMissile missile, Vec3 target, Vec3 targetVelocity, Vec3 endGameOffset) {
        state.speed = nextSpeed(missile, state.age, state.speed);
        if (state.age >= missile.verticalTicks && target != null) {
            Vec3 aim = lead(state.pos, state.speed, target, targetVelocity == null ? Vec3.ZERO : targetVelocity);
            if (endGameOffset != null && aim.distanceTo(state.pos) < state.speed * END_GAME_TICKS) aim = aim.add(endGameOffset);
            double limit = maxTurn(missile, state.speed);
            // Pitch-over right after a vertical launch is flown by the gas-dynamic system.
            if (state.age < missile.verticalTicks + 6 && missile.verticalTicks > 0) limit = Math.max(limit, Math.toRadians(14));
            state.dir = turn(state.dir, aim.subtract(state.pos), limit);
        }
        state.pos = state.pos.add(state.dir.scale(state.speed));
        state.age++;
    }

    /**
     * Closest distance between missile and target over one tick when both move linearly from
     * their start to their end points. Returns {distance, fraction of the tick}.
     */
    public static double[] closestApproach(Vec3 missileStart, Vec3 missileEnd, Vec3 targetStart, Vec3 targetEnd) {
        Vec3 r0 = targetStart.subtract(missileStart);
        Vec3 r1 = targetEnd.subtract(missileEnd);
        Vec3 dr = r1.subtract(r0);
        double len = dr.lengthSqr();
        double t = len < 1e-12 ? 0 : Math.max(0, Math.min(1, -r0.dot(dr) / len));
        return new double[] { r0.add(dr.scale(t)).length(), t };
    }

    /** Fraction of maximum health a burst removes at this miss distance (0 outside the fuse). */
    public static double damageFraction(SamMissile missile, double miss) {
        if (!(miss >= 0) || miss > missile.fuseRadius) return 0;
        double core = missile.fuseRadius * 0.5;
        if (miss <= core) return missile.lethality;
        return missile.lethality * (1 - 0.6 * (miss - core) / (missile.fuseRadius - core));
    }

    /** True when the missile has no energy or time left to keep chasing. */
    public static boolean spent(SamMissile missile, State state) {
        return state.age > missile.lifeTicks || (!missile.burning(state.age) && state.speed < missile.minSpeed);
    }
}
