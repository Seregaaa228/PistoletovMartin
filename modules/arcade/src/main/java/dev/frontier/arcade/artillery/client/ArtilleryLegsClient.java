package dev.frontier.arcade.artillery.client;

import com.atsuishio.superbwarfare.entity.vehicle.base.SpArtilleryEntity;
import dev.frontier.arcade.ArcadeVehicles;
import dev.frontier.arcade.artillery.ArtilleryLegs;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The legs as this client sees them: a state per vehicle and the time it last changed, from which the renderer
 * eases them down or up. The driver pulling away raises them by itself.
 */
@Mod.EventBusSubscriber(modid = ArcadeVehicles.ID, value = Dist.CLIENT)
public final class ArtilleryLegsClient {
    private static final Map<Integer, Leg> LEGS = new HashMap<>();
    private static long lastRaise;

    private record Leg(boolean down, long since) { }

    private ArtilleryLegsClient() { }

    public static void state(int vehicle, boolean down, boolean instant) {
        var level = Minecraft.getInstance().level;
        long now = level == null ? 0 : level.getGameTime();
        LEGS.put(vehicle, new Leg(down, instant ? now - ArtilleryLegs.DEPLOY_TICKS : now));
    }

    public static boolean down(Entity vehicle) {
        var leg = LEGS.get(vehicle.getId());
        return leg != null && leg.down;
    }

    /** 0 stowed .. 1 fully down, eased. */
    public static float progress(Entity vehicle, float partialTick) {
        var leg = LEGS.get(vehicle.getId());
        if (leg == null) return 0;
        float t = Math.min(1, Math.max(0, (vehicle.level().getGameTime() - leg.since + partialTick) / ArtilleryLegs.DEPLOY_TICKS));
        float eased = t * t * (3 - 2 * t);
        return leg.down ? eased : 1 - eased;
    }

    /** X in a braced-capable vehicle: lower the legs first; once they are down, X opens the fire map. */
    static boolean consoleKey(SpArtilleryEntity gun) {
        if (!ArtilleryLegs.stabilised(gun.getType()) || down(gun)) return false;
        ArtilleryLegs.request(true);
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(Component.translatable("message.frontier_arcade_vehicles.legs.lowering"), true);
        return true;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) return;
        if (!(mc.player.getVehicle() instanceof SpArtilleryEntity gun) || !ArtilleryLegs.stabilised(gun.getType()) || !down(gun)) return;
        if (gun.getSeatIndex(mc.player) != 0 || !(mc.options.keyUp.isDown() || mc.options.keyDown.isDown())) return;
        long now = mc.level.getGameTime();
        if (now - lastRaise < 10) return;
        lastRaise = now;
        ArtilleryLegs.request(false);
        mc.player.displayClientMessage(Component.translatable("message.frontier_arcade_vehicles.legs.raising"), true);
    }

    @SubscribeEvent
    public static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        LEGS.clear();
    }
}
