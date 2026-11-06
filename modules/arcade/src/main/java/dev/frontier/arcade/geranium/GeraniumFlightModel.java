package dev.frontier.arcade.geranium;

/**
 * Pure, server-side, hand-flown fixed-wing model for the station-flown strike aircraft
 * (one call per 20 Hz tick): the jet Geranium-2 and the propeller Shahed-136 share it and
 * differ only by their {@link Profile}.
 *
 * <p>Units: degrees and blocks per tick, with Minecraft signs: yaw grows when turning right
 * (D), pitch is positive nose-down, roll is positive with the right wing down (a right turn): the
 * sign the native renderer (ZP after GeckoLib's half turn) and the camera roll both draw that way.
 *
 * <p>Stick: A/D and horizontal mouse command a bank angle; the bank produces a coordinated
 * yaw rate and the wings level again when the stick is centred. Vertical mouse commands a
 * rate-limited pitch rate. Mouse deflection goes through an expo curve (fine control near the
 * centre, full authority at the end of the swipe); the wings roll with a limited roll rate and
 * roll acceleration and settle on the commanded bank without overshoot. At low airspeed the
 * bank limit shrinks. A centred stick holds the current attitude; close to level, after
 * F6/SPACE, after the launch climb and whenever the pilot is off the monitor the nose is
 * brought to level flight, which holds the altitude because the powered path follows the nose.
 * Throttle sets the cruise speed; climbing costs and diving gains speed, and below the stall
 * speed lift fades: the aircraft sinks and drops its nose until it has flying speed again.
 * Without engine power (battery, radio, owner) it falls ballistically, nose along the fall line.
 *
 * <p>The caller applies {@link #vx()}/{@link #vy()}/{@link #vz()} once; VehicleEntity moves.
 * No autonomous navigation: every heading change comes from the pilot's stick.
 * Supersedes the earlier manual-input adaptation of Uncomplicated FPV's GPL-3.0
 * Shahed136Entity.updateFlight (see META-INF/NOTICE.txt).
 */
public final class GeraniumFlightModel {
    /**
     * Airframe and engine constants.
     *
     * @param cruiseSpeed   speed at the default throttle
     * @param slowBank      bank limit at the stall speed, growing to maxBank at fullBankSpeed
     * @param rollAccel     roll-rate change per tick (per tick squared); ignored by legacyRoll
     * @param rollGain      largest fraction of the remaining bank error flown in one tick
     * @param mouseFull     native SBW mouse units (already smoothed by its client) for a full stick
     * @param expo          stick curve (1-e)*s + e*s^3; 0 is linear
     * @param legacyRoll    the original constant-rate roll and proportional levelling (before/after measurements only)
     */
    public record Profile(String name, float minSpeed, float maxSpeed, float cruiseSpeed, float stallSpeed,
                          float zeroLiftSpeed, float maxDiveSpeed,
                          float maxBank, float slowBank, float fullBankSpeed,
                          float rollRate, float rollAccel, float rollGain, float levelRollRate,
                          float turnGain, float maxTurnRate,
                          float maxPitchRate, float pitchAccel, float levelPitchRate,
                          float thrust, float maxThrustAccel, float launchSpeed, float launchCruise,
                          float mouseFull, float expo, int energyPerTick, boolean legacyRoll) {
        /** Jet Geranium-2: fast, still a gentle, rate-limited stick. */
        public static final Profile JET = new Profile("jet", .5f, 1.6f, 1f, .38f, .15f, 2.2f,
            40, 20, .9f, 3, .4f, 1, 3, 2.8f, 3.5f, 2.2f, .25f, 1.8f,
            .06f, .05f, .5f, 1.2f, 9, .5f, 10, false);
        /** Propeller Shahed-136: slower, softer engine, longer endurance. */
        public static final Profile PROPELLER = new Profile("propeller", .3f, .8f, .5f, .22f, .08f, 1.4f,
            35, 18, .45f, 2.4f, .3f, 1, 2.4f, 1.9f, 3.5f, 1.8f, .2f, 1.6f,
            .035f, .022f, .36f, .7f, 9, .5f, 6, false);
        /** The pre-jet tuning (instant linear stick, constant roll rate), kept for comparisons. */
        public static final Profile LEGACY = new Profile("legacy", .35f, 1f, .5f, .26f, .1f, 1.4f,
            40, 40, 0, 4, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, 3, 2.3f, 5, 2.5f, .5f, 2,
            .04f, .03f, .35f, .9f, 6, 0, 8, true);

        public float defaultThrottle() { return (cruiseSpeed - minSpeed) / (maxSpeed - minSpeed); }
        /** HUD helper: a launched airframe below this airspeed is losing lift. */
        public boolean stalled(double airspeed) { return airspeed < stallSpeed; }
        /** Bank limit at this airspeed: slowBank at the stall speed, maxBank from fullBankSpeed. */
        public float bankLimit(float airspeed) {
            if (fullBankSpeed <= stallSpeed) return maxBank;
            float t = clamp((airspeed - stallSpeed) / (fullBankSpeed - stallSpeed), 0, 1);
            return slowBank + (maxBank - slowBank) * t;
        }
        /** Stick shaping for a raw, already clamped deflection in [-1, 1]. */
        public float curve(float stick) { return (1 - expo) * stick + expo * stick * stick * stick; }
    }

    public static final float MIN_PITCH = -35, MAX_PITCH = 80, LEVEL_CAPTURE = 6;
    public static final int LAUNCH_TICKS = 30;
    public static final float LAUNCH_PITCH = -20;
    public static final float THROTTLE_STEP = .015f;
    public static final double FALL_GRAVITY = .06, FALL_DRAG = .98, MAX_FALL_SPEED = 2;
    static final float STALL_SINK = .3f, STALL_NOSE_DROP = 3, STALL_NOSE_LIMIT = 25;
    static final float GRAVITY_ALONG_PATH = .035f;
    static final float LEVEL_GAIN = .3f, STICK_DEADZONE = .04f, ROLL_SNAP = .05f;
    static final int MOUSE_HOLD_TICKS = 2;

    private final Profile profile;
    private float yaw, pitch, roll, rollRate, pitchRate, speed, throttle;
    private double vx, vy, vz;
    private float mouseX, mouseY;
    private int mouseAge = Integer.MAX_VALUE, flightTicks;
    private boolean launched, spaceDown, levelRequested = true;
    private short keys;

    public GeraniumFlightModel(Profile profile, float yaw, float pitch) {
        this.profile = profile;
        this.throttle = profile.defaultThrottle();
        this.yaw = wrap(finite(yaw, 0));
        this.pitch = clamp(finite(pitch, 0), MIN_PITCH, MAX_PITCH);
    }

    public Profile profile() { return profile; }

    /** Booster throw along the current heading; returns false when this airframe has already launched. */
    public boolean launch() {
        if (launched) return false;
        launched = true;
        flightTicks = 0;
        speed = profile.launchSpeed;
        pitchRate = 0;
        levelRequested = true; // level off by itself once the launch climb ends
        velocityAlongNose(0);
        return true;
    }

    /** Native SBW keys: A=1, D=2, W=4, S=8, Space=16, Shift=32. */
    public void input(short nextKeys) {
        keys = nextKeys;
        boolean nextSpace = (nextKeys & 16) != 0;
        if (nextSpace && !spaceDown) {
            if (launched) stabilize(); else launch();
        }
        spaceDown = nextSpace;
    }

    /** Native, client-smoothed mouse speed; held for a couple of ticks to bridge packet jitter. */
    public void mouse(double dx, double dy) {
        mouseX = Double.isFinite(dx) ? clamp((float) dx, -20, 20) : 0;
        mouseY = Double.isFinite(dy) ? clamp((float) dy, -20, 20) : 0;
        mouseAge = 0;
    }

    /** Level the nose and wings on the current heading; never stops the aircraft or adds lift. */
    public void stabilize() {
        levelRequested = true;
        clearMouse();
    }

    /** Offline/test form: no ground contact, the previous commanded velocity was not obstructed. */
    public void tick(boolean controlAllowed, boolean engineAllowed) {
        tick(controlAllowed, engineAllowed, false, vx, vy, vz);
    }

    /**
     * @param controlAllowed the owner is flying from an active monitor with signal and power
     * @param engineAllowed  battery, radio link and owner allow thrust (and stabilisation)
     * @param onGround       ground contact after the previous move
     * @param motionX        actual motion after the previous move (used for the unpowered fall)
     */
    public void tick(boolean controlAllowed, boolean engineAllowed, boolean onGround,
                     double motionX, double motionY, double motionZ) {
        if (!controlAllowed) {
            keys = 0;
            spaceDown = false;
            clearMouse();
            levelRequested = true;
        }
        if (!launched) {
            speed = 0;
            pitchRate = 0;
            rollRate = 0;
            vx = vy = vz = 0;
            clearMouse();
            return;
        }
        if (flightTicks < Integer.MAX_VALUE) flightTicks++;

        float rollStick = 0, pitchStick = 0;
        if (controlAllowed) {
            int gas = ((keys & 4) != 0 ? 1 : 0) - ((keys & 8) != 0 ? 1 : 0);
            throttle = clamp(throttle + gas * THROTTLE_STEP, 0, 1);
            int turn = ((keys & 2) != 0 ? 1 : 0) - ((keys & 1) != 0 ? 1 : 0);
            boolean fresh = mouseAge <= MOUSE_HOLD_TICKS;
            rollStick = stick(turn + (fresh ? mouseX / profile.mouseFull : 0));
            pitchStick = stick(fresh ? mouseY / profile.mouseFull : 0);
        }
        if (mouseAge < Integer.MAX_VALUE) mouseAge++;

        if (engineAllowed) powered(rollStick, pitchStick);
        else unpowered(onGround, motionX, motionY, motionZ);
    }

    /** Clamped, dead-zoned and expo-shaped stick deflection. */
    float stick(float raw) {
        float clamped = clamp(raw, -1, 1);
        return Math.abs(clamped) < STICK_DEADZONE ? 0 : profile.curve(clamped);
    }

    private void powered(float rollStick, float pitchStick) {
        float lift = lift(speed);
        float authority = .35f + .65f * lift;

        // Bank-to-turn: the stick commands a bank; a centred stick levels the wings.
        float targetRoll = rollStick * profile.bankLimit(speed);
        float maxRollRate = rollStick == 0 ? profile.levelRollRate : profile.rollRate * authority;
        if (profile.legacyRoll) roll = approach(roll, targetRoll, maxRollRate);
        else rollToward(targetRoll, maxRollRate);
        float bank = roll;
        float turnRate = profile.turnGain * (float) Math.tan(Math.toRadians(bank))
            / Math.max(speed, profile.stallSpeed) * authority;
        yaw = wrap(yaw + clamp(turnRate, -profile.maxTurnRate, profile.maxTurnRate));

        boolean launching = flightTicks <= LAUNCH_TICKS;
        if (lift < 1) levelRequested = true; // a stall breaks the attitude hold: recover to level, not into a held dive
        float commanded;
        if (launching) {
            commanded = settle(LAUNCH_PITCH - pitch, profile.maxPitchRate);
        } else if (pitchStick != 0) {
            levelRequested = false;
            commanded = pitchStick * profile.maxPitchRate * authority;
        } else if (levelRequested || Math.abs(pitch) < LEVEL_CAPTURE) {
            commanded = settle(-pitch, profile.levelPitchRate);
        } else {
            commanded = 0; // attitude hold: keep the dive or climb the pilot set
        }
        if (lift < 1 && pitch < STALL_NOSE_LIMIT) commanded += (1 - lift) * STALL_NOSE_DROP;
        pitchRate = approach(pitchRate, commanded, profile.pitchAccel);
        pitch += pitchRate;
        if (pitch < MIN_PITCH || pitch > MAX_PITCH) {
            pitch = clamp(pitch, MIN_PITCH, MAX_PITCH);
            pitchRate = 0;
        }

        float cruise = profile.minSpeed + (profile.maxSpeed - profile.minSpeed) * throttle;
        if (launching) cruise = Math.max(cruise, profile.launchCruise);
        float thrust = clamp((cruise - speed) * profile.thrust, -profile.maxThrustAccel, profile.maxThrustAccel);
        float alongPath = GRAVITY_ALONG_PATH * (float) Math.sin(Math.toRadians(pitch));
        speed = clamp(speed + thrust + alongPath, 0, profile.maxDiveSpeed);
        velocityAlongNose((1 - lift(speed)) * STALL_SINK);
    }

    /** Pitch rate toward an attitude; brakes within the pitch acceleration so the nose does not swing past it. */
    private float settle(float error, float maxRate) {
        if (profile.legacyRoll) return clamp(error * LEVEL_GAIN, -maxRate, maxRate);
        float distance = Math.abs(error);
        return Math.copySign(Math.min(maxRate, Math.min(LEVEL_GAIN * distance, brake(distance, profile.pitchAccel))), error);
    }

    /**
     * Roll rate limited by the airframe rate, rollGain times the remaining error and the braking
     * distance (about sqrt(2*accel*error)); the rate itself changes by at most rollAccel per tick.
     * Lands on the target instead of passing it.
     */
    private void rollToward(float target, float maxRate) {
        float error = target - roll, distance = Math.abs(error);
        float desired = Math.copySign(Math.min(maxRate, Math.min(profile.rollGain * distance,
            brake(distance, profile.rollAccel))), error);
        rollRate = approach(rollRate, desired, profile.rollAccel);
        boolean reaching = rollRate != 0 && Math.signum(rollRate) == Math.signum(error) && Math.abs(rollRate) >= distance;
        if (reaching || (distance <= ROLL_SNAP && Math.abs(rollRate) <= profile.rollAccel)) {
            roll = target;
            rollRate = 0;
        } else {
            roll += rollRate;
        }
    }

    /** Largest rate that still stops within distance when it drops by accel every tick (v + (v-a) + ... = d). */
    static float brake(float distance, float accel) {
        return accel * ((float) Math.sqrt(2 * distance / accel + .25f) - .5f);
    }

    private void unpowered(boolean onGround, double motionX, double motionY, double motionZ) {
        pitchRate = 0;
        rollRate = 0;
        roll = approach(roll, 0, profile.levelRollRate * .5f);
        double mx = Double.isFinite(motionX) ? motionX : 0;
        double my = Double.isFinite(motionY) ? motionY : 0;
        double mz = Double.isFinite(motionZ) ? motionZ : 0;
        if (onGround) {
            vx = 0; vy = -FALL_GRAVITY; vz = 0;
        } else {
            vx = mx * FALL_DRAG;
            vy = Math.max(my - FALL_GRAVITY, -MAX_FALL_SPEED);
            vz = mz * FALL_DRAG;
        }
        double horizontal = Math.sqrt(vx * vx + vz * vz);
        if (!onGround && horizontal + Math.abs(vy) > .05) {
            float fallLine = (float) Math.toDegrees(Math.atan2(-vy, horizontal));
            pitch = approach(pitch, clamp(fallLine, MIN_PITCH, MAX_PITCH), 3);
        }
        speed = onGround ? 0 : (float) Math.min(profile.maxDiveSpeed, Math.sqrt(horizontal * horizontal + vy * vy));
    }

    private void velocityAlongNose(float sink) {
        double p = Math.toRadians(pitch), y = Math.toRadians(yaw);
        double horizontal = Math.cos(p) * speed;
        vx = -Math.sin(y) * horizontal;
        vy = -Math.sin(p) * speed - sink;
        vz = Math.cos(y) * horizontal;
    }

    /** Restores persistent state without replaying launch or accepting invalid NBT floats. */
    public void restore(float yaw, float pitch, float roll, float speed, float throttle,
                        int flightTicks, boolean launched) {
        this.yaw = wrap(finite(yaw, 0));
        this.pitch = clamp(finite(pitch, 0), MIN_PITCH, MAX_PITCH);
        this.roll = clamp(finite(roll, 0), -profile.maxBank, profile.maxBank);
        this.throttle = clamp(finite(throttle, profile.defaultThrottle()), 0, 1);
        this.speed = launched ? clamp(finite(speed, profile.launchSpeed), 0, profile.maxDiveSpeed) : 0;
        this.flightTicks = Math.max(0, flightTicks);
        this.launched = launched;
        pitchRate = 0;
        rollRate = 0;
        levelRequested = true; // nobody is on the monitor right after a load
        keys = 0;
        spaceDown = false;
        clearMouse();
        if (launched) velocityAlongNose(0); else vx = vy = vz = 0;
    }

    public float yaw() { return yaw; }
    public float pitch() { return pitch; }
    public float roll() { return roll; }
    /** Current roll rate (degrees per tick); 0 for the legacy constant-rate roll. */
    public float rollRate() { return rollRate; }
    /** Airspeed while powered, fall speed while unpowered, 0 before launch. */
    public float speed() { return speed; }
    public float throttle() { return throttle; }
    public boolean launched() { return launched; }
    public int flightTicks() { return flightTicks; }
    public boolean levelHold() { return levelRequested; }
    public double vx() { return vx; }
    public double vy() { return vy; }
    public double vz() { return vz; }

    float lift(float airspeed) {
        return clamp((airspeed - profile.zeroLiftSpeed) / (profile.stallSpeed - profile.zeroLiftSpeed), 0, 1);
    }
    private void clearMouse() { mouseX = mouseY = 0; mouseAge = Integer.MAX_VALUE; }
    private static float approach(float value, float target, float step) { return value + clamp(target - value, -step, step); }
    private static float finite(float value, float fallback) { return Float.isFinite(value) ? value : fallback; }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
    private static float wrap(float angle) {
        angle %= 360;
        if (angle >= 180) angle -= 360;
        if (angle < -180) angle += 360;
        return angle;
    }
}
