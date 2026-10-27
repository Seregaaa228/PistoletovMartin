package dev.frontier.aviation.mixin;

import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import dev.frontier.aviation.AircraftProfile;
import dev.sbw.deepstrike.vehicle.Su24mEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.UUID;

/** Revalidate the DSM pilot at execution of SBW's deferred named missile shot. */
@Mixin(value = VehicleEntity.class, remap = false)
public abstract class F16MissileQueuedShot {
    @Inject(method = "vehicleShoot$lambda$28$lambda$27(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lnet/minecraft/world/entity/LivingEntity;Ljava/lang/String;Ljava/util/UUID;Lnet/minecraft/world/phys/Vec3;Lcom/atsuishio/superbwarfare/data/gun/GunData;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void aviation$validateQueuedMissile(VehicleEntity vehicle, LivingEntity pilot,
            String weapon, UUID targetId, Vec3 target, GunData gun, CallbackInfo result) {
        if (AircraftProfile.of(vehicle) != AircraftProfile.F16 || !"Missile".equals(weapon)) return;
        if (!(vehicle instanceof Su24mEntity plane) || !(pilot instanceof ServerPlayer player)
                || !plane.isAlive() || !player.isAlive() || player.getVehicle() != plane
                || plane.getSeatIndex(player) != 0 || !plane.canOperate(player, true)
                || !"Missile".equals(plane.getGunName(0)) || gun != plane.getGunData("Missile")
                || !gun.canShoot(plane.getAmmoSupplier())) result.cancel();
    }
}
