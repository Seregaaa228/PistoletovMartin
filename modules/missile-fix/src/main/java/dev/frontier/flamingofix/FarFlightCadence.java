package dev.frontier.flamingofix;

/**
 * Per-recipient cadence for DeepStrike's FlightState broadcast.
 *
 * Only the network send cadence to distant observers changes: the missile's
 * server simulation still runs every tick. Nearby players (anyone who can be
 * inside normal entity-tracking range), the owner and the missile-camera viewer
 * keep every tick. Players beyond that range only feed the client's
 * serverTick-keyed ghost timeline, which already interpolates between samples.
 */
public final class FarFlightCadence {
    /** One far sample every 4 ticks (5 Hz): within DeepStrike's 3-tick presentation buffer
     *  plus one tick, and far below its 200-tick stale-flight timeout. */
    public static final int FAR_INTERVAL = 4;
    /** DeepStrike starts launch effects/acoustics only for flights first seen at age <= 16. */
    public static final int LAUNCH_AGE = 20;
    /** Never treat anyone closer than this (horizontal blocks) as far, regardless of view distance. */
    public static final int MIN_NEAR_BLOCKS = 256;

    /** Horizontal radius treated as normal tracking range; tracking is clamped to view distance. */
    public static double nearRadius(int viewDistanceChunks) {
        return Math.max(MIN_NEAR_BLOCKS, (viewDistanceChunks + 1) * 16.0);
    }

    /** True when every recipient must get this packet regardless of distance. */
    public static boolean unconditional(boolean alive, boolean intercepted, int flightAge) {
        // alive=false is the terminal/removal state; intercept is a one-off event.
        return !alive || intercepted || flightAge <= LAUNCH_AGE;
    }

    /** Far recipients get the packet on a phase spread by entity id so missiles don't burst together. */
    public static boolean farTick(long serverTick, int entityId) {
        return Math.floorMod(serverTick + entityId, FAR_INTERVAL) == 0;
    }

    private FarFlightCadence() { }
}
