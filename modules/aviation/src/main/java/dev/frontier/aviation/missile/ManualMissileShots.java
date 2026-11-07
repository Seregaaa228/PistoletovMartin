package dev.frontier.aviation.missile;

import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.data.gun.ShootParameters;
import com.atsuishio.superbwarfare.entity.projectile.Agm65Entity;
import com.atsuishio.superbwarfare.entity.projectile.WireGuideMissileEntity;
import com.atsuishio.superbwarfare.entity.projectile.MissileProjectile;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import dev.frontier.aviation.LightAviation;
import dev.frontier.aviation.aircraft.AircraftCatalog;
import dev.frontier.aviation.api.AircraftController;
import dev.frontier.aviation.control.ControlLease;
import dev.frontier.aviation.ordnance.StoreLoadout;
import dev.frontier.aviation.ordnance.StoreStations;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import dev.sbw.deepstrike.vehicle.Su24mEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/** Bounded server tickets bind native deferred shots to the epoch which submitted them. */
@Mod.EventBusSubscriber(modid = LightAviation.ID)
public final class ManualMissileShots {
    public static final String DIRECT_AIM = "frontierAircraftDirectAim";
    public static final String LOCKED_WIRE = "frontierAircraftLockedWire";
    /** Proximity fuse radius (blocks) of an air-to-air missile launched by our aircraft. */
    public static final String AAM_FUSE = "frontierAircraftAamFuse";
    private record Ticket(UUID vehicle, UUID pilot, long epoch, String gun, Vec3 target, Vec3 ray, UUID trackedEntity, long tick, int station) {}
    private static final class Frame {
        final Ticket ticket;
        final boolean denied;
        ShootParameters parameters;
        Frame(Ticket ticket, boolean denied) { this.ticket = ticket; this.denied = denied; }
    }
    private static final Map<VehicleEntity, ArrayDeque<Ticket>> PENDING = new WeakHashMap<>();
    private static final ThreadLocal<ArrayDeque<Frame>> FRAMES = ThreadLocal.withInitial(ArrayDeque::new);
    private ManualMissileShots() {}

    /** Visual kind (StoreStations, 2..5) a native missile gun shows on its station, or 0. */
    private static int kind(String gun) {
        int index = StoreStations.GUNS.indexOf(gun);
        return index < 0 ? 0 : StoreStations.AGM_65 + index;
    }

    public static boolean reserve(VehicleEntity plane, LivingEntity pilot, long epoch, String gun,
                                  int count, Vec3 target, Vec3 ray, UUID trackedEntity) {
        if (!(plane.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) return false;
        if (pilot == null || !dev.frontier.aviation.weapons.WeaponAimResolver.finite(ray)
                || ray.lengthSqr() < 1e-12 || target != null
                    && !dev.frontier.aviation.weapons.WeaponAimResolver.finite(target)) return false;
        long now = plane.level().getGameTime();
        var tickets = PENDING.computeIfAbsent(plane, ignored -> new ArrayDeque<>());
        while (!tickets.isEmpty() && now - tickets.peekFirst().tick() > 4) tickets.removeFirst();
        if (count < 1 || count > 2 || tickets.size() + count > 16) return false;
        var layout = StoreLoadout.layout(plane).orElse(null);
        int bits = layout != null ? StoreLoadout.visualBits((Su24mEntity) plane) : 0;
        int kind = kind(gun);
        // Each shot leaves from a station showing that missile, in the layout's firing order.
        int[] order = layout == null || kind == 0 ? new int[0] : layout.fireOrder(
            kind == StoreStations.LIGHT_AAM ? StoreStations.Store.LIGHT_AIR_TO_AIR : StoreStations.Store.UNDERWING);
        int[] stations = {-1, -1};
        int matched = 0;
        for (int station : order) if (((bits >>> (station * 3)) & 7) == kind && matched < count)
            stations[matched++] = station;
        for (int shot = 0; shot < count; shot++) tickets.addLast(
            new Ticket(plane.getUUID(), pilot.getUUID(), epoch, gun, target, ray, trackedEntity, now, stations[shot]));
        return true;
    }

    /** Every callback pushes a frame, so a nested non-aircraft shot cannot inherit its caller's marker. */
    public static void enter(VehicleEntity plane, LivingEntity pilot, String weapon, UUID targetId, Vec3 target, GunData gun) {
        if (!(plane.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) {
            FRAMES.get().push(new Frame(null, true));
            return;
        }
        var id = ForgeRegistries.ENTITY_TYPES.getKey(plane.getType());
        boolean guided = id != null && AircraftCatalog.find(id.toString())
            .flatMap(definition -> definition.weapons().nativeGun(weapon))
            .filter(mount -> mount.kind().guided()).isPresent();
        Ticket accepted = null;
        boolean denied = false;
        if (guided) {
            var tickets = PENDING.get(plane);
            Ticket ticket = tickets == null ? null : tickets.peekFirst();
            // An unrelated/nested native invocation must not consume a pilot's
            // reservation. Expired matching requests still fail validation.
            if (ticket != null && pilot != null && ticket.pilot().equals(pilot.getUUID())
                    && ticket.gun().equals(weapon) && Objects.equals(ticket.target(), target)
                    && Objects.equals(ticket.trackedEntity(), targetId)) tickets.removeFirst();
            else ticket = null;
            if (tickets != null && tickets.isEmpty()) PENDING.remove(plane);
            denied = !(plane.level() instanceof ServerLevel) || ticket == null || pilot == null
                    || !ticket.pilot().equals(pilot.getUUID()) || !ticket.gun().equals(weapon)
                    || !Objects.equals(ticket.target(), target) || !Objects.equals(ticket.trackedEntity(), targetId)
                    || plane.level().getGameTime() < ticket.tick()
                    || plane.level().getGameTime() - ticket.tick() > 4
                    || plane.getHealth() <= 0 || plane.isWreck() || !weapon.equals(plane.getGunName(0))
                    || gun != plane.getGunData(weapon)
                    || targetId != null && (level.getEntity(targetId) == null
                        || !level.getEntity(targetId).isAlive()
                        || level.getEntity(targetId).getRootVehicle() == plane.getRootVehicle())
                    || !AircraftController.owns(pilot, plane, new ControlLease(ticket.vehicle(), ticket.pilot(), ticket.epoch()))
                    || pilot instanceof ServerPlayer player && plane instanceof Su24mEntity su24 && !su24.canOperate(player, true);
            if (!denied) accepted = ticket;
        }
        FRAMES.get().push(new Frame(accepted, denied));
    }

    public static void leave() {
        var frames = FRAMES.get();
        if (!frames.isEmpty()) frames.pop();
        if (frames.isEmpty()) FRAMES.remove();
    }

    public static void shootNative(GunData gun, ShootParameters parameters) {
        var frame = FRAMES.get().peek();
        if (frame != null && frame.denied) return;
        if (frame != null) frame.parameters = parameters;
        try {
            gun.shoot(parameters);
        } catch (RuntimeException | Error failure) {
            leave(); // A throwing callback has no normal RETURN injection.
            throw failure;
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void projectile(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel)) return;
        var frame = FRAMES.get().peek();
        if (frame == null || frame.ticket == null || frame.parameters == null) return;
        if (event.getEntity() instanceof MissileProjectile aam && aam.getOwner() != null
                && (aam.getOwner().getUUID().equals(frame.ticket.pilot()) || aam.getOwner().getUUID().equals(frame.ticket.vehicle()))) {
            // Entity-seeking air-to-air rounds get a proximity fuse (AircraftMissileFuse).
            if (frame.ticket.gun().equals("AAMissile")) aam.getPersistentData().putFloat(AAM_FUSE, 4.0F);
            else if (frame.ticket.gun().equals("LightAAMissile")) aam.getPersistentData().putFloat(AAM_FUSE, 3.0F);
        }
        if (event.getEntity() instanceof MissileProjectile projectile && frame.ticket.station() >= 0) {
            var owner = projectile.getOwner();
            var vehicle = ((ServerLevel) event.getLevel()).getEntity(frame.ticket.vehicle());
            if (vehicle instanceof VehicleEntity plane && owner != null
                    && (owner.getUUID().equals(frame.ticket.pilot()) || owner.getUUID().equals(frame.ticket.vehicle()))) {
                var layout = StoreLoadout.layout(plane).orElse(null);
                int station = frame.ticket.station(), kind = kind(frame.ticket.gun());
                if (layout != null && station < layout.size() && kind != 0) {
                    // Leave from the hung missile's own origin (its exhaust), where it was drawn.
                    double[] p = StoreStations.launch(layout.stations().get(station), StoreStations.model(kind, -1));
                    var point = plane.getTransformFromString("Vehicle").transformPosition(new org.joml.Vector3d(p[0], p[1], p[2]));
                    projectile.setPos(point.x, point.y, point.z);
                }
            }
        }
        if (event.getEntity() instanceof WireGuideMissileEntity wire
                && frame.ticket.gun().equals("MediumAGMissile") && frame.ticket.target() != null) {
            var owner = wire.getOwner();
            if (owner == null || (!owner.getUUID().equals(frame.ticket.pilot())
                    && !owner.getUUID().equals(frame.ticket.vehicle()))) return;
            var tag = wire.getPersistentData();
            tag.putBoolean(LOCKED_WIRE, true);
            tag.putDouble("frontierTargetX", frame.ticket.target().x);
            tag.putDouble("frontierTargetY", frame.ticket.target().y);
            tag.putDouble("frontierTargetZ", frame.ticket.target().z);
            if (frame.ticket.trackedEntity() != null) tag.putUUID("frontierTargetEntity", frame.ticket.trackedEntity());
            MissileAimSolution.resolve(wire.position(), frame.ticket.target()).ifPresent(solution -> {
                double speed = Math.max(frame.parameters.data.get(GunProp.VELOCITY), wire.getDeltaMovement().length());
                if (Double.isFinite(speed) && speed > 0)
                    wire.shoot(solution.direction().x, solution.direction().y, solution.direction().z,
                        (float) speed, (float) frame.parameters.spread);
            });
            return;
        }
        if (!(event.getEntity() instanceof Agm65Entity missile)) return;
        var owner = missile.getOwner();
        if (owner == null || (!owner.getUUID().equals(frame.ticket.pilot())
                && !owner.getUUID().equals(frame.ticket.vehicle()))) return;
        Vec3 direction = frame.ticket.target() == null ? frame.ticket.ray()
            : MissileAimSolution.resolve(missile.position(), frame.ticket.target()).map(MissileAimSolution::direction).orElse(null);
        double base = frame.parameters.data.get(GunProp.VELOCITY);
        double speed = Math.max(base, missile.getDeltaMovement().length());
        if (direction == null || !Double.isFinite(speed) || speed <= 0) return;
        // Pinned SBW multiplies projectile speed by root-vehicle speed for
        // AddShooterDeltaMovement; it does not add a velocity vector. Retain its
        // moving launch speed, with native base speed as a stationary floor.
        missile.shoot(direction.x, direction.y, direction.z, (float) speed, (float) frame.parameters.spread);
        missile.getPersistentData().putBoolean(DIRECT_AIM, true);
    }

    public static void forget(VehicleEntity plane) { PENDING.remove(plane); }

    @SubscribeEvent public static void endTick(TickEvent.ServerTickEvent event) {
        // Native preparation can fail before GunData.shoot is entered. Never
        // let an exceptional callback scope survive into a later server tick.
        if (event.phase == TickEvent.Phase.END) FRAMES.remove();
    }

    @SubscribeEvent public static void stopping(ServerStoppingEvent event) {
        PENDING.clear();
        FRAMES.remove();
    }
}
