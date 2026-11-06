package dev.frontier.aviation.client;

/** Tick-based visual wheel angle driven by signed ground travel, in blocks. */
public final class F16WheelSpin {
    /** F-16 tyre radii in blocks (0.36 m main, 0.29 m nose); other jets pass their own. */
    private static final double F16_MAIN_RADIUS = 11.52 / 32.0;
    private static final double F16_NOSE_RADIUS = 9.28 / 32.0;
    private static final double TAU = Math.PI * 2;
    private final double mainRadius;
    private final double noseRadius;
    // Accumulated wheel angles (radians) at the previous and the current tick.
    private double previousMain;
    private double main;
    private double previousNose;
    private double nose;

    public F16WheelSpin() {
        this(F16_MAIN_RADIUS, F16_NOSE_RADIUS);
    }

    /** Tyre radii in blocks, as modelled (the tyre's first cube is centred on its wheel bone). */
    public F16WheelSpin(double mainRadius, double noseRadius) {
        if (!(mainRadius > 0) || !(noseRadius > 0)) throw new IllegalArgumentException("wheel radius");
        this.mainRadius = mainRadius;
        this.noseRadius = noseRadius;
    }

    /**
     * Wheels of an owned jet by its id (su25, f16, j10). Radii are the modelled tyres of
     * art/realistic in blocks (geo px / 16); the resource contract test compares them with the geo.
     */
    public static F16WheelSpin forAircraft(String id) {
        return switch (id) {
            case "su25" -> new F16WheelSpin(6.0 / 16, 5.05 / 16);
            case "j10" -> new F16WheelSpin(5.0 / 16, 4.4 / 16);
            default -> new F16WheelSpin();
        };
    }

    double radius(boolean noseWheel) {
        return noseWheel ? noseRadius : mainRadius;
    }

    public void tick(boolean grounded, boolean gearDown, double signedTravel) {
        previousMain = main;
        previousNose = nose;
        if (grounded && gearDown && Double.isFinite(signedTravel)) {
            // One tick cannot plausibly cover hundreds of blocks. Reject teleports.
            // SBW wheel bones use the opposite X rotation to the signed forward
            // displacement measured in world coordinates.
            if (Math.abs(signedTravel) <= 16) {
                main -= signedTravel / mainRadius;
                nose -= signedTravel / noseRadius;
            }
        }
        // Rebase each wheel by whole turns to bound precision loss; the visible phase and the
        // interpolation interval stay the same.
        double offset = rebase(main);
        previousMain -= offset;
        main -= offset;
        offset = rebase(nose);
        previousNose -= offset;
        nose -= offset;
    }

    private static double rebase(double angle) {
        return Math.abs(angle) > 10000 ? Math.floor(angle / TAU) * TAU : 0;
    }

    public float angle(float partialTick, boolean noseWheel) {
        float partial = Float.isFinite(partialTick) ? Math.max(0, Math.min(1, partialTick)) : 0;
        double from = noseWheel ? previousNose : previousMain;
        double to = noseWheel ? nose : main;
        return (float) ((from + (to - from) * partial) % TAU);
    }
}
