package dev.frontier.aviation.control.net;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;

public final class F16PayloadWireTest {
    public static void main(String[] args) {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000016");
        var packet = new FlightNetwork.Payload(id, 1 | (2 << 3) | (3 << 6) | (4 << 15));
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            FlightNetwork.Payload.encode(packet, buffer);
            if (buffer.readableBytes() != 20 || !packet.equals(FlightNetwork.Payload.decode(buffer)))
                throw new AssertionError("payload round trip");
        } finally { buffer.release(); }
        for (int invalid : new int[]{-1, 1 << 18, 6, 7 << 15}) {
            buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                buffer.writeUUID(id).writeInt(invalid);
                try { FlightNetwork.Payload.decode(buffer); throw new AssertionError("invalid payload accepted"); }
                catch (DecoderException expected) { }
            } finally { buffer.release(); }
        }
        System.out.println("F16 payload wire test passed");
    }
}
