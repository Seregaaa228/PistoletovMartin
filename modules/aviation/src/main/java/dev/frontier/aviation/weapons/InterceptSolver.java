package dev.frontier.aviation.weapons;

import java.util.Optional;
import net.minecraft.world.phys.Vec3;

/**
 * Constant-velocity lead calculation for server-side, unaccelerated projectiles.
 *
 * <p>The projectile is modelled as {@code inheritedVelocity + airspeed * direction}.
 * This helper does not model AGM acceleration, steering, gravity, drag, or collision
 * geometry, so its result must not be used to claim an accelerating missile hit.</p>
 */
public final class InterceptSolver {
    private static final double COEFFICIENT_EPSILON = 1.0e-15;

    private InterceptSolver() {
    }

    /**
     * Finds the earliest finite non-negative intercept within {@code maxFlightTicks}.
     *
     * <p>Positions are in blocks and velocities in blocks per tick. The returned direction
     * is the direction to pass to the projectile's airspeed component; the inherited
     * velocity is already accounted for and must not be added to it again.</p>
     */
    public static Optional<Solution> solve(
            Vec3 shooterPosition,
            Vec3 targetPosition,
            Vec3 targetVelocity,
            Vec3 inheritedProjectileVelocity,
            double projectileAirspeed,
            double maxFlightTicks) {
        if (!finite(shooterPosition) || !finite(targetPosition)
                || !finite(targetVelocity) || !finite(inheritedProjectileVelocity)
                || !Double.isFinite(projectileAirspeed) || projectileAirspeed <= 0.0
                || !Double.isFinite(maxFlightTicks) || maxFlightTicks < 0.0) {
            return Optional.empty();
        }

        Vec3 displacement = subtract(targetPosition, shooterPosition);
        Vec3 relativeVelocity = subtract(targetVelocity, inheritedProjectileVelocity);
        if (displacement == null || relativeVelocity == null) {
            return Optional.empty();
        }

        if (isZero(displacement)) {
            return Optional.of(new Solution(targetPosition, Vec3.ZERO, 0.0));
        }
        if (maxFlightTicks == 0) return Optional.empty();

        double positionScale = maxComponent(displacement);
        double velocityScale = Math.max(maxComponent(relativeVelocity), projectileAirspeed);
        if (!(positionScale > 0.0) || !Double.isFinite(positionScale) || !(velocityScale > 0.0)) {
            return Optional.empty();
        }

        // Solve in independent position/velocity units. Scaling both by the
        // displacement would erase the quadratic term at large distances.
        double rx = displacement.x / positionScale;
        double ry = displacement.y / positionScale;
        double rz = displacement.z / positionScale;
        double wx = relativeVelocity.x / velocityScale;
        double wy = relativeVelocity.y / velocityScale;
        double wz = relativeVelocity.z / velocityScale;
        double speed = projectileAirspeed / velocityScale;

        double a = dot(wx, wy, wz, wx, wy, wz) - speed * speed;
        double b = 2.0 * dot(rx, ry, rz, wx, wy, wz);
        double c = dot(rx, ry, rz, rx, ry, rz);
        if (!finite(a, b, c)) {
            return Optional.empty();
        }

        double scaledTime = earliestRoot(a, b, c);
        double time = scaledTime * (positionScale / velocityScale);
        if (!Double.isFinite(time) || time <= 0.0
                || time > maxFlightTicks && time - maxFlightTicks > 8 * Math.ulp(maxFlightTicks)) {
            return Optional.empty();
        }
        if (time > maxFlightTicks) {
            time = maxFlightTicks;
        }

        Vec3 aimPoint = addScaled(targetPosition, targetVelocity, time);
        if (aimPoint == null) {
            return Optional.empty();
        }

        double dx = Math.fma(wx, scaledTime, rx);
        double dy = Math.fma(wy, scaledTime, ry);
        double dz = Math.fma(wz, scaledTime, rz);
        double norm = Math.hypot(Math.hypot(dx, dy), dz);
        if (!(norm > 0) || !Double.isFinite(norm)) return Optional.empty();
        double travelled = speed * scaledTime;
        if (!Double.isFinite(travelled) || Math.abs(norm - travelled) > 1e-8 * Math.max(norm, travelled))
            return Optional.empty();
        Vec3 direction = new Vec3(dx / norm, dy / norm, dz / norm);
        return Optional.of(new Solution(aimPoint, direction, time));
    }

    private static double earliestRoot(double a, double b, double c) {
        double coefficientScale = Math.max(Math.abs(a), Math.max(Math.abs(b), Math.abs(c)));
        if (!(coefficientScale > 0.0) || !Double.isFinite(coefficientScale)) {
            return Double.NaN;
        }
        a /= coefficientScale;
        b /= coefficientScale;
        c /= coefficientScale;

        if (a == 0) {
            if (b == 0) {
                return c == 0 ? 0.0 : Double.NaN;
            }
            double root = -c / b;
            return root >= 0.0 ? root : Double.NaN;
        }

        double discriminant = b * b - 4.0 * a * c;
        if (!Double.isFinite(discriminant)) {
            return Double.NaN;
        }
        if (discriminant < 0.0) {
            if (discriminant > -COEFFICIENT_EPSILON) {
                discriminant = 0.0;
            } else {
                return Double.NaN;
            }
        }

        double squareRoot = Math.sqrt(discriminant);
        double q = -0.5 * (b + Math.copySign(squareRoot, b));
        double first;
        double second;
        if (q == 0.0) {
            first = -b / (2.0 * a);
            second = first;
        } else {
            first = q / a;
            second = c / q;
        }
        double result = Double.POSITIVE_INFINITY;
        if (Double.isFinite(first) && first >= 0.0) {
            result = first;
        }
        if (Double.isFinite(second) && second >= 0.0) {
            result = Math.min(result, second);
        }
        return Double.isFinite(result) ? result : Double.NaN;
    }

    private static double maxComponent(Vec3 vector) {
        return Math.max(Math.max(Math.abs(vector.x), Math.abs(vector.y)), Math.abs(vector.z));
    }

    private static double dot(double ax, double ay, double az, double bx, double by, double bz) {
        return ax * bx + ay * by + az * bz;
    }

    private static Vec3 subtract(Vec3 left, Vec3 right) {
        double x = left.x - right.x;
        double y = left.y - right.y;
        double z = left.z - right.z;
        return finite(x, y, z) ? new Vec3(x, y, z) : null;
    }

    private static Vec3 addScaled(Vec3 position, Vec3 velocity, double time) {
        double x = position.x + velocity.x * time;
        double y = position.y + velocity.y * time;
        double z = position.z + velocity.z * time;
        return finite(x, y, z) ? new Vec3(x, y, z) : null;
    }

    private static boolean isZero(Vec3 vector) {
        return vector.x == 0.0 && vector.y == 0.0 && vector.z == 0.0;
    }

    private static boolean finite(Vec3 vector) {
        return vector != null && finite(vector.x, vector.y, vector.z);
    }

    private static boolean finite(double... values) {
        for (double value : values) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    public record Solution(Vec3 aimPoint, Vec3 launchDirection, double flightTicks) {
    }
}
