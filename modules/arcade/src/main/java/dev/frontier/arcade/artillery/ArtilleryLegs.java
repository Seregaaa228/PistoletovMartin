package dev.frontier.arcade.artillery;

import com.atsuishio.superbwarfare.entity.vehicle.base.SpArtilleryEntity;
import dev.frontier.arcade.ArcadeVehicles;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Stabilisers of the wheeled guns: the Grad's rear jacks and the Bohdana's spades. A crew member lowers them (X);
 * the driver raises them by driving off. While they are down the vehicle stands fast: pushes and recoil do not
 * slide it. The state is saved with the vehicle and sent to everyone who sees it; clients animate it.
 */
@Mod.EventBusSubscriber(modid = ArcadeVehicles.ID)
public final class ArtilleryLegs {
    /** Time the legs take to go fully down or up, for the animation. */
    public static final int DEPLOY_TICKS = 30;
    private static final String TAG = "frontier_legs_down";
    private static final Set<Entity> DOWN = Collections.newSetFromMap(new WeakHashMap<>());
    private static SimpleChannel channel;

    private ArtilleryLegs() { }

    public static boolean stabilised(EntityType<?> type) {
        return type == ArtilleryVehicles.GRAD.get() || type == ArtilleryVehicles.BOHDANA.get();
    }

    static void register(SimpleChannel artillery, int firstId) {
        channel = artillery;
        artillery.registerMessage(firstId, Request.class, Request::encode, Request::decode, Request::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        artillery.registerMessage(firstId + 1, State.class, State::encode, State::decode, State::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    /** Client: ask to lower (true) or raise (false) the legs of the vehicle the player rides. */
    public static void request(boolean down) {
        channel.sendToServer(new Request(down));
    }

    public static boolean down(Entity vehicle) {
        return vehicle.getPersistentData().getBoolean(TAG);
    }

    private static void set(SpArtilleryEntity gun, boolean down) {
        if (down(gun) == down) return;
        gun.getPersistentData().putBoolean(TAG, down);
        if (down) DOWN.add(gun); else DOWN.remove(gun);
        channel.send(PacketDistributor.TRACKING_ENTITY.with(() -> gun), new State(gun.getId(), down, false));
    }

    @SubscribeEvent
    public static void join(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && stabilised(event.getEntity().getType()) && down(event.getEntity()))
            DOWN.add(event.getEntity());
    }

    /** Someone coming into view of a braced vehicle sees it braced, without the lowering animation. */
    @SubscribeEvent
    public static void tracking(PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer player && stabilised(event.getTarget().getType()) && down(event.getTarget()))
            channel.send(PacketDistributor.PLAYER.with(() -> player), new State(event.getTarget().getId(), true, true));
    }

    @SubscribeEvent
    public static void tick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel) || DOWN.isEmpty()) return;
        for (Entity vehicle : DOWN) {
            if (vehicle.isRemoved() || vehicle.level() != event.level) continue;
            var motion = vehicle.getDeltaMovement();
            if (motion.x != 0 || motion.z != 0) vehicle.setDeltaMovement(0, motion.y, 0);
        }
    }

    private record Request(boolean down) {
        private static void encode(Request packet, FriendlyByteBuf buffer) { buffer.writeBoolean(packet.down); }
        private static Request decode(FriendlyByteBuf buffer) { return new Request(buffer.readBoolean()); }
        private static void handle(Request packet, Supplier<NetworkEvent.Context> contextSupplier) {
            var context = contextSupplier.get();
            context.enqueueWork(() -> {
                var player = context.getSender();
                if (player != null && player.isAlive() && player.getVehicle() instanceof SpArtilleryEntity gun
                    && !gun.isWreck() && stabilised(gun.getType())) set(gun, packet.down);
            });
            context.setPacketHandled(true);
        }
    }

    private record State(int vehicle, boolean down, boolean instant) {
        private static void encode(State packet, FriendlyByteBuf buffer) {
            buffer.writeVarInt(packet.vehicle); buffer.writeBoolean(packet.down); buffer.writeBoolean(packet.instant);
        }
        private static State decode(FriendlyByteBuf buffer) { return new State(buffer.readVarInt(), buffer.readBoolean(), buffer.readBoolean()); }
        private static void handle(State packet, Supplier<NetworkEvent.Context> contextSupplier) {
            var context = contextSupplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> dev.frontier.arcade.artillery.client.ArtilleryLegsClient.state(packet.vehicle, packet.down, packet.instant)));
            context.setPacketHandled(true);
        }
    }
}
