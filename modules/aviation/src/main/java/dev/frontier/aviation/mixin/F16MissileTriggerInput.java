package dev.frontier.aviation.mixin;

import dev.frontier.aviation.client.F16MissileTriggerClient;
import com.atsuishio.superbwarfare.event.ClientEventHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The gesture owns only the selected F16 AGM; other native weapons retain their input path. */
@Mixin(targets = "com.atsuishio.superbwarfare.event.ClientEventHandler", remap = false)
public abstract class F16MissileTriggerInput {
    @Inject(method = "handleVehicleGunShoot()V", at = @At("HEAD"), cancellable = true)
    private void aviation$missileGesture(CallbackInfo ci) {
        if (F16MissileTriggerClient.handlesNative()) {
            // A release in a menu may never reach SBW's mouse handler. Do
            // not carry its old hold flag into the next selected weapon.
            ClientEventHandler.holdFireVehicle = false;
            ci.cancel();
        }
    }
}
