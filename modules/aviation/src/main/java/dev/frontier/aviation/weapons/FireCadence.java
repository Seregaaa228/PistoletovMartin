package dev.frontier.aviation.weapons;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Server-side ceiling on native SBW fire requests for this project's aircraft.
 *
 * <p>SBW's {@code VehicleFireMessage} paces fire only on the client: each
 * accepted message queues one round, and heat is the only server limit, so a
 * modified client can empty the heat budget in one tick. This token bucket
 * refills at the weapon's own RPM (plus a small clock/rounding allowance) and
 * holds half a second of rounds, so the native client's catch-up loop and
 * network jitter pass while a burst stays bounded.</p>
 */
public final class FireCadence {
    static final double BURST_SECONDS = .5, RATE_ALLOWANCE = 1.05;
    // SBW's own client falls back to this cadence for a weapon without RPM.
    static final int DEFAULT_RPM = 240;
    private static final Map<Object, Map<String, FireCadence>> OWNERS = new WeakHashMap<>();

    private double tokens;
    private long last;
    private boolean started;

    /** Main-thread caller; weak keys release a removed vehicle's buckets. */
    public static boolean allow(Object vehicle, String weapon, int rpm, long nowNanos) {
        synchronized (OWNERS) {
            return OWNERS.computeIfAbsent(vehicle, ignored -> new HashMap<>())
                .computeIfAbsent(weapon, ignored -> new FireCadence()).tryFire(rpm, nowNanos);
        }
    }

    boolean tryFire(int rpm, long nowNanos) {
        double perSecond = (rpm > 0 ? rpm : DEFAULT_RPM) / 60.0;
        double capacity = Math.max(2, Math.ceil(perSecond * BURST_SECONDS));
        if (!started) {
            started = true;
            tokens = capacity;
        } else {
            long elapsed = Math.max(0, nowNanos - last);
            tokens = Math.min(capacity, tokens + elapsed / 1e9 * perSecond * RATE_ALLOWANCE);
        }
        last = nowNanos;
        if (tokens < 1) return false;
        tokens -= 1;
        return true;
    }
}
