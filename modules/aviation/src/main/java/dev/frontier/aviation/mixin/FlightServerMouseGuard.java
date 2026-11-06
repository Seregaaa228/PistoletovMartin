package dev.frontier.aviation.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import dev.frontier.aviation.control.server.FlightControlService;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "com.atsuishio.superbwarfare.network.message.send.MouseMoveMessage", remap = false)
public abstract class FlightServerMouseGuard {
    @Redirect(method = "handler", at = @At(value = "INVOKE", target =
        "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;mouseInput(DD)V"), require = 1, allow = 1)
    private void aviation$flightMouse(VehicleEntity vehicle, double x, double y, Supplier<NetworkEvent.Context> context) {
        ServerPlayer sender = context.get().getSender();
        if (sender != null && sender.getVehicle() == vehicle && FlightControlService.get(sender.server).owns(sender, vehicle)) return;
        vehicle.mouseInput(x, y);
    }
}
