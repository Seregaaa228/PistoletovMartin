package dev.frontier.arcade.geranium;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * Chunk tickets ahead of a fast station aircraft, never on terrain that was not generated yet.
 *
 * <p>Each flying aircraft keeps its own chunk entity-ticking and, along its current velocity,
 * the chunks of the next {@link #NEAR_TICKS} ticks entity-ticking too (so it never freezes on a
 * chunk border) and those up to {@link #AHEAD_TICKS} ticks ahead merely loaded from disk. A ticket
 * of radius r loads the chunks within r to full status, so it is placed only when all of them are
 * loaded already or saved as fully generated; the region file is scanned asynchronously for just
 * that field. The aircraft itself only enters chunks whose entity-ticking neighbourhood
 * ({@link #NEAR_RADIUS}) is generated; anything else is a wall for it.
 */
public final class FlightChunks {
    public static final TicketType<UUID> FLIGHT = TicketType.create("frontier_geranium_flight", UUID::compareTo, 40);
    static final int NEAR_TICKS = 12, AHEAD_TICKS = 60, MAX_AHEAD = 12, NEAR_RADIUS = 2, FAR_RADIUS = 0;
    private static final int STATUS_TTL = 600, MAX_STATUS = 20000;
    /** Per level: chunk -> (generated, game time of the answer). Written from the IO worker as well. */
    private static final Map<ServerLevel, Map<Long, Status>> STATUS = new WeakHashMap<>();
    private static final Map<UUID, Held> HELD = new HashMap<>();

    private record Status(boolean generated, long tick) {}
    private record Held(ServerLevel level, Map<Long, Integer> tickets) {}

    private FlightChunks() { }

    /** Loaded chunks are generated; otherwise the saved status, scanned once and remembered for 30 s. */
    public static Boolean generated(ServerLevel level, int chunkX, int chunkZ) {
        if (level.getChunkSource().getChunkNow(chunkX, chunkZ) != null) return true;
        Map<Long, Status> known;
        synchronized (STATUS) { known = STATUS.computeIfAbsent(level, ignored -> new ConcurrentHashMap<>()); }
        long key = ChunkPos.asLong(chunkX, chunkZ), now = level.getGameTime();
        Status status = known.get(key);
        if (status != null && (status.tick < 0 || now - status.tick < STATUS_TTL)) return status.tick < 0 ? null : status.generated;
        if (known.size() > MAX_STATUS) known.clear();
        known.put(key, new Status(false, -1)); // scan in flight
        CollectFields fields = new CollectFields(new FieldSelector(StringTag.TYPE, "Status"));
        level.getChunkSource().chunkMap.chunkScanner().scanChunk(new ChunkPos(chunkX, chunkZ), fields).whenComplete((ignored, failure) -> {
            boolean full = false;
            if (failure == null && fields.getResult() instanceof CompoundTag tag) {
                String saved = tag.getString("Status");
                full = saved.equals("full") || saved.equals("minecraft:full");
            }
            known.put(key, new Status(full, now));
        });
        return null;
    }

    /** TRUE when every chunk within radius is generated, FALSE when one is known not to be, else unknown. */
    public static Boolean generatedAround(ServerLevel level, int chunkX, int chunkZ, int radius) {
        Boolean all = true;
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            Boolean one = generated(level, chunkX + dx, chunkZ + dz);
            if (Boolean.FALSE.equals(one)) return false;
            if (one == null) all = null;
        }
        return all;
    }

    /** Whether the aircraft may enter the chunk at pos: TRUE, FALSE (a wall) or unknown yet (wait). */
    public static Boolean enterable(ServerLevel level, BlockPos pos) {
        if (!level.getWorldBorder().isWithinBounds(pos)) return false;
        return generatedAround(level, pos.getX() >> 4, pos.getZ() >> 4, NEAR_RADIUS);
    }

    /** Called every server tick for each flying aircraft; refreshes or moves its tickets. */
    public static void hold(ServerLevel level, GeraniumEntity aircraft) {
        Map<Long, Integer> wanted = new HashMap<>();
        ChunkPos own = aircraft.chunkPosition();
        wanted.put(own.toLong(), NEAR_RADIUS);
        Vec3 velocity = aircraft.getDeltaMovement(), at = aircraft.position();
        double step = Math.max(.25, velocity.horizontalDistance());
        int ahead = 0;
        // Sample every ~8 blocks along the path; stop at the first chunk that is not generated (a wall).
        for (int tick = 1; tick <= AHEAD_TICKS && ahead < MAX_AHEAD; tick += Math.max(1, (int) (8 / step))) {
            Vec3 point = at.add(velocity.scale(tick));
            int x = ((int) Math.floor(point.x)) >> 4, z = ((int) Math.floor(point.z)) >> 4;
            long key = ChunkPos.asLong(x, z);
            if (wanted.containsKey(key)) continue;
            int radius = tick <= NEAR_TICKS ? NEAR_RADIUS : FAR_RADIUS;
            if (!level.getWorldBorder().isWithinBounds(new ChunkPos(x, z)) || !Boolean.TRUE.equals(generatedAround(level, x, z, radius))) break;
            wanted.put(key, radius);
            ahead++;
        }
        Held held = HELD.get(aircraft.getUUID());
        if (held != null && held.level != level) { release(aircraft.getUUID()); held = null; }
        Map<Long, Integer> previous = held == null ? Map.of() : held.tickets;
        UUID id = aircraft.getUUID();
        for (var entry : previous.entrySet())
            if (!entry.getValue().equals(wanted.get(entry.getKey())))
                level.getChunkSource().removeRegionTicket(FLIGHT, new ChunkPos(entry.getKey()), entry.getValue(), id);
        // Re-adding refreshes the 40-tick lifetime; a crash or missed release cannot pin chunks for long.
        for (var entry : wanted.entrySet())
            level.getChunkSource().addRegionTicket(FLIGHT, new ChunkPos(entry.getKey()), entry.getValue(), id);
        HELD.put(id, new Held(level, wanted));
    }

    public static void release(UUID id) {
        Held held = HELD.remove(id);
        if (held == null) return;
        for (var entry : held.tickets.entrySet())
            held.level.getChunkSource().removeRegionTicket(FLIGHT, new ChunkPos(entry.getKey()), entry.getValue(), id);
    }

    public static int heldTickets(UUID id) { Held held = HELD.get(id); return held == null ? 0 : held.tickets.size(); }

    public static void clear() { HELD.clear(); synchronized (STATUS) { STATUS.clear(); } }
}
