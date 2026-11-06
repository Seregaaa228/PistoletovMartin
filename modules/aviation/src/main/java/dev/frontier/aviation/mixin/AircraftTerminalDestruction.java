package dev.frontier.aviation.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Complete a new death or a loaded wreck before native ground-wait/overkill processing. */
@Mixin(value = VehicleEntity.class, remap = false)
public abstract class AircraftTerminalDestruction {
    @Inject(method = "m_6075_()V", at = {
        @At("HEAD"),
        @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;destroy()V", shift = At.Shift.AFTER)
    }, cancellable = true, require = 2)
    private void aviation$discardOwnedAircraft(CallbackInfo ci) {
        if (dev.frontier.aviation.destruction.AircraftTerminalDestruction
                .completeIfTerminal((VehicleEntity) (Object) this)) ci.cancel();
    }
}
