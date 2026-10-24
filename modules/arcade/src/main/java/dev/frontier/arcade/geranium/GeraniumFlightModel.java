package dev.frontier.arcade.geranium;

/**
 * Manual input adapter for Shahed136Entity.updateFlight (Uncomplicated FPV,
 * lavafrai, GPL-3.0). Retains its acceleration .05, forward rotation vector,
 * normal turn rate 1.5, bank clamp30 and roll smoothing2. Coordinate targeting
 * is replaced by pilot-requested yaw/pitch; this is not the FPV Drone.travel model.
 * Uses the original UF Shahed's forward velocity, acceleration and limited turns.
 * The caller applies its forward vector and moves exactly once through VehicleEntity.
 */
public final class GeraniumFlightModel {
    public static final float TURN_RATE = 1.5f;
    public static final float ACCELERATION = .05f;
    public static final float MIN_SPEED = .35f;
    public static final float MAX_SPEED = 1f;
    public static final float DEFAULT_THROTTLE = (.5f - MIN_SPEED) / (MAX_SPEED - MIN_SPEED);
    private static final int LAUNCH_ASSIST_TICKS = 20;
    private float yaw, pitch, roll, speed, throttle = DEFAULT_THROTTLE;
    private float targetYaw, targetPitch, mouseX, mouseY;
    private boolean launched, spaceDown, pitchCommanded;
    private int flightTicks;
    private short keys;

    public GeraniumFlightModel(float yaw, float pitch) {
        this.yaw = targetYaw = wrap(finite(yaw, 0));
        this.pitch = targetPitch = clamp(finite(pitch, 0), -45, 85);
    }

    /** Returns false when this airframe has already launched. */
    public boolean launch() {
        if (launched) return false;
        launched = true;
        flightTicks = 0;
        speed = .1f;
        targetYaw = yaw;
        targetPitch = -12;
        pitchCommanded = false;
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

    /** Native mouse deltas; consume once per simulation tick, reject malformed values. */
    public void mouse(double dx, double dy) {
        mouseX = Double.isFinite(dx) ? clamp((float) dx, -20, 20) : 0;
        mouseY = Double.isFinite(dy) ? clamp((float) dy, -20, 20) : 0;
    }

    /** Level the nose and retain the current heading; never stop or add vertical lift. */
    public void stabilize() {
        targetYaw = yaw;
        targetPitch = 0;
        pitchCommanded = true;
        mouseX = mouseY = 0;
    }

    public void tick(boolean controlAllowed, boolean engineAllowed) {
        if (!controlAllowed) {
            keys = 0;
            spaceDown = false;
            mouseX = mouseY = 0;
            targetYaw = yaw;
            targetPitch = pitch;
            pitchCommanded = true;
        }
        if (!launched) {
            speed = 0;
            mouseX = mouseY = 0;
            return;
        }

        if (flightTicks < Integer.MAX_VALUE) flightTicks++;
        if (controlAllowed) {
            int gas = ((keys & 4) != 0 ? 1 : 0) - ((keys & 8) != 0 ? 1 : 0);
            throttle = clamp(throttle + gas * .015f, 0, 1);
            int turn = ((keys & 2) != 0 ? 1 : 0) - ((keys & 1) != 0 ? 1 : 0);
            targetYaw = wrap(targetYaw + turn * TURN_RATE + mouseX * .5f);
            if (Math.abs(mouseY) > .0001f) {
                targetPitch = clamp(targetPitch + mouseY * .5f, -45, 85);
                pitchCommanded = true;
            }
        }
        mouseX = mouseY = 0;
        if (flightTicks > LAUNCH_ASSIST_TICKS && !pitchCommanded) targetPitch = 0;

        float yawChange = clamp(wrap(targetYaw - yaw), -TURN_RATE, TURN_RATE);
        yaw = wrap(yaw + yawChange);
        pitch += clamp(targetPitch - pitch, -TURN_RATE, TURN_RATE);
        float targetRoll = clamp(-yawChange * 15, -30, 30);
        roll += clamp(targetRoll - roll, -2, 2);

        if (engineAllowed) {
            float wantedSpeed = MIN_SPEED + (MAX_SPEED - MIN_SPEED) * throttle;
            speed += clamp(wantedSpeed - speed, -ACCELERATION, ACCELERATION);
        } else {
            speed *= .98f;
        }
    }

    /** Restores persistent state without replaying launch or accepting invalid NBT floats. */
    public void restore(float yaw, float pitch, float roll, float speed, float throttle,
                        int flightTicks, boolean launched) {
        this.yaw = targetYaw = wrap(finite(yaw, 0));
        this.pitch = targetPitch = clamp(finite(pitch, 0), -45, 85);
        this.roll = clamp(finite(roll, 0), -30, 30);
        this.throttle = clamp(finite(throttle, DEFAULT_THROTTLE), 0, 1);
        this.speed = launched ? clamp(finite(speed, .1f), 0, MAX_SPEED) : 0;
        this.flightTicks = Math.max(0, flightTicks);
        this.launched = launched;
        this.pitchCommanded = true;
        keys = 0;
        spaceDown = false;
        mouseX = mouseY = 0;
    }

    public float yaw() { return yaw; }
    public float pitch() { return pitch; }
    public float roll() { return roll; }
    public float speed() { return speed; }
    public float throttle() { return throttle; }
    public boolean launched() { return launched; }
    public int flightTicks() { return flightTicks; }

    private static float finite(float value, float fallback) { return Float.isFinite(value) ? value : fallback; }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
    private static float wrap(float angle) {
        angle %= 360;
        if (angle >= 180) angle -= 360;
        if (angle < -180) angle += 360;
        return angle;
    }
}
