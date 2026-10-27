package dev.frontier.aviation.mixin;

import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.ShootParameters;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import dev.frontier.aviation.missile.ManualMissileShots;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.UUID;

/** Revalidate the DSM pilot at execution of SBW's deferred named missile shot. */
@Mixin(value = VehicleEntity.class, remap = false)
public abstract class F16MissileQueuedShot {
    @Inject(method = "vehicleShoot$lambda$28$lambda$27(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lnet/minecraft/world/entity/LivingEntity;Ljava/lang/String;Ljava/util/UUID;Lnet/minecraft/world/phys/Vec3;Lcom/atsuishio/superbwarfare/data/gun/GunData;)V", at = @At("HEAD"), remap = false)
    private static void aviation$validateQueuedMissile(VehicleEntity vehicle, LivingEntity pilot,
            String weapon, UUID targetId, Vec3 target, GunData gun, CallbackInfo result) {
        ManualMissileShots.enter(vehicle, pilot, weapon, targetId, target, gun);
    }

    @Redirect(method = "vehicleShoot$lambda$28$lambda$27", at = @At(value = "INVOKE",
        target = "Lcom/atsuishio/superbwarfare/data/gun/GunData;shoot(Lcom/atsuishio/superbwarfare/data/gun/ShootParameters;)V"), require = 1)
    private static void aviation$nativeShotScope(GunData gun, ShootParameters parameters) {
        ManualMissileShots.shootNative(gun, parameters);
    }

    @Inject(method = "vehicleShoot$lambda$28$lambda$27", at = @At("RETURN"), require = 2)
    private static void aviation$endShotScope(VehicleEntity vehicle, LivingEntity pilot,
            String weapon, UUID targetId, Vec3 target, GunData gun, CallbackInfo result) {
        ManualMissileShots.leave();
    }
}
