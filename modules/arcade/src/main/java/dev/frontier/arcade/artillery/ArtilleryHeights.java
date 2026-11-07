package dev.frontier.arcade.artillery;

import com.atsuishio.superbwarfare.entity.vehicle.base.SpArtilleryEntity;
import dev.frontier.arcade.ArcadeVehicles;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Surface height of a fire-mission point far outside the crew's loaded terrain. Only a player seated in a
 * self-propelled gun may ask, within the gun's reach, twice a second at most; no chunk is loaded to answer.
 */
public final class ArtilleryHeights {
    /** A little beyond the PLZ-05's 5.4 km level-ground reach. */
    private static final double MAX_DISTANCE = 6000;
    private static final int COOLDOWN_TICKS = 10;
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(ArcadeVehicles.ID, "artillery"), () -> "1", "1"::equals, "1"::equals);
    private static final Map<UUID, Long> LAST_QUERY = new WeakHashMap<>();
    private static boolean initialized;

    private ArtilleryHeights() { }

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        CHANNEL.registerMessage(0, Query.class, Query::encode, Query::decode, Query::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, Answer.class, Answer::encode, Answer::decode, Answer::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static void query(int x, int z) {
        CHANNEL.sendToServer(new Query(x, z));
    }

    private static void answer(ServerPlayer player, Query query) {
        if (player == null || !player.isAlive() || !(player.getVehicle() instanceof SpArtilleryEntity gun) || gun.isWreck()) return;
        if (Math.hypot(query.x + 0.5 - gun.getX(), query.z + 0.5 - gun.getZ()) > MAX_DISTANCE) return;
        var level = player.serverLevel();
        if (!level.getWorldBorder().isWithinBounds(query.x, query.z)) return;
        long now = level.getGameTime();
        Long last = LAST_QUERY.get(player.getUUID());
        if (last != null && now - last < COOLDOWN_TICKS) return;
        LAST_QUERY.put(player.getUUID(), now);
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Answer(query.x, query.z, surface(level, query.x, query.z)));
    }

    /**
     * Top block of a column without loading it: the real heightmap when the chunk is already loaded, otherwise
     * the world generator's natural terrain (player buildings there are unknown until the chunk loads).
     */
    public static int surface(ServerLevel level, int x, int z) {
        var source = level.getChunkSource();
        var chunk = source.getChunkNow(x >> 4, z >> 4);
        if (chunk != null) return chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15);
        return source.getGenerator().getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, source.randomState()) - 1;
    }

    private record Query(int x, int z) {
        private static void encode(Query packet, FriendlyByteBuf buffer) { buffer.writeInt(packet.x); buffer.writeInt(packet.z); }
        private static Query decode(FriendlyByteBuf buffer) { return new Query(buffer.readInt(), buffer.readInt()); }
        private static void handle(Query packet, Supplier<NetworkEvent.Context> contextSupplier) {
            var context = contextSupplier.get();
            context.enqueueWork(() -> answer(context.getSender(), packet));
            context.setPacketHandled(true);
        }
    }

    private record Answer(int x, int z, int y) {
        private static void encode(Answer packet, FriendlyByteBuf buffer) {
            buffer.writeInt(packet.x); buffer.writeInt(packet.z); buffer.writeInt(packet.y);
        }
        private static Answer decode(FriendlyByteBuf buffer) { return new Answer(buffer.readInt(), buffer.readInt(), buffer.readInt()); }
        private static void handle(Answer packet, Supplier<NetworkEvent.Context> contextSupplier) {
            var context = contextSupplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> dev.frontier.arcade.artillery.client.FireMission.surface(packet.x, packet.z, packet.y)));
            context.setPacketHandled(true);
        }
    }
}
