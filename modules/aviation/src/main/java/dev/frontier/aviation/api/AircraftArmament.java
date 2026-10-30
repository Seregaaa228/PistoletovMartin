package dev.frontier.aviation.api;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import dev.frontier.aviation.aircraft.AircraftCatalog;
import dev.frontier.aviation.control.ControlLease;
import dev.frontier.aviation.missile.F16MissileLauncher;
import dev.frontier.aviation.weapons.WeaponKind;
import dev.frontier.aviation.weapons.WeaponMount;
import dev.sbw.deepstrike.vehicle.Su24mEntity;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

/** Server-thread armament port. Native SBW/DSM owns rounds, heat, reload and damage. */
public final class AircraftArmament {
    public enum Result { QUEUED, INVALID_LEASE, UNSUPPORTED_WEAPON, UNSUPPORTED_TARGET, WRONG_WEAPON, INVALID_TARGET, NOT_ALIGNED, NOT_READY }
    public record State(String slotId, String nativeGun, WeaponKind kind, int rounds, int heat,
                        boolean selected, boolean ready) {}
    private static final Map<VehicleEntity, Map<String, Long>> INTENTS = new WeakHashMap<>();
    private static final double CANNON_ALIGNMENT = Math.cos(Math.toRadians(1));

    private AircraftArmament() {}

    public static boolean select(LivingEntity pilot, VehicleEntity aircraft, ControlLease lease, String slotId) {
        if (!AircraftController.owns(pilot, aircraft, lease) || !operable(pilot, aircraft)) return false;
        var mount = mount(aircraft, slotId).orElse(null);
        var seat = aircraft.getSeat(0);
        if (mount == null || seat == null || seat.weapons() == null) return false;
        int index = seat.weapons().indexOf(mount.nativeGun());
        if (index < 0) return false;
        aircraft.setWeaponIndex(0, index);
        return mount.nativeGun().equals(aircraft.getGunName(0));
    }

    public static Optional<State> inspect(LivingEntity pilot, VehicleEntity aircraft, ControlLease lease, String slotId) {
        if (!AircraftController.owns(pilot, aircraft, lease) || !operable(pilot, aircraft)) return Optional.empty();
        return mount(aircraft, slotId).flatMap(mount -> {
            var gun = aircraft.getGunData(mount.nativeGun());
            if (gun == null) return Optional.empty();
            boolean selected = mount.nativeGun().equals(aircraft.getGunName(0));
            boolean ready = selected && gun.canShoot(aircraft.getAmmoSupplier()) && cadence(aircraft, mount);
            return Optional.of(new State(mount.id(), mount.nativeGun(), mount.kind(), gun.ammo.get(),
                aircraft.getWeaponHeat(mount.nativeGun()), selected, ready));
        });
    }

    /**
     * One intent, revalidated again by native deferred shooting. QUEUED is not a
     * hit confirmation. For a fixed cannon the target is an alignment check;
     * steer the aircraft first. This API never swivels its barrel toward a point.
     */
    public static Result fire(LivingEntity pilot, VehicleEntity aircraft, ControlLease lease,
                              String slotId, int count, Vec3 target) {
        return fire(pilot, aircraft, lease, slotId, count, target, null);
    }

    /** Native entity-seeking port for future air-to-air loadouts; ground-only AGM data stays ground-only. */
    public static Result fireTracked(LivingEntity pilot, VehicleEntity aircraft, ControlLease lease,
                                    String slotId, int count, Entity target) {
        if (!AircraftController.owns(pilot, aircraft, lease)) return Result.INVALID_LEASE;
        if (target == null || target.level() != aircraft.level() || !target.isAlive()
                || target.getRootVehicle() == aircraft.getRootVehicle()) return Result.INVALID_TARGET;
        var weapon = mount(aircraft, slotId).orElse(null);
        var gun = weapon == null ? null : aircraft.getGunData(weapon.nativeGun());
        if (gun == null) return Result.UNSUPPORTED_WEAPON;
        if (weapon.kind() != WeaponKind.GUIDED_MISSILE || gun.get(GunProp.SEEK_WEAPON_INFO).getOnlyLockBlock())
            return Result.UNSUPPORTED_TARGET;
        return fire(pilot, aircraft, lease, slotId, count, target.getBoundingBox().getCenter(), target.getUUID());
    }

    private static Result fire(LivingEntity pilot, VehicleEntity aircraft, ControlLease lease,
                               String slotId, int count, Vec3 target, java.util.UUID trackedEntity) {
        if (!AircraftController.owns(pilot, aircraft, lease) || !operable(pilot, aircraft)) return Result.INVALID_LEASE;
        var mount = mount(aircraft, slotId).orElse(null);
        if (mount == null) return Result.UNSUPPORTED_WEAPON;
        if (!mount.nativeGun().equals(aircraft.getGunName(0))) return Result.WRONG_WEAPON;
        if (count < 1 || count > mount.maximumBurst()) return Result.NOT_READY;
        var gun = aircraft.getGunData(mount.nativeGun());
        if (gun == null || gun.ammo.get() < count || !gun.canShoot(aircraft.getAmmoSupplier())
                || !cadence(aircraft, mount)) return Result.NOT_READY;
        if (target != null && !finite(target)) return Result.INVALID_TARGET;

        if (mount.kind() == WeaponKind.GUIDED_MISSILE) {
            if (!(aircraft instanceof F16MissileLauncher launcher)) return Result.UNSUPPORTED_WEAPON;
            if (!launcher.aviation$fireMissiles(pilot, count, target, trackedEntity)) return Result.NOT_READY;
        } else if (mount.kind() == WeaponKind.CANNON) {
            if (target != null && aircraft.getShootPos(mount.nativeGun(), 1).distanceToSqr(target)
                    > mount.maximumRange() * mount.maximumRange()) return Result.INVALID_TARGET;
            if (target != null && !aligned(aircraft, mount, target)) return Result.NOT_ALIGNED;
            aircraft.vehicleShoot(pilot, mount.nativeGun(), null, target);
        } else {
            return Result.UNSUPPORTED_WEAPON;
        }
        INTENTS.computeIfAbsent(aircraft, ignored -> new HashMap<>()).put(mount.id(), aircraft.level().getGameTime());
        return Result.QUEUED;
    }

    public static void forget(VehicleEntity aircraft) {
        INTENTS.remove(aircraft);
        dev.frontier.aviation.missile.ManualMissileShots.forget(aircraft);
    }

    private static Optional<WeaponMount> mount(VehicleEntity aircraft, String slotId) {
        var id = ForgeRegistries.ENTITY_TYPES.getKey(aircraft.getType());
        return id == null ? Optional.empty() : AircraftCatalog.find(id.toString())
            .flatMap(definition -> definition.weapons().find(slotId));
    }

    private static boolean operable(LivingEntity pilot, VehicleEntity aircraft) {
        // DSM's player permission check is retained for real network callers.
        // NPCs reach this helper only after exact server-owned lease validation.
        return aircraft.getHealth() > 0 && !aircraft.isWreck()
            && (!(pilot instanceof ServerPlayer player) || !(aircraft instanceof Su24mEntity plane)
                || plane.canOperate(player, true));
    }

    private static boolean cadence(VehicleEntity aircraft, WeaponMount mount) {
        var previous = INTENTS.get(aircraft);
        Long tick = previous == null ? null : previous.get(mount.id());
        long now = aircraft.level().getGameTime();
        return tick == null || now >= tick && now - tick >= mount.minimumIntervalTicks();
    }

    private static boolean aligned(VehicleEntity aircraft, WeaponMount mount, Vec3 target) {
        Vec3 offset = target.subtract(aircraft.getShootPos(mount.nativeGun(), 1));
        Vec3 direction = aircraft.getShootVec(mount.nativeGun(), 1);
        return finite(direction) && offset.lengthSqr() > 1e-12 && direction.lengthSqr() > 1e-12
            && offset.normalize().dot(direction.normalize()) >= CANNON_ALIGNMENT;
    }

    private static boolean finite(Vec3 vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }
}
