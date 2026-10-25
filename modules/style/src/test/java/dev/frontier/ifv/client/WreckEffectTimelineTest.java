package dev.frontier.ifv.client;

public final class WreckEffectTimelineTest {
    public static void main(String[] args) {
        var effect = new WreckEffectTimeline(500);
        int emissions = 0;
        for (int age = 0; age <= 300; age++) {
            long tick = 500 + age;
            if (effect.emit(tick)) {
                emissions++;
                check(!effect.emit(tick), "Multiple native calls cannot duplicate the same wreck emission");
                int bright = effect.brightLifetime(tick);
                int smoke = effect.smokeLifetime(tick);
                check(bright == 0 || age + bright <= 40, "All bright particles expire by two seconds");
                check(smoke == 0 || age + smoke <= 100, "All residual particles expire by five seconds");
            }
            if (age >= 40) check(effect.brightLifetime(tick) == 0, "No renewed bright phase");
            if (age >= 100) check(effect.smokeLifetime(tick) == 0, "No renewed residual phase");
        }
        check(emissions == 25, "At most 25 emission batches per wreck");
        System.out.println("WreckEffectTimelineTest: all lifetime and deduplication checks passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
