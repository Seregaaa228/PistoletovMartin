package dev.frontier.flamingofix;

public final class FarFlightCadenceTest {
    public static void main(String[] args) {
        int checks = 0;
        if (!FarFlightCadence.unconditional(false, false, 500)) throw new AssertionError("terminal packet throttled");
        if (!FarFlightCadence.unconditional(true, true, 500)) throw new AssertionError("intercept packet throttled");
        if (!FarFlightCadence.unconditional(true, false, 16)) throw new AssertionError("launch packet throttled");
        if (FarFlightCadence.unconditional(true, false, 200)) throw new AssertionError("cruise packet not throttled");
        checks += 4;
        for (int id = -7; id < 40; id++) {
            long previous = Long.MIN_VALUE; int sent = 0;
            for (long tick = 1000; tick < 1400; tick++) {
                if (!FarFlightCadence.farTick(tick, id)) continue;
                if (previous != Long.MIN_VALUE && tick - previous != FarFlightCadence.FAR_INTERVAL)
                    throw new AssertionError("far cadence gap id=" + id);
                previous = tick; sent++;
            }
            if (sent != 400 / FarFlightCadence.FAR_INTERVAL) throw new AssertionError("far rate id=" + id);
            checks++;
        }
        if (FarFlightCadence.nearRadius(10) < 256 || FarFlightCadence.nearRadius(32) < 32 * 16)
            throw new AssertionError("near radius below tracking range");
        checks++;
        System.out.println("FarFlightCadenceTest: " + checks + " checks passed");
    }
}
