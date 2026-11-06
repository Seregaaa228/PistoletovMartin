package dev.frontier.arcade.relay;

import dev.frontier.arcade.ArcadeVehicles;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import ru.lavafrai.uncomplicatedfpv.entity.AddonDroneEntity;
import ru.lavafrai.uncomplicatedfpv.entity.DroneSignalModel;
import ru.lavafrai.uncomplicatedfpv.entity.DroneSignalSample;

/**
 * Server-side direct radio link from the operator to an addon drone, using UF's terrain and REB model
 * (the name is historical: airborne relays were removed, every link is direct now).
 *
 * <p>Each evaluation traces rays as long as the link, so a sample is reused for up to five ticks while
 * the operator stays put and the aircraft moves less than 4 blocks or 1% of its distance.
 */
@Mod.EventBusSubscriber(modid = ArcadeVehicles.ID)
public final class RelayNetwork {
    private static final int CACHE_TICKS = 5, MAX_CACHED_DRONES = 256;
    private static final double ORIGIN_TOLERANCE_SQR = .25, POSITION_TOLERANCE_SQR = 16, FAR_POSITION_TOLERANCE = .01;
    private static final Map<ServerLevel, Map<UUID, CachedLink>> CACHE = new WeakHashMap<>();

    private RelayNetwork() {}

    @SubscribeEvent public static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel server) CACHE.remove(server);
    }

    /** The native direct sample, reused within the tolerances above. */
    public static LinkInfo linkInfo(AddonDroneEntity drone, Vec3 origin) {
        if (!(drone.level() instanceof ServerLevel level) || origin == null) return new LinkInfo(0, false);
        Map<UUID, CachedLink> links = CACHE.computeIfAbsent(level, ignored -> new LinkedHashMap<>());
        CachedLink cached = links.get(drone.getUUID());
        long now = level.getGameTime();
        if (cached != null && now >= cached.tick && now - cached.tick < CACHE_TICKS
            && cached.origin.distanceToSqr(origin) <= ORIGIN_TOLERANCE_SQR
            && cached.target.distanceToSqr(drone.position()) <= positionTolerance(cached)) return cached.info;
        DroneSignalSample sample = DroneSignalModel.INSTANCE.evaluate(level, drone, origin);
        LinkInfo info = new LinkInfo(sample.getQuality(), Double.isFinite(sample.getQuality()) && sample.getQuality() > 0);
        links.remove(drone.getUUID());
        if (links.size() >= MAX_CACHED_DRONES) {
            links.entrySet().removeIf(entry -> now - entry.getValue().tick >= 200);
            while (links.size() >= MAX_CACHED_DRONES) links.remove(links.keySet().iterator().next());
        }
        links.put(drone.getUUID(), new CachedLink(now, origin, drone.position(), info));
        return info;
    }

    public static boolean reachable(AddonDroneEntity drone, Vec3 origin) { return linkInfo(drone, origin).connected(); }

    private static double positionTolerance(CachedLink cached) {
        double far = cached.origin.distanceTo(cached.target) * FAR_POSITION_TOLERANCE;
        return Math.max(POSITION_TOLERANCE_SQR, far * far);
    }

    public record LinkInfo(double quality, boolean connected) {}
    private record CachedLink(long tick, Vec3 origin, Vec3 target, LinkInfo info) {}
}
