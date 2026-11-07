package dev.frontier.arcade.artillery.client;

import dev.frontier.arcade.ArcadeVehicles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Development aid, off unless the client runs with -Dfrontier.autoShot=true: after each teleport (a test world's
 * data pack walks the camera round the vehicles) it waits for the view to settle and saves a screenshot, and
 * -Dfrontier.debugPose=true shows every stabiliser down.
 */
@Mod.EventBusSubscriber(modid = ArcadeVehicles.ID, value = Dist.CLIENT)
public final class ArtilleryShotDebug {
    static final boolean POSE = Boolean.getBoolean("frontier.debugPose");
    private static final boolean SHOTS = Boolean.getBoolean("frontier.autoShot");
    private static Vec3 last;
    private static int settle = -1, shot;
    private static boolean grab;

    private ArtilleryShotDebug() { }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (!SHOTS || event.phase != TickEvent.Phase.END) return;
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        Vec3 now = player.position();
        if (last != null && now.distanceToSqr(last) > 4) settle = 40;
        last = now;
        if (settle > 0 && --settle == 0) grab = true;
    }

    @SubscribeEvent
    public static void render(TickEvent.RenderTickEvent event) {
        if (!grab || event.phase != TickEvent.Phase.END) return;
        grab = false;
        var mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, "frontier-shot-" + (++shot) + ".png", mc.getMainRenderTarget(), message -> { });
    }
}
