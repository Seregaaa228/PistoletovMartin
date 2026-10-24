package dev.frontier.industry;

import com.google.gson.JsonParser;
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
        new ResourceLocation(IndustryMod.ID, "operations"), () -> "2", "2"::equals, "2"::equals);
    private static final Map<UUID, Integer> LAST_ACTION = new HashMap<>(), LAST_REFRESH = new HashMap<>();
    public static void init() {
        CHANNEL.registerMessage(0, Action.class, Action::encode, Action::decode, Action::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, View.class, View::encode, View::decode, View::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.server.ServerStoppedEvent e) -> {
            LAST_ACTION.clear(); LAST_REFRESH.clear(); Operations.clear();
        });
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e) -> {
            LAST_ACTION.remove(e.getEntity().getUUID()); LAST_REFRESH.remove(e.getEntity().getUUID());
        });
    }
    public static boolean hasPda(ServerPlayer player) {
        return player != null && player.isAlive() && !player.isSpectator()
            && (IndustryContent.isTablet(player.getMainHandItem()) || IndustryContent.isTablet(player.getOffhandItem()));
    }
    public static void open(ServerPlayer player) { if (hasPda(player)) send(player, true, false); }
    public static void request(String action, String arg, int value) { CHANNEL.sendToServer(new Action(action, arg, value)); }
    private static void send(ServerPlayer player, boolean open, boolean camera) {
        String json = Operations.snapshot(player).toString();
        if (json.length() <= 196608) CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new View(json, open, camera));
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
    public record View(String json, boolean open, boolean camera) {
        static void encode(View p, FriendlyByteBuf b) { b.writeUtf(p.json, 196608); b.writeBoolean(p.open); b.writeBoolean(p.camera); }
        static View decode(FriendlyByteBuf b) { return new View(b.readUtf(196608), b.readBoolean(), b.readBoolean()); }
        static void handle(View p, Supplier<NetworkEvent.Context> source) {
            var context = source.get(); context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                dev.frontier.industry.client.OperationsScreen.receive(JsonParser.parseString(p.json).getAsJsonObject(), p.open, p.camera)));
            context.setPacketHandled(true);
        }
    }
}
