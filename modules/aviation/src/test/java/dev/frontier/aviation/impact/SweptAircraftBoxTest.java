package dev.frontier.aviation.impact;

public final class SweptAircraftBoxTest {
    private static final double[] AXES = {1,0,0, 0,1,0, 0,0,1};
    public static void main(String[] args) {
        var thin = new SweptAircraftBox.Box(0, 0, -5, 2, .15, .2, AXES);
        // The native endpoint MTV can see neither the start nor the end here:
        // z=-5 +/- .2 -> z=5 +/- .2 crosses a .2-block obstacle at zero.
        check(-5 + .2 < -.1 && 5 - .2 > .1, "Native endpoint overlap misses crossed thin obstacle");
        var hit = SweptAircraftBox.contact(thin, -.1, -.1, -.1, .1, .1, .1, 0, 0, 10);
        check(hit != null && hit.time() > .45 && hit.time() < .5 && Math.abs(hit.nz()) == 1,
                "Continuous sweep detects crossed thin wing/nose obstacle");
        double allowed = SweptAircraftBox.allowedFraction(hit.time(), 10);
        check(-5 + allowed * 10 + .2 < -.1, "Clamped movement stops before the crossed obstacle");
        check(SweptAircraftBox.allowedFraction(0, 3) == 0, "Ongoing contact cannot advance through during cooldown");
        check(SweptAircraftBox.contact(thin, -.1, .3, -.1, .1, .5, .1, 0, 0, 10) == null,
                "Separated near miss stays safe");
        var slider = new SweptAircraftBox.Box(0, 1.5, 0, .5, .5, .5, AXES);
        check(SweptAircraftBox.contact(slider, -1, 0, -1, 1, 1, 1, 3, 0, 0) == null,
                "Tangential sliding on a touching runway creates no impact");
        var landing = SweptAircraftBox.contact(slider, -8, 0, -8, 8, 1, 8, 1.1, -.2, 0);
        check(landing != null && AircraftImpactRules.damage(Math.hypot(1.1,.2), 0, .2, 0) == 0,
                "Runway normal speed, rather than horizontal flight speed, controls landing damage");
        var nose = new SweptAircraftBox.Box(0, 2, 0, 1, 1, 7.6, AXES);
        check(SweptAircraftBox.contact(nose, -.5, 1.5, 8, .5, 2.5, 9, 0, 0, 3) != null,
                "Nose outside central four-block bounding radius is detected");
        check(SweptAircraftBox.contact(nose, 8, 1.5, -.5, 9, 2.5, .5, 0, 0, 3) == null,
                "Wide central AABB cannot create a false wing contact");
        double c = Math.sqrt(.5);
        var rotated = new SweptAircraftBox.Box(0, 0, -5, 2, .15, .2,
                new double[]{c,0,c, 0,1,0, -c,0,c});
        check(SweptAircraftBox.contact(rotated, -.1, -.1, -.1, .1, .1, .1, 0, 0, 10) != null,
                "Rotated aircraft axes are respected");
        var wing = new SweptAircraftBox.Box(5, 0, 0, .5, .15, .2, AXES);
        var glance = SweptAircraftBox.contact(wing, 0, -.5, .3, 12, .5, 1.3, 3, 0, .3);
        check(glance != null && Math.abs(glance.nz()) == 1
                        && AircraftImpactRules.damage(Math.hypot(3,.3), 0, 0, .3) == 0,
                "Shallow wing contact remains a physical hit without damage");
        var clipped = SweptAircraftBox.clipMotion(glance.time(), glance.nx(), glance.ny(), glance.nz(), 3, 0, .3);
        check(clipped[2] + .2 < .3 && clipped[0] == 3,
                "Shallow wing is stopped at wall while full tangent motion is retained");
        var touching = SweptAircraftBox.contact(wing, 0, -.5, .2, 12, .5, 1.2, 3, 0, .3);
        var slide = SweptAircraftBox.clipMotion(touching.time(), touching.nx(), touching.ny(), touching.nz(), 3, 0, .3);
        check(slide[0] == 3 && Math.abs(slide[2]) < 1e-8,
                "Existing wall contact permits tangent sliding without entering the wall");
        System.out.println("SweptAircraftBoxTest: 13 continuous geometry checks passed");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
