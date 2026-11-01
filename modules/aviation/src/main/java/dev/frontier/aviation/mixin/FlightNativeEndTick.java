package dev.frontier.aviation.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import dev.frontier.aviation.control.nativebridge.FlightNativeHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Capture the final native attitude after terrain and collision updates. */
@Mixin(value = VehicleEntity.class, remap = false)
public abstract class FlightNativeEndTick {
    @Inject(method = "m_6075_()V", at = @At("TAIL"), require = 1)
    private void flight$endTick(CallbackInfo ci) {
        FlightNativeHooks.afterPhysics((VehicleEntity) (Object) this);
    }
}
