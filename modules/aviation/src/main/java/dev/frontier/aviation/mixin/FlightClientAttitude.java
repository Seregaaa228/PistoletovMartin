package dev.frontier.aviation.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import dev.frontier.aviation.control.client.FlightClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Leave handleClientSync's position/velocity interpolation and its countdown intact. */
@Mixin(value = VehicleEntity.class, remap = false)
public abstract class FlightClientAttitude {
    @Redirect(method = "handleClientSync", at = @At(value = "INVOKE", target =
        "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;m_146922_(F)V"), require = 1, allow = 1)
    private void aviation$yaw(VehicleEntity vehicle, float yaw) {
        if (!FlightClient.managedAttitude(vehicle)) vehicle.setYRot(yaw);
    }
    @Redirect(method = "handleClientSync", at = @At(value = "INVOKE", target =
        "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;m_146926_(F)V"), require = 1, allow = 1)
    private void aviation$pitch(VehicleEntity vehicle, float pitch) {
        if (!FlightClient.managedAttitude(vehicle)) vehicle.setXRot(pitch);
    }
}
