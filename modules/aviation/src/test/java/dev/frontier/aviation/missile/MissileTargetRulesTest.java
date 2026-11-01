package dev.frontier.aviation.missile;

public final class MissileTargetRulesTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        check(MissileTargetRules.eligible(0, 0, 256, 0, 0, 1), "range endpoint");
        check(!MissileTargetRules.eligible(0, 0, 256.001, 0, 0, 1), "range exceeded");
        check(!MissileTargetRules.eligible(0, 0, 0.99, 0, 0, 1), "near target");
        check(!MissileTargetRules.eligible(Double.NaN, 0, 10, 0, 0, 1), "NaN target");
        check(!MissileTargetRules.eligible(0, 0, 10, 0, Double.POSITIVE_INFINITY, 1), "infinite forward");
        check(!MissileTargetRules.eligible(0, 0, 10, 0, 0, 0), "zero forward");
        double a = Math.toRadians(21.5);
        check(MissileTargetRules.eligible(100 * Math.sin(a), 0, 100 * Math.cos(a), 0, 0, 1), "parallax allowance");
        a = Math.toRadians(24);
        check(!MissileTargetRules.eligible(100 * Math.sin(a), 0, 100 * Math.cos(a), 0, 0, 1), "outside cone");
        check(MissileTargetRules.closeToHit(0.75, 0, 0), "hit tolerance endpoint");
        check(!MissileTargetRules.closeToHit(0.751, 0, 0), "obstruction not proposal");
        check(!MissileTargetRules.closeToHit(Double.NaN, 0, 0), "non-finite hit");
        var limiter = new MissileTargetRules.AttemptLimiter();
        check(limiter.allow(100), "first attempt");
        check(!limiter.allow(100), "miss consumes tick");
        check(limiter.allow(101), "next tick retry");
        check(!limiter.allow(101), "successful or failed retry consumes tick");
        System.out.println("Missile target rules passed");
    }
}
