package dev.frontier.aviation.control.server;

import dev.frontier.aviation.control.*;
import dev.frontier.aviation.control.nativebridge.NativeFlightAccess;
import dev.frontier.aviation.control.nativebridge.FlightNativeHooks;
import dev.frontier.aviation.control.nativebridge.NativeFlightAdapter;
import dev.frontier.aviation.control.net.FlightNetwork;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/** Sole owner of server flight sessions. Every public world API runs on the server thread. */
public final class FlightControlService {
    private static final Map<MinecraftServer, FlightControlService> SERVICES = new IdentityHashMap<>();
    private final MinecraftServer server;
    private final NativeFlightAccess nativeAccess;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private long nextEpoch;
    private boolean closed;
    private static final class Session {
        final Entity vehicle;
        final ServerLevel level;
        final FlightAuthority authority;
        FlightState state;
        FlightTelemetry telemetry;
        FlightSnapshot sent;
        FlightOutput output;
        long stepped = Long.MIN_VALUE, captured = Long.MIN_VALUE, published = Long.MIN_VALUE;
        long receiptTick;
        Session(Entity vehicle, FlightState state, long epoch, FlightKind kind) {
            this.vehicle = vehicle; this.level = (ServerLevel) vehicle.level(); this.state = state;
            this.authority = new FlightAuthority(vehicle.getUUID(), epoch, kind);
        }
    }
    private FlightControlService(MinecraftServer server, NativeFlightAccess access) { this.server = server; nativeAccess = access; }
    public static FlightControlService get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("flight service accessed off server thread");
        return SERVICES.computeIfAbsent(server, key -> new FlightControlService(key, NativeFlightAdapter.INSTANCE));
    }
    private void thread() {
        if (!server.isSameThread() || closed) throw new IllegalStateException("inactive/off-thread flight service");
    }
    private long epoch() {
        if (nextEpoch == Long.MAX_VALUE) throw new IllegalStateException("flight epoch exhausted");
        return ++nextEpoch;
    }
    private boolean live(Entity entity) {
        return entity != null && !entity.isRemoved() && entity.isAlive() && entity.level() instanceof ServerLevel level
            && level.getServer() == server && level.getEntity(entity.getUUID()) == entity;
    }
    private boolean validPilot(LivingEntity controller, Entity vehicle) {
        return live(controller) && live(vehicle) && controller.level() == vehicle.level() && nativeAccess.pilot(vehicle) == controller
            && (!(controller instanceof ServerPlayer player) || (!player.isSpectator() && server.getPlayerList().getPlayer(player.getUUID()) == player));
    }
    private Session session(Entity vehicle) {
        Session old = sessions.get(vehicle.getUUID());
        if (old != null && (old.vehicle != vehicle || old.level != vehicle.level())) { removeVehicle(vehicle.getUUID(), ReleaseReason.VEHICLE_REMOVED); old = null; }
        if (old == null) {
            old = new Session(vehicle, nativeAccess.initialState(vehicle), epoch(), nativeAccess.profile(vehicle).kind()); sessions.put(vehicle.getUUID(), old);
            updateTelemetry(old, nativeAccess.sample(vehicle, vehicle.level().getGameTime()), ControlStatus.OK, false, false, false);
        }
        return old;
    }
    public Optional<ControlLease> acquire(LivingEntity controller, Entity vehicle) {
        thread();
        if (vehicle == null || !nativeAccess.supports(vehicle) || NativeFlightAdapter.blocksManualAcquire(vehicle) || !validPilot(controller, vehicle)) return Optional.empty();
        NativeFlightAdapter.beginManual(vehicle);
        Session s = session(vehicle); if (!validate(s)) return Optional.empty();
        ControlLease existing = s.authority.lease();
        if (existing != null && expired(s, System.nanoTime(), vehicle.level().getGameTime())) {
            release(existing, ReleaseReason.REQUESTED);
            existing = s.authority.lease();
        }
        if (existing != null) return existing.controllerId().equals(controller.getUUID()) ? Optional.of(existing) : Optional.empty();
        // Ownership changes start from actual attitude; retained throttle/modes survive release.
        s.state = zeroRates(nativeAccess.captureAttitude(vehicle, s.state));
        ControlLease lease = s.authority.acquire(controller.getUUID(), epoch(), System.nanoTime());
        s.receiptTick = vehicle.level().getGameTime();
        refresh(s, ControlStatus.OK); publish(s, true);
        return Optional.of(lease);
    }
    public ControlStatus submit(LivingEntity controller, ControlLease lease, PilotCommand command) {
        thread();
        if (controller == null || lease == null) return ControlStatus.INVALID_LEASE;
        Session s = sessions.get(lease.vehicleId());
        if (s == null) return ControlStatus.INVALID_LEASE;
        if (!validate(s)) return ControlStatus.BUSY;
        if (!validPilot(controller, s.vehicle)) return ControlStatus.NOT_PILOT;
        long now = System.nanoTime();
        // Dropped frames cannot be acknowledged by a later frame in the same epoch.
        // End that epoch before accepting input after a timeout or an overflow.
        if (s.authority.matches(controller.getUUID(), lease) && s.authority.timedOut(now)) {
            release(lease, ReleaseReason.REQUESTED);
            return ControlStatus.TIMEOUT;
        }
        ControlStatus result = s.authority.submit(controller.getUUID(), lease, command, now);
        if (result == ControlStatus.BUSY) release(lease, ReleaseReason.REQUESTED);
        if (result == ControlStatus.OK) s.receiptTick = s.vehicle.level().getGameTime();
        return result;
    }
    public Optional<FlightTelemetry> read(Entity vehicle) {
        thread(); Session s = vehicle == null ? null : sessions.get(vehicle.getUUID());
        return s != null && s.vehicle == vehicle && s.level == vehicle.level() && validate(s) ? Optional.of(s.telemetry) : Optional.empty();
    }
    public Optional<FlightSnapshot> snapshot(Entity vehicle) {
        thread(); Session s = vehicle == null ? null : sessions.get(vehicle.getUUID());
        if (s == null || s.vehicle != vehicle || s.level != vehicle.level()) return Optional.empty();
        return validate(s) ? Optional.of(snapshot(s)) : Optional.empty();
    }
    public boolean owns(LivingEntity controller, Entity vehicle) {
        thread(); Session s = sessions.get(vehicle.getUUID());
        if (s == null || s.vehicle != vehicle) return false;
        if (!validate(s)) return false;
        ControlLease lease = s.authority.lease();
        return lease != null && controller != null && lease.controllerId().equals(controller.getUUID()) && validPilot(controller, vehicle);
    }
    public void release(ControlLease lease, ReleaseReason reason) {
        thread(); if (lease == null) return; Session s = sessions.get(lease.vehicleId());
        if (s != null && NativeFlightAdapter.hasNativeAuthority(s.vehicle)) { stopForNative(s); return; }
        if (s != null && s.authority.release(lease)) {
            s.state = zeroRates(s.state); refresh(s, ControlStatus.RELEASED);
            nativeAccess.savePersistent(s.vehicle, s.state); publish(s, true);
        }
    }
    public boolean manages(Entity vehicle) {
        thread();
        if (!live(vehicle) || !nativeAccess.supports(vehicle)) return false;
        if (NativeFlightAdapter.hasNativeAuthority(vehicle)) {
            Session s = sessions.get(vehicle.getUUID());
            if (s != null) stopForNative(s);
            return false;
        }
        Session s = sessions.get(vehicle.getUUID());
        if (s != null) return validate(s) && s.vehicle == vehicle && s.level == vehicle.level();
        LivingEntity pilot = nativeAccess.pilot(vehicle);
        if (pilot instanceof ServerPlayer && validPilot(pilot, vehicle)) { session(vehicle); return true; }
        // Loaded extension state must continue engine rundown even with an empty seat.
        if (vehicle.getPersistentData().contains(NativeFlightAdapter.PERSISTENT_KEY)) { session(vehicle); return true; }
        return false;
    }
    public void beforePhysics(Entity vehicle) {
        thread(); if (!manages(vehicle)) return; Session s = session(vehicle); long tick = vehicle.level().getGameTime();
        if (s.stepped == tick) return;
        if (!validate(s)) return;
        s.stepped = tick;
        FlightEnvironment environment = nativeAccess.sample(vehicle, tick);
        long now = System.nanoTime();
        boolean timeout = expired(s, now, tick);
        if (timeout) release(s.authority.lease(), ReleaseReason.REQUESTED);
        PilotCommand input = timeout ? s.authority.neutralize() : s.authority.consume(now);
        s.output = FlightControlModel.step(nativeAccess.profile(vehicle), s.state, input, environment);
        s.authority.markApplied();
        s.state = s.output.state();
        ControlStatus status = timeout && s.output.status() == ControlStatus.OK ? ControlStatus.TIMEOUT : s.output.status();
        updateTelemetry(s, environment, status, s.output.brake(), s.output.secondaryBrake(), s.output.boost());
        nativeAccess.apply(vehicle, s.output);
    }
    private static boolean expired(Session s, long now, long tick) {
        return s.authority.timedOut(now) || (s.authority.lease() != null && tick - s.receiptTick >= 10);
    }
    public void afterPhysics(Entity vehicle) {
        thread(); Session s = sessions.get(vehicle.getUUID()); long tick = vehicle.level().getGameTime();
        if (s == null || s.vehicle != vehicle || s.stepped != tick || s.captured == tick || !validate(s)) return;
        s.captured = tick; s.state = nativeAccess.captureAttitude(vehicle, s.state);
        FlightEnvironment environment = nativeAccess.sample(vehicle, tick);
        updateTelemetry(s, environment, s.telemetry.status(), s.telemetry.brake(), s.telemetry.secondaryBrake(), s.telemetry.boost());
        nativeAccess.savePersistent(vehicle, s.state); publish(s, false);
    }
    /** Native ownership is terminal for this managed epoch, including already-released sessions. */
    private void stopForNative(Session s) {
        if (sessions.get(s.vehicle.getUUID()) != s) return;
        ControlLease lease = s.authority.lease();
        if (lease != null) s.authority.release(lease);
        s.authority.neutralize();
        s.state = zeroRates(nativeAccess.captureAttitude(s.vehicle, s.state));
        nativeAccess.savePersistent(s.vehicle, s.state);
        refresh(s, ControlStatus.BUSY);
        // Do not call release(): RELEASED would precede BUSY in this same tick.
        publish(s, true);
        sessions.remove(s.vehicle.getUUID(), s);
        FlightNativeHooks.clear(s.vehicle);
    }
    private boolean validate(Session s) {
        if (sessions.get(s.vehicle.getUUID()) != s) return false;
        if (NativeFlightAdapter.hasNativeAuthority(s.vehicle)) { stopForNative(s); return false; }
        ControlLease lease = s.authority.lease(); if (lease == null) return true;
        Entity controller = s.level.getEntity(lease.controllerId());
        if (!(controller instanceof LivingEntity living) || !validPilot(living, s.vehicle))
            release(lease, controller == null || !controller.isAlive() ? ReleaseReason.DEATH : controller.level() != s.vehicle.level() ? ReleaseReason.DIMENSION_CHANGE : ReleaseReason.SEAT_CHANGE);
        return true;
    }
    public void validateLeases() { thread(); for (Session s : new ArrayList<>(sessions.values())) validate(s); }
    public void releaseController(UUID controllerId, ReleaseReason reason) {
        thread(); for (Session s : new ArrayList<>(sessions.values())) {
            ControlLease lease = s.authority.lease(); if (lease != null && lease.controllerId().equals(controllerId)) release(lease, reason);
        }
    }
    public void removeVehicle(UUID vehicleId, ReleaseReason reason) {
        thread(); Session s = sessions.get(vehicleId); if (s == null) return;
        release(s.authority.lease(), reason); nativeAccess.savePersistent(s.vehicle, s.state); sessions.remove(vehicleId); FlightNativeHooks.clear(s.vehicle);
        if (s.vehicle instanceof com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity plane)
            dev.frontier.aviation.api.AircraftArmament.forget(plane);
    }
    public void close() {
        if (closed) return; thread();
        for (UUID id : new ArrayList<>(sessions.keySet())) removeVehicle(id, ReleaseReason.SERVER_STOP);
        closed = true; SERVICES.remove(server); FlightNativeHooks.clear();
    }
    private static FlightState zeroRates(FlightState state) {
        return new FlightState(state.pitch(), state.yaw(), state.roll(), 0, 0, 0, state.targetThrottle(), state.effectivePower(),
            state.enginePhase(), state.phaseTicks(), state.spool(), state.gearDown(), state.hover(), 0);
    }
    private void refresh(Session s, ControlStatus status) {
        updateTelemetry(s, nativeAccess.sample(s.vehicle, s.vehicle.level().getGameTime()), status, false, false, false);
    }
    private void updateTelemetry(Session s, FlightEnvironment environment, ControlStatus status, boolean brake, boolean secondary, boolean boost) {
        s.telemetry = new FlightTelemetry(s.state, environment.speed(), environment.maximumPower(), brake, secondary, boost, status,
            environment.tick(), s.authority.acknowledged());
    }
    private FlightSnapshot snapshot(Session s) {
        ControlLease lease = s.authority.lease();
        return new FlightSnapshot(s.level.dimension().location().toString(), s.vehicle.getId(), s.vehicle.getUUID(),
            lease == null ? null : lease.controllerId(), s.authority.epoch(), s.telemetry);
    }
    private void publish(Session s, boolean forced) {
        FlightSnapshot next = snapshot(s); long tick = s.vehicle.level().getGameTime();
        boolean transition = s.sent == null || !Objects.equals(next.controllerId(), s.sent.controllerId()) || next.epoch() != s.sent.epoch()
            || next.telemetry().status() != s.sent.telemetry().status()
            || next.telemetry().state().enginePhase() != s.sent.telemetry().state().enginePhase()
            || next.telemetry().state().gearDown() != s.sent.telemetry().state().gearDown()
            || next.telemetry().state().hover() != s.sent.telemetry().state().hover()
            || next.telemetry().brake() != s.sent.telemetry().brake() || next.telemetry().secondaryBrake() != s.sent.telemetry().secondaryBrake()
            || next.telemetry().boost() != s.sent.telemetry().boost();
        if (!forced && !transition && tick - s.published < 2) return;
        s.sent = next; s.published = tick; FlightNetwork.broadcastSnapshot(s.vehicle, next);
    }
}
