package dev.frontier.aviation.handling;

/** Pure, bounded aircraft correction model. Native physics owns integration. */
public final class AerodynamicModel {
    private AerodynamicModel() {}

    public record Vector(double x, double y, double z) {
        public Vector {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("nonfinite vector");
            }
        }

        public double dot(Vector other) { return x * other.x + y * other.y + z * other.z; }
        public Vector add(Vector other) { return new Vector(x + other.x, y + other.y, z + other.z); }
        public Vector subtract(Vector other) { return new Vector(x - other.x, y - other.y, z - other.z); }
        public Vector scale(double factor) { return new Vector(x * factor, y * factor, z * factor); }
        public double lengthSquared() { return dot(this); }
        public double length() { return Math.hypot(Math.hypot(x, y), z); }
        public Vector normalize() {
            double length = length();
            return length < 1.0e-9 ? new Vector(0, 0, 0) : scale(1 / length);
        }
        public Vector cross(Vector other) {
            return new Vector(y * other.z - z * other.y, z * other.x - x * other.z,
                x * other.y - y * other.x);
        }
    }

    public record Correction(Vector acceleration, double lateralSpeed, double speed, double damping) {}

    public static Correction correction(HandlingProfile profile, Vector velocity, Vector forward,
                                        Vector up, boolean onGround) {
        if (profile.nativeOnly() || onGround) {
            return new Correction(new Vector(0, 0, 0), 0, velocity.length(), 0);
        }
        Vector f = forward.normalize();
        Vector u = up.normalize();
        Vector right = u.cross(f).normalize();
        double speed = velocity.length();
        double lateralSpeed = velocity.dot(right);
        double speedWeight = profile.fullAuthoritySpeed() == 0
            ? 1 : clamp(speed / profile.fullAuthoritySpeed(), 0, 1);
        double damping = profile.lowSpeedDamping()
            + (profile.highSpeedDamping() - profile.lowSpeedDamping()) * speedWeight;
        Vector lateral = right.scale(clampMagnitude(-lateralSpeed * damping, profile.maximumCorrectionPerTick()));
        // Only dissipate the body-right component. Forward motion and the
        // orthogonal component remain native; no extra lift or yaw snap.
        return new Correction(lateral, lateralSpeed, speed, damping);
    }

    private static double clampMagnitude(double value, double maximum) {
        return clamp(value, -maximum, maximum);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
