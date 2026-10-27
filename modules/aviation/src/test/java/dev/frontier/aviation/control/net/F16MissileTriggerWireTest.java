package dev.frontier.aviation.control.net;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;

/** Exact new wire budget and hostile input rejection, independent of a world. */
public final class F16MissileTriggerWireTest {
    private static final UUID VEHICLE = UUID.fromString("00000000-0000-0000-0000-000000000016");
    public static void main(String[] args) {
        for (int count : new int[] {1, 2}) {
            for (Vec3 target : new Vec3[] {null, new Vec3(12.5, 64, -128.25)}) {
                var request = new FlightNetwork.MissileTrigger(VEHICLE, 17, count, target);
                FriendlyByteBuf buffer = buffer();
                try {
                    FlightNetwork.MissileTrigger.encode(request, buffer);
                    check(buffer.readableBytes() == (target == null ? 26 : 50), "bounded encoding");
                    check(request.equals(FlightNetwork.MissileTrigger.decode(buffer)), "request round trip");
                } finally { buffer.release(); }
            }
        }
        rejected(25, 1, 1, 0, false, false);
        rejected(27, 1, 1, 0, false, false);
        rejected(26, 0, 1, 0, false, false);
        rejected(26, 1, 0, 0, false, false);
        rejected(26, 1, 3, 0, false, false);
        rejected(26, 1, 1, 2, false, false);
        rejected(50, 1, 2, 1, true, false);
        rejected(50, 1, 2, 1, false, true);
        rejected(26, 1, 1, 1, false, false); // target present with missing coordinates
        rejected(50, 1, 1, 0, false, false); // unclaimed trailing coordinates
        System.out.println("F16MissileTriggerWireTest passed");
    }
    private static void rejected(int bytes, long epoch, int count, int flag, boolean nan, boolean infinity) {
        FriendlyByteBuf buffer = buffer();
        try {
            buffer.writeUUID(VEHICLE).writeLong(epoch).writeByte(count).writeByte(flag);
            if (bytes >= 50) buffer.writeDouble(nan ? Double.NaN : 0).writeDouble(infinity ? Double.POSITIVE_INFINITY : 0).writeDouble(0);
            buffer.writerIndex(bytes);
            try {
                FlightNetwork.MissileTrigger.decode(buffer);
                throw new AssertionError("invalid packet accepted");
            } catch (DecoderException expected) { }
        } finally { buffer.release(); }
    }
    private static FriendlyByteBuf buffer() { return new FriendlyByteBuf(Unpooled.buffer(64)); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
