package dev.frontier.aviation.mixin;

import com.atsuishio.superbwarfare.network.message.send.VehicleFireMessage;
import dev.frontier.aviation.AircraftProfile;
import dev.sbw.deepstrike.vehicle.Su24mEntity;
import net.minecraftforge.network.NetworkEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.function.Supplier;

/** SBW accepts a remote shooter id; these single-seat planes require their live pilot. */
@Mixin(value = VehicleFireMessage.class, remap = false)
public abstract class AuthorizeAircraftCannon {
    @Inject(method = "handler", at = @At("HEAD"), cancellable = true, remap = false)
    private void aviation$requirePilot(Supplier<NetworkEvent.Context> context, CallbackInfo result) {
        var sender = context.get().getSender();
        if (sender == null) { result.cancel(); return; }
        var packet = (VehicleFireMessage)(Object)this;
        Integer id = packet.getShooterVehicleId();
        var vehicle = id == null ? sender.getVehicle() : sender.level().getEntity(id);
        if (vehicle instanceof Su24mEntity plane && AircraftProfile.of(plane) != null
                && (!sender.isAlive() || !plane.isAlive() || sender.getVehicle() != plane
                || plane.getSeatIndex(sender) != 0 || !plane.canOperate(sender, true))) {
            result.cancel();
        }
    }
}
