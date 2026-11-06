package dev.frontier.aviation.impact;

public final class MidAirCollisionTest {
    private static final double[] AXES = {1,0,0, 0,1,0, 0,0,1};
    private static final double C = Math.sqrt(.5);
    private static int checks;

    public static void main(String[] args) {
        geometry();
        rules();
        System.out.println("MidAirCollisionTest: " + checks + " checks passed");
    }

    private static void geometry() {
        // F-16 wing (native OBB half sizes 5.75 x .22 x 2) of two jets, one diving 2 blocks/tick.
        var wing = new SweptAircraftBox.Box(0, -1, 0, 5.75, .22, 2, AXES);
        var other = new SweptAircraftBox.Box(0, 0, 0, 5.75, .22, 2, AXES);
        // A discrete end-of-tick sample (native crushEntities, every 4th tick) sees no overlap.
        check(SweptAircraftPair.contact(wing, other, 0, 2, 0, 0, 0, 0) == null
                && SweptAircraftPair.contact(wing, other, 0, 0, 0) == null,
                "Both tick endpoints are clear of the other wing");
        var dive = SweptAircraftPair.contact(wing, other, 0, 2, 0, 0, -2, 0);
        check(dive != null && dive.time() > .25 && dive.time() < .35 && dive.ny() == 1,
                "Wing crossing another wing between ticks is caught, normal from the hit jet");
        check(SweptAircraftPair.contact(wing, other, 12, 2, 0, 0, -2, 0) == null,
                "Passing beside the wingtip is a near miss");
        var formation = new SweptAircraftBox.Box(0, 0, 5, 5.75, .22, 2, AXES);
        check(SweptAircraftPair.contact(formation, other, 0, 0, 0) == null,
                "Separated formation without relative motion never touches");
        var overlap = SweptAircraftPair.contact(new SweptAircraftBox.Box(0, .1, 1, 5.75, .22, 2, AXES), other, 0, 0, 0);
        check(overlap != null && overlap.time() == 0, "Overlapping airframes report a contact at once");

        // Head-on fuselages (1.45 x 1 x 7.6) closing at 8 blocks/tick.
        var nose = new SweptAircraftBox.Box(0, 0, 0, 1.45, 1, 7.6, AXES);
        var tail = new SweptAircraftBox.Box(0, 0, 10, 1.45, 1, 7.6, AXES);
        var headOn = SweptAircraftPair.contact(nose, tail, 0, 0, -8, 0, 0, 8);
        check(headOn != null && headOn.nz() == -1 && Math.abs(headOn.time() - .35) < 1e-6,
                "Head-on closure hits nose to nose");
        check(SweptAircraftPair.contact(nose, tail, 0, 0, -8, 0, 0, -8) == null, "Diverging jets never hit");

        // A rotated thin wing whose world AABB overlaps the partner while the OBBs do not.
        double[] diagonal = {C,0,-C, 0,1,0, C,0,C};
        var rotated = new SweptAircraftBox.Box(3, 0, 3, 3, .5, .2, diagonal);
        var cube = new SweptAircraftBox.Box(0, 0, 0, 1, 1, 1, AXES);
        check(SweptAircraftPair.contact(rotated, cube, 0, 0, 0) == null,
                "Rotated airframe axes are respected; AABB overlap alone is no hit");
        var closing = SweptAircraftPair.contact(rotated, cube, -4 * C, 0, -4 * C);
        check(closing != null && Math.abs(closing.time() - (3 * Math.sqrt(2) - .2 - Math.sqrt(2)) / 4) < 1e-6
                        && Math.abs(closing.nx() - C) < 1e-9 && Math.abs(closing.nz() - C) < 1e-9,
                "Rotated face contact time and normal follow the airframe axis");
        // Edge-on crossing that only a cross-product axis separates.
        // Face axes alone overlap here (gap -0.44); only edge(z) x edge(x) = y separates (+0.37).
        double[] rollZ = {C,C,0, -C,C,0, 0,0,1};
        double[] rollX = {1,0,0, 0,C,C, 0,-C,C};
        var edgeA = new SweptAircraftBox.Box(0, 0, 0, 1, 1, 1, rollZ);
        var edgeB = new SweptAircraftBox.Box(0, 3.2, 0, 1, 1, 1, rollX);
        check(SweptAircraftPair.contact(edgeA, edgeB, 0, 0, 0) == null,
                "Edge-edge separation along a cross axis is not a contact");
        var edgeHit = SweptAircraftPair.contact(edgeA, edgeB, 0, 1, 0);
        check(edgeHit != null && Math.abs(edgeHit.time() - (3.2 - 2 * Math.sqrt(2))) < 1e-6
                        && Math.abs(edgeHit.ny() + 1) < 1e-9, "Edges closing along the cross axis do hit");
        check(SweptAircraftPair.contact(edgeA, edgeB, Double.NaN, 0, 0) == null, "Invalid motion is rejected");
    }

    private static void rules() {
        check(MidAirCollisionRules.exempt(0, true, .6, true), "Parked and taxiing jets on the ground are exempt");
        check(!MidAirCollisionRules.exempt(0, true, 3, true), "Take-off roll into a parked jet is not exempt");
        check(!MidAirCollisionRules.exempt(.5, false, .5, false), "Slow airborne helicopters are not exempt");
        check(MidAirCollisionRules.exempt(Double.NaN, false, 3, false), "Invalid speed is exempt");

        float f16 = 17, j10 = 18, tank = 60;
        check(MidAirCollisionRules.damage(MidAirCollisionRules.impact(.3, .3), f16, j10) == 0,
                "Formation bump is harmless");
        float moderate = MidAirCollisionRules.damage(MidAirCollisionRules.impact(1, 1), f16, f16);
        check(moderate > 7 && moderate < 360, "A 72 km/h bump damages without destroying");
        float headOn = MidAirCollisionRules.damage(MidAirCollisionRules.impact(8, 8), f16, j10);
        check(headOn > 360 && headOn <= MidAirCollisionRules.MAX_DAMAGE, "Head-on fighters destroy each other");
        float scrape = MidAirCollisionRules.damage(MidAirCollisionRules.impact(5, .1), f16, j10);
        check(scrape > 0 && scrape < headOn, "Fast parallel scrape hurts less than a head-on");
        check(MidAirCollisionRules.damage(MidAirCollisionRules.impact(3, 3), f16, tank)
                > MidAirCollisionRules.damage(MidAirCollisionRules.impact(3, 3), tank, f16),
                "The lighter body takes more of the impact");
        check(MidAirCollisionRules.damage(1e9, 1, 1e9f) == MidAirCollisionRules.MAX_DAMAGE, "Damage is bounded");
        check(MidAirCollisionRules.damage(Double.NaN, 1, 1) == 0, "Invalid impact rejected");
        check(MidAirCollisionRules.damage(3, 0, -1) > 0, "Unknown mass falls back to a neutral ratio");

        check(!MidAirCollisionRules.newContact(0, -1), "Overlapping bodies moving apart are no new hit");
        check(MidAirCollisionRules.newContact(0, 1) && MidAirCollisionRules.newContact(.5, 0),
                "Approaching overlap and swept contact are hits");
        check(MidAirCollisionRules.impulse(-1, 17, 17) == 0, "Separating bodies are not pushed");
        double impulse = MidAirCollisionRules.impulse(2, 17, 17);
        check(Math.abs(impulse / 17 - (1 + MidAirCollisionRules.RESTITUTION)) < 1e-9,
                "Equal masses each change normal speed by half the rebound");
        check(MidAirCollisionRules.pairKey(3, 9) == MidAirCollisionRules.pairKey(9, 3)
                && MidAirCollisionRules.pairKey(3, 9) != MidAirCollisionRules.pairKey(3, 10), "Pair key is symmetric");
        check(MidAirCollisionRules.pairKey(-1, 2) != MidAirCollisionRules.pairKey(2, -2), "Negative ids stay distinct");
        check(MidAirCollisionRules.sweepable(MidAirCollisionRules.MAX_SWEEP)
                && !MidAirCollisionRules.sweepable(MidAirCollisionRules.MAX_SWEEP + 1), "Teleports are not swept");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
