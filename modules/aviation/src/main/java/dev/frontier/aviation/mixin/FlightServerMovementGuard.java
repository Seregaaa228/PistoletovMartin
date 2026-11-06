package dev.frontier.aviation.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import dev.frontier.aviation.control.server.FlightControlService;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "com.atsuishio.superbwarfare.network.message.send.VehicleMovementMessage", remap = false)
public abstract class FlightServerMovementGuard {
    @Redirect(method = "handler", at = @At(value = "INVOKE", target =
        "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;processInput(S)V"), require = 1, allow = 1)
    private void aviation$flightMovement(VehicleEntity vehicle, short keys, Supplier<NetworkEvent.Context> context) {
        ServerPlayer sender = context.get().getSender();
        if (sender != null && sender.getVehicle() == vehicle && FlightControlService.get(sender.server).owns(sender, vehicle)) {
            // Keep every unowned bit (fire/decoy included). Adapter restores owned flags before forces.
            vehicle.processInput((short) (keys & ~(1 | 2 | 4 | 8 | 16 | 32 | 256)));
        } else vehicle.processInput(keys);
    }
}
