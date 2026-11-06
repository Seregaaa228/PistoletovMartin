package dev.frontier.arcade.geranium;

/**
 * Pure, server-side, hand-flown fixed-wing model for the Geranium-2 (one call per 20 Hz tick).
 *
 * <p>Units: degrees and blocks per tick, with Minecraft signs: yaw grows when turning right
 * (D), pitch is positive nose-down, roll is negative with the right wing down (a right turn).
 *
 * <p>Stick: A/D and horizontal mouse command a bank angle; the bank produces a coordinated
 * yaw rate and the wings level again when the stick is centred. Vertical mouse commands a
 * rate-limited pitch rate. A centred stick holds the current attitude; close to level, after
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
    /** Cruise speed at 0% and 100% throttle. */
    public static final float MIN_SPEED = .35f, MAX_SPEED = 1f;
    /** Below this airspeed the wing loses lift. */
    public static final float STALL_SPEED = .26f;
    public static final float MAX_DIVE_SPEED = 1.4f;
    public static final float DEFAULT_THROTTLE = (.5f - MIN_SPEED) / (MAX_SPEED - MIN_SPEED);
    public static final float MAX_BANK = 40, ROLL_RATE = 4, LEVEL_ROLL_RATE = 3;
    /** Coordinated turn: yaw rate = TURN_GAIN * tan(bank) / airspeed, capped. */
    public static final float TURN_GAIN = 2.3f, MAX_TURN_RATE = 5;
    public static final float MAX_PITCH_RATE = 2.5f, PITCH_ACCEL = .5f, LEVEL_PITCH_RATE = 2;
    public static final float MIN_PITCH = -35, MAX_PITCH = 80, LEVEL_CAPTURE = 6;
    /** Native SBW mouse units (already smoothed by its client) that give a full stick. */
    public static final float MOUSE_FULL = 6;
    public static final int LAUNCH_TICKS = 30;
    public static final float LAUNCH_PITCH = -20, LAUNCH_SPEED = .35f, LAUNCH_CRUISE = .9f;
    public static final float THROTTLE_STEP = .015f;
    public static final double FALL_GRAVITY = .06, FALL_DRAG = .98, MAX_FALL_SPEED = 2;
    static final float ZERO_LIFT_SPEED = .1f, STALL_SINK = .3f, STALL_NOSE_DROP = 3, STALL_NOSE_LIMIT = 25;
    static final float THRUST = .04f, MAX_THRUST_ACCEL = .03f, GRAVITY_ALONG_PATH = .035f;
    static final float LEVEL_GAIN = .3f, STICK_DEADZONE = .04f;
    static final int MOUSE_HOLD_TICKS = 2;

    private float yaw, pitch, roll, pitchRate, speed, throttle = DEFAULT_THROTTLE;
    private double vx, vy, vz;
    private float mouseX, mouseY;
    private int mouseAge = Integer.MAX_VALUE, flightTicks;
    private boolean launched, spaceDown, levelRequested = true;
    private short keys;

    public GeraniumFlightModel(float yaw, float pitch) {
        this.yaw = wrap(finite(yaw, 0));
        this.pitch = clamp(finite(pitch, 0), MIN_PITCH, MAX_PITCH);
    }

    /** Booster throw along the current heading; returns false when this airframe has already launched. */
    public boolean launch() {
        if (launched) return false;
        launched = true;
        flightTicks = 0;
        speed = LAUNCH_SPEED;
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
            rollStick = deadzone(clamp(turn + (fresh ? mouseX / MOUSE_FULL : 0), -1, 1));
            pitchStick = deadzone(fresh ? clamp(mouseY / MOUSE_FULL, -1, 1) : 0);
        }
        if (mouseAge < Integer.MAX_VALUE) mouseAge++;

        if (engineAllowed) powered(rollStick, pitchStick);
        else unpowered(onGround, motionX, motionY, motionZ);
    }

    private void powered(float rollStick, float pitchStick) {
        float lift = lift(speed);
        float authority = .35f + .65f * lift;

        // Bank-to-turn: the stick commands a bank; a centred stick levels the wings.
        float targetRoll = -rollStick * MAX_BANK;
        roll = approach(roll, targetRoll, rollStick == 0 ? LEVEL_ROLL_RATE : ROLL_RATE * authority);
        float bank = -roll;
        float turnRate = TURN_GAIN * (float) Math.tan(Math.toRadians(bank)) / Math.max(speed, STALL_SPEED) * authority;
        yaw = wrap(yaw + clamp(turnRate, -MAX_TURN_RATE, MAX_TURN_RATE));

        boolean launching = flightTicks <= LAUNCH_TICKS;
        if (lift < 1) levelRequested = true; // a stall breaks the attitude hold: recover to level, not into a held dive
        float commanded;
        if (launching) {
            commanded = clamp((LAUNCH_PITCH - pitch) * LEVEL_GAIN, -MAX_PITCH_RATE, MAX_PITCH_RATE);
        } else if (pitchStick != 0) {
            levelRequested = false;
            commanded = pitchStick * MAX_PITCH_RATE * authority;
        } else if (levelRequested || Math.abs(pitch) < LEVEL_CAPTURE) {
            commanded = clamp(-pitch * LEVEL_GAIN, -LEVEL_PITCH_RATE, LEVEL_PITCH_RATE);
        } else {
            commanded = 0; // attitude hold: keep the dive or climb the pilot set
        }
        if (lift < 1 && pitch < STALL_NOSE_LIMIT) commanded += (1 - lift) * STALL_NOSE_DROP;
        pitchRate = approach(pitchRate, commanded, PITCH_ACCEL);
        pitch += pitchRate;
        if (pitch < MIN_PITCH || pitch > MAX_PITCH) {
            pitch = clamp(pitch, MIN_PITCH, MAX_PITCH);
            pitchRate = 0;
        }

        float cruise = MIN_SPEED + (MAX_SPEED - MIN_SPEED) * throttle;
        if (launching) cruise = Math.max(cruise, LAUNCH_CRUISE);
        float thrust = clamp((cruise - speed) * THRUST, -MAX_THRUST_ACCEL, MAX_THRUST_ACCEL);
        float alongPath = GRAVITY_ALONG_PATH * (float) Math.sin(Math.toRadians(pitch));
        speed = clamp(speed + thrust + alongPath, 0, MAX_DIVE_SPEED);
        velocityAlongNose((1 - lift(speed)) * STALL_SINK);
    }

    private void unpowered(boolean onGround, double motionX, double motionY, double motionZ) {
        pitchRate = 0;
        roll = approach(roll, 0, LEVEL_ROLL_RATE * .5f);
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
        speed = onGround ? 0 : (float) Math.min(MAX_DIVE_SPEED, Math.sqrt(horizontal * horizontal + vy * vy));
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
        this.roll = clamp(finite(roll, 0), -MAX_BANK, MAX_BANK);
        this.throttle = clamp(finite(throttle, DEFAULT_THROTTLE), 0, 1);
        this.speed = launched ? clamp(finite(speed, LAUNCH_SPEED), 0, MAX_DIVE_SPEED) : 0;
        this.flightTicks = Math.max(0, flightTicks);
        this.launched = launched;
        pitchRate = 0;
        levelRequested = true; // nobody is on the monitor right after a load
        keys = 0;
        spaceDown = false;
        clearMouse();
        if (launched) velocityAlongNose(0); else vx = vy = vz = 0;
    }

    public float yaw() { return yaw; }
    public float pitch() { return pitch; }
    public float roll() { return roll; }
    /** Airspeed while powered, fall speed while unpowered, 0 before launch. */
    public float speed() { return speed; }
    public float throttle() { return throttle; }
    public boolean launched() { return launched; }
    public int flightTicks() { return flightTicks; }
    public boolean levelHold() { return levelRequested; }
    public double vx() { return vx; }
    public double vy() { return vy; }
    public double vz() { return vz; }

    /** HUD helper: a launched airframe below this airspeed is losing lift. */
    public static boolean stallSpeed(double airspeed) { return airspeed < STALL_SPEED; }

    static float lift(float airspeed) { return clamp((airspeed - ZERO_LIFT_SPEED) / (STALL_SPEED - ZERO_LIFT_SPEED), 0, 1); }
    private void clearMouse() { mouseX = mouseY = 0; mouseAge = Integer.MAX_VALUE; }
    private static float deadzone(float stick) { return Math.abs(stick) < STICK_DEADZONE ? 0 : stick; }
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
