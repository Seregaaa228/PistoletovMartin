package dev.frontier.aviation.mixin;

import dev.frontier.aviation.AircraftProfile;
import dev.sbw.deepstrike.vehicle.Su24mEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** DSM also scans Su24 subclasses/types globally for exhaust lighting. */
@Mixin(targets = "dev.sbw.deepstrike.client.Su24mExhaust", remap = false)
public abstract class SkipNativeAircraftLights {
    @Inject(method = "running", at = @At("HEAD"), cancellable = true, remap = false)
    private static void aviation$plainEffects(Su24mEntity plane, CallbackInfoReturnable<Boolean> result) {
        if (AircraftProfile.of(plane) != null) result.setReturnValue(false);
    }
}
