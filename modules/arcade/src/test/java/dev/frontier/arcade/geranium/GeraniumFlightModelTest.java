package dev.frontier.arcade.geranium;

import static dev.frontier.arcade.geranium.GeraniumFlightModel.*;

/** Pure offline flight checks for the hand-flown Geranium-2 (one tick = 1/20 s). */
public final class GeraniumFlightModelTest {
    private static int checks;
    private static final short A = 1, D = 2, W = 4, S = 8, SPACE = 16;

    public static void main(String[] args) {
        groundedUntilLaunch();
        launchClimbsThenLevels();
        throttleIsSmoothAndBounded();
        bankToTurnAndAutoLevel();
        sustainedMouseTurnNeverReverses();
        pitchIsRateLimitedAndHeld();
        offMonitorFliesLevel();
        stabilizeLevelsWithoutStopping();
        stallSinksAndRecovers();
        unpoweredFallsBallistically();
        repoweredFallContinuesAlongNose();
        malformedInputAndRestore();
        System.out.println("GeraniumFlightModelTest: " + checks + " checks passed");
    }

    private static GeraniumFlightModel cruising(float pitch, float speed, float throttle) {
        GeraniumFlightModel m = new GeraniumFlightModel(0, 0);
        m.restore(0, pitch, 0, speed, throttle, 200, true);
        return m;
    }

    private static void groundedUntilLaunch() {
        GeraniumFlightModel m = new GeraniumFlightModel(37, 0);
        for (int t = 0; t < 40; t++) { m.input(W); m.mouse(20, 20); m.tick(true, true); }
        require(!m.launched() && m.speed() == 0 && m.vx() == 0 && m.vy() == 0 && m.vz() == 0, "no motion before launch");
        require(m.yaw() == 37 && m.pitch() == 0 && m.flightTicks() == 0, "attitude unchanged before launch");
        m.input(SPACE);
        require(m.launched() && !m.launch(), "SPACE launches exactly once");
        m.input(SPACE); m.input((short) 0);
        require(m.flightTicks() == 0, "held SPACE does not relaunch");
    }

    private static void launchClimbsThenLevels() {
        GeraniumFlightModel m = new GeraniumFlightModel(90, 0);
        m.input(SPACE); m.input((short) 0);
        double y = 0, horizontal = 0, minSpeed = 9;
        for (int t = 0; t < LAUNCH_TICKS; t++) {
            m.mouse(0, 20); // a nose-down stick cannot spoil the launch climb
            m.tick(true, true); y += m.vy(); horizontal += Math.hypot(m.vx(), m.vz());
            minSpeed = Math.min(minSpeed, m.speed());
        }
        require(y > 3.5, "launch climbs clear of ground clutter: " + y);
        require(horizontal > 10, "launch moves forward: " + horizontal);
        require(minSpeed >= LAUNCH_SPEED - 1e-4 && minSpeed > STALL_SPEED, "launch never stalls");
        require(m.vx() < 0 && Math.abs(m.vz()) < 1e-6, "launch follows the initial heading (yaw 90 = west)");
        for (int t = 0; t < 40; t++) { m.mouse(0, 0); m.tick(true, true); y += m.vy(); }
        require(Math.abs(m.pitch()) < .2 && Math.abs(m.vy()) < .005, "levels off after the launch climb: " + m.pitch());
        require(m.speed() > MIN_SPEED, "cruise after launch above zero-throttle speed");
    }

    private static void throttleIsSmoothAndBounded() {
        GeraniumFlightModel m = cruising(0, .5f, DEFAULT_THROTTLE);
        float previous = m.speed(), maxStep = 0;
        for (int t = 0; t < 400; t++) {
            m.input(W); m.tick(true, true);
            maxStep = Math.max(maxStep, Math.abs(m.speed() - previous)); previous = m.speed();
        }
        require(m.throttle() == 1, "W saturates throttle");
        require(Math.abs(m.speed() - MAX_SPEED) < .01, "full throttle reaches max cruise: " + m.speed());
        require(maxStep <= MAX_THRUST_ACCEL + 1e-4, "speed changes smoothly: " + maxStep);
        for (int t = 0; t < 400; t++) { m.input(S); m.tick(true, true); }
        require(m.throttle() == 0 && Math.abs(m.speed() - MIN_SPEED) < .01, "idle cruise is the minimum flying speed");
        require(m.speed() > STALL_SPEED && Math.abs(m.vy()) < 1e-6, "idle level flight does not stall or sink");
        m.input((short) 0);
        float throttle = m.throttle();
        m.tick(false, true);
        require(m.throttle() == throttle, "leaving the monitor keeps the set throttle");
    }

    private static void bankToTurnAndAutoLevel() {
        GeraniumFlightModel m = cruising(0, .5f, DEFAULT_THROTTLE);
        float yaw = m.yaw(), maxRate = 0;
        for (int t = 0; t < 30; t++) {
            m.input(D); float before = m.yaw(); m.tick(true, true);
            float rate = wrapped(m.yaw() - before);
            require(rate >= -1e-4, "right bank never turns left");
            maxRate = Math.max(maxRate, rate);
        }
        require(Math.abs(m.roll() + MAX_BANK) < 1e-3, "D banks right wing down to the bank limit: " + m.roll());
        require(maxRate <= MAX_TURN_RATE + 1e-4 && maxRate > 2, "coordinated turn rate from bank: " + maxRate);
        require(wrapped(m.yaw() - yaw) > 45, "sustained bank turns right");
        require(Math.abs(m.vy()) < 1e-6, "a level coordinated turn keeps altitude");
        m.input((short) 0);
        int levelTicks = 0;
        while (Math.abs(m.roll()) > 1e-3 && levelTicks < 100) { m.tick(true, true); levelTicks++; }
        require(levelTicks <= 15, "centred stick levels the wings: " + levelTicks);
        float settled = m.yaw(); m.tick(true, true);
        require(Math.abs(wrapped(m.yaw() - settled)) < 1e-4, "wings level stop the turn");
        GeraniumFlightModel left = cruising(0, .5f, DEFAULT_THROTTLE);
        for (int t = 0; t < 20; t++) { left.input(A); left.tick(true, true); }
        require(left.roll() > 0 && wrapped(left.yaw()) < -10, "A banks and turns left");
    }

    private static void sustainedMouseTurnNeverReverses() {
        GeraniumFlightModel m = cruising(0, .5f, DEFAULT_THROTTLE);
        float total = 0;
        for (int t = 0; t < 120; t++) {
            m.mouse(20, 0); float before = m.yaw(); m.tick(true, true);
            float rate = wrapped(m.yaw() - before);
            require(rate >= -1e-4, "a long right mouse swipe never reverses the turn (tick " + t + ")");
            total += rate;
        }
        require(total > 360, "a full stick can circle: " + total);
        for (int t = 0; t < 20; t++) { m.mouse(0, 0); m.tick(true, true); }
        float settled = m.yaw(); m.tick(true, true);
        require(Math.abs(wrapped(m.yaw() - settled)) < 1e-4, "turn stops soon after the mouse stops");
        m.mouse(20, 0); m.tick(true, true);
        for (int t = 0; t < 10; t++) m.tick(true, true); // no further packets: stale mouse expires
        require(Math.abs(m.roll()) < 1e-3, "stale mouse input expires and the wings level: " + m.roll());
    }

    private static void pitchIsRateLimitedAndHeld() {
        GeraniumFlightModel m = cruising(0, .5f, DEFAULT_THROTTLE);
        float previous = m.pitch(), previousRate = 0;
        for (int t = 0; t < 12; t++) {
            m.mouse(0, 20); m.tick(true, true);
            float rate = m.pitch() - previous;
            require(Math.abs(rate) <= MAX_PITCH_RATE + 1e-4, "pitch rate limit");
            require(Math.abs(rate - previousRate) <= PITCH_ACCEL + 1e-4, "pitch acceleration limit");
            previous = m.pitch(); previousRate = rate;
        }
        float dive = m.pitch();
        require(dive > 15, "mouse down noses over: " + dive);
        for (int t = 0; t < 10; t++) { m.mouse(0, 0); m.tick(true, true); }
        float held = m.pitch();
        for (int t = 0; t < 40; t++) m.tick(true, true);
        require(Math.abs(m.pitch() - held) < 1e-3 && held > LEVEL_CAPTURE, "centred stick holds the pilot's dive");
        require(m.vy() < 0 && m.speed() > .5f, "diving gains speed");
        GeraniumFlightModel climb = cruising(0, .7f, 1);
        for (int t = 0; t < 60; t++) { climb.mouse(0, -20); climb.tick(true, true); }
        require(Math.abs(climb.pitch() - MIN_PITCH) < 1e-3, "climb is limited: " + climb.pitch());
        GeraniumFlightModel near = cruising(4, .5f, DEFAULT_THROTTLE);
        for (int t = 0; t < 30; t++) near.tick(true, true);
        require(Math.abs(near.pitch()) < .05, "near-level centred stick captures level flight");
    }

    private static void offMonitorFliesLevel() {
        GeraniumFlightModel m = cruising(30, .7f, .5f);
        m.restore(0, 30, -30, .7f, .5f, 200, true);
        m.input((short) (W | D)); m.mouse(15, 15);
        for (int t = 0; t < 60; t++) m.tick(false, true);
        require(Math.abs(m.pitch()) < .05 && Math.abs(m.roll()) < 1e-3, "off-monitor: wings and nose level");
        require(Math.abs(m.vy()) < 1e-3 && m.speed() > STALL_SPEED, "off-monitor: level powered cruise");
        GeraniumFlightModel climbing = cruising(-30, .7f, .5f);
        for (int t = 0; t < 60; t++) climbing.tick(false, true);
        require(Math.abs(climbing.pitch()) < .05, "off-monitor: no endless climb");
        float heading = climbing.yaw(); climbing.tick(false, true);
        require(Math.abs(wrapped(climbing.yaw() - heading)) < 1e-4, "off-monitor: holds heading");
    }

    private static void stabilizeLevelsWithoutStopping() {
        GeraniumFlightModel m = cruising(0, .6f, .5f);
        for (int t = 0; t < 12; t++) { m.mouse(0, -12); m.tick(true, true); }
        require(m.pitch() < -15, "pilot climbs before stabilising");
        m.input(SPACE); m.input((short) 0); // SPACE in flight = stabilise
        require(m.levelHold(), "SPACE in flight requests level flight");
        for (int t = 0; t < 30; t++) m.tick(true, true);
        require(Math.abs(m.pitch()) < .2 && m.speed() > .4, "stabilise levels in 1.5 s and keeps flying: " + m.pitch());
        m.mouse(0, 12); m.tick(true, true);
        require(!m.levelHold(), "a deliberate pitch input cancels the level request");
    }

    private static void stallSinksAndRecovers() {
        GeraniumFlightModel m = cruising(0, .4f, 0);
        boolean stalled = false, sank = false;
        for (int t = 0; t < 30; t++) { m.mouse(0, -20); m.tick(true, true); }
        for (int t = 0; t < 200; t++) {
            m.mouse(0, 0); m.tick(true, true);
            stalled |= stallSpeed(m.speed());
            sank |= m.vy() < -.05;
        }
        require(stalled && sank, "an idle-power steep climb stalls and sinks");
        require(m.speed() > STALL_SPEED && Math.abs(m.pitch()) < 1 && Math.abs(m.vy()) < .01, "stall recovers to level flight: " + m.pitch() + " " + m.speed());
        require(lift(STALL_SPEED) == 1 && lift(0) == 0, "lift fades below the stall speed");
    }

    private static void unpoweredFallsBallistically() {
        GeraniumFlightModel m = cruising(0, .5f, .5f);
        double vy = m.vy(), vz = m.vz();
        for (int t = 0; t < 30; t++) {
            m.input(SPACE); m.mouse(0, -20);
            m.tick(false, false, false, m.vx(), m.vy(), m.vz());
            require(Math.abs(m.vy() - Math.max(vy - FALL_GRAVITY, -MAX_FALL_SPEED)) < 1e-9, "gravity drives the fall");
            require(Math.abs(m.vz() - vz * FALL_DRAG) < 1e-9, "air drag on horizontal coast");
            vy = m.vy(); vz = m.vz();
        }
        require(m.pitch() > 45, "nose follows the fall line: " + m.pitch());
        m.tick(false, false, true, m.vx(), m.vy(), m.vz());
        require(m.vx() == 0 && m.vz() == 0 && m.vy() == -FALL_GRAVITY && m.speed() == 0, "rests on the ground");
        GeraniumFlightModel blocked = cruising(0, .5f, .5f);
        blocked.tick(false, false, false, 0, 0, 0);
        require(blocked.vz() == 0 && blocked.vy() == -FALL_GRAVITY, "fall uses the actual, obstructed motion");
    }

    private static void repoweredFallContinuesAlongNose() {
        GeraniumFlightModel m = cruising(0, .5f, .5f);
        for (int t = 0; t < 12; t++) m.tick(false, false, false, m.vx(), m.vy(), m.vz());
        float fall = m.speed();
        m.tick(true, true);
        double length = Math.sqrt(m.vx() * m.vx() + m.vy() * m.vy() + m.vz() * m.vz());
        require(Math.abs(length - m.speed()) < 1e-3 && Math.abs(m.speed() - fall) < .06, "regained link keeps the fall energy along the nose");
    }

    private static void malformedInputAndRestore() {
        GeraniumFlightModel m = cruising(0, .5f, .5f);
        m.mouse(Double.NaN, Double.POSITIVE_INFINITY); m.tick(true, true);
        require(Math.abs(m.roll()) < 1e-6 && Math.abs(m.pitch()) < 1e-6, "non-finite mouse is ignored");
        m.mouse(1e9, -1e9); m.tick(true, true);
        require(Float.isFinite(m.yaw()) && Math.abs(m.roll()) <= MAX_BANK && Math.abs(m.pitch()) <= MAX_PITCH, "huge mouse is clamped");
        m.tick(true, true, false, Double.NaN, Double.NaN, Double.NaN);
        m.tick(false, false, false, Double.NaN, Double.NaN, Double.NaN);
        require(Double.isFinite(m.vx()) && Double.isFinite(m.vy()) && Double.isFinite(m.vz()), "non-finite motion is ignored");
        m.restore(Float.NaN, Float.POSITIVE_INFINITY, Float.NaN, Float.NaN, Float.NaN, -5, true);
        require(m.yaw() == 0 && m.pitch() == 0 && m.roll() == 0 && m.throttle() == DEFAULT_THROTTLE
            && m.speed() == LAUNCH_SPEED && m.flightTicks() == 0 && m.levelHold(), "invalid NBT floats fall back safely");
        m.restore(725, 120, 90, 9, 3, Integer.MAX_VALUE, true);
        require(m.yaw() == 5 && m.pitch() == MAX_PITCH && m.roll() == MAX_BANK && m.speed() == MAX_DIVE_SPEED && m.throttle() == 1, "restore clamps");
        m.tick(true, true);
        require(m.flightTicks() == Integer.MAX_VALUE, "flight tick counter saturates");
        m.restore(10, 0, 0, .8f, .5f, 50, false);
        require(!m.launched() && m.speed() == 0 && m.vx() == 0, "unlaunched restore has no motion");
        for (int t = 0; t < 2000; t++) { m.input(D); m.mouse(20, 0); if (!m.launched()) m.launch(); m.tick(true, true); }
        require(m.yaw() >= -180 && m.yaw() < 180, "yaw stays wrapped");
    }

    private static float wrapped(float angle) {
        angle %= 360;
        if (angle >= 180) angle -= 360;
        if (angle < -180) angle += 360;
        return angle;
    }

    private static void require(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
}
