package dev.frontier.arcade.geranium;

import static dev.frontier.arcade.geranium.GeraniumFlightModel.*;

import dev.frontier.arcade.geranium.GeraniumFlightModel.Profile;
import java.util.List;
import java.util.Locale;

/** Pure offline flight checks for the hand-flown jet Geranium-2 and propeller Shahed-136 (one tick = 1/20 s). */
public final class GeraniumFlightModelTest {
    private static int checks;
    private static final short A = 1, D = 2, W = 4, S = 8, SPACE = 16;
    private static final List<Profile> PROFILES = List.of(Profile.JET, Profile.PROPELLER, Profile.LEGACY);

    public static void main(String[] args) {
        for (Profile p : PROFILES) {
            groundedUntilLaunch(p);
            launchClimbsThenLevels(p);
            throttleIsSmoothAndBounded(p);
            bankToTurnAndAutoLevel(p);
            sustainedMouseTurnNeverReverses(p);
            pitchIsRateLimitedAndHeld(p);
            offMonitorFliesLevel(p);
            stabilizeLevelsWithoutStopping(p);
            stallSinksAndRecovers(p);
            unpoweredFallsBallistically(p);
            repoweredFallContinuesAlongNose(p);
            malformedInputAndRestore(p);
        }
        for (Profile p : List.of(Profile.JET, Profile.PROPELLER)) {
            stickCurve(p);
            smoothRollWithoutOvershoot(p);
            lowSpeedBankLimit(p);
        }
        jetOutrunsPropeller();
        realisticSpeedsStayControllable();
        smootherThanLegacy();
        System.out.println("GeraniumFlightModelTest: " + checks + " checks passed");
    }

    private static GeraniumFlightModel cruising(Profile p, float pitch, float speed, float throttle) {
        GeraniumFlightModel m = new GeraniumFlightModel(p, 0, 0);
        m.restore(0, pitch, 0, speed, throttle, 200, true);
        return m;
    }

    private static GeraniumFlightModel cruising(Profile p) { return cruising(p, 0, p.cruiseSpeed(), p.defaultThrottle()); }

    private static void groundedUntilLaunch(Profile p) {
        GeraniumFlightModel m = new GeraniumFlightModel(p, 37, 0);
        for (int t = 0; t < 40; t++) { m.input(W); m.mouse(20, 20); m.tick(true, true); }
        require(!m.launched() && m.speed() == 0 && m.vx() == 0 && m.vy() == 0 && m.vz() == 0, p, "no motion before launch");
        require(m.yaw() == 37 && m.pitch() == 0 && m.flightTicks() == 0, p, "attitude unchanged before launch");
        m.input(SPACE);
        require(m.launched() && !m.launch(), p, "SPACE launches exactly once");
        m.input(SPACE); m.input((short) 0);
        require(m.flightTicks() == 0, p, "held SPACE does not relaunch");
    }

    private static void launchClimbsThenLevels(Profile p) {
        GeraniumFlightModel m = new GeraniumFlightModel(p, 90, 0);
        m.input(SPACE); m.input((short) 0);
        double y = 0, horizontal = 0, minSpeed = 9;
        for (int t = 0; t < LAUNCH_TICKS; t++) {
            m.mouse(0, 20); // a nose-down stick cannot spoil the launch climb
            m.tick(true, true); y += m.vy(); horizontal += Math.hypot(m.vx(), m.vz());
            minSpeed = Math.min(minSpeed, m.speed());
        }
        require(y > 3, p, "launch climbs clear of ground clutter: " + y);
        require(horizontal > 10, p, "launch moves forward: " + horizontal);
        require(minSpeed >= p.launchSpeed() * .95f && minSpeed > p.stallSpeed(), p, "launch never stalls: " + minSpeed);
        require(m.vx() < 0 && Math.abs(m.vz()) < 1e-6, p, "launch follows the initial heading (yaw 90 = west)");
        for (int t = 0; t < 40; t++) { m.mouse(0, 0); m.tick(true, true); y += m.vy(); }
        require(Math.abs(m.pitch()) < .2 && Math.abs(m.vy()) < .005, p, "levels off after the launch climb: " + m.pitch());
        require(m.speed() > p.minSpeed(), p, "cruise after launch above zero-throttle speed");
    }

    private static void throttleIsSmoothAndBounded(Profile p) {
        GeraniumFlightModel m = cruising(p);
        float previous = m.speed(), maxStep = 0;
        for (int t = 0; t < 400; t++) {
            m.input(W); m.tick(true, true);
            maxStep = Math.max(maxStep, Math.abs(m.speed() - previous)); previous = m.speed();
        }
        require(m.throttle() == 1, p, "W saturates throttle");
        require(Math.abs(m.speed() - p.maxSpeed()) < .01, p, "full throttle reaches max cruise: " + m.speed());
        require(maxStep <= p.maxThrustAccel() + 1e-4, p, "speed changes smoothly: " + maxStep);
        for (int t = 0; t < 400; t++) { m.input(S); m.tick(true, true); }
        require(m.throttle() == 0 && Math.abs(m.speed() - p.minSpeed()) < .01, p, "idle cruise is the minimum flying speed");
        require(m.speed() > p.stallSpeed() && Math.abs(m.vy()) < 1e-6, p, "idle level flight does not stall or sink");
        m.input((short) 0);
        float throttle = m.throttle();
        m.tick(false, true);
        require(m.throttle() == throttle, p, "leaving the monitor keeps the set throttle");
    }

    private static void bankToTurnAndAutoLevel(Profile p) {
        GeraniumFlightModel m = cruising(p);
        float turned = 0, maxRate = 0, limit = p.bankLimit(m.speed());
        for (int t = 0; t < 60; t++) {
            m.input(D); float before = m.yaw(); m.tick(true, true);
            float rate = wrapped(m.yaw() - before);
            require(rate >= -1e-4, p, "right bank never turns left");
            maxRate = Math.max(maxRate, rate); turned += rate;
        }
        require(Math.abs(m.roll() - limit) < 1e-3, p, "D banks right wing down to the bank limit: " + m.roll());
        require(maxRate <= p.maxTurnRate() + 1e-4 && maxRate > 2, p, "coordinated turn rate from bank: " + maxRate);
        require(turned > 45, p, "sustained bank turns right");
        require(Math.abs(m.vy()) < 1e-6, p, "a level coordinated turn keeps altitude");
        m.input((short) 0);
        int levelTicks = 0;
        while (Math.abs(m.roll()) > 1e-3 && levelTicks < 100) { m.tick(true, true); levelTicks++; }
        require(levelTicks <= (p.legacyRoll() ? 15 : 30), p, "centred stick levels the wings: " + levelTicks);
        float settled = m.yaw(); m.tick(true, true);
        require(Math.abs(wrapped(m.yaw() - settled)) < 1e-4, p, "wings level stop the turn");
        GeraniumFlightModel left = cruising(p);
        for (int t = 0; t < 30; t++) { left.input(A); left.tick(true, true); }
        require(left.roll() < 0 && wrapped(left.yaw()) < -10, p, "A banks and turns left");
    }

    private static void sustainedMouseTurnNeverReverses(Profile p) {
        GeraniumFlightModel m = cruising(p);
        float total = 0;
        for (int t = 0; t < 200; t++) {
            m.mouse(20, 0); float before = m.yaw(); m.tick(true, true);
            float rate = wrapped(m.yaw() - before);
            require(rate >= -1e-4, p, "a long right mouse swipe never reverses the turn (tick " + t + ")");
            total += rate;
        }
        require(total > 360, p, "a full stick can circle: " + total);
        for (int t = 0; t < 40; t++) { m.mouse(0, 0); m.tick(true, true); }
        float settled = m.yaw(); m.tick(true, true);
        require(Math.abs(wrapped(m.yaw() - settled)) < 1e-4, p, "turn stops soon after the mouse stops");
        m.mouse(20, 0); m.tick(true, true);
        for (int t = 0; t < 40; t++) m.tick(true, true); // no further packets: stale mouse expires
        require(Math.abs(m.roll()) < 1e-3, p, "stale mouse input expires and the wings level: " + m.roll());
    }

    private static void pitchIsRateLimitedAndHeld(Profile p) {
        GeraniumFlightModel m = cruising(p);
        float start = m.speed(), previous = m.pitch(), previousRate = 0;
        for (int t = 0; t < 20; t++) {
            m.mouse(0, 20); m.tick(true, true);
            float rate = m.pitch() - previous;
            require(Math.abs(rate) <= p.maxPitchRate() + 1e-4, p, "pitch rate limit");
            require(Math.abs(rate - previousRate) <= p.pitchAccel() + 1e-4, p, "pitch acceleration limit");
            previous = m.pitch(); previousRate = rate;
        }
        float dive = m.pitch();
        require(dive > 15, p, "mouse down noses over: " + dive);
        for (int t = 0; t < 20; t++) { m.mouse(0, 0); m.tick(true, true); }
        float held = m.pitch();
        for (int t = 0; t < 40; t++) m.tick(true, true);
        require(Math.abs(m.pitch() - held) < 1e-3 && held > LEVEL_CAPTURE, p, "centred stick holds the pilot's dive");
        require(m.vy() < 0 && m.speed() > start, p, "diving gains speed");
        GeraniumFlightModel climb = cruising(p, 0, p.maxSpeed(), 1);
        for (int t = 0; t < 60; t++) { climb.mouse(0, -20); climb.tick(true, true); }
        require(Math.abs(climb.pitch() - MIN_PITCH) < 1e-3, p, "climb is limited: " + climb.pitch());
        GeraniumFlightModel near = cruising(p, 4, p.cruiseSpeed(), p.defaultThrottle());
        for (int t = 0; t < 30; t++) near.tick(true, true);
        require(Math.abs(near.pitch()) < .05, p, "near-level centred stick captures level flight");
    }

    private static void offMonitorFliesLevel(Profile p) {
        GeraniumFlightModel m = cruising(p, 30, p.cruiseSpeed(), .5f);
        m.restore(0, 30, -30, p.cruiseSpeed(), .5f, 200, true);
        m.input((short) (W | D)); m.mouse(15, 15);
        for (int t = 0; t < 60; t++) m.tick(false, true);
        require(Math.abs(m.pitch()) < .05 && Math.abs(m.roll()) < 1e-3, p, "off-monitor: wings and nose level");
        require(Math.abs(m.vy()) < 1e-3 && m.speed() > p.stallSpeed(), p, "off-monitor: level powered cruise");
        GeraniumFlightModel climbing = cruising(p, -30, p.cruiseSpeed(), .5f);
        for (int t = 0; t < 60; t++) climbing.tick(false, true);
        require(Math.abs(climbing.pitch()) < .05, p, "off-monitor: no endless climb");
        float heading = climbing.yaw(); climbing.tick(false, true);
        require(Math.abs(wrapped(climbing.yaw() - heading)) < 1e-4, p, "off-monitor: holds heading");
    }

    private static void stabilizeLevelsWithoutStopping(Profile p) {
        GeraniumFlightModel m = cruising(p, 0, p.cruiseSpeed() + .1f, .5f);
        for (int t = 0; t < 16; t++) { m.mouse(0, -12); m.tick(true, true); }
        require(m.pitch() < -15, p, "pilot climbs before stabilising: " + m.pitch());
        m.input(SPACE); m.input((short) 0); // SPACE in flight = stabilise
        require(m.levelHold(), p, "SPACE in flight requests level flight");
        for (int t = 0; t < 40; t++) m.tick(true, true);
        require(Math.abs(m.pitch()) < .2 && m.speed() > p.stallSpeed(), p, "stabilise levels in 2 s and keeps flying: " + m.pitch());
        m.mouse(0, 12); m.tick(true, true);
        require(!m.levelHold(), p, "a deliberate pitch input cancels the level request");
    }

    private static void stallSinksAndRecovers(Profile p) {
        GeraniumFlightModel m = cruising(p, 0, p.stallSpeed() + .14f, 0);
        boolean stalled = false, sank = false;
        // Hold the climb until the wing gives up (faster aircraft bleed speed over more ticks).
        for (int t = 0, stalledTicks = 0; t < 400 && stalledTicks < 60; t++) {
            if (p.stalled(m.speed())) stalledTicks++;
            m.mouse(0, -20); m.tick(true, true);
            stalled |= p.stalled(m.speed());
            sank |= m.vy() < -.05;
        }
        for (int t = 0; t < 300; t++) {
            m.mouse(0, 0); m.tick(true, true);
            stalled |= p.stalled(m.speed());
            sank |= m.vy() < -.05;
        }
        require(stalled && sank, p, "an idle-power steep climb stalls and sinks");
        require(m.speed() > p.stallSpeed() && Math.abs(m.pitch()) < 1 && Math.abs(m.vy()) < .01, p,
            "stall recovers to level flight: " + m.pitch() + " " + m.speed());
        require(m.lift(p.stallSpeed()) == 1 && m.lift(0) == 0, p, "lift fades below the stall speed");
    }

    private static void unpoweredFallsBallistically(Profile p) {
        GeraniumFlightModel m = cruising(p, 0, .5f, .5f);
        double vy = m.vy(), vz = m.vz();
        for (int t = 0; t < 30; t++) {
            m.input(SPACE); m.mouse(0, -20);
            m.tick(false, false, false, m.vx(), m.vy(), m.vz());
            require(Math.abs(m.vy() - Math.max(vy - FALL_GRAVITY, -MAX_FALL_SPEED)) < 1e-9, p, "gravity drives the fall");
            require(Math.abs(m.vz() - vz * FALL_DRAG) < 1e-9, p, "air drag on horizontal coast");
            vy = m.vy(); vz = m.vz();
        }
        require(m.pitch() > 45, p, "nose follows the fall line: " + m.pitch());
        m.tick(false, false, true, m.vx(), m.vy(), m.vz());
        require(m.vx() == 0 && m.vz() == 0 && m.vy() == -FALL_GRAVITY && m.speed() == 0, p, "rests on the ground");
        GeraniumFlightModel blocked = cruising(p, 0, .5f, .5f);
        blocked.tick(false, false, false, 0, 0, 0);
        require(blocked.vz() == 0 && blocked.vy() == -FALL_GRAVITY, p, "fall uses the actual, obstructed motion");
    }

    private static void repoweredFallContinuesAlongNose(Profile p) {
        GeraniumFlightModel m = cruising(p, 0, p.cruiseSpeed(), .5f);
        for (int t = 0; t < 12; t++) m.tick(false, false, false, m.vx(), m.vy(), m.vz());
        float fall = m.speed();
        m.tick(true, true);
        double length = Math.sqrt(m.vx() * m.vx() + m.vy() * m.vy() + m.vz() * m.vz());
        require(Math.abs(length - m.speed()) < 1e-3 && Math.abs(m.speed() - fall) < p.maxThrustAccel() + .04, p, "regained link keeps the fall energy along the nose");
    }

    private static void malformedInputAndRestore(Profile p) {
        GeraniumFlightModel m = cruising(p);
        m.mouse(Double.NaN, Double.POSITIVE_INFINITY); m.tick(true, true);
        require(Math.abs(m.roll()) < 1e-6 && Math.abs(m.pitch()) < 1e-6, p, "non-finite mouse is ignored");
        m.mouse(1e9, -1e9); m.tick(true, true);
        require(Float.isFinite(m.yaw()) && Math.abs(m.roll()) <= p.maxBank() && Math.abs(m.pitch()) <= MAX_PITCH, p, "huge mouse is clamped");
        m.tick(true, true, false, Double.NaN, Double.NaN, Double.NaN);
        m.tick(false, false, false, Double.NaN, Double.NaN, Double.NaN);
        require(Double.isFinite(m.vx()) && Double.isFinite(m.vy()) && Double.isFinite(m.vz()), p, "non-finite motion is ignored");
        m.restore(Float.NaN, Float.POSITIVE_INFINITY, Float.NaN, Float.NaN, Float.NaN, -5, true);
        require(m.yaw() == 0 && m.pitch() == 0 && m.roll() == 0 && m.throttle() == p.defaultThrottle()
            && m.speed() == p.launchSpeed() && m.flightTicks() == 0 && m.levelHold(), p, "invalid NBT floats fall back safely");
        m.restore(725, 120, 90, 9, 3, Integer.MAX_VALUE, true);
        require(m.yaw() == 5 && m.pitch() == MAX_PITCH && m.roll() == p.maxBank() && m.speed() == p.maxDiveSpeed() && m.throttle() == 1, p, "restore clamps");
        m.tick(true, true);
        require(m.flightTicks() == Integer.MAX_VALUE, p, "flight tick counter saturates");
        m.restore(10, 0, 0, .8f, .5f, 50, false);
        require(!m.launched() && m.speed() == 0 && m.vx() == 0, p, "unlaunched restore has no motion");
        for (int t = 0; t < 2000; t++) { m.input(D); m.mouse(20, 0); if (!m.launched()) m.launch(); m.tick(true, true); }
        require(m.yaw() >= -180 && m.yaw() < 180, p, "yaw stays wrapped");
    }

    /** Expo: centre deflections are softened, full deflection unchanged, odd and monotonic. */
    private static void stickCurve(Profile p) {
        require(p.curve(0) == 0 && Math.abs(p.curve(1) - 1) < 1e-6 && Math.abs(p.curve(-1) + 1) < 1e-6, p, "curve keeps ends");
        require(Math.abs(p.curve(.5f) - .3125f) < 1e-6, p, "half stick gives 31% with e=0.5: " + p.curve(.5f));
        float previous = -2;
        for (int i = -100; i <= 100; i++) {
            float value = p.curve(i / 100f);
            require(value > previous && Math.abs(value + p.curve(-i / 100f)) < 1e-6, p, "curve monotonic and odd");
            previous = value;
        }
        GeraniumFlightModel m = cruising(p);
        require(m.stick(.03f) == 0 && m.stick(5) == 1 && m.stick(-5) == -1, p, "dead zone and clamp");
        require(Math.abs(m.stick(1 / p.mouseFull()) - p.curve(1 / p.mouseFull())) < 1e-6, p, "mouse unit maps through the curve");
    }

    /** Roll rate and acceleration limits, no overshoot past the commanded bank, gentle reversal. */
    private static void smoothRollWithoutOvershoot(Profile p) {
        GeraniumFlightModel m = cruising(p);
        float limit = p.bankLimit(m.speed()), previousRate = 0, previousRoll = 0;
        for (int t = 0; t < 120; t++) {
            short key = t < 40 ? D : t < 80 ? A : 0;
            m.input(key); m.tick(true, true);
            float rate = m.roll() - previousRoll;
            require(Math.abs(rate) <= p.rollRate() + 1e-4, p, "roll rate limit (tick " + t + "): " + rate);
            boolean landed = m.rollRate() == 0 && rate != 0; // the final, shorter step onto the commanded bank
            if (landed) require(Math.abs(rate) <= Math.abs(previousRate) + 1e-4 && Math.signum(rate) == Math.signum(previousRate), p, "lands while braking");
            else require(Math.abs(rate - previousRate) <= p.rollAccel() + 1e-4, p, "roll acceleration limit (tick " + t + ")");
            require(Math.abs(m.roll()) <= limit + 1e-4, p, "never banks past the limit: " + m.roll());
            if (t >= 80) require(Math.signum(m.roll()) != -Math.signum(previousRoll) || previousRoll == 0, p, "wings level without passing zero");
            previousRate = rate; previousRoll = m.roll();
        }
        require(m.roll() == 0 && m.rollRate() == 0, p, "settles exactly on level wings");
        // A small, steady mouse deflection settles on its small bank without overshoot.
        GeraniumFlightModel fine = cruising(p);
        float peak = 0;
        for (int t = 0; t < 60; t++) { fine.mouse(2, 0); fine.tick(true, true); peak = Math.max(peak, fine.roll()); }
        float target = p.curve(2 / p.mouseFull()) * limit;
        require(Math.abs(fine.roll() - target) < 1e-3 && peak <= target + 1e-4, p, "small stick: exact bank, no overshoot " + peak + "/" + target);
    }

    private static void lowSpeedBankLimit(Profile p) {
        require(p.bankLimit(p.stallSpeed()) == p.slowBank() && p.bankLimit(p.fullBankSpeed()) == p.maxBank()
            && p.bankLimit(p.maxDiveSpeed()) == p.maxBank(), p, "bank limit endpoints");
        float slow = (p.stallSpeed() + p.fullBankSpeed()) / 2;
        GeraniumFlightModel m = cruising(p, 0, slow, 0);
        for (int t = 0; t < 80; t++) { m.input(D); m.tick(true, true); }
        require(m.roll() < p.maxBank() - 5 && m.roll() >= p.slowBank() - 1e-3, p, "slow flight limits the bank: " + m.roll());
    }

    private static void jetOutrunsPropeller() {
        Profile jet = Profile.JET, prop = Profile.PROPELLER;
        require(jet.maxSpeed() > prop.maxSpeed() * 1.5f && jet.cruiseSpeed() > prop.cruiseSpeed() && jet.maxDiveSpeed() > prop.maxDiveSpeed(),
            jet, "jet is much faster than the propeller Shahed");
        require(jet.maxThrustAccel() > prop.maxThrustAccel() * 2, jet, "jet accelerates harder");
        require(jet.energyPerTick() > prop.energyPerTick(), jet, "jet burns more energy");
        GeraniumFlightModel a = cruising(jet), b = cruising(prop);
        double da = 0, db = 0;
        for (int t = 0; t < 200; t++) { a.input(W); b.input(W); a.tick(true, true); b.tick(true, true); da += a.speed(); db += b.speed(); }
        require(da > db * 1.6, jet, "jet covers more ground at full throttle: " + da + " vs " + db);
    }

    /** 1 block/tick = 72 km/h. Real-world speeds, and turns that still fit a combat area. */
    private static void realisticSpeedsStayControllable() {
        Profile jet = Profile.JET, prop = Profile.PROPELLER;
        require(prop.maxSpeed() * 72 >= 150 && prop.maxSpeed() * 72 <= 190, prop, "Shahed-136 tops out at 150-190 km/h");
        require(jet.maxSpeed() * 72 >= 288 && jet.maxSpeed() * 72 <= 432, jet, "jet Geranium-2 tops out at 290-430 km/h");
        for (Profile p : List.of(jet, prop)) {
            GeraniumFlightModel m = cruising(p);
            float turned = 0;
            for (int t = 0; t < 100; t++) { m.input(D); float before = m.yaw(); m.tick(true, true); if (t >= 40) turned += wrapped(m.yaw() - before); }
            float rate = (float) Math.toRadians(turned / 60);
            float radius = p.cruiseSpeed() / rate;
            require(radius > 30 && radius < 150, p, "full-bank turn radius at cruise: " + radius);
            require(p.minSpeed() > p.stallSpeed() && p.launchSpeed() > p.stallSpeed(), p, "idle and launch speeds fly");
        }
    }

    /**
     * Before/after for the same pilot inputs: a small flick and a full swipe. Printed so the
     * numbers can be compared with the in-game log.
     */
    private static void smootherThanLegacy() {
        float[] small = response(Profile.LEGACY, 3), smallJet = response(Profile.JET, 3), smallProp = response(Profile.PROPELLER, 3);
        float[] full = response(Profile.LEGACY, 20), fullJet = response(Profile.JET, 20);
        System.out.println(String.format(Locale.ROOT,
            "response (bank deg, peak roll rate deg/t, peak roll accel deg/t2, ticks to 90%%): legacy 3u %s | jet 3u %s | prop 3u %s | legacy full %s | jet full %s",
            fmt(small), fmt(smallJet), fmt(smallProp), fmt(full), fmt(fullJet)));
        require(smallJet[0] < small[0] * .5f && smallProp[0] < small[0] * .5f, Profile.JET, "a small flick banks far less than before");
        require(smallJet[1] < small[1] && fullJet[1] < full[1], Profile.JET, "lower peak roll rate");
        require(fullJet[2] <= Profile.JET.rollAccel() + 1e-4 && full[2] >= Profile.LEGACY.rollRate() - 1e-4, Profile.JET,
            "legacy jumps to full roll rate in one tick, jet ramps: " + full[2] + " / " + fullJet[2]);
        require(fullJet[0] >= Profile.JET.maxBank() - 1e-3, Profile.JET, "a full swipe still reaches full bank");
    }

    /** Steady mouse input from level cruise: final bank, peak rate, peak rate change, ticks to 90% bank. */
    private static float[] response(Profile p, float mouse) {
        GeraniumFlightModel m = cruising(p);
        float previousRoll = 0, previousRate = 0, peakRate = 0, peakAccel = 0, settle = -1;
        for (int t = 0; t < 60; t++) {
            m.mouse(mouse, 0); m.tick(true, true);
            float rate = Math.abs(m.roll() - previousRoll);
            peakRate = Math.max(peakRate, rate);
            if (p.legacyRoll() || m.rollRate() != 0 || rate == 0) peakAccel = Math.max(peakAccel, Math.abs(rate - previousRate)); // not the landing step
            previousRoll = m.roll(); previousRate = rate;
        }
        float bank = m.roll();
        GeraniumFlightModel again = cruising(p);
        for (int t = 1; t <= 60 && settle < 0; t++) { again.mouse(mouse, 0); again.tick(true, true); if (again.roll() >= bank * .9f) settle = t; }
        return new float[] {bank, peakRate, peakAccel, settle};
    }

    private static String fmt(float[] r) { return String.format(Locale.ROOT, "%.1f/%.2f/%.2f/%.0f", r[0], r[1], r[2], r[3]); }

    private static float wrapped(float angle) {
        angle %= 360;
        if (angle >= 180) angle -= 360;
        if (angle < -180) angle += 360;
        return angle;
    }

    private static void require(boolean condition, Profile p, String message) {
        checks++; if (!condition) throw new AssertionError(p.name() + ": " + message);
    }
}
