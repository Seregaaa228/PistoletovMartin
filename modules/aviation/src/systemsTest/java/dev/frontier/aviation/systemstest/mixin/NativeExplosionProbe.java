package dev.frontier.aviation.systemstest.mixin;

import com.atsuishio.superbwarfare.data.vehicle.subdata.DestroyInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import dev.frontier.aviation.systemstest.ExplosionObservations;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = VehicleEntity.class, remap = false)
public abstract class NativeExplosionProbe {
    @Inject(method = "vehicleExplosion(Lcom/atsuishio/superbwarfare/data/vehicle/subdata/DestroyInfo;)V", at = @At("HEAD"), require = 1)
    private void systems$observeExplosion(DestroyInfo info, CallbackInfo ci) {
        ExplosionObservations.observed((VehicleEntity) (Object) this);
    }
}
