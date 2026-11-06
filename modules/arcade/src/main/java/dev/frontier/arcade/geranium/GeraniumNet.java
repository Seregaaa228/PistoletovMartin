package dev.frontier.arcade.geranium;

import dev.frontier.arcade.ArcadeVehicles;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/** Addon monitor actions and bounded render hints; native flight input stays server authoritative. */
public final class GeraniumNet {
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(new ResourceLocation(ArcadeVehicles.ID, "geranium"), () -> "5", "5"::equals, "5"::equals);
    private static final Map<UUID,Integer> VIEW = new HashMap<>();
    private GeraniumNet() { }
    public static void init() {
        CHANNEL.registerMessage(0, ViewDistance.class, ViewDistance::encode, ViewDistance::decode, ViewDistance::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, Stabilize.class, (p,b) -> {}, b -> new Stabilize(), Stabilize::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        MinecraftForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> VIEW.clear());
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> VIEW.remove(e.getEntity().getUUID()));
    }
    public static int viewDistance(UUID owner) { return VIEW.getOrDefault(owner, 2); }
    public static void sendView(int distance) { CHANNEL.sendToServer(new ViewDistance(Math.max(2,Math.min(32,distance)))); }
    public static void sendStabilize() { CHANNEL.sendToServer(new Stabilize()); }
    private record Stabilize() {
        private static void handle(Stabilize p,Supplier<NetworkEvent.Context> ctx) {
            var context = ctx.get(); context.enqueueWork(() -> {
                var sender = context.getSender(); if (sender == null || !sender.isAlive()) return;
                var tag = sender.getMainHandItem().getTag(); if (tag == null) return;
                var entity = com.atsuishio.superbwarfare.tools.EntityFindUtil.findDrone(sender.level(),tag.getString("LinkedDrone"));
                if (entity instanceof GeraniumEntity drone && drone.ownedBy(sender) && drone.activeMonitor()) drone.stabilizeFlight();
            }); context.setPacketHandled(true);
        }
    }
    private record ViewDistance(int distance) {
        private static void encode(ViewDistance p,FriendlyByteBuf b) { b.writeVarInt(p.distance); }
        private static ViewDistance decode(FriendlyByteBuf b) { return new ViewDistance(b.readVarInt()); }
        private static void handle(ViewDistance p,Supplier<NetworkEvent.Context> ctx) {
            var context = ctx.get(); context.enqueueWork(() -> {
                var sender = context.getSender(); if (sender == null || p.distance < 2 || p.distance > 32) return;
                VIEW.put(sender.getUUID(),p.distance);
            }); context.setPacketHandled(true);
        }
    }
}
