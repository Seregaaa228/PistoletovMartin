package dev.frontier.aviation.ordnance;

import dev.frontier.aviation.LightAviation;
import dev.frontier.aviation.control.net.FlightNetwork;
import dev.sbw.deepstrike.vehicle.Su24mEntity;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Sends derived visual state; native stores remain authoritative and persistent. */
@Mod.EventBusSubscriber(modid = LightAviation.ID)
public final class F16PayloadSync {
    private static final Map<Su24mEntity, Integer> PLANES = new WeakHashMap<>();
    private F16PayloadSync() {}

    @SubscribeEvent public static void join(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof Su24mEntity plane
                && F16Loadout.supports(plane)) PLANES.put(plane, -1);
    }
    @SubscribeEvent public static void leave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof Su24mEntity plane) PLANES.remove(plane);
    }
    @SubscribeEvent public static void tracking(PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer player && event.getTarget() instanceof Su24mEntity plane
                && F16Loadout.supports(plane)) FlightNetwork.sendPayload(player, plane, F16Loadout.visualBits(plane));
    }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        long tick = event.getServer().getTickCount();
        if (tick % 2 != 0) return;
        PLANES.entrySet().removeIf(entry -> entry.getKey().isRemoved());
        for (var entry : PLANES.entrySet()) {
            Su24mEntity plane = entry.getKey();
            if ((Object) plane instanceof F16StormLoader loader) loader.aviation$loadInventoryStores();
            int bits = F16Loadout.visualBits(plane);
            if (bits != entry.getValue() || tick % 40 == 0) {
                FlightNetwork.broadcastPayload(plane, bits);
                entry.setValue(bits);
            }
        }
    }
    @SubscribeEvent public static void stop(ServerStoppingEvent event) { PLANES.clear(); }
}
