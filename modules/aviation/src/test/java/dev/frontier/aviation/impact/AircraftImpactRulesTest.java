package dev.frontier.aviation.impact;

public final class AircraftImpactRulesTest {
    public static void main(String[] args) {
        check(AircraftImpactRules.damage(.3, .3, 0, 0) == 0, "Taxi contact is harmless");
        check(AircraftImpactRules.damage(3, 0, .15, 0) == 0, "Safe runway sink at flight speed");
        check(AircraftImpactRules.damage(3, 0, .4, 0) == 0, "Landing sink threshold");
        check(AircraftImpactRules.damage(3, 0, 0, 3) > 360, "Combat nose strike destroys F-16");
        check(AircraftImpactRules.damage(3, 3, 0, 0) > 360, "Combat wing strike destroys F-16");
        check(AircraftImpactRules.damage(3, 0, 3, 0) > 360, "Combat fuselage strike destroys F-16");
        check(AircraftImpactRules.damage(3, 0, .8, 0) > 0, "Hard landing damages aircraft");
        check(AircraftImpactRules.damage(4, 0, 0, 0) == 0, "Free flight never damages aircraft");
        check(AircraftImpactRules.damage(Double.NaN, 0, 3, 0) == 0, "Invalid movement rejected");
        check(AircraftImpactRules.damage(100000, 100000, 0, 0) == 10000, "Damage is bounded");
        System.out.println("AircraftImpactRulesTest: 10 checks passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
