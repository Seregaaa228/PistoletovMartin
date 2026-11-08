package dev.frontier.arcade.airdefense;

/**
 * Interceptor types. Game numbers, not real weapon data: blocks and ticks, tuned against the jets
 * (F-16 up to ~4.7 blocks/tick) and the drones (Shahed ~2.1, jet Geranium ~3.3).
 *
 * <p>A missile accelerates during its motor burn, then coasts and bleeds speed; its lateral
 * acceleration falls with the square of speed, so a long shot arrives slower and less agile.
 * Lethality is the fraction of the target's maximum health removed by a hit at the fuse core.</p>
 */
public enum SamMissile {
    /** S-125 V-601: the old two-stage missile. Fast enough for jets, but a hard break can beat it. */
    V601(0, 12, 7.0, 0.004, 1.1, 90, 5.0, 0.85, 0.30, 2.5, 4.8, 4.3, 1.0F),
    /** S-300/S-400 48N6: vertical launch, long reach, kills any aircraft it reaches. */
    M48N6(6, 25, 10.0, 0.003, 2.2, 110, 7.0, 1.4, 0.12, 3.0, 4.5, 4.2, 1.25F),
    /** S-400 9M96: short range, very agile; the S-400's answer to drones and helicopters. */
    M9M96(4, 14, 9.0, 0.006, 3.2, 70, 4.0, 1.2, 0.08, 3.0, 2.4, 2.25, 0.9F),
    /** Pantsir-S1 57E6: a fast dart with a small warhead; burns out quickly and decelerates. */
    M57E6(0, 6, 11.0, 0.012, 2.6, 60, 3.0, 0.6, 0.20, 3.0, 2.3, 2.15, 0.7F);

    /** Ticks flown straight up before guidance (vertical launch); zero for rail launch. */
    public final int verticalTicks;
    public final int boostTicks;
    public final double maxSpeed;
    /** Fraction of speed lost per tick after burnout. */
    public final double drag;
    /** Lateral acceleration (blocks/tick^2) available at full speed. */
    public final double maxLateral;
    public final int lifeTicks;
    public final double fuseRadius;
    public final double lethality;
    /** Chance that one fresh decoy near the target seduces the missile. */
    public final double decoyChance;
    /** Below this speed the missile self-destructs. */
    public final double minSpeed;
    /**
     * Terminal guidance error: a burst lands {@code sigma * |N(0,1)| + maneuverPenalty * a} blocks
     * off the target, where {@code a} is the target's lateral acceleration (blocks/tick^2) in the
     * end game. Against a straight flyer that gives the system's nominal kill chance; a hard break
     * (8 deg/tick at full speed is about 0.66) roughly halves the S-125's.
     */
    public final double sigma, maneuverPenalty;
    /** Visual size of the plume and trail. */
    public final float plume;

    SamMissile(int verticalTicks, int boostTicks, double maxSpeed, double drag, double maxLateral, int lifeTicks,
               double fuseRadius, double lethality, double decoyChance, double minSpeed, double sigma, double maneuverPenalty,
               float plume) {
        this.verticalTicks = verticalTicks;
        this.boostTicks = boostTicks;
        this.maxSpeed = maxSpeed;
        this.drag = drag;
        this.maxLateral = maxLateral;
        this.lifeTicks = lifeTicks;
        this.fuseRadius = fuseRadius;
        this.lethality = lethality;
        this.decoyChance = decoyChance;
        this.minSpeed = minSpeed;
        this.sigma = sigma;
        this.maneuverPenalty = maneuverPenalty;
        this.plume = plume;
    }

    public static SamMissile of(int ordinal) {
        SamMissile[] all = values();
        return all[Math.floorMod(ordinal, all.length)];
    }

    /** True while the motor burns (plume and thick smoke). */
    public boolean burning(int age) { return age < verticalTicks + boostTicks; }
}
