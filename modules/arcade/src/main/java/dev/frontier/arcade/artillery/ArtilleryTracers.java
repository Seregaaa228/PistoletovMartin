package dev.frontier.arcade.artillery;

import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Keeps a parked round visible. While the server carries a shell or rocket outside active terrain there is no entity
 * to see, so each nearby player gets one packet with its position, velocity and gravity and one when it lands back;
 * the client flies the same arc and draws the trail itself. Two small packets per round per observer, nothing per
 * tick.
 */
public final class ArtilleryTracers {
    /** Players this close to a round on either packet see its trail. */
    private static final double SIGHT = 1024;
    private static SimpleChannel channel;
    private static int nextId;

    private ArtilleryTracers() { }

    static void register(SimpleChannel artillery, int firstId) {
        channel = artillery;
        artillery.registerMessage(firstId, Start.class, Start::encode, Start::decode, Start::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        artillery.registerMessage(firstId + 1, End.class, End::encode, End::decode, End::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    static int start(ServerLevel level, Vec3 position, Vec3 velocity, float gravity, boolean rocket) {
        int id = ++nextId;
        var packet = new Start(id, position, velocity, gravity, rocket);
        for (ServerPlayer player : level.players())
            if (horizontal(player.position(), position) <= SIGHT) channel.send(PacketDistributor.PLAYER.with(() -> player), packet);
        return id;
    }

    static void end(ServerLevel level, int id, Vec3 position) {
        var packet = new End(id);
        // Generous: a player who saw it start may have moved; an unknown id is ignored by the client.
        for (ServerPlayer player : level.players())
            if (horizontal(player.position(), position) <= SIGHT * 2) channel.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    private record Start(int id, Vec3 position, Vec3 velocity, float gravity, boolean rocket) {
        private static void encode(Start p, FriendlyByteBuf b) {
            b.writeVarInt(p.id);
            b.writeDouble(p.position.x); b.writeDouble(p.position.y); b.writeDouble(p.position.z);
            b.writeFloat((float) p.velocity.x); b.writeFloat((float) p.velocity.y); b.writeFloat((float) p.velocity.z);
            b.writeFloat(p.gravity); b.writeBoolean(p.rocket);
        }
        private static Start decode(FriendlyByteBuf b) {
            return new Start(b.readVarInt(), new Vec3(b.readDouble(), b.readDouble(), b.readDouble()),
                new Vec3(b.readFloat(), b.readFloat(), b.readFloat()), b.readFloat(), b.readBoolean());
        }
        private static void handle(Start p, Supplier<NetworkEvent.Context> contextSupplier) {
            var context = contextSupplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                dev.frontier.arcade.artillery.client.ArtilleryTracersClient.start(p.id, p.position, p.velocity, p.gravity, p.rocket)));
            context.setPacketHandled(true);
        }
    }

    private record End(int id) {
        private static void encode(End p, FriendlyByteBuf b) { b.writeVarInt(p.id); }
        private static End decode(FriendlyByteBuf b) { return new End(b.readVarInt()); }
        private static void handle(End p, Supplier<NetworkEvent.Context> contextSupplier) {
            var context = contextSupplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                dev.frontier.arcade.artillery.client.ArtilleryTracersClient.end(p.id)));
            context.setPacketHandled(true);
        }
    }
}
