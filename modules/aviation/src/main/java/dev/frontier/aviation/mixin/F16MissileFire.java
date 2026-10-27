package dev.frontier.aviation.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import dev.frontier.aviation.aircraft.AircraftCatalog;
import dev.frontier.aviation.missile.MissileTargetRules;
import dev.frontier.aviation.missile.F16MissileLauncher;
import dev.frontier.aviation.missile.ManualMissileShots;
import dev.frontier.aviation.weapons.WeaponKind;
import dev.frontier.aviation.weapons.WeaponMount;
import dev.frontier.aviation.weapons.WeaponAimResolver;
import dev.frontier.aviation.weapons.WeaponAimResult;
import dev.sbw.deepstrike.vehicle.Su24mEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.UUID;

/** Catalogued guided mounts use the native shooter with one canonical reticle point. */
@Mixin(value = Su24mEntity.class, remap = false)
public abstract class F16MissileFire extends VehicleEntity implements F16MissileLauncher {
    @Unique private long aviation$lastMissileTick = Long.MIN_VALUE;
    @Unique private final MissileTargetRules.AttemptLimiter aviation$attempts = new MissileTargetRules.AttemptLimiter();

    protected F16MissileFire(EntityType<?> type, Level level) { super(type, level); }

    @Unique private WeaponMount aviation$mountForNativeGun(String nativeGun) {
        var key = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(getType());
        return key == null ? null : AircraftCatalog.find(key.toString())
                .flatMap(definition -> definition.weapons().nativeGun(nativeGun))
                .filter(mount -> mount.kind() == WeaponKind.GUIDED_MISSILE)
                .orElse(null);
    }

    @Unique private boolean aviation$selectedMissile(LivingEntity pilot) {
        WeaponMount missile = aviation$mountForNativeGun(getGunName(0));
        return missile != null && missile.kind() == WeaponKind.GUIDED_MISSILE && pilot != null
                && pilot.isAlive() && isAlive() && pilot.getVehicle() == this
                && getSeatIndex(pilot) == 0
                && missile.nativeGun().equals(getGunName(0));
    }

    @Unique private boolean aviation$trustedOperator(LivingEntity pilot) {
        if (!(level() instanceof ServerLevel serverLevel) || pilot == null) return false;
        if (!dev.frontier.aviation.control.server.FlightControlService
                .get(serverLevel.getServer()).owns(pilot, this)) return false;
        if (pilot instanceof ServerPlayer player) {
            return ((Su24mEntity) (Object) this).canOperate(player, true);
        }
        return pilot.isAlive() && pilot.getVehicle() == this && getSeatIndex(pilot) == 0;
    }

    @Inject(method = "canShoot(Lnet/minecraft/world/entity/LivingEntity;)Z", at = @At("HEAD"), cancellable = true)
    private void aviation$missileCanShoot(LivingEntity pilot, CallbackInfoReturnable<Boolean> result) {
        if (aviation$selectedMissile(pilot)) result.setReturnValue(super.canShoot(pilot));
    }

    @Inject(method = "vehicleShoot(Lnet/minecraft/world/entity/LivingEntity;Ljava/lang/String;Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), cancellable = true)
    private void aviation$missileShoot(LivingEntity pilot, String weapon, Vec3 ignored, CallbackInfo result) {
        WeaponMount missile = aviation$mountForNativeGun(weapon);
        if (missile != null && missile.kind() == WeaponKind.GUIDED_MISSILE
                && missile.nativeGun().equals(weapon)) {
            result.cancel();
            aviation$launch(pilot, ignored);
        }
    }

    @Inject(method = "vehicleShoot(Lnet/minecraft/world/entity/LivingEntity;Ljava/lang/String;Ljava/util/UUID;Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), cancellable = true)
    private void aviation$missileShootTarget(LivingEntity pilot, String weapon, UUID ignoredId, Vec3 ignored, CallbackInfo result) {
        WeaponMount missile = aviation$mountForNativeGun(weapon);
        if (missile != null && missile.kind() == WeaponKind.GUIDED_MISSILE
                && missile.nativeGun().equals(weapon)) {
            result.cancel();
            aviation$launch(pilot, ignored);
        }
    }

    @Inject(method = "vehicleShoot(Lnet/minecraft/world/entity/LivingEntity;Ljava/util/UUID;Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), cancellable = true)
    private void aviation$selectedMissileShoot(LivingEntity pilot, UUID ignoredId, Vec3 ignored, CallbackInfo result) {
        WeaponMount missile = aviation$mountForNativeGun(getGunName(0));
        if (missile != null && missile.kind() == WeaponKind.GUIDED_MISSILE
                && missile.nativeGun().equals(getGunName(0))) {
            result.cancel();
            aviation$launch(pilot, ignored);
        }
    }

    @Unique private void aviation$launch(LivingEntity pilot, Vec3 claimed) {
        aviation$fireMissiles(pilot, 1, claimed);
    }

    @Override
    public boolean aviation$fireMissiles(LivingEntity pilot, int count, Vec3 claimed, UUID trackedEntity) {
        WeaponMount missile = aviation$mountForNativeGun(getGunName(0));
        if (missile == null || missile.kind() != WeaponKind.GUIDED_MISSILE) return false;
        var gun = getGunData(missile.nativeGun());
        if (count < 1 || count > missile.maximumBurst() || level().isClientSide
                || !aviation$selectedMissile(pilot)
                || !aviation$trustedOperator(pilot)
                || isRemoved() || isWreck() || gun == null || gun.ammo.get() < count
                || !super.canShoot(pilot)) return false;
        long now = level().getGameTime();
        if (aviation$lastMissileTick != Long.MIN_VALUE
                && now - aviation$lastMissileTick < missile.minimumIntervalTicks()) return false;
        if (!aviation$attempts.allow(now)) return false;
        Vec3 from = getShootPosForHud(pilot, 1);
        Vec3 ray = getShootDirectionForHud(pilot, 1);
        double range = WeaponAimResolver.range(missile, gun), sector = WeaponAimResolver.sector(gun);
        var aim = claimed == null
            ? WeaponAimResolver.resolve(level(), pilot, from, ray, range)
            : WeaponAimResolver.validateProposal(level(), pilot, from, ray, claimed, range, sector);
        if (trackedEntity != null) {
            if (gun.get(GunProp.SEEK_WEAPON_INFO).getOnlyLockBlock() || !(level() instanceof ServerLevel serverLevel)) return false;
            var victim = serverLevel.getEntity(trackedEntity);
            if (victim == null || !victim.isAlive() || victim.getRootVehicle() == getRootVehicle()) return false;
            Vec3 point = victim.getBoundingBox().getCenter();
            if (!WeaponAimResolver.canResolvePoint(from, point, ray, range, sector)) return false;
            aim = WeaponAimResolver.resolve(level(), pilot, from, point.subtract(from), Math.min(range, from.distanceTo(point) + 1));
            if (aim.isEmpty() || !aim.get().entity().filter(trackedEntity::equals).isPresent()) return false;
        }
        if (claimed != null && aim.isEmpty()) return false;
        if (claimed != null && trackedEntity == null && gun.get(GunProp.SEEK_WEAPON_INFO).getOnlyLockBlock()
                && aim.get().kind() == WeaponAimResult.Kind.SPACE) return false;
        // A clear sky ray is free fire, not a persistent point target to orbit.
        Vec3 target = aim.filter(result -> result.kind() != WeaponAimResult.Kind.SPACE)
            .map(result -> result.point()).orElse(null);
        UUID nativeTarget = trackedEntity != null || gun.get(GunProp.SEEK_WEAPON_INFO).getOnlyLockBlock()
            ? trackedEntity : aim.flatMap(result -> result.entity()).orElse(null);
        var snapshot = dev.frontier.aviation.control.server.FlightControlService.get(getServer()).snapshot(this).orElse(null);
        if (snapshot == null || !ManualMissileShots.reserve(this, pilot, snapshot.epoch(),
                missile.nativeGun(), count, target, ray, nativeTarget)) return false;
        aviation$lastMissileTick = now;
        // Native work runs in submission order in the same tick. Each shot
        // reads the remaining magazine to choose its pylon, revalidates the
        // pilot and consumes its own round in GunData.shoot.
        for (int shot = 0; shot < count; shot++) {
            super.vehicleShoot(pilot, missile.nativeGun(), nativeTarget, target);
        }
        return true;
    }

}
