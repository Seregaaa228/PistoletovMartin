package dev.frontier.aviation.mixin;

import dev.sbw.deepstrike.vehicle.Su24mEntity;
import dev.frontier.aviation.control.nativebridge.NativeFlightAdapter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Su24 writes commandedThrottle after super.tick has already consumed power. */
@Mixin(value = Su24mEntity.class, remap = false)
public abstract class FlightSu24Throttle {
    @Redirect(method = "m_8119_()V", at = @At(value = "INVOKE", target = "Ldev/sbw/deepstrike/vehicle/Su24mEntity;setPower(F)V"), require = 1)
    private void flight$latePower(Su24mEntity vehicle, float power) {
        if (!NativeFlightAdapter.INSTANCE.controls(vehicle)) vehicle.setPower(power);
    }
}
