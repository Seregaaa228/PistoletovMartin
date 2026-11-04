package dev.frontier.aviation.client;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import dev.frontier.aviation.LightAviation;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * SBW asks which entity the pilot looks at twice per client tick: the aircraft HUD at the
 * start of the tick and the vehicle crosshair on every frame. Each answer is a 512-block
 * ray plus an entity search. Both now share one answer per tick period: the period starts
 * before SBW's tick handlers run, so the HUD computes it and the frames until the next tick
 * reuse it.
 */
@Mod.EventBusSubscriber(modid = LightAviation.ID, value = Dist.CLIENT)
public final class LookAtPickCache {
    private static long period, cachedPeriod = -1;
    private static VehicleEntity vehicle;
    private static Entity viewer, target;
    private static double range;

    private LookAtPickCache() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) period++;
    }

    public static Entity lookAt(VehicleEntity vehicle, Entity viewer, double range, float partialTick) {
        if (period != cachedPeriod || vehicle != LookAtPickCache.vehicle || viewer != LookAtPickCache.viewer
                || range != LookAtPickCache.range) {
            target = vehicle.getPlayerLookAtEntityOnVehicle(viewer, range, partialTick);
            cachedPeriod = period;
            LookAtPickCache.vehicle = vehicle;
            LookAtPickCache.viewer = viewer;
            LookAtPickCache.range = range;
        }
        return target;
    }
}
