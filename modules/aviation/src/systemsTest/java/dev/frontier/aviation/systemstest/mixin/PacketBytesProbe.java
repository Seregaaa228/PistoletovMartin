package dev.frontier.aviation.systemstest.mixin;

import dev.frontier.aviation.systemstest.PacketBytes;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Counts server-to-client packet bytes by type while a network scenario is measuring. */
@Mixin(value = Connection.class, remap = false)
public abstract class PacketBytesProbe {
    @Inject(method = "m_243124_(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
            at = @At("HEAD"), remap = false)
    private void systems$count(Packet<?> packet, PacketSendListener listener, CallbackInfo ci) {
        if (PacketBytes.active() && ((Connection) (Object) this).getSending() == PacketFlow.CLIENTBOUND) PacketBytes.count(packet);
    }
}
