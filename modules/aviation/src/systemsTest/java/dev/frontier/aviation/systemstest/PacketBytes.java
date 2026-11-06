package dev.frontier.aviation.systemstest;

import com.google.gson.JsonObject;
import io.netty.buffer.Unpooled;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;

/**
 * Serialized size of every clientbound packet by type (custom payloads by channel). The
 * integrated server hands packets over in memory, so sizes are measured by writing each
 * packet once into a scratch buffer.
 */
public final class PacketBytes {
    private static volatile boolean active;
    private static final Map<String, LongAdder> BYTES = new ConcurrentHashMap<>(), COUNT = new ConcurrentHashMap<>();

    private PacketBytes() {}

    public static boolean active() { return active; }
    static void start() { BYTES.clear(); COUNT.clear(); active = true; }
    static void stop() { active = false; }

    public static void count(Packet<?> packet) {
        if (packet instanceof BundlePacket<?> bundle) {
            for (Packet<?> part : bundle.subPackets()) count(part);
            return;
        }
        String label = packet instanceof ClientboundCustomPayloadPacket payload
            ? "payload " + payload.getIdentifier() : packet.getClass().getSimpleName();
        int size;
        try {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            packet.write(buffer);
            size = buffer.writerIndex();
            buffer.release();
        } catch (RuntimeException failure) {
            size = -1;
        }
        BYTES.computeIfAbsent(label, k -> new LongAdder()).add(Math.max(0, size));
        COUNT.computeIfAbsent(label, k -> new LongAdder()).increment();
    }

    /** Bytes per second by type over {@code seconds}, largest first. */
    static JsonObject summary(double seconds) {
        var out = new JsonObject();
        long total = BYTES.values().stream().mapToLong(LongAdder::sum).sum();
        out.addProperty("totalBytesPerSecond", Math.round(total / seconds));
        BYTES.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum())).limit(14)
            .forEach(e -> out.addProperty(e.getKey(), Math.round(e.getValue().sum() / seconds) + " B/s, "
                + Math.round(COUNT.get(e.getKey()).sum() / seconds) + "/s"));
        return out;
    }
}
