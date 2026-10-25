package dev.frontier.industry;

import com.google.gson.JsonObject;
import io.netty.handler.codec.DecoderException;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/** PDA requests contain intentions only. Native mods and server inventories remain authoritative. */
public final class OperationsNet {
    private OperationsNet() {}
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(IndustryMod.ID, "operations"), () -> "3", "3"::equals, "3"::equals);
    private static final Map<UUID, Integer> LAST_ACTION = new HashMap<>(), LAST_REFRESH = new HashMap<>();
    private static final Map<UUID, PendingView> PENDING = new HashMap<>();
    private static long nextView;
    private static final class PendingView {
        final long id;
        final int sentAt;
        boolean queued, open, camera;
        PendingView(long id, int sentAt) { this.id = id; this.sentAt = sentAt; }
    }
    public static void init() {
        CHANNEL.registerMessage(0, Action.class, Action::encode, Action::decode, Action::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, View.class, View::encode, View::decode, View::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(2, Received.class, Received::encode, Received::decode, Received::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.server.ServerStoppedEvent e) -> {
            LAST_ACTION.clear(); LAST_REFRESH.clear(); PENDING.clear(); nextView = 0; Operations.clear();
        });
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e) -> {
            LAST_ACTION.remove(e.getEntity().getUUID()); LAST_REFRESH.remove(e.getEntity().getUUID()); PENDING.remove(e.getEntity().getUUID());
        });
    }
    public static boolean hasPda(ServerPlayer player) {
        return player != null && player.isAlive() && !player.isSpectator()
            && player.server.getPlayerList().getPlayer(player.getUUID()) == player
            && (IndustryContent.isTablet(player.getMainHandItem()) || IndustryContent.isTablet(player.getOffhandItem()));
    }
    public static void open(ServerPlayer player) { if (hasPda(player)) send(player, true, false); }
    public static void request(String action, String arg, int value) { CHANNEL.sendToServer(new Action(action, arg, value)); }
    public static void received(long id) { CHANNEL.sendToServer(new Received(id)); }
    private static void send(ServerPlayer player, boolean open, boolean camera) {
        PendingView previous = PENDING.get(player.getUUID());
        if (previous != null) {
            previous.queued = true; previous.open |= open; previous.camera |= camera;
            // A slow connection gets at most one complete snapshot in flight. Actions still execute.
            if (player.server.getTickCount() - previous.sentAt < 600) return;
            open |= previous.open; camera |= previous.camera;
        }
        byte[] bytes;
        try { bytes = OperationsPayload.encode(Operations.snapshot(player).toString()); }
        catch (IOException tooLarge) {
            JsonObject error = new JsonObject(); error.addProperty("transportError", true);
            error.addProperty("message", "Слишком много данных КПК. Предыдущие данные сохранены; сообщи администратору.");
            try { bytes = OperationsPayload.encode(error.toString()); }
            catch (IOException impossible) { throw new IllegalStateException(impossible); }
        }
        long id = ++nextView;
        PENDING.put(player.getUUID(), new PendingView(id, player.server.getTickCount()));
        int parts = (bytes.length + OperationsPayload.PART_BYTES - 1) / OperationsPayload.PART_BYTES;
        for (int part = 0; part < parts; part++) {
            int start = part * OperationsPayload.PART_BYTES;
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new View(id, part, bytes.length,
                Arrays.copyOfRange(bytes, start, Math.min(bytes.length, start + OperationsPayload.PART_BYTES)), open, camera));
        }
    }
    public record Action(String action, String arg, int value) {
        static void encode(Action p, FriendlyByteBuf b) { b.writeUtf(p.action, 40); b.writeUtf(p.arg, 128); b.writeVarInt(p.value); }
        static Action decode(FriendlyByteBuf b) { return new Action(b.readUtf(40), b.readUtf(128), b.readVarInt()); }
        static void handle(Action p, Supplier<NetworkEvent.Context> source) {
            var context = source.get(); context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (!hasPda(player) || p.value < 0 || p.value > 64 || !Operations.allowed(p.action)) return;
                boolean refresh = p.action.equals("refresh");
                var limits = refresh ? LAST_REFRESH : LAST_ACTION;
                int now = player.server.getTickCount();
                if (now - limits.getOrDefault(player.getUUID(), now - 40) < (refresh ? 10 : 4)) return;
                limits.put(player.getUUID(), now);
                if (!refresh) Operations.action(player, p.action, p.arg, p.value);
                boolean camera = p.action.equals("camera") && player.getMainHandItem().hasTag()
                    && player.getMainHandItem().getTag().getBoolean("FrontierUavUsing");
                send(player, false, camera);
            }); context.setPacketHandled(true);
        }
    }
    public record Received(long id) {
        static void encode(Received p, FriendlyByteBuf b) { b.writeLong(p.id); }
        static Received decode(FriendlyByteBuf b) { return new Received(b.readLong()); }
        static void handle(Received p, Supplier<NetworkEvent.Context> source) {
            var context = source.get(); context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player == null || player.server.getPlayerList().getPlayer(player.getUUID()) != player) return;
                PendingView pending = PENDING.get(player.getUUID());
                if (pending == null || pending.id != p.id) return;
                PENDING.remove(player.getUUID());
                if (pending.queued && hasPda(player)) send(player, pending.open, pending.camera);
            }); context.setPacketHandled(true);
        }
    }
    public record View(long id, int part, int totalBytes, byte[] data, boolean open, boolean camera) {
        public int parts() { return (totalBytes + OperationsPayload.PART_BYTES - 1) / OperationsPayload.PART_BYTES; }
        static void encode(View p, FriendlyByteBuf b) {
            b.writeLong(p.id); b.writeVarInt(p.part); b.writeVarInt(p.totalBytes);
            b.writeByteArray(p.data); b.writeBoolean(p.open); b.writeBoolean(p.camera);
        }
        static View decode(FriendlyByteBuf b) {
            long id = b.readLong(); int part = b.readVarInt(), total = b.readVarInt();
            if (id <= 0 || total <= 0 || total > OperationsPayload.MAX_BYTES || part < 0
                    || part >= (total + OperationsPayload.PART_BYTES - 1) / OperationsPayload.PART_BYTES)
                throw new DecoderException("Invalid PDA transfer header");
            byte[] data = b.readByteArray(OperationsPayload.PART_BYTES);
            if (data.length != Math.min(OperationsPayload.PART_BYTES, total - part * OperationsPayload.PART_BYTES))
                throw new DecoderException("Invalid PDA transfer part");
            return new View(id, part, total, data, b.readBoolean(), b.readBoolean());
        }
        static void handle(View p, Supplier<NetworkEvent.Context> source) {
            var context = source.get(); context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                var connection=net.minecraft.client.Minecraft.getInstance().getConnection();
                if(connection!=null&&connection.getConnection()==context.getNetworkManager())
                    dev.frontier.industry.client.OperationsViewBuffer.accept(p);
            }));
            context.setPacketHandled(true);
        }
    }
}
