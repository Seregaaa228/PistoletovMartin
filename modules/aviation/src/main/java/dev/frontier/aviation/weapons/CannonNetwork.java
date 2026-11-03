package dev.frontier.aviation.weapons;

import io.netty.handler.codec.DecoderException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/** One packet per cannon shot: clients fly the tracer themselves (CannonShells has no entity). */
public final class CannonNetwork {
    public static final String PROTOCOL = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation("frontier_aviation", "cannon"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);
    private static Consumer<Tracer> receiver = ignored -> { };

    private CannonNetwork() {}

    public static void register() {
        CHANNEL.registerMessage(0, Tracer.class, Tracer::encode, Tracer::decode, Tracer::handle,
            Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static void installReceiver(Consumer<Tracer> client) { receiver = Objects.requireNonNull(client); }

    /** To everyone who sees the jet (its pilot included). */
    static void tracer(Entity plane, Vec3 from, Vec3 velocity, float gravity) {
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> plane),
            new Tracer(from.x, from.y, from.z, (float) velocity.x, (float) velocity.y, (float) velocity.z, gravity));
    }

    public record Tracer(double x, double y, double z, float vx, float vy, float vz, float gravity) {
        public Tracer {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Float.isFinite(vx)
                    || !Float.isFinite(vy) || !Float.isFinite(vz) || !Float.isFinite(gravity)
                    || vx * vx + vy * vy + vz * vz > 256 * 256 || Math.abs(gravity) > 4)
                throw new IllegalArgumentException("invalid tracer");
        }

        static void encode(Tracer t, FriendlyByteBuf buffer) {
            buffer.writeDouble(t.x); buffer.writeDouble(t.y); buffer.writeDouble(t.z);
            buffer.writeFloat(t.vx); buffer.writeFloat(t.vy); buffer.writeFloat(t.vz); buffer.writeFloat(t.gravity);
        }

        static Tracer decode(FriendlyByteBuf buffer) {
            try {
                if (buffer.readableBytes() != 40) throw new IllegalArgumentException("tracer size");
                return new Tracer(buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                    buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat());
            } catch (IllegalArgumentException | IndexOutOfBoundsException failure) {
                throw new DecoderException("Malformed cannon tracer", failure);
            }
        }

        static void handle(Tracer packet, Supplier<NetworkEvent.Context> source) {
            var context = source.get();
            context.enqueueWork(() -> receiver.accept(packet));
            context.setPacketHandled(true);
        }
    }
}
