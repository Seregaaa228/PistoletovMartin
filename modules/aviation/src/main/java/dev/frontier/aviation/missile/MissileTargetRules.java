package dev.frontier.aviation.missile;

/** Pure bounds for server validation of a client-proposed ground target. */
public final class MissileTargetRules {
    private MissileTargetRules() {}
    public static boolean eligible(double x, double y, double z, double fx, double fy, double fz) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Double.isFinite(fx) || !Double.isFinite(fy) || !Double.isFinite(fz)) return false;
        double length = Math.sqrt(x*x + y*y + z*z);
        double forwardLength = Math.sqrt(fx*fx + fy*fy + fz*fz);
        if (!Double.isFinite(length) || length < 1.0 || length > 256.0
                || !Double.isFinite(forwardLength) || forwardLength * forwardLength < 0.99) return false;
        double dot = (x*fx + y*fy + z*fz) / (length * forwardLength);
        return Double.isFinite(dot) && dot >= Math.cos(Math.toRadians(20.0) + Math.atan2(4.0, length));
    }
    public static boolean closeToHit(double dx, double dy, double dz) {
        double squared = dx*dx + dy*dy + dz*dz;
        return Double.isFinite(squared) && squared <= 0.75 * 0.75;
    }
    public static final class AttemptLimiter {
        private long lastTick = Long.MIN_VALUE;
        public boolean allow(long tick) {
            if (lastTick == tick) return false;
            lastTick = tick;
            return true;
        }
    }
}
