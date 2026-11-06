package dev.frontier.aviation.handling;

/**
 * Pure ground phase of our jets: stance on the gear, nose-wheel steering, wheel brakes
 * and tyre grip. Angles follow the native convention (positive pitch = nose down,
 * positive roll = right wing down); speeds are blocks per tick.
 */
public final class GroundHandling {
    private GroundHandling() {}

    /**
     * Gear stance pitch of the Su-25, F-16 and J-10: the nose and main wheel contact points
     * (vehicle TerrainCompat, model wheel bottoms) all lie at height 0, so they sit level.
     */
    public static final float GEAR_STANCE_PITCH = 0;
    /** Most nose-up rotation the tail allows on the runway. */
    public static final float MAX_ROTATION = 12;
    /** Rotation speed as a fraction of the stall speed: below it the nose wheel stays down. */
    public static final float ROTATION_FRACTION = .9f;
    /** Nose-up rotation rate the stick gives on the runway above rotation speed (deg/tick). */
    public static final float ROTATION_RATE = .9f;
    /** Gentle derotation once the jet slows below rotation speed (deg/tick). */
    public static final float DEROTATION_RATE = .8f;
    /** Settling back onto the gear after a bounce or a wing-low landing (deg/tick). */
    public static final float SETTLE_RATE = 3;
    private static final float SETTLE_FRACTION = .4f, SNAP = .05f;
    /**
     * A block surface this far (blocks) under the gear footprint still counts as the jet
     * being on its wheels. The server and every client take every ground decision from
     * this band (position and terrain only) instead of native onGround, which each side
     * sets from its own last move (and clients also from server move packets), so near a
     * block edge or after a position correction the two sides disagreed and corrected each
     * other while taxiing. The band also covers a slab drop, so such a hop does not flip
     * the jet between ground and air handling for a tick. Below a full block: a real
     * 1-block drop is still a hop (FlightControlModel.taxiHop) and a take-off still leaves.
     */
    public static final double CONTACT_BAND = .55;

    public record Attitude(float pitch, float roll) {}

    /**
     * One tick of the airframe settling onto its gear. Below rotation speed the target is
     * the gear stance (all three wheels down, wings level); above it a pilot-held nose-up
     * rotation up to {@link #MAX_ROTATION} is kept. Never noses down past the stance.
     */
    public static Attitude settle(float pitch, float roll, float speed, float rotationSpeed, float stancePitch) {
        pitch = finite(pitch, stancePitch); roll = finite(roll, 0);
        boolean rotating = rotationSpeed > 0 && speed >= rotationSpeed;
        float target = rotating ? clamp(pitch, stancePitch - MAX_ROTATION, stancePitch) : stancePitch;
        // A nose-up attitude below rotation speed comes down gently, like a real derotation;
        // nose-down (a bounce over a block edge) and bank recover at once.
        float pitchStep = pitch < target ? DEROTATION_RATE : SETTLE_RATE;
        return new Attitude(step(pitch, target, pitchStep), step(roll, 0, SETTLE_RATE));
    }

    /** Nose-wheel steering: a fixed turn radius at taxi speed, fading out at take-off speed. */
    public static float steeringRate(float speed, FlightDynamics dynamics) {
        if (!(speed > 0) || !Float.isFinite(speed)) return 0;
        float radiusRate = (float) Math.toDegrees(speed / dynamics.steeringRadius());
        // Above ~70 km/h the nose wheel gives way to the rudder; keep a third for the run-up.
        float fade = clamp(1 - (speed - 1f) / 1.5f, .35f, 1);
        return Math.min(radiusRate, dynamics.steeringRate()) * fade;
    }

    /** Horizontal ground speed after one tick of wheel braking. Stops cleanly at zero. */
    public static double brake(double speed, FlightDynamics dynamics) {
        if (!(speed > 0) || !Double.isFinite(speed)) return 0;
        return Math.max(0, speed - dynamics.brakeDecel() - dynamics.brakeDrag() * speed);
    }

    /** Sideways ground velocity after one tick of tyre grip (forward motion is untouched). */
    public static double grip(double lateral, FlightDynamics dynamics) {
        return Double.isFinite(lateral) ? lateral * (1 - dynamics.tyreGrip()) : 0;
    }

    private static float step(float value, float target, float maxStep) {
        float delta = target - value;
        if (Math.abs(delta) <= SNAP) return target;
        return value + clamp(delta * SETTLE_FRACTION, -maxStep, maxStep);
    }
    private static float finite(float value, float fallback) { return Float.isFinite(value) ? value : fallback; }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
}
