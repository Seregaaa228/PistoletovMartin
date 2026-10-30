package dev.frontier.aviation.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import dev.frontier.aviation.control.nativebridge.FlightNativeHooks;
import dev.frontier.aviation.control.nativebridge.NativeFlightAdapter;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = VehicleEntity.class, remap = false)
public abstract class FlightNativePersistence {
    @Inject(method = "m_7378_(Lnet/minecraft/nbt/CompoundTag;)V", at = @At("TAIL"), require = 1)
    private void flight$load(CompoundTag tag, CallbackInfo ci) {
        VehicleEntity vehicle = (VehicleEntity) (Object) this;
        if (NativeFlightAdapter.INSTANCE.supports(vehicle)) NativeFlightAdapter.INSTANCE.loaded(vehicle, tag);
    }
    @Inject(method = "engineRunning()Z", at = @At("RETURN"), cancellable = true, require = 1)
    private void flight$engineSound(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(NativeFlightAdapter.INSTANCE.audible((VehicleEntity) (Object) this, cir.getReturnValue()));
    }
}
