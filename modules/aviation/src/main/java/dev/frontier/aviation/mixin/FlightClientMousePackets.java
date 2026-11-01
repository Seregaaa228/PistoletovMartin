package dev.frontier.aviation.mixin;

import com.atsuishio.superbwarfare.network.message.send.MouseMoveMessage;
import com.atsuishio.superbwarfare.tools.MinecraftUtil;
import dev.frontier.aviation.control.client.FlightClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "com.atsuishio.superbwarfare.event.ClientMouseHandler", remap = false)
public abstract class FlightClientMousePackets {
    @Redirect(method = "handleClientTick", at = @At(value = "INVOKE", target =
        "Lcom/atsuishio/superbwarfare/tools/MinecraftUtil;sendPacketToServer(Ljava/lang/Object;)V"), require = 6, allow = 6)
    private void aviation$mousePacket(Object packet) {
        if (!(packet instanceof MouseMoveMessage) || !FlightClient.suppressNativeInput()) MinecraftUtil.sendPacketToServer(packet);
    }
}
