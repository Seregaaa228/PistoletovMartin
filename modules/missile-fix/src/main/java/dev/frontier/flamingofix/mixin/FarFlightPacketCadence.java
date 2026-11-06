package dev.frontier.flamingofix.mixin;

import dev.frontier.flamingofix.FarFlightCadence;
import dev.sbw.deepstrike.game.GuidedMissileEntity;
import dev.sbw.deepstrike.network.DeepStrikeNetwork;
import dev.sbw.deepstrike.network.MissileCameraStream;
import java.util.UUID;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * DeepStrike broadcasts every missile's FlightState to the whole dimension every tick.
 * Keep the same packet, but send it to players outside normal tracking range only every
 * {@link FarFlightCadence#FAR_INTERVAL} ticks. Launch, intercept and terminal packets, the
 * owner, the camera viewer and every nearby player stay at full rate.
 */
@Mixin(value = DeepStrikeNetwork.class, remap = false)
public abstract class FarFlightPacketCadence {
    @Redirect(method = "flight(Ldev/sbw/deepstrike/game/GuidedMissileEntity;Z)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraftforge/network/simple/SimpleChannel;"
                    + "send(Lnet/minecraftforge/network/PacketDistributor$PacketTarget;Ljava/lang/Object;)V"),
            remap = false, require = 1)
    private static void flamingo$farCadence(SimpleChannel channel, PacketDistributor.PacketTarget target,
            Object message, GuidedMissileEntity missile, boolean alive) {
        if (!(message instanceof DeepStrikeNetwork.FlightState state)
                || !(missile.level() instanceof ServerLevel level)
                || FarFlightCadence.unconditional(state.alive(), state.intercepted(), state.flightAge())
                || FarFlightCadence.farTick(state.serverTick(), state.id())) {
            channel.send(target, message);
            return;
        }
        double near = FarFlightCadence.nearRadius(level.getServer().getPlayerList().getViewDistance());
        double nearSq = near * near;
        UUID owner = state.owner();
        Packet<?> packet = null;
        for (ServerPlayer player : level.players()) {
            double dx = player.getX() - state.x(), dz = player.getZ() - state.z();
            if (dx * dx + dz * dz > nearSq && !player.getUUID().equals(owner)
                    && !MissileCameraStream.owns(player, missile)) continue;
            if (packet == null) packet = channel.toVanillaPacket(message, NetworkDirection.PLAY_TO_CLIENT);
            player.connection.send(packet);
        }
    }
}
