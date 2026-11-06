package dev.frontier.aviation.impact;

/** Pure vehicle-vs-vehicle impact rules in blocks/tick; no world access. */
public final class MidAirCollisionRules {
    /** Formation bumps and gentle touches below this impact speed are harmless. */
    public static final double SAFE_IMPACT = .4;
    /** A grazing pass still scrapes the airframes: part of the tangential speed counts. */
    public static final double GLANCING_SHARE = .25;
    /** Both bodies on the ground and slower than this (taxi, parking) never take damage. */
    public static final double GROUND_SPEED = AircraftImpactRules.MIN_FLIGHT_SPEED;
    /** Per-pair pause after an accepted hit, so one pass-through is a single impact. */
    public static final int PAIR_COOLDOWN_TICKS = 20;
    /** Per-tick displacement beyond this is a teleport/placement, not a sweep. */
    public static final double MAX_SWEEP = 16;
    /** Search reach for partners; covers any partner moving up to MAX_SWEEP per tick. */
    public static final double PARTNER_REACH = MAX_SWEEP;
    public static final double RESTITUTION = .2;
    public static final float MAX_DAMAGE = 10000;

    private MidAirCollisionRules() { }

    public static boolean exempt(double speedA, boolean groundedA, double speedB, boolean groundedB) {
        if (!Double.isFinite(speedA) || !Double.isFinite(speedB)) return true;
        return groundedA && groundedB && Math.max(speedA, speedB) < GROUND_SPEED;
    }

    /** Head-on and crossing speed counts fully; parallel scraping counts a quarter. */
    public static double impact(double relativeSpeed, double normalSpeed) {
        if (!Double.isFinite(relativeSpeed) || !Double.isFinite(normalSpeed)) return 0;
        return Math.max(Math.max(0, normalSpeed), GLANCING_SHARE * Math.max(0, relativeSpeed));
    }

    /** Bodies already overlapping at the start of a tick and moving apart are not a new impact. */
    public static boolean newContact(double time, double approachSpeed) {
        return Double.isFinite(time) && Double.isFinite(approachSpeed) && (time > 0 || approachSpeed > 0);
    }

    /** Raw strike damage to one body; the heavier partner hurts more (native 0.25-4 mass ratio). */
    public static float damage(double impact, float ownMass, float otherMass) {
        if (!Double.isFinite(impact) || impact <= SAFE_IMPACT) return 0;
        double ratio = clamp(mass(otherMass) / mass(ownMass), .25, 4);
        double excess = impact - SAFE_IMPACT;
        return (float) Math.min(MAX_DAMAGE, 72 * excess * excess * ratio);
    }

    /** Inelastic normal impulse magnitude for an approach speed; zero when separating. */
    public static double impulse(double approachSpeed, float massA, float massB) {
        if (!Double.isFinite(approachSpeed) || approachSpeed <= 0) return 0;
        return (1 + RESTITUTION) * approachSpeed / (1 / mass(massA) + 1 / mass(massB));
    }

    public static double mass(float mass) {
        return Float.isFinite(mass) && mass > 0 ? mass : 1;
    }

    /** Order-independent key for two entity ids. */
    public static long pairKey(int a, int b) {
        int low = Math.min(a, b), high = Math.max(a, b);
        return ((long) low << 32) | (high & 0xffffffffL);
    }

    /** Ignore a sweep that is a teleport rather than one tick of motion. */
    public static boolean sweepable(double displacement) {
        return Double.isFinite(displacement) && displacement <= MAX_SWEEP;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
