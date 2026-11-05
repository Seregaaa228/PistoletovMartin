package dev.frontier.aviation.mixin;

import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import dev.frontier.aviation.ordnance.StoreLoadout;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * A vehicle reloads its guns with itself as the ammo supplier. On an owned jet a missile
 * reload may not add more rounds than its free store stations take (StoreLoadout.reloadLimit).
 */
@Mixin(value = GunData.class, remap = false)
public abstract class StationMagazineLimit {
    @Redirect(method = "reloadAmmo(Lnet/minecraft/world/entity/Entity;Z)V", at = @At(value = "INVOKE", target =
        "Lcom/atsuishio/superbwarfare/data/gun/GunData;countBackupAmmo(Lnet/minecraft/world/entity/Entity;)I"), require = 1, allow = 1)
    private int aviation$stationLimit(GunData gun, Entity supplier) {
        int available = gun.countBackupAmmo(supplier);
        return supplier instanceof VehicleEntity plane ? StoreLoadout.reloadLimit(plane, gun, available) : available;
    }
}
