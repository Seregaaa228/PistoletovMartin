package dev.frontier.arcade.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.DroneEntity;
import com.atsuishio.superbwarfare.init.ModItems;
import com.atsuishio.superbwarfare.tools.EntityFindUtil;
import dev.frontier.arcade.geranium.GeraniumEntity;
import dev.frontier.arcade.relay.RelayEntity;
import dev.frontier.arcade.control.ControlSessions;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.lavafrai.uncomplicatedfpv.entity.AddonDroneEntity;

/** Authorizes the addon airframes in the pinned SBW 0.8.9.1 server packet handlers. */
@Mixin(targets = {
    "com.atsuishio.superbwarfare.network.message.send.VehicleMovementMessage",
    "com.atsuishio.superbwarfare.network.message.send.MouseMoveMessage",
    "com.atsuishio.superbwarfare.network.message.send.DroneFireMessage"
}, remap = false)
public abstract class GeraniumNativePacketGuard {
    /* ServerPacketPayload.handleInternal enqueues handler(Supplier) on the server.
       Redirect the lookup rather than canceling MouseMove's separate mounted-vehicle branch.
       FireKeyMessage only handles GunItem; the native drone fire packet is DroneFireMessage. */
    @Redirect(
        method = "handler(Ljava/util/function/Supplier;)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/atsuishio/superbwarfare/tools/EntityFindUtil;findDrone(Lnet/minecraft/world/level/Level;Ljava/lang/String;)Lcom/atsuishio/superbwarfare/entity/vehicle/DroneEntity;",
            remap = false
        ),
        remap = false,
        require = 1
    )
    private DroneEntity frontier$authorizedGeranium(
        Level level, String droneId, Supplier<NetworkEvent.Context> context
    ) {
        NetworkEvent.Context network = context == null ? null : context.get();
        ServerPlayer sender = network == null ? null : network.getSender();
        if (sender == null || sender.getServer() == null || !sender.getServer().isSameThread()) return null;

        DroneEntity drone = EntityFindUtil.findDrone(level, droneId);
        if (!(drone instanceof AddonDroneEntity addon)) return drone;
        boolean ownerAndMonitor = ControlSessions.authorizedInput(sender, addon);

        ItemStack held = sender.getMainHandItem();
        CompoundTag tag = held.getTag();
        if (sender.level() != level || drone.level() != level || drone.isRemoved() || !drone.isAlive()
            || !sender.isAlive() || sender.isSpectator() || !ownerAndMonitor
            || !held.is(ModItems.MONITOR.get()) || tag == null
            || !tag.getBoolean("Using") || !tag.getBoolean("Linked")
            || !drone.getStringUUID().equals(droneId)
            || !droneId.equals(tag.getString("LinkedDrone"))) return null;
        return drone;
    }
}
