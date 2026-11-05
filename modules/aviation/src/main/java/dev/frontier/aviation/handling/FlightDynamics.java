package dev.frontier.aviation.handling;

/**
 * Per-jet "feel" layered on the deterministic control model. Every value is a pure
 * number, so the server and the client prediction compute exactly the same attitude.
 * {@link #NONE} keeps the original linear-ramp model (native SBW aircraft, fixtures).
 *
 * @param response      fraction of the remaining rate error closed per tick while an axis is held
 * @param release       fraction of the rate that decays per tick once the axis is released
 * @param bankTurn      yaw (deg/tick) a level 90 degree bank adds at cruise speed: coordinated turn
 * @param adverseYaw    yaw opposite to the roll, as a fraction of the roll rate
 * @param noseSink      nose-down pitch (deg/tick) at stall speed, fading out at 1.6x stall
 * @param steeringRadius nose-wheel turn radius at taxi speed, in blocks
 * @param steeringRate  most yaw (deg/tick) the nose wheel can give
 * @param brakeDecel    constant wheel-brake deceleration (blocks/tick per tick)
 * @param brakeDrag     extra speed-proportional wheel-brake drag per tick
 * @param tyreGrip      fraction of sideways ground speed the tyres remove per tick
 * @param buffet        visual buffet and turbulence strength (1 = reference)
 */
public record FlightDynamics(float response, float release, float bankTurn, float adverseYaw,
                             float noseSink, float steeringRadius, float steeringRate,
                             float brakeDecel, float brakeDrag, float tyreGrip, float buffet) {
    public static final FlightDynamics NONE = new FlightDynamics(1, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0);

    /** Su-25: heavy and forgiving, slow to start and stop rotating, steady on the ground. */
    public static final FlightDynamics SU25 =
        new FlightDynamics(.42f, .5f, .32f, .05f, .14f, 9, 1.9f, .011f, .028f, .6f, .8f);
    /** J-10: between the two. */
    public static final FlightDynamics J10 =
        new FlightDynamics(.5f, .56f, .40f, .045f, .18f, 10, 1.8f, .010f, .025f, .55f, .9f);
    /** F-16: crisp, the quickest to answer and to settle. */
    public static final FlightDynamics F16 =
        new FlightDynamics(.6f, .62f, .45f, .04f, .22f, 11, 1.7f, .010f, .025f, .55f, 1);

    public FlightDynamics {
        unit(response, "response"); unit(release, "release");
        if (response <= 0 || release <= 0) throw new IllegalArgumentException("response and release must be positive");
        nonNegative(bankTurn, "bankTurn"); unit(adverseYaw, "adverseYaw"); nonNegative(noseSink, "noseSink");
        if (!(steeringRadius > 0) || !Float.isFinite(steeringRadius)) throw new IllegalArgumentException("steeringRadius");
        nonNegative(steeringRate, "steeringRate"); nonNegative(brakeDecel, "brakeDecel");
        unit(brakeDrag, "brakeDrag"); unit(tyreGrip, "tyreGrip"); nonNegative(buffet, "buffet");
    }

    /** Whether this jet uses the inertial model at all. */
    public boolean active() { return !equals(NONE); }

    private static void nonNegative(float value, String name) {
        if (!Float.isFinite(value) || value < 0) throw new IllegalArgumentException(name + " must be finite and nonnegative");
    }
    private static void unit(float value, String name) {
        nonNegative(value, name);
        if (value > 1) throw new IllegalArgumentException(name + " must be in [0,1]");
    }
}
