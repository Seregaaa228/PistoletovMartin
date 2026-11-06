package dev.frontier.arcade;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client tick counter for per-tick memoization. Camera, HUD, overlay and key-context callers may ask
 * for the piloted target many times per frame; the entity scan behind that answer runs once per tick.
 */
@Mod.EventBusSubscriber(modid = ArcadeVehicles.ID, value = Dist.CLIENT)
public final class ArcadeClientTicks {
    private static long ticks;

    private ArcadeClientTicks() { }

    public static long now() { return ticks; }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) ticks++;
    }
}
