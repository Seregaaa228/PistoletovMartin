package dev.frontier.aviation.weapons;

/** Server fire ceiling: native client cadence passes, a one-tick flood does not. */
public final class FireCadenceTest {
    private static final long MS = 1_000_000L;

    public static void main(String[] args) {
        // A modified client floods one tick: only the half-second burst passes,
        // far below the ~66 rounds the 1.5-heat cannon budget would allow.
        var flood = new FireCadence();
        int accepted = 0;
        for (int shot = 0; shot < 200; shot++) if (flood.tryFire(1200, 0)) accepted++;
        check(accepted == 10, "1200 RPM burst bounded to half a second, got " + accepted);

        // SBW's client sends one 1200 RPM round every 50 ms; with jitter that
        // bunches four packets into one server tick, every round still passes.
        var native_ = new FireCadence();
        long now = 0;
        for (int shot = 0; shot < 400; shot++) {
            now += 50 * MS;
            long arrival = now - (shot % 4) * 50 * MS + 150 * MS;
            check(native_.tryFire(1200, Math.max(arrival, 0)), "native cadence round " + shot + " rejected");
        }

        // Sustained flooding is held to the weapon's RPM plus the allowance.
        var sustained = new FireCadence();
        accepted = 0;
        for (long t = 0; t <= 10_000 * MS; t += 5 * MS) if (sustained.tryFire(1200, t)) accepted++;
        check(accepted <= 10 + 200 * FireCadence.RATE_ALLOWANCE + 1, "sustained ceiling exceeded: " + accepted);
        check(accepted >= 200, "sustained cadence starved: " + accepted);

        // Slow weapons keep a minimum two-round bucket; a missing RPM uses SBW's fallback.
        var slow = new FireCadence();
        check(slow.tryFire(60, 0) && slow.tryFire(60, 0) && !slow.tryFire(60, 0), "60 RPM burst");
        check(slow.tryFire(60, 1000 * MS), "60 RPM refill after one second");
        var fallback = new FireCadence();
        accepted = 0;
        for (int shot = 0; shot < 20; shot++) if (fallback.tryFire(0, 0)) accepted++;
        check(accepted == 2, "fallback RPM burst " + accepted);

        // A clock that steps backwards never mints rounds.
        var backwards = new FireCadence();
        for (int shot = 0; shot < 10; shot++) backwards.tryFire(1200, 1000 * MS);
        check(!backwards.tryFire(1200, 0), "backward clock refilled");

        // Buckets are per vehicle and per weapon.
        Object a = new Object(), b = new Object();
        for (int shot = 0; shot < 10; shot++) check(FireCadence.allow(a, "Cannon", 1200, 0), "vehicle a");
        check(!FireCadence.allow(a, "Cannon", 1200, 0), "vehicle a exhausted");
        check(FireCadence.allow(b, "Cannon", 1200, 0), "vehicle b independent");
        check(FireCadence.allow(a, "AAMissile", 60, 0), "weapon independent");
        System.out.println("FireCadenceTest passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
