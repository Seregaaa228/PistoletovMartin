package dev.frontier.aviation.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.Tom6Entity;
import dev.frontier.aviation.control.nativebridge.NativeFlightAdapter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = Tom6Entity.class, remap = false)
public abstract class FlightTomEngineSound {
    @Inject(method = "engineRunning()Z", at = @At("RETURN"), cancellable = true, require = 1)
    private void flight$ignitionSound(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(NativeFlightAdapter.INSTANCE.audible((Tom6Entity) (Object) this, cir.getReturnValue()));
    }
}
