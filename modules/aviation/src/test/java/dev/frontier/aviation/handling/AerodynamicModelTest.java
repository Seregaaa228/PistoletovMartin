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
        System.out.println("AerodynamicModelTest: " + checks + " checks passed");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
}
