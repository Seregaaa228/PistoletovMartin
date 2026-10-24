package dev.frontier.arcade.relay;

/**
 * Deterministic game flight, in blocks per server tick. Bank produces a turn;
 * the airframe and its velocity settle gradually instead of pivoting in place.
 */
public final class RelayOrbitModel {
    public static final double RADIUS = 72;
    public static final float CRUISE_SPEED = .5f;
    public static final float MAX_SPEED = .72f;
    public static final float TURN_RATE = 1.8f;
    public static final float PITCH_RATE = .6f;
    public static final float MAX_CLIMB_PITCH = 25f;
    public static final float MAX_BANK = 35f;
    private static final float DEFAULT_THROTTLE = .525f;
    private static final float ACCELERATION = .006f;
    private static final double TURN_GRAVITY = .018;
    private static final int LAUNCH_ASSIST_TICKS = 90;
    private double centerX, centerY, centerZ;
    private double velocityX, velocityY, velocityZ;
    private float yaw, pitch, roll, speed, airSpeed, throttle = DEFAULT_THROTTLE;
    private float targetYaw, targetPitch, mouseX, mouseY;
    private int flightTicks;
    private short keys;
    private boolean launched, stopped, orbit, spaceDown, pendingLaunch, pitchCommanded, keyBanking;

    /** Placed aircraft are parked until the pilot presses Space. */
    public RelayOrbitModel(float yaw) { this.yaw = targetYaw = wrap(finite(yaw, 0)); }

    /** Explicit manual launch; repeated requests cannot reset an airborne aircraft. */
    public boolean launchManual(double x, double y, double z) {
        if (launched || stopped) return false;
        centerX = coordinate(x); centerY = altitude(y); centerZ = coordinate(z);
        flightTicks = 0; launched = true; orbit = false; pendingLaunch = false;
        targetYaw = yaw; targetPitch = -14; pitchCommanded = false;
        speed = airSpeed = .20f;
        setVelocityFromAttitude();
        return true;
    }

    /** Legacy autonomous-launch entry point used by existing saves and integrations. */
    public void launch(double x, double targetAltitude, double z) {
        if (launchManual(x, targetAltitude, z)) {
            centerX = coordinate(x); centerY = altitude(targetAltitude); centerZ = coordinate(z);
            orbit = true;
        }
    }

    public void stop() { stopped = true; pendingLaunch = false; clearInput(); }

    /** Native SBW keys: A=1, D=2, W=4, S=8, Space=16, Shift=32. */
    public void input(short nextKeys) {
        keys = nextKeys;
        boolean nextSpace = (nextKeys & 16) != 0;
        if (nextSpace && !spaceDown) {
            if (!launched) pendingLaunch = true;
            else if (!orbit) stabilize();
        }
        spaceDown = nextSpace;
    }

    /** Deltas accumulate until the next server tick, then are consumed once. */
    public void mouse(double dx, double dy) {
        mouseX = clamp(mouseX + (float) clamp(finite(dx, 0), -20, 20), -20, 20);
        mouseY = clamp(mouseY + (float) clamp(finite(dy, 0), -20, 20), -20, 20);
    }

    /**
     * Capture a circle tangent to the present flight path. The selected place is
     * where the circle begins; its center lies one radius to the turn side.
     * Attitude, throttle and velocity are unchanged by the mode switch.
     */
    public void setOrbit(double x, double y, double z) {
        if (!launched || stopped) return;
        double heading = Math.toRadians(trackYaw());
        centerX = coordinate(finite(x, centerX) - Math.cos(heading) * RADIUS);
        centerY = altitude(finite(y, centerY));
        centerZ = coordinate(finite(z, centerZ) - Math.sin(heading) * RADIUS);
        orbit = true;
        clearInput();
    }

    /** Retain the actual heading and pitch when the pilot resumes control. */
    public void setManual() {
        if (!launched || stopped) return;
        orbit = false; targetYaw = yaw; targetPitch = pitch; pitchCommanded = true;
        keyBanking = Math.abs(roll) > .3f;
        clearInput();
    }

    /** A level-flight request, never a brake or hover command. */
    public void stabilize() {
        targetYaw = yaw; targetPitch = 0; pitchCommanded = true;
        keyBanking = Math.abs(roll) > .3f;
        mouseX = mouseY = 0;
    }

    /** Backward-compatible autonomous tick. */
    public void tick(double x, double y, double z, boolean engineAllowed) {
        tick(x, y, z, engineAllowed, true);
    }

    public void tick(double x, double y, double z, boolean engineAllowed, boolean controlAllowed) {
        x = finite(x, centerX); y = finite(y, centerY); z = finite(z, centerZ);
        if (pendingLaunch) {
            pendingLaunch = false;
            if (engineAllowed && controlAllowed) launchManual(x, y, z);
        }
        if (!launched) { speed = 0; velocityX = velocityY = velocityZ = 0; mouseX = mouseY = 0; return; }
        if (!engineAllowed) stopped = true;
        if (!controlAllowed) {
            if (!orbit && !stopped) setOrbit(x, y, z);
            clearInput();
            spaceDown = false;
        }
        if (flightTicks < Integer.MAX_VALUE) flightTicks++;

        float desiredBank;
        float desiredPitch;
        if (stopped) {
            desiredBank = 0;
            desiredPitch = 9;
        } else if (orbit) {
            throttle += clamp(DEFAULT_THROTTLE - throttle, -.003f, .003f);
            double dx = x - centerX, dz = z - centerZ;
            double radius = Math.hypot(dx, dz);
            double nx = radius > .01 ? dx / radius : Math.cos(Math.toRadians(yaw));
            double nz = radius > .01 ? dz / radius : Math.sin(Math.toRadians(yaw));
            // A vector field captures displaced aircraft without pointing at the center.
            double radial = clamp((RADIUS - radius) / 30, -1.4, 1.4);
            float desiredTrack = (float) Math.toDegrees(Math.atan2(nz - radial * nx, nx + radial * nz));
            double horizontalSpeed = Math.hypot(velocityX, velocityZ);
            float circleBank = (float) Math.toDegrees(Math.atan(horizontalSpeed * horizontalSpeed / (TURN_GRAVITY * RADIUS)));
            desiredBank = -clamp(circleBank + wrap(desiredTrack - trackYaw()) * 1.05f, -MAX_BANK, MAX_BANK);
            double wantedClimb = clamp((centerY - y) * .025 - velocityY * .55, -.13, .16);
            desiredPitch = (float) -Math.toDegrees(Math.atan2(wantedClimb, Math.max(.25, horizontalSpeed)));
            desiredPitch = clamp(desiredPitch, -MAX_CLIMB_PITCH, 18);
        } else {
            int gas = ((keys & 4) != 0 ? 1 : 0) - ((keys & 8) != 0 ? 1 : 0);
            throttle = clamp(throttle + gas * .008f, 0, 1);
            int turn = ((keys & 2) != 0 ? 1 : 0) - ((keys & 1) != 0 ? 1 : 0);
            targetYaw = wrap(targetYaw + mouseX * .5f);
            if (Math.abs(mouseY) > .0001f) {
                targetPitch = clamp(targetPitch + mouseY * .45f, -MAX_CLIMB_PITCH, 35);
                pitchCommanded = true;
            }
            if (turn != 0) {
                desiredBank = -turn * MAX_BANK;
                targetYaw = yaw;
                keyBanking = true;
            } else if (keyBanking && Math.abs(mouseX) < .0001f) {
                // Releasing a bank levels the wings at the emerging heading.
                desiredBank = 0; targetYaw = yaw;
                if (Math.abs(roll) < .3f) keyBanking = false;
            } else {
                keyBanking = false;
                desiredBank = -clamp(wrap(targetYaw - yaw) * 1.05f, -MAX_BANK, MAX_BANK);
            }
            if (flightTicks > LAUNCH_ASSIST_TICKS && !pitchCommanded) targetPitch = 0;
            desiredPitch = targetPitch;
        }
        mouseX = mouseY = 0;
        if (!stopped && flightTicks <= LAUNCH_ASSIST_TICKS && (!pitchCommanded || flightTicks < 30)) {
            desiredPitch = Math.min(desiredPitch, -14);
            desiredBank = clamp(desiredBank, -12, 12);
        }
        if (speed < .29f && (stopped || flightTicks > LAUNCH_ASSIST_TICKS)) {
            // Soft stall recovery trades height for forward speed.
            desiredPitch = Math.max(desiredPitch, 4 + (.29f - speed) * 95);
            desiredBank = clamp(desiredBank, -12, 12);
        }
        roll += clamp((desiredBank - roll) * .14f, -1f, 1f);
        pitch += clamp((desiredPitch - pitch) * .14f, -PITCH_RATE, PITCH_RATE);
        // Negative visible roll corresponds to increasing Minecraft yaw.
        double liftAuthority = clamp(speed / .4, .35, 1);
        float yawChange = (float) Math.toDegrees(-TURN_GRAVITY * Math.tan(Math.toRadians(roll))
            / Math.max(.25, speed) * liftAuthority);
        yaw = wrap(yaw + clamp(yawChange, -TURN_RATE, TURN_RATE));

        float levelSpeed = stopped ? .30f : .29f + .4f * throttle;
        float energyExchange = pitch < 0 ? pitch * .0033f : pitch * .002f;
        float wantedSpeed = clamp(levelSpeed + energyExchange - Math.abs(roll) * .00045f, .23f, MAX_SPEED);
        airSpeed += clamp((wantedSpeed - airSpeed) * .025f, -ACCELERATION, ACCELERATION);
        double yawRad = Math.toRadians(yaw), pitchRad = Math.toRadians(pitch);
        double horizontal = Math.cos(pitchRad) * airSpeed;
        velocityX += (-Math.sin(yawRad) * horizontal - velocityX) * .14;
        velocityZ += (Math.cos(yawRad) * horizontal - velocityZ) * .14;
        double wantedVertical = -Math.sin(pitchRad) * airSpeed;
        if (speed < .29f && flightTicks > LAUNCH_ASSIST_TICKS) wantedVertical -= (.29f - speed) * .35;
        velocityY += (wantedVertical - velocityY) * .11;
        speed = (float) Math.sqrt(velocityX * velocityX + velocityY * velocityY + velocityZ * velocityZ);
    }

    /** Legacy NBT had no velocity, throttle or mode. It resumes its autonomous orbit. */
    public void restore(double x, double y, double z, float yaw, float pitch, float roll,
                        float speed, int ticks, boolean launched, boolean stopped) {
        restore(x, y, z, yaw, pitch, roll, speed, DEFAULT_THROTTLE, Double.NaN, Double.NaN, Double.NaN,
            ticks, launched, stopped, true);
    }

    /** Bounds both old and current persisted state before any value enters movement. */
    public void restore(double x, double y, double z, float yaw, float pitch, float roll,
                        float speed, float throttle, double vx, double vy, double vz,
                        int ticks, boolean launched, boolean stopped, boolean orbit) {
        centerX = coordinate(x); centerY = altitude(y); centerZ = coordinate(z);
        this.yaw = targetYaw = wrap(finite(yaw, 0));
        this.pitch = targetPitch = clamp(finite(pitch, 0), -MAX_CLIMB_PITCH, 35);
        this.roll = clamp(finite(roll, 0), -MAX_BANK, MAX_BANK);
        this.speed = airSpeed = launched ? clamp(finite(speed, .20f), 0, MAX_SPEED) : 0;
        this.throttle = clamp(finite(throttle, DEFAULT_THROTTLE), 0, 1);
        flightTicks = Math.max(0, ticks); this.launched = launched; this.stopped = stopped;
        this.orbit = launched && orbit; pitchCommanded = true;
        clearInput(); spaceDown = pendingLaunch = keyBanking = false;
        setVelocityFromAttitude();
        if (launched && Double.isFinite(vx) && Double.isFinite(vy) && Double.isFinite(vz)) {
            double magnitude = Math.hypot(Math.hypot(vx, vy), vz);
            double scale = magnitude > MAX_SPEED ? MAX_SPEED / magnitude : 1;
            // Extremely large but finite components may overflow their norm.
            if (Double.isFinite(magnitude)) {
                velocityX = vx * scale; velocityY = vy * scale; velocityZ = vz * scale;
                this.speed = airSpeed = (float) Math.min(MAX_SPEED, magnitude);
            }
        }
    }

    private void clearInput() { keys = 0; mouseX = mouseY = 0; }
    private void setVelocityFromAttitude() {
        double yawRad = Math.toRadians(yaw), pitchRad = Math.toRadians(pitch);
        velocityX = -Math.sin(yawRad) * Math.cos(pitchRad) * speed;
        velocityY = -Math.sin(pitchRad) * speed;
        velocityZ = Math.cos(yawRad) * Math.cos(pitchRad) * speed;
    }
    private float trackYaw() {
        return velocityX * velocityX + velocityZ * velocityZ > .0001
            ? (float) Math.toDegrees(Math.atan2(-velocityX, velocityZ)) : yaw;
    }
    public double centerX() { return centerX; }
    public double centerY() { return centerY; }
    public double centerZ() { return centerZ; }
    public float yaw() { return yaw; }
    public float pitch() { return pitch; }
    public float roll() { return roll; }
    public float speed() { return speed; }
    public float throttle() { return throttle; }
    public double velocityX() { return velocityX; }
    public double velocityY() { return velocityY; }
    public double velocityZ() { return velocityZ; }
    public int flightTicks() { return flightTicks; }
    public boolean launched() { return launched; }
    public boolean stopped() { return stopped; }
    public boolean isOrbit() { return orbit; }
    public static int boundedEnergy(int energy) { return Math.max(0, Math.min(96_000, energy)); }
    private static float finite(float v, float fallback) { return Float.isFinite(v) ? v : fallback; }
    private static double finite(double v, double fallback) { return Double.isFinite(v) ? v : fallback; }
    private static double coordinate(double v) { return clamp(finite(v, 0), -29_999_900, 29_999_900); }
    private static double altitude(double v) { return clamp(finite(v, 80), -2048, 2048); }
    private static float clamp(float v, float min, float max) { return Math.max(min, Math.min(max, v)); }
    private static double clamp(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }
    private static float wrap(float v) { v %= 360; if (v >= 180) v -= 360; if (v < -180) v += 360; return v; }
}
