package dev.frontier.arcade.airdefense;

import net.minecraft.world.phys.Vec3;

/** Pure kinematics: each complex reaches and hits what it should, and a hard break can beat the old S-125. */
public final class SamFlightTest {
    private static int checks;

    public static void main(String[] args) {
        // Turning is bounded and exact.
        Vec3 east = new Vec3(1, 0, 0), north = new Vec3(0, 0, -1);
        Vec3 turned = SamFlight.turn(east, north, Math.toRadians(10));
        require(Math.abs(Math.toDegrees(Math.acos(turned.dot(east))) - 10) < 1e-6, "turn limited to 10 deg");
        require(SamFlight.turn(east, east.scale(5), 0.1).distanceTo(east) < 1e-9, "aligned stays");
        require(Math.abs(SamFlight.turn(east, east.scale(-1), 0.2).length() - 1) < 1e-9, "opposite still unit");

        // Kinematic range: every missile flies at least its system's maximum range before it is spent.
        for (SamSystem system : SamSystem.values()) for (var rack : system.racks) {
            var s = new SamFlight.State(Vec3.ZERO, new Vec3(0, 0, 1), 0.5);
            double flown = 0;
            while (!SamFlight.spent(rack.missile(), s)) { Vec3 p = s.pos; SamFlight.step(s, rack.missile(), null, null); flown += s.pos.distanceTo(p); }
            double needed = rack.missile().shortReach() > 0 ? rack.missile().shortReach() : system.maxRange;
            require(flown > needed * 1.15, system + " " + rack.missile() + " reaches " + needed + " (flew " + (int) flown + ")");
        }

        // Without terminal error the guidance itself reaches every kind of target.
        for (SamMissile missile : SamMissile.values()) {
            double range = range(missile);
            double miss = engage(missile, range, 60, 4.7, 0, 0, null);
            require(miss <= missile.fuseRadius * .5, missile + " guides onto a straight jet, miss " + miss);
            require(engage(missile, range, 60, 4.7, Math.toRadians(8), 0, null) <= missile.fuseRadius, missile + " follows a break");
        }
        require(engage(SamMissile.M57E6, 140, 30, 2.1, 0, 0, null) <= SamMissile.M57E6.fuseRadius, "Pantsir vs Shahed");
        require(engage(SamMissile.M9M96, 140, 30, 3.3, 0, 0, null) <= SamMissile.M9M96.fuseRadius, "9M96 vs jet Geranium");

        // Kill chance with the terminal error, seeded: nominal against a straight jet, lower against a hard break.
        double[][] straight = {{.55, .85}, {.75, .95}, {.80, .97}, {.65, .92}, {.75, .95}, {.85, .99}};
        double s125Break = pk(SamMissile.V601, Math.toRadians(8)), s300Break = pk(SamMissile.M48N6, Math.toRadians(8));
        for (SamMissile missile : SamMissile.values()) {
            double p = pk(missile, 0);
            double[] band = straight[missile.ordinal()];
            System.out.printf("  %s Pk straight %.2f, 8 deg/tick break %.2f%n", missile, p, pk(missile, Math.toRadians(8)));
            require(p >= band[0] && p <= band[1], missile + " nominal kill chance " + p);
        }
        require(s125Break < .5, "a hard break beats the S-125 more often than not (" + s125Break + ")");
        require(s300Break > s125Break + .15, "the 48N6 holds on better (" + s300Break + ")");
        require(SamFlight.terminalError(SamMissile.V601, 0, Double.NaN) == 0, "NaN acceleration ignored");

        // Fuse geometry.
        double[] pass = SamFlight.closestApproach(new Vec3(0, 0, 0), new Vec3(10, 0, 0), new Vec3(5, 3, 0), new Vec3(5, 3, 0));
        require(Math.abs(pass[0] - 3) < 1e-9 && Math.abs(pass[1] - .5) < 1e-9, "closest approach mid-tick");
        require(SamFlight.damageFraction(SamMissile.M48N6, 1) == SamMissile.M48N6.lethality, "core lethal");
        require(SamFlight.damageFraction(SamMissile.M48N6, 8) == 0, "outside fuse");
        require(SamFlight.damageFraction(SamMissile.V601, 5) < SamMissile.V601.lethality, "edge reduced");
        require(SamFlight.damageFraction(SamMissile.V601, Double.NaN) == 0, "NaN miss");

        // Envelope and missile choice.
        int[] s400 = {2, 8};
        require(SamEnvelope.choose(SamSystem.S400, s400, SamEnvelope.Kind.DRONE, 100, 40) == SamMissile.M9M96, "9M96 for drones");
        require(SamEnvelope.choose(SamSystem.S400, s400, SamEnvelope.Kind.JET, 300, 80) == SamMissile.M48N6, "48N6 far");
        require(SamEnvelope.choose(SamSystem.S400, new int[]{0, 8}, SamEnvelope.Kind.JET, 300, 80) == null, "9M96 cannot reach 300");
        require(SamEnvelope.choose(SamSystem.S400, new int[]{2, 0}, SamEnvelope.Kind.DRONE, 100, 40) == SamMissile.M48N6, "fallback");
        require(SamEnvelope.choose(SamSystem.S300, new int[]{4}, SamEnvelope.Kind.JET, 100, 4) == null, "below minimum altitude");
        require(!SamEnvelope.visible(SamSystem.S300, SamEnvelope.Kind.DRONE, 250, 40), "small drone unseen far out");
        require(SamEnvelope.visible(SamSystem.PANTSIR, SamEnvelope.Kind.DRONE, 200, 10), "Pantsir sees drones");
        require(!SamEnvelope.visible(SamSystem.S125, SamEnvelope.Kind.JET, 100, 1), "nap-of-earth hides");
        require(SamEnvelope.threat(SamEnvelope.Kind.JET, 100, 4) > SamEnvelope.threat(SamEnvelope.Kind.JET, 200, 4), "nearer first");
        int[] patriot = {2, 8};
        require(SamEnvelope.choose(SamSystem.PATRIOT, patriot, SamEnvelope.Kind.MISSILE, 250, 120) == SamMissile.PAC2, "PAC-2 beyond PAC-3 reach");
        require(SamEnvelope.choose(SamSystem.PATRIOT, patriot, SamEnvelope.Kind.MISSILE, 150, 120) == SamMissile.PAC3, "PAC-3 against missiles");
        require(SamEnvelope.choose(SamSystem.PATRIOT, patriot, SamEnvelope.Kind.JET, 280, 80) == SamMissile.PAC2, "PAC-2 for far jets");
        require(SamEnvelope.choose(SamSystem.S125, new int[]{4}, SamEnvelope.Kind.MISSILE, 120, 60) == null, "S-125 does not engage missiles");
        require(SamEnvelope.choose(SamSystem.S300, new int[]{4}, SamEnvelope.Kind.MISSILE, 120, 60) == SamMissile.M48N6, "S-300 engages missiles");
        System.out.println("SamFlightTest: " + checks + " checks passed");
    }

    private static double range(SamMissile missile) {
        return switch (missile) { case V601 -> 150; case M57E6, M9M96, PAC3 -> 120; default -> 220; };
    }

    private static double pk(SamMissile missile, double turnRate) {
        var random = new java.util.Random(1234 + missile.ordinal());
        int hits = 0, trials = 300;
        for (int i = 0; i < trials; i++) {
            double range = range(missile);
            double error = SamFlight.terminalError(missile, random.nextGaussian(), turnRate * 4.7);
            double angle = random.nextDouble() * Math.PI * 2;
            if (engage(missile, range, 60, 4.7, turnRate, error, angle) <= missile.fuseRadius) hits++;
        }
        return hits / (double) trials;
    }

    /**
     * Launch at a target crossing at constant speed (turning away at {@code turnRate} once the
     * missile is within 60 blocks). {@code error} is the terminal aim offset, applied
     * perpendicular to the line of sight at {@code angle}. Returns the closest miss distance.
     */
    private static double engage(SamMissile missile, double range, double altitude, double speed, double turnRate,
                                 double error, Double angle) {
        Vec3 target = new Vec3(-range * .3, altitude, range);
        Vec3 heading = new Vec3(1, 0, 0);
        Vec3 up = missile.verticalTicks > 0 ? new Vec3(0, 1, 0) : target.normalize();
        var s = new SamFlight.State(new Vec3(0, 2, 0), up, 0.5);
        double best = Double.MAX_VALUE;
        boolean breaking = false;
        Vec3 offset = null;
        while (!SamFlight.spent(missile, s)) {
            if (turnRate > 0 && s.pos.distanceTo(target) < 60) breaking = true;
            if (breaking) heading = SamFlight.turn(heading, new Vec3(-heading.z, 0, heading.x), turnRate);
            Vec3 velocity = heading.scale(speed);
            // The error is fixed in the world when the end game starts, as the entity does.
            if (angle != null && offset == null && target.distanceTo(s.pos) < s.speed * SamFlight.END_GAME_TICKS) {
                Vec3 los = target.subtract(s.pos).normalize();
                Vec3 a = los.cross(new Vec3(0, 1, 0)).normalize(), b = a.cross(los);
                offset = a.scale(Math.cos(angle) * error).add(b.scale(Math.sin(angle) * error));
            }
            Vec3 missileStart = s.pos, targetStart = target;
            SamFlight.step(s, missile, target, velocity, offset);
            target = target.add(velocity);
            double d = SamFlight.closestApproach(missileStart, s.pos, targetStart, target)[0];
            best = Math.min(best, d);
            if (d <= missile.fuseRadius * .5 && angle == null) break;
            if (best < Double.MAX_VALUE && d > best + 20) break;
        }
        return best;
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
