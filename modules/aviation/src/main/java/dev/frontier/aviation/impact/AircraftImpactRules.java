package dev.frontier.aviation.impact;

/** Pure rules in blocks/tick: landing sink is distinct from a stopped flight axis. */
public final class AircraftImpactRules {
    public static final double MIN_FLIGHT_SPEED = 1.0;
    public static final double SAFE_AXIS_LOSS = .4;

    private AircraftImpactRules() { }

    public static float damage(double speed, double lostX, double lostY, double lostZ) {
        if (!Double.isFinite(speed) || !Double.isFinite(lostX)
                || !Double.isFinite(lostY) || !Double.isFinite(lostZ) || speed < MIN_FLIGHT_SPEED) return 0;
        double horizontal = Math.hypot(lostX, lostZ);
        double impact = Math.max(horizontal, Math.abs(lostY));
        if (impact <= SAFE_AXIS_LOSS) return 0;
        double excess = impact - SAFE_AXIS_LOSS;
        return (float) Math.min(10000, 72 * excess * excess);
    }
}
