package dev.frontier.aviation.mixin;

import dev.frontier.aviation.control.client.FlightClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(targets = "com.atsuishio.superbwarfare.event.ClientEventHandler", remap = false)
public abstract class FlightClientMovementPackets {
    @ModifyArg(method = "handleControlVehicle", at = @At(value = "INVOKE", target =
        "Lcom/atsuishio/superbwarfare/network/message/send/VehicleMovementMessage;<init>(S)V"), index = 0, require = 1, allow = 1)
    private short aviation$movementBits(short bits) {
        return FlightClient.suppressNativeInput() ? (short) (bits & ~(1 | 2 | 4 | 8 | 16 | 32 | 256)) : bits;
    }
}
