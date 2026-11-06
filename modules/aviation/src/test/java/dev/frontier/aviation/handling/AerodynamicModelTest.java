package dev.frontier.aviation.handling;

import java.util.Random;
import static dev.frontier.aviation.handling.AerodynamicModel.*;

/** Checks physical invariants of the actual production presets and custom profiles. */
public final class AerodynamicModelTest {
    private static int checks;
    public static void main(String[] args) {
        var forward = new Vector(0, 0, 1);
        var up = new Vector(0, 1, 0);
        var velocity = new Vector(2, -2, 5);
        check(correction(HandlingProfile.NATIVE, velocity, forward, up, false).acceleration().length() == 0, "native inert");
        check(correction(HandlingProfile.FIGHTER, velocity, forward, up, true).acceleration().length() == 0, "ground inert");
        var result = correction(HandlingProfile.FIGHTER, velocity, forward, up, false);
        check(result.acceleration().x() < 0, "opposes sideslip");
        check(result.acceleration().y() == 0 && result.acceleration().z() == 0, "flat flight adds no lift or thrust");
        var slow = correction(HandlingProfile.FIGHTER, new Vector(.001, 0, .01), forward, up, false);
        check(slow.damping() < result.damping(), "speed-dependent damping");
        check(correction(HandlingProfile.ATTACK, velocity, forward, up, false).damping() != result.damping(), "model-specific handling");
        var inverted = correction(HandlingProfile.FIGHTER, new Vector(0, -2, 5), forward, up.scale(-1), false);
        check(inverted.acceleration().length() == 0, "inverted fall remains native");

        var random = new Random(0xA17C4AFL);
        for (int i = 0; i < 2000; i++) {
            double pitch = (random.nextDouble() - .5) * Math.PI;
            double bank = (random.nextDouble() * 2 - 1) * Math.PI;
            var f = new Vector(0, Math.sin(pitch), Math.cos(pitch));
            var unbankedUp = new Vector(0, Math.cos(pitch), -Math.sin(pitch));
            var u = unbankedUp.scale(Math.cos(bank)).add(new Vector(1, 0, 0).scale(Math.sin(bank)));
            var right = u.cross(f).normalize();
            double slip = (random.nextDouble() * 2 - 1) * 4;
            var v = f.scale(random.nextDouble() * 6 - 1).add(u.scale(random.nextDouble() * 4 - 2)).add(right.scale(slip));
            for (var profile : new HandlingProfile[]{HandlingProfile.FIGHTER, HandlingProfile.ATTACK,
                    new HandlingProfile(false, .9f, .99f, .1f, .02f)}) {
                var c = correction(profile, v, f, u, false);
                var after = v.add(c.acceleration());
                check(after.lengthSquared() <= v.lengthSquared() + 1e-9, "no kinetic energy added");
                check(Math.abs(c.acceleration().dot(f)) < 1e-9, "forward component retained");
                check(Math.abs(c.acceleration().dot(u)) < 1e-9, "body-up component retained");
                check(Math.abs(after.dot(right)) <= Math.abs(slip) + 1e-9, "sideslip dissipates");
                check(after.dot(right) * slip >= -1e-9, "does not overshoot zero sideslip");
                check(c.acceleration().length() <= profile.maximumCorrectionPerTick() + 1e-9, "bounded per-step correction");
            }
        }
        var tiny = new Vector(1e-12, 0, 1);
        var after = tiny.add(correction(new HandlingProfile(false, 1, 1, 0, 1), tiny, forward, up, false).acceleration());
        check(after.lengthSquared() <= tiny.lengthSquared(), "tiny slip never gains energy");
        try {
            new HandlingProfile(false, 2, .1f, 1, .1f);
            throw new AssertionError("unbounded damping accepted");
        } catch (IllegalArgumentException expected) { checks++; }
        groundStance();
        groundSteeringBrakesAndGrip();
        buffet();
        dynamicsValidation();
        System.out.println("AerodynamicModelTest: " + checks + " checks passed");
    }

    private static void groundStance() {
        float stance = GroundHandling.GEAR_STANCE_PITCH, rotation = 1.1f;
        var level = GroundHandling.settle(stance, 0, 0, rotation, stance);
        check(level.pitch() == stance && level.roll() == 0, "a level jet stays exactly level");
        // Landed nose-high and wing-low, then slowed below rotation speed.
        float pitch = -8, roll = 6;
        int ticks = 0;
        while ((pitch != stance || roll != 0) && ticks < 100) {
            var next = GroundHandling.settle(pitch, roll, .5f, rotation, stance);
            check(next.pitch() - pitch <= GroundHandling.DEROTATION_RATE + 1e-6f, "gentle derotation");
            check(next.pitch() >= pitch && next.pitch() <= stance, "nose comes down monotonically to the stance");
            check(Math.abs(next.roll()) <= Math.abs(roll) && next.roll() * roll >= 0, "wings level without overshoot");
            pitch = next.pitch(); roll = next.roll(); ticks++;
        }
        check(pitch == stance && roll == 0, "settled exactly on the gear");
        check(ticks <= 20, "settles within a second, not the native 5+ seconds: " + ticks);
        // A bounce off a block edge (nose-down) recovers quickly.
        var bounce = GroundHandling.settle(5, 0, .3f, rotation, stance);
        check(bounce.pitch() < 5 && 5 - bounce.pitch() <= GroundHandling.SETTLE_RATE + 1e-6f, "nose-down bounce recovers at once");
        // Above rotation speed a pilot-held rotation is kept, but only up to the tail limit.
        check(GroundHandling.settle(-6, 0, 1.2f, rotation, stance).pitch() == -6, "rotation held above Vr");
        var strike = GroundHandling.settle(-20, 0, 1.2f, rotation, stance);
        check(strike.pitch() > -20 && strike.pitch() <= -20 + GroundHandling.DEROTATION_RATE + 1e-6f, "over-rotation eases to the tail limit");
        float p = -20;
        for (int i = 0; i < 60; i++) p = GroundHandling.settle(p, 0, 1.2f, rotation, stance).pitch();
        check(p == stance - GroundHandling.MAX_ROTATION, "never more than the tail-strike rotation");
        check(GroundHandling.settle(3, 0, 1.2f, rotation, stance).pitch() < 3, "never nose-down on the wheels even when fast");
        check(GroundHandling.settle(Float.NaN, Float.NaN, 0, rotation, stance).pitch() == stance, "non-finite attitude recovers");
        // Contact band shared by server and client: covers a slab drop and small position
        // corrections, but a full block drop is a hop and a take-off leaves the ground.
        check(GroundHandling.CONTACT_BAND >= .5 && GroundHandling.CONTACT_BAND < 1, "gear contact band between a slab and a block");
    }

    private static void groundSteeringBrakesAndGrip() {
        for (var d : new FlightDynamics[]{FlightDynamics.SU25, FlightDynamics.J10, FlightDynamics.F16}) {
            check(GroundHandling.steeringRate(0, d) == 0, "no pivot turn at a standstill");
            float taxi = GroundHandling.steeringRate(.3f, d);
            // Native ground steering at 0.3 b/t: yawSpeed * 0.6 * 0.3 / 0.75 = 0.24 deg/tick.
            check(taxi > 3 * .24f, "nose-wheel steering works at taxi speed: " + taxi);
            check(taxi <= d.steeringRate() + 1e-6f, "bounded steering rate");
            check(GroundHandling.steeringRate(.1f, d) < taxi, "a fixed turn radius at a crawl");
            float fast = GroundHandling.steeringRate(2.5f, d);
            check(fast < taxi && fast > 0, "nose wheel fades toward take-off speed");
            double speed = 2.5;
            int ticks = 0;
            while (speed > 0 && ticks < 200) {
                double next = GroundHandling.brake(speed, d);
                check(next < speed && next >= 0, "brakes only slow down");
                speed = next; ticks++;
            }
            check(ticks > 15 && ticks < 80, "brakes stop 180 km/h in a few seconds, not instantly: " + ticks);
            speed = .3; ticks = 0;
            while (speed > 0 && ticks < 200) { speed = GroundHandling.brake(speed, d); ticks++; }
            check(ticks <= 25, "brakes stop a taxiing jet within about a second: " + ticks);
            check(GroundHandling.brake(Double.NaN, d) == 0 && GroundHandling.brake(-1, d) == 0, "invalid speeds stop");
            double lateral = .4;
            double gripped = GroundHandling.grip(lateral, d);
            check(gripped < lateral && gripped > 0, "tyres remove sideways speed without reversing it");
            check(GroundHandling.grip(-lateral, d) == -gripped, "grip symmetric");
        }
    }

    private static void buffet() {
        float stall = 1.25f;
        check(Buffet.intensity(3, stall, 2, 200, false) == 0, "smooth at cruise, high up");
        check(Buffet.intensity(0, stall, 0, 0, true) == 0, "parked jet does not shake");
        float roll = Buffet.intensity(2, stall, 0, 0, true);
        check(roll > 0 && roll <= .25f, "light take-off-roll rumble");
        check(Buffet.intensity(stall * 1.02f, stall, 3, 200, false) > .8f, "buffet near the stall");
        check(Buffet.intensity(3, stall, 20, 200, false) == 1, "buffet at a high angle of attack");
        float low = Buffet.intensity(3, stall, 2, 2, false);
        check(low > 0 && low < .5f, "light turbulence low and fast");
        check(Buffet.intensity(3, 0, 30, 0, false) == 0, "no aerodynamic buffet without a stall model");
        for (float i : new float[]{0, .3f, 1, 5}) {
            float bound = Math.min(1, i);
            for (int t = 0; t < 400; t++) {
                var s = Buffet.shake(t * .37, 77, i, 1);
                check(Math.abs(s.pitch()) <= Buffet.PITCH_AMPLITUDE * bound + 1e-6f
                    && Math.abs(s.roll()) <= Buffet.ROLL_AMPLITUDE * bound + 1e-6f, "bounded shake");
            }
        }
        var a = Buffet.shake(10, 1, 1, 1);
        check(a.pitch() == Buffet.shake(10, 1, 1, 1).pitch(), "deterministic shake");
        check(Buffet.shake(10, 1, 0, 1).pitch() == 0, "no intensity, no shake");
    }

    private static void dynamicsValidation() {
        check(!FlightDynamics.NONE.active(), "native dynamics inert");
        check(FlightDynamics.SU25.active() && FlightDynamics.J10.active() && FlightDynamics.F16.active(), "jets active");
        check(FlightDynamics.SU25.response() < FlightDynamics.J10.response()
            && FlightDynamics.J10.response() < FlightDynamics.F16.response(), "Su-25 heaviest, F-16 crispest");
        check(FlightDynamics.SU25.release() < FlightDynamics.F16.release(), "Su-25 carries its rotation longest");
        try {
            new FlightDynamics(0, .5f, 0, 0, 0, 10, 1, 0, 0, 0, 0);
            throw new AssertionError("zero response accepted");
        } catch (IllegalArgumentException expected) { checks++; }
        try {
            new FlightDynamics(.5f, .5f, 0, 0, 0, 0, 1, 0, 0, 0, 0);
            throw new AssertionError("zero steering radius accepted");
        } catch (IllegalArgumentException expected) { checks++; }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
}
