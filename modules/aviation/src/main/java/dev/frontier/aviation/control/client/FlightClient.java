package dev.frontier.aviation.control.client;

import com.atsuishio.superbwarfare.config.client.ControlConfig;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.init.ModItems;
import com.atsuishio.superbwarfare.init.ModKeyMappings;
import dev.frontier.aviation.FlightControls;
import dev.frontier.aviation.control.*;
import dev.frontier.aviation.control.diagnostics.FlightDiagnostics;
import dev.frontier.aviation.control.nativebridge.*;
import dev.frontier.aviation.control.net.FlightNetwork;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Client-only lease, input and attitude synchronization. Native movement runs once. */
public final class FlightClient implements FlightNativeHooks.Callbacks {
    private static final FlightClient INSTANCE = new FlightClient();
    private static final NativeFlightAdapter NATIVE = NativeFlightAdapter.INSTANCE;
    private final Map<UUID, Tracked> tracked = new HashMap<>();
    private final FlightMouseInput mouse = new FlightMouseInput();
    private final FlightSnapshotPolicy snapshotPolicy = new FlightSnapshotPolicy();
    private ClientLevel world;
    private VehicleEntity selected;
    private ControlLease lease;
    private FlightPrediction prediction = new FlightPrediction();
    private final FlightCommandBuffer commands = new FlightCommandBuffer();
    private long clientTick, requestTick = -100;
    private boolean engineKey, gearKey, hoverKey, freeCamera, inputActive;
    private boolean desiredEngine, desiredGear, desiredHover;
    private static boolean registered;
    private static final class Tracked {
        final Entity entity;
        FlightSnapshot snapshot;
        FlightState from, displayed;
        long receivedTick, steppedTick = Long.MIN_VALUE;
        Tracked(Entity entity) { this.entity = entity; }
    }
    private FlightClient() {}
    public static void register() {
        if (registered) return;
        registered = true;
        MinecraftForge.EVENT_BUS.register(INSTANCE);
        FlightNetwork.installClientReceiver(INSTANCE::receive);
        FlightNetwork.installPayloadReceiver(dev.frontier.aviation.client.F16PayloadVisual::receive);
        FlightControls.installClient(INSTANCE);
    }
    public static boolean isLocalPilot() { return localVehicle() != null; }
    public static VehicleEntity controlledEntity() { return localVehicle(); }
    public static boolean supportsLocalHover() {
        VehicleEntity vehicle = localVehicle();
        return vehicle != null && NATIVE.profile(vehicle).kind() == FlightKind.HELICOPTER;
    }
    public static FlightSnapshot snapshot() {
        VehicleEntity vehicle = localVehicle();
        Tracked state = vehicle == null ? null : INSTANCE.tracked.get(vehicle.getUUID());
        return state != null && state.entity == vehicle ? state.snapshot : null;
    }
    public static FlightTelemetry telemetry() {
        FlightSnapshot snapshot = snapshot();
        if (snapshot == null) return null;
        return snapshot.telemetry();
    }
    public static FlightState predictedState() { return INSTANCE.prediction.displayed(); }
    /** Current local input without consuming it or changing the control stream. */
    public static PilotCommand visualInput(Entity entity) {
        return entity == localVehicle() && INSTANCE.lease != null && INSTANCE.inputActive
            ? INSTANCE.commands.visualIntent() : null;
    }
    /** Visual brake state: local Space input when piloting, replicated telemetry otherwise. */
    public static boolean visualAirBrake(Entity entity) {
        if (entity == null) return false;
        // The command buffer is populated only after the lease and prediction are
        // ready. Read the local mapping directly so the visual signal is present
        // immediately while piloting, including during lease acquisition.
        if (entity == localVehicle()) return INSTANCE.inputActive && FlightKeys.BRAKE.isDown();
        if (NATIVE.pilot(entity) == null) return false;
        PilotCommand input = visualInput(entity);
        if (input != null) return input.brake();
        Tracked entry = INSTANCE.tracked.get(entity.getUUID());
        return entry != null && entry.entity == entity && entry.snapshot != null
            && entry.snapshot.telemetry().brake();
    }
    public static boolean hasVisualPilot(Entity entity) { return NATIVE.pilot(entity) != null; }
    /** Last client-side flight state for any tracked aircraft, including remote pilots. */
    public static FlightState visualState(Entity entity) {
        Tracked entry = INSTANCE.tracked.get(entity.getUUID());
        if (entry == null || entry.entity != entity || entry.snapshot == null) return null;
        return entry.displayed != null ? entry.displayed : entry.snapshot.telemetry().state();
    }
    /** Scope includes waiting-for-lease pilots, so old input never races acquire. */
    public static boolean suppressNativeInput() { return localVehicle() != null && !usingDroneMonitor(); }
    public static boolean capturesMouse() {
        Minecraft mc = Minecraft.getInstance();
        return suppressNativeInput() && mc.screen == null && mc.isWindowActive() && mc.mouseHandler.isMouseGrabbed()
            && !ModKeyMappings.FREE_CAMERA.isDown();
    }
    public static void rawMouse(double dx, double dy) {
        if (capturesMouse()) INSTANCE.mouse.add(dx, dy);
        else INSTANCE.mouse.clear();
    }
    public static void clearMouse() { INSTANCE.mouse.clear(); }
    public static boolean managedAttitude(Entity entity) { return INSTANCE.manages(entity); }
    private static VehicleEntity localVehicle() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !mc.player.isAlive() || mc.player.isSpectator()) return null;
        Entity entity = mc.player.getVehicle();
        return entity instanceof VehicleEntity vehicle && !vehicle.isRemoved() && NATIVE.supports(vehicle)
            && NATIVE.pilot(vehicle) == mc.player && !NativeFlightAdapter.hasNativeAuthority(vehicle) ? vehicle : null;
    }
    public static boolean usingDroneMonitor() {
        var player = Minecraft.getInstance().player;
        if (player == null) return false;
        var item = player.getMainHandItem();
        var tag = item.getTag();
        return item.is(ModItems.MONITOR.get()) && tag != null && tag.getBoolean("Using") && tag.getBoolean("Linked");
    }
    @SubscribeEvent public void worldUnload(LevelEvent.Unload event) {
        if (event.getLevel() == world) {
            if (FlightDiagnostics.ENABLED) FlightDiagnostics.dumpClient();
            clear(false); world = null;
        }
    }
    @SubscribeEvent public void screenOpening(ScreenEvent.Opening event) {
        mouse.clear();
        commands.clearTransient();
        inputActive = false;
        syncKeys();
    }
    @SubscribeEvent public void keyEvent(InputEvent.Key event) {
        // Clear even a complete press/release of C between two client ticks.
        if (event.getKey() == ModKeyMappings.FREE_CAMERA.getKey().getValue()) mouse.clear();
    }
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != world) {
            if (world != null && FlightDiagnostics.ENABLED) FlightDiagnostics.dumpClient();
            clear(false); world = mc.level;
        }
        if (mc.level == null) return;
        clientTick++;
        tracked.values().removeIf(entry -> {
            if (entry.entity.isRemoved() || entry.entity.level() != mc.level
                || mc.level.getEntity(entry.entity.getId()) != entry.entity) {
                FlightNativeHooks.clear(entry.entity); return true;
            }
            return false;
        });
        VehicleEntity vehicle = localVehicle();
        if (vehicle != selected) { release(); selected = vehicle; requestTick = -100; }
        if (vehicle == null) { mouse.clear(); syncKeys(); return; }
        if (lease == null && clientTick - requestTick >= 20) {
            FlightNetwork.requestLease(vehicle); requestTick = clientTick;
        }
        boolean active = mc.screen == null && mc.isWindowActive() && !usingDroneMonitor();
        if (!active) commands.clearTransient();
        boolean free = ModKeyMappings.FREE_CAMERA.isDown();
        if (free != freeCamera || active != inputActive) mouse.clear();
        freeCamera = free; inputActive = active;
        if (lease == null || prediction.state() == null) { mouse.clear(); syncKeys(); return; }
        FlightMouseInput.Axes axes = active && !free
            ? mouse.consume(mc.options.sensitivity().get(), mc.options.invertYMouse().get()
                ^ ControlConfig.INVERT_AIRCRAFT_CONTROL.get()) : new FlightMouseInput.Axes(0, 0);
        if (!active || free) mouse.clear();
        boolean engine = FlightKeys.ENGINE.isDown(), gear = FlightKeys.GEAR.isDown(), hover = FlightKeys.HOVER.isDown();
        ModeRequest ignition = ModeRequest.KEEP, gearRequest = ModeRequest.KEEP, hoverRequest = ModeRequest.KEEP;
        if (active && engine && !engineKey) { desiredEngine = !desiredEngine; ignition = request(desiredEngine); }
        if (active && gear && !gearKey) { desiredGear = !desiredGear; gearRequest = request(desiredGear); }
        if (active && supportsLocalHover() && hover && !hoverKey) { desiredHover = !desiredHover; hoverRequest = request(desiredHover); }
        engineKey = engine; gearKey = gear; hoverKey = hover;
        float bank = active ? axis(ModKeyMappings.MOVE_RIGHT.isDown(), ModKeyMappings.MOVE_LEFT.isDown()) : 0;
        float throttle = active ? axis(ModKeyMappings.MOVE_FORWARD.isDown(), ModKeyMappings.MOVE_BACKWARD.isDown()) : 0;
        boolean heli = NATIVE.profile(vehicle).kind() == FlightKind.HELICOPTER;
        PilotCommand candidate = new PilotCommand(0, axes.vertical(), heli ? bank : axes.horizontal(),
            heli ? -axes.horizontal() : bank, throttle, active && FlightKeys.BRAKE.isDown(),
            active && ModKeyMappings.MOVE_SHIFT.isDown(), active && ModKeyMappings.MOVE_CTRL.isDown(),
            ignition, gearRequest, hoverRequest);
        commands.update(candidate);
    }
    private void receive(FlightSnapshot snapshot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().location().toString().equals(snapshot.dimensionId())) return;
        Entity entity = mc.level.getEntity(snapshot.entityId());
        if (entity == null || !entity.getUUID().equals(snapshot.vehicleId()) || !NATIVE.supports(entity)) return;
        Tracked entry = tracked.get(snapshot.vehicleId());
        if (entry != null && entry.entity != entity) { FlightNativeHooks.clear(entry.entity); entry = null; }
        FlightSnapshot previous = entry == null ? null : entry.snapshot;
        FlightSnapshotPolicy.Decision decision = snapshotPolicy.accept(snapshot, previous);
        if (decision == FlightSnapshotPolicy.Decision.IGNORE) return;
        if (decision == FlightSnapshotPolicy.Decision.RETIRE) {
            tracked.remove(snapshot.vehicleId());
            if (lease != null && lease.vehicleId().equals(snapshot.vehicleId()) && lease.epoch() <= snapshot.epoch()) resetLocal();
            FlightNativeHooks.clear(entity);
            return;
        }
        if (entry == null) { entry = new Tracked(entity); tracked.put(snapshot.vehicleId(), entry); }
        entry.from = entry.displayed == null ? snapshot.telemetry().state() : entry.displayed;
        entry.snapshot = snapshot; entry.receivedTick = clientTick;
        if (entity == localVehicle() && mc.player.getUUID().equals(snapshot.controllerId())) {
            boolean newEpoch = lease == null || lease.epoch() != snapshot.epoch();
            if (newEpoch) {
                lease = new ControlLease(snapshot.vehicleId(), snapshot.controllerId(), snapshot.epoch());
                selected = (VehicleEntity) entity;
                commands.reset();
                prediction = new FlightPrediction(); mouse.clear(); syncKeys();
                FlightNativeHooks.clear(entity);
            }
            prediction.accept(NATIVE.profile(entity), snapshot.epoch(), snapshot.telemetry());
            FlightState state = prediction.state();
            desiredEngine = state.enginePhase() == EnginePhase.STARTING || state.enginePhase() == EnginePhase.RUNNING;
            desiredGear = state.gearDown(); desiredHover = state.hover();
        } else if (lease != null && lease.vehicleId().equals(snapshot.vehicleId())) {
            // Server has revoked or handed off this authority. Do not release its new owner.
            resetLocal();
        }
    }
    @Override public boolean manages(Entity entity) {
        Minecraft mc = Minecraft.getInstance();
        if (entity.level() != mc.level || entity.isRemoved() || NativeFlightAdapter.hasNativeAuthority(entity)) return false;
        Tracked entry = tracked.get(entity.getUUID());
        return entry != null && entry.entity == entity && entry.snapshot != null;
    }
    @Override public long simulationTick(Entity entity) { return clientTick; }
    @Override public void beforePhysics(Entity entity) {
        Tracked entry = tracked.get(entity.getUUID());
        if (entry == null || !manages(entity)) return;
        long tick = clientTick;
        if (entry.steppedTick == tick) return;
        entry.steppedTick = tick;
        FlightOutput output;
        if (lease != null && lease.vehicleId().equals(entity.getUUID()) && entity == localVehicle()) {
            PilotCommand command = commands.next();
            var vehicle = (VehicleEntity) entity;
            var info = vehicle.getEngineInfo();
            var limits = NativeFlightAdapter.emergency(vehicle) ? FlightAttitudeLimits.UNRESTRICTED : new FlightAttitudeLimits(
                NativeFlightAdapter.permittedPitch(info, vehicle.onGround(), -Float.MAX_VALUE),
                NativeFlightAdapter.permittedPitch(info, vehicle.onGround(), Float.MAX_VALUE),
                Math.min(180, NativeFlightAdapter.permittedRoll(info, Float.MAX_VALUE)));
            FlightNetwork.submit(lease, command);
            output = prediction.step(NATIVE.profile(entity), command, NATIVE.sample(entity, tick), limits);
        } else {
            FlightTelemetry telemetry = entry.snapshot.telemetry();
            FlightState state = FlightPrediction.interpolate(entry.from, telemetry.state(), (clientTick - entry.receivedTick + 1) / 2f);
            float nativePower = state.enginePhase() == EnginePhase.STARTING ? NATIVE.sample(entity, tick).idlePower() : state.effectivePower();
            output = new FlightOutput(state, nativePower, state.enginePhase() == EnginePhase.RUNNING,
                telemetry.brake(), telemetry.secondaryBrake(), telemetry.boost(), telemetry.status());
        }
        VehicleEntity vehicle = (VehicleEntity) entity;
        vehicle.setPrevRoll(vehicle.getRoll());
        NATIVE.apply(entity, output);
        entry.displayed = output.state();
    }
    @Override public void afterPhysics(Entity entity) {
        Tracked entry = tracked.get(entity.getUUID());
        if (entry == null || entry.displayed == null) return;
        FlightState observed = NATIVE.captureAttitude(entity, entry.displayed);
        if (lease != null && lease.vehicleId().equals(entity.getUUID()) && entity == localVehicle()) {
            prediction.capture(observed);
            FlightState state = prediction.state();
            desiredEngine = state.enginePhase() == EnginePhase.STARTING || state.enginePhase() == EnginePhase.RUNNING;
            desiredGear = state.gearDown(); desiredHover = state.hover();
        }
        entry.displayed = observed;
        VehicleEntity vehicle = (VehicleEntity) entity;
        vehicle.setPrevRoll(FlightRenderAngles.previous(vehicle.getPrevRoll(), vehicle.getRoll()));
        vehicle.yRotO = FlightRenderAngles.previous(vehicle.yRotO, vehicle.getYRot());
        vehicle.xRotO = FlightRenderAngles.previous(vehicle.xRotO, vehicle.getXRot());
    }
    private void release() {
        if (lease != null && Minecraft.getInstance().getConnection() != null) {
            FlightNetwork.release(lease);
        }
        resetLocal();
    }
    private void resetLocal() {
        lease = null; prediction = new FlightPrediction(); commands.reset();
        mouse.clear(); syncKeys(); inputActive = false; freeCamera = false;
    }
    private void clear(boolean sendRelease) {
        if (sendRelease) release(); else resetLocal();
        for (Tracked entry : tracked.values()) FlightNativeHooks.clear(entry.entity);
        tracked.clear(); snapshotPolicy.clear(); selected = null; clientTick = 0;
    }
    private void syncKeys() {
        engineKey = FlightKeys.ENGINE.isDown(); gearKey = FlightKeys.GEAR.isDown(); hoverKey = FlightKeys.HOVER.isDown();
    }
    private static ModeRequest request(boolean enabled) { return enabled ? ModeRequest.ENABLE : ModeRequest.DISABLE; }
    private static float axis(boolean positive, boolean negative) { return (positive ? 1 : 0) - (negative ? 1 : 0); }
}
