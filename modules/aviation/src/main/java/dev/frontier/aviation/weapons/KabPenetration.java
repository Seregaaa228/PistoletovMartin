package dev.frontier.aviation.weapons;

import java.util.function.IntToDoubleFunction;

/**
 * Delayed fuze of a КАБ that struck terrain. SBW detonates on the first block touched, and
 * its blast volume is squashed vertically (reach down is only ~0.28 of the radius), so a bomb
 * on a roof, a tree canopy or a water surface spent half its blast on air and broke one
 * storey. The store keeps going along its flight path, losing a material budget to what it
 * passes (hardness per block; air and plants are free), and a second, block-breaking charge
 * goes off where it stops: under the roof, inside the building, or below the crater floor.
 *
 * Pure rules (no game types) so the depths are fixed by tests; KabEntity samples the world.
 */
public final class KabPenetration {
    /** Path sampling step along the flight direction, in blocks. */
    public static final double STEP = 0.5;
    /** Obsidian-class blocks (and unbreakable ones) stop the store; it bursts in front of them. */
    public static final double STOP_HARDNESS = 50;
    /** Water and lava brake the store like soft ground; their block hardness (100) is no wall. */
    public static final double FLUID_RESISTANCE = 1.0;
    /** Fuze travel per blast radius, clamped: КАБ-500 5.6, КАБ-1500 14.35, КАБ-3000 20 blocks. */
    public static final double DEPTH_PER_RADIUS = 0.35, MIN_TRAVEL = 5, MAX_TRAVEL = 20;
    /** Hardness·blocks the store can push through per block of fuze travel (stone 1.5 is free-running). */
    public static final double BUDGET_PER_BLOCK = 2.5;
    /** A shorter run is no penetration: the surface blast already covers it. */
    public static final double MIN_BURST_DEPTH = 1.0;
    /**
     * The penetrator charge, as a share of the КАБ's blast radius. Fully buried it breaks about
     * a quarter of the blocks the surface blast does, and it never damages entities.
     */
    public static final float BURST_RADIUS_SHARE = 0.5F;

    private KabPenetration() {}

    public static double maxTravel(double radius) {
        if (!Double.isFinite(radius) || radius <= 0) return 0;
        return Math.max(MIN_TRAVEL, Math.min(MAX_TRAVEL, radius * DEPTH_PER_RADIUS));
    }

    public static double budget(double radius) { return maxTravel(radius) * BUDGET_PER_BLOCK; }

    /**
     * Distance from the impact point at which the fuze fires. {@code resistanceAt(i)} is the
     * material at the i-th step ((i + 0.5) * STEP along the path): 0 for air, the block
     * hardness for solids, FLUID_RESISTANCE for liquids, and a negative value or NaN where the
     * store must stop (unbreakable, unloaded or outside the world).
     */
    public static double depth(double radius, IntToDoubleFunction resistanceAt) {
        double budget = budget(radius);
        int steps = (int) Math.floor(maxTravel(radius) / STEP + 1.0E-9);
        for (int i = 0; i < steps; i++) {
            double resistance = resistanceAt.applyAsDouble(i);
            if (!(resistance >= 0) || resistance >= STOP_HARDNESS) return i * STEP;
            budget -= resistance * STEP;
            if (budget < 0) return i * STEP;
        }
        return steps * STEP;
    }

    /** True when the run is deep enough for a penetrator charge. */
    public static boolean bursts(double depth) { return depth >= MIN_BURST_DEPTH; }

    public static float burstRadius(float radius) { return radius * BURST_RADIUS_SHARE; }
}
