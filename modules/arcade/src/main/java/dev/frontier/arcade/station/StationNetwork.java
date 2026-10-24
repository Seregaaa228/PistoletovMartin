package dev.frontier.arcade.station;

import dev.frontier.arcade.ArcadeVehicles;
import dev.frontier.arcade.control.ControlSessions;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/** Station actions accept only a target from the open menu and a fresh server-side owned-aircraft list. */
public final class StationNetwork {
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(ArcadeVehicles.ID, "station"), () -> "1", "1"::equals, "1"::equals);
    private static boolean initialized;

    private StationNetwork() { }

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        CHANNEL.registerMessage(0, Connect.class, Connect::encode, Connect::decode, Connect::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, Refresh.class, Refresh::encode, Refresh::decode, Refresh::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    public static void connect(StationMenu menu, UUID id) { CHANNEL.sendToServer(new Connect(menu.containerId, menu.stationPosition(), id)); }
    public static void refresh(StationMenu menu) { CHANNEL.sendToServer(new Refresh(menu.containerId, menu.stationPosition())); }

    private static StationMenu validatedMenu(ServerPlayer player, int container, BlockPos pos) {
        if (player == null || !player.isAlive() || player.isSpectator() || !(player.containerMenu instanceof StationMenu menu)
            || menu.containerId != container || !menu.stationPosition().equals(pos) || !menu.stillValid(player)) return null;
        return menu;
    }

    private record Connect(int container, BlockPos station, UUID target) {
        private static void encode(Connect packet, FriendlyByteBuf buffer) {
            buffer.writeVarInt(packet.container); buffer.writeBlockPos(packet.station); buffer.writeUUID(packet.target);
        }
        private static Connect decode(FriendlyByteBuf buffer) { return new Connect(buffer.readVarInt(), buffer.readBlockPos(), buffer.readUUID()); }
        private static void handle(Connect packet, Supplier<NetworkEvent.Context> contextSupplier) {
            var context = contextSupplier.get();
            context.enqueueWork(() -> {
                var player = context.getSender();
                var menu = validatedMenu(player, packet.container, packet.station);
                if (menu == null || !menu.contains(packet.target)) return;
                boolean available = ControlSessions.ownedRecon(player, packet.station).stream()
                    .anyMatch(entry -> entry.id().equals(packet.target) && entry.online());
                if (!available || !ControlSessions.connectStation(player, packet.station, packet.target)) {
                    player.displayClientMessage(Component.translatable("message.frontier_arcade_vehicles.station.unavailable"), true);
                    if (player.containerMenu == menu && player.level().getBlockEntity(packet.station) instanceof StationBlockEntity station) station.open(player);
                    return;
                }
                // Core closes the old menu before native monitor activation; never close a different replacement menu.
                if (player.containerMenu == menu) player.closeContainer();
            });
            context.setPacketHandled(true);
        }
    }

    private record Refresh(int container, BlockPos station) {
        private static void encode(Refresh packet, FriendlyByteBuf buffer) { buffer.writeVarInt(packet.container); buffer.writeBlockPos(packet.station); }
        private static Refresh decode(FriendlyByteBuf buffer) { return new Refresh(buffer.readVarInt(), buffer.readBlockPos()); }
        private static void handle(Refresh packet, Supplier<NetworkEvent.Context> contextSupplier) {
            var context = contextSupplier.get();
            context.enqueueWork(() -> {
                var player = context.getSender();
                if (validatedMenu(player, packet.container, packet.station) != null
                    && player.level().getBlockEntity(packet.station) instanceof StationBlockEntity station) station.open(player);
            });
            context.setPacketHandled(true);
        }
    }
}
