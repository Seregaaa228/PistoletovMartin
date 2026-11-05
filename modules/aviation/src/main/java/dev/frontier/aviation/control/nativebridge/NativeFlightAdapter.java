package dev.frontier.aviation.control.nativebridge;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import dev.frontier.aviation.control.*;
import dev.frontier.aviation.aircraft.AircraftCatalog;
import dev.frontier.aviation.handling.AerodynamicModel;
import dev.frontier.aviation.handling.FlightDynamics;
import dev.frontier.aviation.handling.GroundHandling;
import dev.frontier.aviation.handling.HandlingProfile;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.registries.ForgeRegistries;

/** The native force integrator remains the sole owner of linear movement. */
public final class NativeFlightAdapter implements NativeFlightAccess {
    public static final NativeFlightAdapter INSTANCE = new NativeFlightAdapter();
    public static final String PERSISTENT_KEY = NativeFlightPersistenceData.KEY;
    private record Applied(long tick, FlightOutput output) {}
    private final Map<Entity, Applied> applied = new WeakHashMap<>();
    private final Map<Entity, Long> corrected = new WeakHashMap<>();
    private NativeFlightAdapter() {}

    @Override public boolean supports(Entity entity) {
        if (!(entity instanceof VehicleEntity)) return false;
        var id = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        return id != null && FlightProfiles.find(id.toString()).isPresent();
    }
    @Override public FlightProfile profile(Entity entity) {
        var id = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        return FlightProfiles.find(id == null ? "" : id.toString())
            .orElseThrow(() -> new IllegalArgumentException("Unsupported aircraft"));
    }
    @Override public LivingEntity pilot(Entity entity) {
        return entity instanceof VehicleEntity vehicle && vehicle.getFirstPassenger() instanceof LivingEntity living ? living : null;
    }
    /** Weapon pylon missions on Su24 are not aircraft flight authority. */
    public static boolean hasNativeAuthority(Entity entity) {
        return entity instanceof VehicleEntity vehicle && (vehicle.getLoiterActive()
            || entity.getPersistentData().hasUUID("frontierIndustryVehicleDriver"));
    }
    public static boolean blocksManualAcquire(Entity entity) {
        return entity.getPersistentData().hasUUID("frontierIndustryVehicleDriver");
    }
    /** Called only after service validation of live seat0 and lease ownership. */
    public static void beginManual(Entity entity) {
        if (entity instanceof VehicleEntity vehicle) vehicle.setLoiterActive(false);
    }
    @Override public FlightState initialState(Entity entity) {
        VehicleEntity vehicle = vehicle(entity);
        boolean helicopter = profile(entity).kind() == FlightKind.HELICOPTER;
        CompoundTag nativeTag = new CompoundTag();
        if (entity instanceof dev.sbw.deepstrike.vehicle.Su24mEntity su24) nativeTag.putDouble("Su24Throttle", su24.throttleSetting());
        var selection = NativeFlightPersistenceData.read(entity.getPersistentData().getCompound(PERSISTENT_KEY), nativeTag,
            helicopter ? .045f : .02f, helicopter ? .12f : 1, vehicle.getPower(), engineUsable(vehicle, vehicle.getPower()));
        float throttle = selection.targetThrottle();
        EnginePhase phase = selection.ignition();
        return new FlightState(vehicle.getXRot(), vehicle.getYRot(), vehicle.getRoll(), 0, 0, 0,
            Mth.clamp(throttle, 0, 1), phase == EnginePhase.RUNNING ? Math.max(0, finite(vehicle.getPower(), 0)) : 0,
            phase, 0, phase == EnginePhase.RUNNING ? 1 : 0, !vehicle.getGearUp(), vehicle.getHoverMode(), 0);
    }
    @Override public FlightEnvironment sample(Entity entity, long tick) {
        VehicleEntity v = vehicle(entity);
        EngineInfo info = v.getEngineInfo();
        FlightEnvironment environment = sampleNative(info, new NativeReadings(tick,
            finite((float) v.getDeltaMovement().length(), 0),
            finite((float) v.getDeltaMovement().dot(v.getViewVector(1)), 0),
            v.onGround(), profile(entity).kind() == FlightKind.HELICOPTER, emergency(v),
            v.getRoll(), v.getXRot(), v.getSynchedPropellerRot(), v.getHoverMode(), v.getPower(),
            v.getMainEngineDamaged(), v.getMaxEnergy(), v.getEnergy()));
        String id = entityId(v);
        var performance = AircraftCatalog.performance(id);
        // Without gravity (a held fixture or scripted aircraft) there is nothing to stall against.
        return performance.isPresent() ? withPerformance(environment, performance.get(), !v.isNoGravity(),
            AircraftCatalog.dynamics(id), groundClearance(v)) : environment;
    }
    /** Height above the terrain column under the aircraft (blocks), from the shared heightmap. */
    private static float groundClearance(VehicleEntity v) {
        var level = v.level();
        int x = Mth.floor(v.getX()), z = Mth.floor(v.getZ());
        if (!level.hasChunk(x >> 4, z >> 4)) return FlightEnvironment.CLEAR;
        double ground = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
        return (float) Mth.clamp(finite((float) (v.getY() - ground), FlightEnvironment.CLEAR), 0, FlightEnvironment.CLEAR);
    }
    /** Values sampled once from native state. No keyboard or previous command flags enter this boundary. */
    public record NativeReadings(long tick, float speed, float forward, boolean ground, boolean helicopter,
        boolean emergency, float roll, float pitch, float rotor, boolean hover, float power,
        boolean mainEngineDamaged, int maximumEnergy, int energy) {}
    public static FlightEnvironment sampleNative(EngineInfo info, NativeReadings nativeState) {
        long tick = nativeState.tick(); float speed = nativeState.speed(), forward = nativeState.forward();
        boolean ground = nativeState.ground(), helicopter = nativeState.helicopter(), emergency = nativeState.emergency();
        float pitch = 0, yaw = 0, roll = 0, rise = .006f, fall = .006f, secondary = .03f;
        float max = 1, boost = 3, idle = .02f, airMin = .025f, secondaryMin = .025f;
        int upRamp = 1, downRamp = 1;
        float rotor = helicopter ? Mth.clamp(nativeState.rotor() / .12f, 0, 1) : 0;
        boolean gear = false;
        if (info instanceof EngineInfo.Aircraft a && !(info instanceof EngineInfo.Tom6)) {
            rise *= a.getIncrement(); fall *= a.getDecrement();
            gear = a.getHasGear();
            pitch = ground ? 0 : 3.5f * (Mth.clamp(forward - .24f, .1f, .2f) / .2f) * a.getPitchSpeed();
            // Normalize full axis authority to the audited native speed envelope.
            float yawLimit = .3f + 3.2f * Math.abs(com.atsuishio.superbwarfare.tools.VectorTool.calculateY(nativeState.roll()));
            yaw = a.getYawSpeed() * (ground ? Mth.clamp(.6f * speed, 0, .75f) / .75f : yawLimit * Math.min(1, .24f * speed));
            // Native q=.9*(q+.6), so pre-damping sustained full A/D envelope is 6.
            roll = ground ? 0 : a.getRollSpeed() * 6f;

        } else if (info instanceof EngineInfo.Helicopter h && !(info instanceof EngineInfo.Aircraft)) {
            max = boost = .12f; idle = .045f; rise = .0007f * h.getIncrement(); fall = .001f * h.getDecrement(); secondary = fall;
            upRamp = 10; downRamp = 5;
            airMin = .058f / Math.max(.001f, h.getLiftSpeed()); secondaryMin = .035f / Math.max(.001f, h.getLiftSpeed());
            float rawRotor = Math.max(0, nativeState.rotor());
            pitch = ground ? 0 : 1.5f * h.getPitchSpeed() * rawRotor;
            yaw = h.getYawSpeed() * (ground ? .1f : 10f) * rotor;
            roll = ground ? 0 : 70f * h.getRollSpeed() * rawRotor;
            if (nativeState.hover()) { pitch *= .2f; yaw *= .5f; roll *= .05f; }
        } else if (info instanceof EngineInfo.Tom6 t) {
            boost = 2.2f; rise = .02f * t.getIncrement(); fall = .01f * t.getDecrement();
            pitch = ground ? 0 : 4f * t.getPitchSpeed() * (Math.min(Math.max(speed - .1f, .01f), .9f) / .9f);
            float yFactor = Math.min((ground ? 1.5f : .9f) * Math.max(speed - .06f, .1f), .9f);
            float yCap = 3f * (Math.abs(Mth.clamp(nativeState.roll() / 60f, -1.5f, 1.5f)) + 1f);
            yaw = t.getYawSpeed() * yCap * (yFactor / .9f);
            roll = ground ? 0 : t.getRollSpeed() * (float) (16d / 3d) * Math.max(0, 1 - Math.abs(nativeState.pitch() / 90f));
        }
        float cost = engineCost(info, Math.max(0, nativeState.power()));
        boolean energy = nativeState.maximumEnergy() <= 0 || nativeState.energy() > cost;
        ThrottleCapabilities throttle = throttleCapabilities(info, ground);
        rise = throttle.normalIncrease(); boost = throttle.boostMaximumPower();
        var caps = new FlightEnvironment.AxisCaps(cap(pitch), cap(pitch), cap(yaw), cap(yaw), cap(roll), cap(roll));
        return new FlightEnvironment(tick, speed, ground, !emergency && !nativeState.mainEngineDamaged(), energy,
            gear, helicopter, emergency, caps, idle, max, boost, cap(rise), cap(fall), cap(secondary),
            upRamp, downRamp, cap(airMin), cap(secondaryMin), helicopter ? max : .25f, rotor, throttle.boostIncrease());
    }
    /** One of our jets on the native engine: its own power, spool, rates and stall speed. */
    public static FlightEnvironment withPerformance(FlightEnvironment e, dev.frontier.aviation.aircraft.AircraftPerformance p, boolean canStall) {
        return withPerformance(e, p, canStall, FlightDynamics.NONE, FlightEnvironment.CLEAR);
    }
    /**
     * With per-jet dynamics the ground phase steers by the nose wheel (a turn radius at taxi
     * speed instead of native steering that only works fast) and lets the pilot raise the
     * nose above rotation speed; it never pitches nose-down or banks on the wheels.
     */
    public static FlightEnvironment withPerformance(FlightEnvironment e, dev.frontier.aviation.aircraft.AircraftPerformance p,
                                                    boolean canStall, FlightDynamics dynamics, float groundClearance) {
        var c = e.caps();
        var caps = new FlightEnvironment.AxisCaps(c.pitchNegative() * p.pitchRate(), c.pitchPositive() * p.pitchRate(),
            c.yawNegative(), c.yawPositive(), c.rollNegative() * p.rollRate(), c.rollPositive() * p.rollRate());
        float stall = canStall ? p.stallSpeed() : 0;
        if (e.onGround() && dynamics.active() && !e.hoverAvailable()) {
            float steering = GroundHandling.steeringRate(e.speed(), dynamics);
            float rotation = stall > 0 && e.speed() >= stall * GroundHandling.ROTATION_FRACTION
                ? GroundHandling.ROTATION_RATE * p.pitchRate() : 0;
            caps = new FlightEnvironment.AxisCaps(rotation, 0, steering, steering, 0, 0);
        }
        return new FlightEnvironment(e.tick(), e.speed(), e.onGround(), e.engineHealthy(), e.energyAvailable(),
            e.gearAvailable(), e.hoverAvailable(), e.nativeEmergency(), caps, e.idlePower(), p.maxPower(), p.boostPower(),
            cap(e.throttleIncrease() * p.throttleRate()), e.throttleDecrease(), e.secondaryDecrease(),
            e.throttleRampUpTicks(), e.throttleRampDownTicks(), e.airborneMinimumPower(), e.secondaryMinimumPower(),
            e.airBrakePowerCap(), e.rotorSpool(), cap(e.boostThrottleIncrease() * p.throttleRate()), stall,
            Math.max(0, finite(groundClearance, FlightEnvironment.CLEAR)), dynamics);
    }
    /** Capabilities are intrinsic to the native engine, never the previous tick's input flags. */
    public record ThrottleCapabilities(float normalIncrease, float boostIncrease, float boostMaximumPower) {}
    public static ThrottleCapabilities throttleCapabilities(EngineInfo info, boolean onGround) {
        if (info instanceof EngineInfo.Aircraft aircraft && !(info instanceof EngineInfo.Tom6)) {
            float base = cap(.006f * aircraft.getIncrement());
            return new ThrottleCapabilities(onGround ? base * 1.6f : base, base * 1.6f, 3);
        }
        if (info instanceof EngineInfo.Tom6 tom) {
            float rate = cap(.02f * tom.getIncrement());
            return new ThrottleCapabilities(rate, rate, 2.2f);
        }
        if (info instanceof EngineInfo.Helicopter helicopter) {
            float rate = cap(.0007f * helicopter.getIncrement());
            return new ThrottleCapabilities(rate, rate, .12f);
        }
        return new ThrottleCapabilities(.006f, .006f, 1);
    }
    private static float engineCost(EngineInfo info, float power) {
        if (info instanceof EngineInfo.Tom6 t) return (int) (t.getEnergyCostRate() * power) * 2f;
        if (info instanceof EngineInfo.Aircraft a) return (int) (a.getEnergyCostRate() * power);
        if (info instanceof EngineInfo.Helicopter h) return (int) ((int) h.getEnergyCostRate() * 8.3333f * power);
        return 0;
    }
    @Override public void apply(Entity entity, FlightOutput output) {
        VehicleEntity v = vehicle(entity);
        boolean starting;
        synchronized (applied) {
            Applied previous = applied.put(entity, new Applied(entity.level().getGameTime(), output));
            starting = output.state().enginePhase() == EnginePhase.STARTING
                && (previous == null || previous.output.state().enginePhase() != EnginePhase.STARTING);
        }
        FlightState state = output.state();
        if (!emergency(v)) {
            float pitch = permittedPitch(v.getEngineInfo(), v.onGround(), state.pitch());
            float roll = permittedRoll(v.getEngineInfo(), state.roll());
            v.setXRot(pitch); v.setYRot(state.yaw()); v.setZRot(roll);
        }
        v.setPower(output.nativePower());
        v.setEngineStart(state.enginePhase() == EnginePhase.STARTING || state.enginePhase() == EnginePhase.RUNNING);
        v.setEngineStartOver(state.enginePhase() == EnginePhase.RUNNING);
        v.setGearUp(!state.gearDown()); v.setHoverMode(state.hover());
        // New input has its own protocol; native W/S/A/D and mouse must not integrate again.
        v.setForwardInputDown(false); v.setBackInputDown(false); v.setLeftInputDown(false); v.setRightInputDown(false);
        v.setUpInputDown(false); v.setDownInputDown(output.secondaryBrake() || output.brake() && profile(entity).kind() == FlightKind.AIRCRAFT);
        v.setSprintInputDown(output.boost()); v.setDeltaRot(0);
        if (starting) playStart(v);
        if (output.brake() && (v.onGround() || profile(entity).kind() == FlightKind.HELICOPTER)) {
            var motion = v.getDeltaMovement();
            FlightDynamics dynamics = AircraftCatalog.dynamics(entityId(v));
            if (v.onGround() && dynamics.active() && profile(entity).kind() == FlightKind.AIRCRAFT) {
                // Our jets: wheel brakes with a sensible deceleration instead of a 20% cut per tick.
                double ground = Math.hypot(motion.x, motion.z);
                double scale = ground > 1.0e-6 ? GroundHandling.brake(ground, dynamics) / ground : 0;
                v.setDeltaMovement(motion.multiply(scale, 1, scale));
            } else {
                v.setDeltaMovement(motion.multiply(v.onGround() ? .8 : .92, 1, v.onGround() ? .8 : .92));
            }
        }
    }
    /** Apply the calibrated correction after the native aircraft force pass. */
    public void applyAerodynamicCorrection(VehicleEntity vehicle) {
        if (!controls(vehicle) || profile(vehicle).kind() != FlightKind.AIRCRAFT) return;
        String id = entityId(vehicle);
        FlightDynamics dynamics = AircraftCatalog.dynamics(id);
        if (vehicle.onGround() && !dynamics.active()) return;
        HandlingProfile handling = AircraftCatalog.find(id)
            .map(definition -> definition.handling()).orElse(HandlingProfile.NATIVE);
        if (handling.nativeOnly() && !dynamics.active()) return;
        long tick = vehicle.level().getGameTime();
        synchronized (applied) {
            if (java.util.Objects.equals(corrected.get(vehicle), tick)) return;
            corrected.put(vehicle, tick);
        }
        if (vehicle.onGround()) {
            // Tyres hold the jet on its heading: remove most of the sideways ground speed,
            // keep the forward roll. Native ground friction alone lets it skid in turns.
            var motion = vehicle.getDeltaMovement();
            double yaw = Math.toRadians(vehicle.getYRot());
            double rightX = Math.cos(yaw), rightZ = Math.sin(yaw);
            double lateral = motion.x * rightX + motion.z * rightZ;
            double removed = lateral - GroundHandling.grip(lateral, dynamics);
            vehicle.setDeltaMovement(motion.x - rightX * removed, motion.y, motion.z - rightZ * removed);
            return;
        }
        if (handling.nativeOnly()) return;
        var velocity = vehicle.getDeltaMovement();
        // Entity.getUpVector contains pitch/yaw only; the native transform also
        // includes bank, which is essential for the body-right drag direction.
        var up = vehicle.getTransformFromString("Vehicle", 1).transformDirection(new org.joml.Vector3d(0, 1, 0));
        var correction = AerodynamicModel.correction(handling,
            new AerodynamicModel.Vector(velocity.x, velocity.y, velocity.z),
            vector(vehicle.getViewVector(1)), new AerodynamicModel.Vector(up.x, up.y, up.z), false);
        var delta = correction.acceleration();
        vehicle.setDeltaMovement(velocity.add(delta.x(), delta.y(), delta.z()));
    }
    /**
     * Replaces native terrain compat for our jets on the wheels: settle onto the gear stance
     * (level wings, gear stance pitch), keeping a pilot's nose-up rotation above rotation
     * speed. Deterministic from attitude and speed, so both sides agree. False: native runs.
     */
    public static boolean settleOnGear(VehicleEntity v) {
        String id = entityId(v);
        var performance = AircraftCatalog.performance(id);
        if (performance.isEmpty() || !AircraftCatalog.dynamics(id).active()) return false;
        if (!v.onGround() || emergency(v) || v.isInFluidType()) return false;
        var motion = v.getDeltaMovement();
        float rotation = v.isNoGravity() ? 0 : performance.get().stallSpeed() * GroundHandling.ROTATION_FRACTION;
        var settled = GroundHandling.settle(v.getXRot(), v.getRoll(), (float) Math.hypot(motion.x, motion.z),
            rotation, GroundHandling.GEAR_STANCE_PITCH);
        v.setXRot(settled.pitch());
        v.setZRot(settled.roll());
        return true;
    }
    /** Put a freshly deployed jet straight onto its gear: stance pitch, wings level. */
    public static void deployOnGear(VehicleEntity v) {
        v.setXRot(GroundHandling.GEAR_STANCE_PITCH); v.xRotO = GroundHandling.GEAR_STANCE_PITCH;
        v.setZRot(0); v.setPrevRoll(0);
    }
    private static String entityId(Entity entity) {
        var id = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        return id == null ? "" : id.toString();
    }
    private static AerodynamicModel.Vector vector(net.minecraft.world.phys.Vec3 value) {
        return new AerodynamicModel.Vector(value.x, value.y, value.z);
    }
    public static float permittedPitch(EngineInfo info, boolean onGround, float pitch) {
        if (info instanceof EngineInfo.Tom6) return Mth.clamp(pitch, onGround ? -12 : -120, onGround ? 3 : 120);
        float limit = info instanceof EngineInfo.Aircraft aircraft ? aircraft.getClampPitch() : 120;
        return Mth.clamp(pitch, -limit, limit);
    }
    public static float permittedRoll(EngineInfo info, float roll) {
        return info instanceof EngineInfo.Aircraft aircraft && !(info instanceof EngineInfo.Tom6)
            ? Mth.clamp(roll, -aircraft.getClampRoll(), aircraft.getClampRoll()) : roll;
    }
    public static net.minecraft.sounds.SoundEvent startSound(EngineInfo info) {
        if (info instanceof EngineInfo.Tom6) return null; // Native tomEngine has no startup sound site.
        return info instanceof EngineInfo.Helicopter helicopter ? helicopter.getEngineStartSound() : null;
    }
    private static void playStart(VehicleEntity v) {
        if (v.level().isClientSide) return;
        var info = v.getEngineInfo();
        var sound = startSound(info);
        if (sound != null) v.level().playSound(null, v, sound, v.getSoundSource(), 3, 1);
    }
    @Override public FlightState captureAttitude(Entity entity, FlightState s) {
        VehicleEntity v = vehicle(entity);
        return new FlightState(v.getXRot(), v.getYRot(), v.getRoll(), s.pitchRate(), s.yawRate(), s.rollRate(),
            s.targetThrottle(), s.enginePhase() == EnginePhase.RUNNING ? Math.max(0, v.getPower()) : 0,
            s.enginePhase(), s.phaseTicks(), s.spool(), !v.getGearUp(), v.getHoverMode(), s.throttleHoldTicks());
    }
    @Override public void savePersistent(Entity entity, FlightState state) {
        NativeFlightPersistenceData.save(entity.getPersistentData(), state.targetThrottle(), state.enginePhase());
    }
    /** Called after native load; only additions in ForgeData are read/written. */
    public void loaded(VehicleEntity entity, CompoundTag nativeTag) {
        clear(entity);
        boolean helicopter = profile(entity).kind() == FlightKind.HELICOPTER;
        float power = nativeTag.contains("Power", 99) ? nativeTag.getFloat("Power") : entity.getPower();
        var selection = NativeFlightPersistenceData.load(entity.getPersistentData(), nativeTag,
            helicopter ? .045f : .02f, helicopter ? .12f : 1, power, engineUsable(entity, power));
        if (selection.ignition() == EnginePhase.OFF
            && !NativeFlightPersistenceData.unknownVersion(entity.getPersistentData().getCompound(PERSISTENT_KEY))) {
            entity.setPower(0); entity.setEngineStart(false); entity.setEngineStartOver(false);
        }
    }
    private static boolean engineUsable(VehicleEntity entity, float power) {
        return !emergency(entity) && !entity.getMainEngineDamaged()
            && (entity.getMaxEnergy() <= 0 || entity.getEnergy() > engineCost(entity.getEngineInfo(), Math.max(0, finite(power, 0))));
    }
    public FlightOutput output(Entity entity) {
        synchronized (applied) {
            Applied value = applied.get(entity);
            return value != null && value.tick == entity.level().getGameTime() ? value.output : null;
        }
    }
    public boolean controls(Entity entity) { return FlightNativeHooks.manages(entity) && output(entity) != null; }
    public void clear(Entity entity) { synchronized (applied) { applied.remove(entity); corrected.remove(entity); } }
    public void clear() { synchronized (applied) { applied.clear(); corrected.clear(); } }
    public static boolean emergency(VehicleEntity v) { return v.isWreck() || v.getHealth() <= v.getMaxHealth() * .1f; }
    /** Our jets keep their gear stance on the wheels (settleOnGear), so native ground pitch writers stay out. */
    public boolean allowAngle(VehicleEntity v) {
        return !controls(v) || emergency(v) || v.getHoverMode()
            || v.onGround() && !AircraftCatalog.dynamics(entityId(v)).active();
    }
    public boolean allowPower(VehicleEntity v, String writer) {
        if (!controls(v)) return true;
        return switch (writer) { case "damage", "emergency", "ground" -> true; case "hover" -> v.getHoverMode(); default -> false; };
    }
    public boolean startFlag(VehicleEntity v, boolean original, boolean complete) {
        FlightOutput out = output(v);
        if (!controls(v) || out == null) return original;
        return complete ? out.state().enginePhase() == EnginePhase.RUNNING
            : out.state().enginePhase() == EnginePhase.STARTING || out.state().enginePhase() == EnginePhase.RUNNING;
    }
    public float propulsion(VehicleEntity v, float nativeValue) {
        FlightOutput out = output(v);
        return controls(v) && out != null && !out.propulsionEnabled() ? 0 : nativeValue;
    }
    public boolean audible(VehicleEntity v, boolean original) {
        if (!FlightNativeHooks.manages(v)) return original;
        FlightState state = initialState(v);
        synchronized (applied) { var a = applied.get(v); if (a != null) state = a.output.state(); }
        return state.enginePhase() == EnginePhase.STARTING || state.enginePhase() == EnginePhase.RUNNING
            || v.getSynchedPropellerRot() > .0004f;
    }
    private static VehicleEntity vehicle(Entity entity) {
        if (!(entity instanceof VehicleEntity v)) throw new IllegalArgumentException("Not a native vehicle");
        return v;
    }
    private static float finite(float value, float fallback) { return Float.isFinite(value) ? value : fallback; }
    private static float cap(float value) { return Math.max(0, finite(value, 0)); }
}
