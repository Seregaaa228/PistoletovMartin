package dev.frontier.arcade.relay;

import com.atsuishio.superbwarfare.entity.vehicle.DroneEntity;
import dev.frontier.arcade.ArcadeVehicles;
import dev.frontier.arcade.geranium.GeraniumEntity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import ru.lavafrai.uncomplicatedfpv.entity.AddonDroneEntity;
import ru.lavafrai.uncomplicatedfpv.entity.DroneSignalModel;
import ru.lavafrai.uncomplicatedfpv.entity.DroneSignalRules;
import ru.lavafrai.uncomplicatedfpv.entity.DroneSignalSample;

/** Server-side, owner-scoped radio routing using UF's terrain and REB model for every hop. */
@Mod.EventBusSubscriber(modid = ArcadeVehicles.ID)
public final class RelayNetwork {
    public static final double RELAY_RANGE = 384;
    public static final double MAX_PATH_DISTANCE = 1024;
    public static final int MAX_RELAYS = 2;
    private static final int CACHE_TICKS = 5, MAX_CANDIDATES = 12, MAX_CACHED_DRONES = 256;
    /** Upstream hops (operator to relay, relay to relay) do not involve the fast piloted aircraft. */
    private static final int EDGE_CACHE_TICKS = 20, MAX_CACHED_EDGES = 1024;
    private static final double RETRANSMIT_QUALITY = .97, EPSILON = 1.0e-6;
    private static final double ORIGIN_TOLERANCE_SQR = .25, POSITION_TOLERANCE_SQR = 16;
    private static final Map<ServerLevel, Map<UUID, CachedLink>> CACHE = new WeakHashMap<>();
    private static final Map<ServerLevel, Map<UpstreamKey, CachedEdge>> EDGES = new WeakHashMap<>();
    /** Loaded relays per level, maintained from join/leave events instead of a world-wide entity scan. */
    private static final Map<ServerLevel, Set<RelayEntity>> RELAYS = new WeakHashMap<>();
    private static final ThreadLocal<NativeHop> NATIVE_HOP = new ThreadLocal<>();

    private RelayNetwork() {}

    /** Native direct calls never see an override; only a router-owned per-hop evaluation does. */
    public static Double nativeHopRange(AddonDroneEntity receiver) {
        NativeHop hop = NATIVE_HOP.get();
        return hop != null && hop.receiver == receiver ? hop.range : null;
    }

    public static boolean evaluatingNativeHop() { return NATIVE_HOP.get() != null; }

    /** Diagnostics use the original native direct path, without considering repeaters. */
    public static DroneSignalSample directSample(AddonDroneEntity drone, Vec3 origin) {
        if (!(drone.level() instanceof ServerLevel server)) throw new IllegalArgumentException("Server radio evaluation required");
        return nativeSample(server, drone, origin, null);
    }

    @SubscribeEvent public static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel server) { CACHE.remove(server); EDGES.remove(server); RELAYS.remove(server); }
    }

    /**
     * Upstream hops are reused for up to EDGE_CACHE_TICKS, so a new REB jammer or terrain change near a
     * relay takes effect within one second in play. Same-tick fixtures that alter the RF environment
     * call this to observe the change immediately, as if that window had already elapsed.
     */
    public static void forgetUpstreamHops(ServerLevel level) { EDGES.remove(level); }

    /** LOWEST priority: register only joins that no other handler cancelled. */
    @SubscribeEvent(priority = EventPriority.LOWEST) public static void join(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel server && event.getEntity() instanceof RelayEntity relay)
            RELAYS.computeIfAbsent(server, ignored -> Collections.newSetFromMap(new IdentityHashMap<>())).add(relay);
    }

    @SubscribeEvent public static void leave(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel server) || !(event.getEntity() instanceof RelayEntity relay)) return;
        Set<RelayEntity> relays = RELAYS.get(server);
        if (relays != null) relays.remove(relay);
    }

    /** Called after the original evaluate method has produced its unmodified direct result. */
    public static DroneSignalSample routeSample(Level level, AddonDroneEntity drone, Vec3 origin, DroneSignalSample direct) {
        if (!(level instanceof ServerLevel server) || origin == null || evaluatingNativeHop()) return direct;
        UUID owner = ownerId(drone);
        Double range = DroneSignalRules.INSTANCE.maxDistance(drone);
        if (owner == null || range == null || range <= 0) return direct;
        CachedLink cached = cached(server, drone, owner, origin, range);
        if (cached != null) return cached.link.sample;
        return calculate(server, drone, owner, origin, range, direct).sample;
    }

    /** Fresh at most every five ticks; a cached relay is checked for removal/power/ownership every call. */
    public static LinkInfo linkInfo(AddonDroneEntity drone, Vec3 origin) {
        if (!(drone.level() instanceof ServerLevel server) || origin == null) return new LinkInfo(0, 0, false, List.of());
        UUID owner = ownerId(drone);
        Double range = DroneSignalRules.INSTANCE.maxDistance(drone);
        if (owner == null || range == null || range <= 0) {
            DroneSignalSample direct = nativeSample(server, drone, origin, null);
            return info(Link.of(direct, List.of()));
        }
        CachedLink cached = cached(server, drone, owner, origin, range);
        Link link = cached != null ? cached.link
            : calculate(server, drone, owner, origin, range, nativeSample(server, drone, origin, null));
        return info(link);
    }

    public static boolean reachable(AddonDroneEntity drone, Vec3 origin) { return linkInfo(drone, origin).connected(); }

    /** UF normally caches quality for ten ticks. Check active routes each tick so destruction is immediate. */
    public static Boolean signalLostOverride(AddonDroneEntity drone, Vec3 origin) {
        if (!(drone.level() instanceof ServerLevel server) || origin == null || evaluatingNativeHop()) return null;
        Map<UUID, CachedLink> links = CACHE.get(server);
        CachedLink previous = links == null ? null : links.get(drone.getUUID());
        // Keep this fact after a broken route has been replaced with a disconnected direct
        // result; otherwise UF's older positive quality could briefly revive the dead route.
        if (previous == null || !previous.everRelayed) return null;
        return !reachable(drone, origin);
    }

    private static LinkInfo info(Link link) { return link.info; }

    private static CachedLink cached(ServerLevel level, AddonDroneEntity drone, UUID owner, Vec3 origin, double range) {
        Map<UUID, CachedLink> links = CACHE.get(level);
        CachedLink cached = links == null ? null : links.get(drone.getUUID());
        if (cached == null || level.getGameTime() < cached.tick || level.getGameTime() - cached.tick >= CACHE_TICKS
            || !owner.equals(cached.owner) || Double.compare(range, cached.range) != 0
            || cached.origin.distanceToSqr(origin) > ORIGIN_TOLERANCE_SQR
            || cached.targetPosition.distanceToSqr(drone.position()) > POSITION_TOLERANCE_SQR) return null;
        for (RelayEntity relay : cached.link.relays) if (!active(relay, level, owner)) return null;
        return cached;
    }

    private static Link calculate(ServerLevel level, AddonDroneEntity drone, UUID owner, Vec3 origin,
                                  double range, DroneSignalSample direct) {
        Link best = Link.of(direct, List.of());
        double bestQuality = direct.getQuality();
        // A perfect direct connection cannot be improved by a retransmission.
        if (bestQuality < RETRANSMIT_QUALITY && origin.distanceToSqr(drone.position()) <= MAX_PATH_DISTANCE * MAX_PATH_DISTANCE) {
            // Same candidate set as the former getEntitiesOfClass(origin box inflated by MAX_PATH_DISTANCE),
            // taken from the loaded-relay registry rather than walking every entity section in that box.
            AABB box = new AABB(origin, origin).inflate(MAX_PATH_DISTANCE);
            List<RelayEntity> relays = new ArrayList<>();
            Set<RelayEntity> loaded = RELAYS.get(level);
            if (loaded != null) for (Iterator<RelayEntity> iterator = loaded.iterator(); iterator.hasNext(); ) {
                RelayEntity relay = iterator.next();
                if (relay.isRemoved() || relay.level() != level) { iterator.remove(); continue; } // Missed leave event.
                // getEntity matches the former lookup's visibility: only entities in accessible sections.
                if (relay != drone && relay.getBoundingBox().intersects(box) && active(relay, level, owner)
                    && level.getEntity(relay.getUUID()) == relay) relays.add(relay);
            }
            relays.sort(Comparator.comparingDouble(relay -> origin.distanceTo(relay.position()) + relay.position().distanceTo(drone.position())));
            if (relays.size() > MAX_CANDIDATES) relays.subList(MAX_CANDIDATES, relays.size()).clear();
            // Repeaters cannot exceed the controlled drone's native per-hop radio budget.
            double hopRange = Math.min(range, RELAY_RANGE);
            Map<Edge, DroneSignalSample> samples = new HashMap<>();
            for (RelayEntity first : relays) {
                double firstDistance = origin.distanceTo(first.position());
                if (firstDistance + first.position().distanceTo(drone.position()) > MAX_PATH_DISTANCE) continue;
                DroneSignalSample firstHop = upstream(level, owner, origin, null, first, hopRange);
                if (firstHop.getQuality() * RETRANSMIT_QUALITY <= bestQuality + EPSILON) continue;
                DroneSignalSample finalHop = edge(level, first.position(), first, drone, hopRange, samples);
                double quality = Math.min(firstHop.getQuality(), finalHop.getQuality()) * RETRANSMIT_QUALITY;
                if (quality > bestQuality + EPSILON) {
                    best = relayed(origin, drone, quality, List.of(first), firstHop, finalHop);
                    bestQuality = quality;
                }
                if (firstHop.getQuality() * RETRANSMIT_QUALITY * RETRANSMIT_QUALITY <= bestQuality + EPSILON) continue;
                for (RelayEntity second : relays) {
                    if (second == first) continue;
                    if (firstDistance + first.position().distanceTo(second.position()) + second.position().distanceTo(drone.position()) > MAX_PATH_DISTANCE) continue;
                    DroneSignalSample middleHop = upstream(level, owner, first.position(), first, second, hopRange);
                    double upstream = Math.min(firstHop.getQuality(), middleHop.getQuality());
                    if (upstream * RETRANSMIT_QUALITY * RETRANSMIT_QUALITY <= bestQuality + EPSILON) continue;
                    DroneSignalSample lastHop = edge(level, second.position(), second, drone, hopRange, samples);
                    quality = Math.min(upstream, lastHop.getQuality()) * RETRANSMIT_QUALITY * RETRANSMIT_QUALITY;
                    if (quality > bestQuality + EPSILON) {
                        best = relayed(origin, drone, quality, List.of(first, second), firstHop, middleHop, lastHop);
                        bestQuality = quality;
                    }
                }
            }
        }
        Map<UUID, CachedLink> links = CACHE.computeIfAbsent(level, ignored -> new LinkedHashMap<>());
        CachedLink previous = links.remove(drone.getUUID());
        if (links.size() >= MAX_CACHED_DRONES) {
            links.entrySet().removeIf(entry -> level.getGameTime() - entry.getValue().tick >= 200);
            while (links.size() >= MAX_CACHED_DRONES) links.remove(links.keySet().iterator().next());
        }
        boolean everRelayed = !best.relays.isEmpty() || previous != null && previous.everRelayed && owner.equals(previous.owner);
        links.put(drone.getUUID(), new CachedLink(level.getGameTime(), owner, origin, drone.position(), range, best, everRelayed));
        return best;
    }

    /**
     * Operator-to-relay and relay-to-relay hops, shared across ticks and aircraft for EDGE_CACHE_TICKS.
     * A hop is reused only while both ends stay within the same tolerances the per-aircraft cache uses.
     */
    private static DroneSignalSample upstream(ServerLevel level, UUID owner, Vec3 from, RelayEntity sender,
                                              RelayEntity receiver, double range) {
        Map<UpstreamKey, CachedEdge> edges = EDGES.computeIfAbsent(level, ignored -> new HashMap<>());
        UpstreamKey key = new UpstreamKey(owner, sender == null ? null : sender.getUUID(), receiver.getUUID());
        long now = level.getGameTime();
        CachedEdge cached = edges.get(key);
        if (cached != null && now >= cached.tick && now - cached.tick < EDGE_CACHE_TICKS
            && Double.compare(range, cached.range) == 0
            && cached.from.distanceToSqr(from) <= (sender == null ? ORIGIN_TOLERANCE_SQR : POSITION_TOLERANCE_SQR)
            && cached.receiverPosition.distanceToSqr(receiver.position()) <= POSITION_TOLERANCE_SQR) return cached.sample;
        if (edges.size() >= MAX_CACHED_EDGES) {
            edges.values().removeIf(edge -> now < edge.tick || now - edge.tick >= EDGE_CACHE_TICKS);
            if (edges.size() >= MAX_CACHED_EDGES) edges.clear();
        }
        DroneSignalSample sample = nativeSample(level, receiver, from, range);
        edges.put(key, new CachedEdge(now, from, receiver.position(), range, sample));
        return sample;
    }

    private static DroneSignalSample edge(ServerLevel level, Vec3 origin, RelayEntity sender,
                                          AddonDroneEntity receiver, double range, Map<Edge, DroneSignalSample> samples) {
        Edge key = new Edge(sender == null ? null : sender.getUUID(), receiver.getUUID());
        return samples.computeIfAbsent(key, ignored -> nativeSample(level, receiver, origin, range));
    }

    private static DroneSignalSample nativeSample(ServerLevel level, AddonDroneEntity receiver, Vec3 origin, Double range) {
        NativeHop previous = NATIVE_HOP.get();
        NATIVE_HOP.set(new NativeHop(receiver, range));
        try {
            return DroneSignalModel.INSTANCE.evaluate(level, receiver, origin);
        } finally {
            if (previous == null) NATIVE_HOP.remove(); else NATIVE_HOP.set(previous);
        }
    }

    private static Link relayed(Vec3 origin, AddonDroneEntity target, double quality, List<RelayEntity> relays,
                                DroneSignalSample... hops) {
        DroneSignalSample weakest = hops[0];
        for (DroneSignalSample hop : hops) if (hop.getQuality() < weakest.getQuality()) weakest = hop;
        // Decode/re-encode at a powered repeater resets RF path loss. End-to-end quality is
        // the weakest native hop with a small retransmission cost; losses are not erased.
        DroneSignalSample sample = new DroneSignalSample(quality, origin.distanceTo(target.position()),
            weakest.getEffectiveDistanceBlocks(), weakest.getDistanceLossDb(), weakest.getObstructionLossDb(),
            weakest.getTotalLossDb(), weakest.getMaxLossDb(), weakest.getDirectLossDb(), weakest.getDiffractedLossDb(),
            weakest.getRaySegments(), weakest.getVisitedBlocks(), weakest.getRebFactor(), weakest.getRebLossDb());
        return Link.of(sample, List.copyOf(relays));
    }

    private static boolean connected(DroneSignalSample sample) { return Double.isFinite(sample.getQuality()) && sample.getQuality() > 0; }

    private static boolean active(RelayEntity relay, ServerLevel level, UUID owner) {
        return relay.level() == level && !relay.isRemoved() && relay.isAlive() && owner.equals(relay.ownerId()) && relay.isRelayActive();
    }

    private static UUID ownerId(AddonDroneEntity drone) {
        if (drone instanceof GeraniumEntity geranium) return geranium.ownerId();
        if (drone instanceof RelayEntity relay) return relay.ownerId();
        // UF can clear its transient controller while the server-owned durable owner remains.
        if (drone.getPersistentData().hasUUID("frontierIndustryVehicleOwner")) return drone.getPersistentData().getUUID("frontierIndustryVehicleOwner");
        String controller = drone.getEntityData().get(DroneEntity.CONTROLLER);
        try { return UUID.fromString(controller); } catch (IllegalArgumentException | NullPointerException ignored) { return null; }
    }

    public record LinkInfo(int relayHops, double quality, boolean connected, List<UUID> relays) {}
    private record NativeHop(AddonDroneEntity receiver, Double range) {}
    private record Edge(UUID sender, UUID receiver) {}
    private record UpstreamKey(UUID owner, UUID sender, UUID receiver) {}
    private record CachedEdge(long tick, Vec3 from, Vec3 receiverPosition, double range, DroneSignalSample sample) {}
    /** LinkInfo is immutable and built once per calculated route, so cache hits return it without allocating. */
    private record Link(DroneSignalSample sample, List<RelayEntity> relays, LinkInfo info) {
        static Link of(DroneSignalSample sample, List<RelayEntity> relays) {
            List<UUID> ids = new ArrayList<>(relays.size());
            for (RelayEntity relay : relays) ids.add(relay.getUUID());
            return new Link(sample, relays, new LinkInfo(relays.size(), sample.getQuality(), connected(sample), List.copyOf(ids)));
        }
    }
    private record CachedLink(long tick, UUID owner, Vec3 origin, Vec3 targetPosition, double range, Link link, boolean everRelayed) {}
}
