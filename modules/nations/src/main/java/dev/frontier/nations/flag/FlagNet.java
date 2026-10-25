package dev.frontier.nations.flag;

import dev.frontier.nations.FrontierNations;
import dev.frontier.nations.network.WriteRateLimit;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Flag packets. C2S {@code FlagSet} carries one player's own faction flag and is
 * accepted only from that faction's native leader; S2C {@code FlagSync} carries the
 * full snapshot on login plus single-entry updates after every accepted write.
 */
public final class FlagNet {
    /** Protocol version advertised to both sides. */
    // v2 gives an empty FlagSync the explicit meaning "clear the client cache".
    public static final String PROTOCOL = "2";

    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(FrontierNations.ID, "flags"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);
    private static boolean registered;
    private static final WriteRateLimit WRITES=new WriteRateLimit(500_000_000L);

    private FlagNet() {}

    public static synchronized void init() {
        if (registered) return;
        registered = true;
        CHANNEL.registerMessage(0, FlagSet.class, FlagSet::encode, FlagSet::decode, FlagSet::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, FlagSync.class, FlagSync::encode, FlagSync::decode, FlagSync::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        MinecraftForge.EVENT_BUS.addListener(FlagNet::onLogin);
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e)->WRITES.remove(e.getEntity().getUUID()));
        MinecraftForge.EVENT_BUS.addListener((ServerStoppedEvent e)->WRITES.clear());
    }

    public static void sendToServer(String factionId, FlagDefinition flag) {
        CHANNEL.sendToServer(new FlagSet(factionId, flag));
    }

    public static void sendSync(ServerPlayer player, Map<String, FlagDefinition> flags) {
        // Zero entries is an authoritative reset; the following snapshot repopulates the cache.
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new FlagSync(Map.of()));
        if(flags!=null&&!flags.isEmpty())CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new FlagSync(flags));
    }

    public static void removeFaction(net.minecraft.server.MinecraftServer server,String faction){
        NationFlags flags=NationFlags.get(server);if(flags.flag(faction)==null)return;
        flags.set(faction,null);Map<String,FlagDefinition> snapshot=flags.snapshot();
        for(ServerPlayer player:server.getPlayerList().getPlayers())sendSync(player,snapshot);
    }

    public static void broadcastUpdate(String factionId, FlagDefinition flag) {
        MinecraftServerProxy.broadcast(new FlagSync(Map.of(factionId, flag)));
    }

    private static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            NationFlags flags = NationFlags.get(player.serverLevel());
            sendSync(player, flags.snapshot());
        }
    }

    /** C2S: replace the sender's own faction flag; only the native leader may write. */
    public record FlagSet(String factionId, FlagDefinition flag) {
        static void encode(FlagSet message, FriendlyByteBuf buffer) {
            buffer.writeUtf(message.factionId == null ? "" : message.factionId, NationFlags.MAX_ID_LENGTH + 1);
            buffer.writeBoolean(message.flag != null);
            if (message.flag != null) message.flag.encode(buffer);
        }

        static FlagSet decode(FriendlyByteBuf buffer) {
            String factionId = buffer.readUtf(NationFlags.MAX_ID_LENGTH + 1);
            FlagDefinition flag = buffer.readBoolean() ? FlagDefinition.decode(buffer) : null;
            return new FlagSet(factionId, flag);
        }

        static void handle(FlagSet message, Supplier<NetworkEvent.Context> context) {
            NetworkEvent.Context ctx = context.get();
            ctx.enqueueWork(() -> {
                ServerPlayer sender = ctx.getSender();
                if (!FlagAuthority.isConnected(sender)) return;
                var limit=WRITES.check(sender.getUUID());
                if(limit!=WriteRateLimit.Decision.ALLOW){
                    if(limit==WriteRateLimit.Decision.WARN)deny(sender,"Флаг: подождите полсекунды перед следующим сохранением.");
                    return;
                }
                // The sender names no authority: the faction must come from native membership
                // and the write must come from the native team leader UUID.
                String own = FlagAuthority.ownFactionId(sender);
                if (own == null || !own.equals(message.factionId) || message.flag == null) {
                    deny(sender, "Флаг: только лидер своей страны может сохранить флаг.");
                    return;
                }
                if (!FlagAuthority.isLeader(sender, own)) {
                    deny(sender, "Флаг: только лидер своей страны может сохранить флаг.");
                    return;
                }
                NationFlags flags = NationFlags.get(sender.serverLevel());
                FlagDefinition previous=flags.flag(own);
                if (!flags.set(own, message.flag)){deny(sender,"Флаг: достигнут лимит флагов сервера.");return;}
                if(!message.flag.equals(previous))broadcastUpdate(own, message.flag);
                else CHANNEL.send(PacketDistributor.PLAYER.with(()->sender),new FlagSync(Map.of(own,message.flag)));
            });
            ctx.setPacketHandled(true);
        }

        private static void deny(ServerPlayer player, String text) {
            player.sendSystemMessage(Component.literal(text));
        }
    }

    /** S2C: full snapshot on login, single-entry update after each accepted write. */
    public record FlagSync(Map<String, FlagDefinition> flags) {
        static void encode(FlagSync message, FriendlyByteBuf buffer) {
            Map<String, FlagDefinition> flags = message.flags == null ? Map.of() : message.flags;
            List<Map.Entry<String, FlagDefinition>> entries = new ArrayList<>();
            for(var entry:flags.entrySet())if(entry.getKey()!=null&&!entry.getKey().isBlank()&&entry.getKey().length()<=NationFlags.MAX_ID_LENGTH&&entry.getValue()!=null)entries.add(entry);
            if(entries.size()>NationFlags.MAX_ENTRIES)throw new IllegalArgumentException("Too many flags");
            buffer.writeVarInt(entries.size());
            for (Map.Entry<String, FlagDefinition> entry : entries) {
                buffer.writeUtf(entry.getKey(), NationFlags.MAX_ID_LENGTH + 1);
                entry.getValue().encode(buffer);
            }
        }

        static FlagSync decode(FriendlyByteBuf buffer) {
            int count = buffer.readVarInt();
            if(count<0||count>NationFlags.MAX_ENTRIES)throw new DecoderException("Invalid flag count");
            Map<String, FlagDefinition> flags = new LinkedHashMap<>();
            for (int index = 0; index < count; index++) {
                String factionId = buffer.readUtf(NationFlags.MAX_ID_LENGTH + 1);
                FlagDefinition flag = FlagDefinition.decode(buffer);
                if(factionId.isBlank()||factionId.length()>NationFlags.MAX_ID_LENGTH||flags.putIfAbsent(factionId,flag)!=null)
                    throw new DecoderException("Invalid or duplicate flag faction ID");
            }
            return new FlagSync(Map.copyOf(flags));
        }

        static void handle(FlagSync message, Supplier<NetworkEvent.Context> context) {
            NetworkEvent.Context ctx = context.get();
            ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> {if(!dev.frontier.nations.network.ClientPacketGuard.current(ctx))return;if(message.flags.isEmpty())FlagCache.replace(Map.of());else FlagCache.apply(message.flags);}));
            ctx.setPacketHandled(true);
        }
    }

    /** Keeps the broadcast call off direct server APIs for unit clarity. */
    private static final class MinecraftServerProxy {
        static void broadcast(FlagSync sync) {
            // Player list already covers every dimension.
            for (ServerPlayer player
                    : net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayers()) {
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), sync);
            }
        }
    }
}
