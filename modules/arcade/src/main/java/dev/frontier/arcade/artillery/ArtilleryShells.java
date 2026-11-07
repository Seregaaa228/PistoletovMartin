package dev.frontier.arcade.artillery;

import com.atsuishio.superbwarfare.entity.projectile.CannonShellEntity;
import com.atsuishio.superbwarfare.entity.projectile.FastThrowableProjectile;
import com.atsuishio.superbwarfare.entity.projectile.MediumRocketEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.SpArtilleryEntity;
import dev.frontier.arcade.ArcadeVehicles;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Far flight for self-propelled-gun shells and launcher rockets, DeepStrike style: no chunk is loaded along the path. While the shell
 * flies over active terrain it is SBW's own entity; before it would leave entity-ticking chunks the server parks it
 * and steps the same motion itself (position, then gravity), reading terrain from loaded heightmaps or the world
 * generator. About {@link #ARRIVAL_TICKS} ticks before it lands a short-lived 3x3 ticket wakes the impact area and the
 * real SBW shell returns there, so the hit, damage, crater and effects stay native.
 */
@Mod.EventBusSubscriber(modid = ArcadeVehicles.ID)
public final class ArtilleryShells {
    public static final String TAG = "frontier_artillery_shell";
    /** Ticks of look-ahead before a shell is parked: SBW sweeps one step per tick and must not reach a frozen chunk. */
    private static final int PARK_LOOKAHEAD = 3;
    /** Lead for the impact-area ticket; chunk loading from disk or generation needs a moment. */
    private static final int ARRIVAL_TICKS = 30;
    private static final int TICKET_LIFESPAN = 200;
    /** A fresh shell this close to a self-propelled gun left its barrel (the PLZ-05 muzzle is ~10 blocks out). */
    private static final double GUN_REACH = 14;
    private static final TicketType<ChunkPos> IMPACT = TicketType.create(ArcadeVehicles.ID + ":artillery_impact",
        Comparator.comparingLong(ChunkPos::toLong), TICKET_LIFESPAN);
    private static final Map<ServerLevel, List<Flight>> FLIGHTS = new IdentityHashMap<>();
    private static final Map<ServerLevel, List<FastThrowableProjectile>> LIVE = new IdentityHashMap<>();

    private ArtilleryShells() { }

    /** A parked shell: its saved SBW state plus the motion SBW would have applied. */
    private static final class Flight {
        final CompoundTag shell;
        final float gravity;
        final int life;
        Vec3 position, velocity;
        int age;
        /** Chunk whose 3x3 area was last woken for this shell, or null. */
        ChunkPos woken;
        /** Id of the trail nearby clients fly for it meanwhile. */
        int tracer;

        Flight(CompoundTag shell, float gravity, int life, int age, Vec3 position, Vec3 velocity) {
            this.shell = shell; this.gravity = gravity; this.life = life; this.age = age;
            this.position = position; this.velocity = velocity;
        }
    }

    /** The rounds a self-propelled gun or launcher fires: SBW cannon shells and medium rockets. */
    public static boolean round(Entity entity) {
        return entity instanceof CannonShellEntity || entity instanceof MediumRocketEntity;
    }

    public static boolean tagged(FastThrowableProjectile shell) {
        return shell.getPersistentData().getBoolean(TAG);
    }

    @SubscribeEvent
    public static void join(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !round(event.getEntity())
            || !(event.getEntity() instanceof FastThrowableProjectile shell)) return;
        if (!tagged(shell)) {
            SpArtilleryEntity gun = shell.getOwner() != null && shell.getOwner().getVehicle() instanceof SpArtilleryEntity seated ? seated
                : level.getEntitiesOfClass(SpArtilleryEntity.class, shell.getBoundingBox().inflate(GUN_REACH)).stream().findFirst().orElse(null);
            if (gun == null) return;
            shell.getPersistentData().putBoolean(TAG, true);
            ArtilleryAim.trueUp(level, gun, shell);
            ArtilleryBlast.launch(level, gun, shell, shell instanceof MediumRocketEntity);
        }
        LIVE.computeIfAbsent(level, key -> new ArrayList<>()).add(shell);
    }

    @SubscribeEvent
    public static void tick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.START || !(event.level instanceof ServerLevel level)) return;
        var live = LIVE.get(level);
        if (live != null) live.removeIf(shell -> !shell.isAlive() || park(level, shell));
        var flights = FLIGHTS.get(level);
        if (flights != null) flights.removeIf(flight -> {
            if (!step(level, flight)) return false;
            ArtilleryTracers.end(level, flight.tracer, flight.position);
            return true;
        });
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) {
        FLIGHTS.clear();
        LIVE.clear();
    }

    /**
     * Parks a live shell whose next steps leave entity-ticking terrain before it meets the ground; true when it was
     * taken over. A shell about to land inside active terrain stays SBW's.
     */
    private static boolean park(ServerLevel level, FastThrowableProjectile shell) {
        Vec3 velocity = shell.getDeltaMovement();
        if (!leavesActiveTerrain(level, shell.position(), velocity, shell.getCustomGravity())) return false;
        var saved = new CompoundTag();
        if (!shell.save(saved)) return false;
        saved.remove("UUID");
        var flight = new Flight(saved, shell.getCustomGravity(), shell.getLife(), shell.tickCount, shell.position(), velocity);
        shell.discard();
        flight.tracer = ArtilleryTracers.start(level, flight.position, velocity, flight.gravity, shell instanceof MediumRocketEntity);
        FLIGHTS.computeIfAbsent(level, key -> new ArrayList<>()).add(flight);
        return true;
    }

    /** True when the next steps reach a chunk that does not tick entities before they reach the ground. */
    private static boolean leavesActiveTerrain(ServerLevel level, Vec3 position, Vec3 velocity, float gravity) {
        for (int i = 0; i < PARK_LOOKAHEAD; i++) {
            position = position.add(velocity);
            velocity = velocity.add(0, -gravity, 0);
            if (position.y <= surface(level, position) + 1) return false;
            if (!level.isPositionEntityTicking(BlockPos.containing(position))) return true;
        }
        return false;
    }

    /** One server tick of a parked flight; true when it ended (returned to the world or expired). */
    private static boolean step(ServerLevel level, Flight flight) {
        if (++flight.age > flight.life || flight.position.y < level.getMinBuildHeight()
            || !level.getWorldBorder().isWithinBounds(BlockPos.containing(flight.position))) return true;
        // The shell's own chunk is awake (the woken arrival area or a player's terrain): SBW flies it from here.
        if (level.isPositionEntityTicking(BlockPos.containing(flight.position))
            && !leavesActiveTerrain(level, flight.position, flight.velocity, flight.gravity)) return land(level, flight);
        if (flight.woken == null && flight.velocity.y < 0) {
            Vec3 arrival = flight.position;
            Vec3 velocity = flight.velocity;
            for (int i = 0; i < ARRIVAL_TICKS; i++) {
                arrival = arrival.add(velocity);
                velocity = velocity.add(0, -flight.gravity, 0);
                if (arrival.y <= surface(level, arrival) + 1) {
                    // Centre one step short of the ground so the 3x3 area also holds the approach.
                    wake(level, flight, arrival.subtract(velocity));
                    break;
                }
            }
        }
        Vec3 next = flight.position.add(flight.velocity);
        // Terrain is waking but not ticking yet: hold just above the ground instead of passing through it.
        if (next.y <= surface(level, next) + 1) {
            wake(level, flight, flight.position);
            return false;
        }
        flight.position = next;
        flight.velocity = flight.velocity.add(0, -flight.gravity, 0);
        return false;
    }

    /** Entity-ticking 3x3 chunks around a point for a few seconds; the ticket expires by itself. */
    private static void wake(ServerLevel level, Flight flight, Vec3 point) {
        var chunk = new ChunkPos(BlockPos.containing(point));
        if (chunk.equals(flight.woken)) return;
        level.getChunkSource().addRegionTicket(IMPACT, chunk, 3, chunk);
        flight.woken = chunk;
    }

    private static int surface(ServerLevel level, Vec3 point) {
        return ArtilleryHeights.surface(level, (int) Math.floor(point.x), (int) Math.floor(point.z));
    }

    private static boolean land(ServerLevel level, Flight flight) {
        var entity = EntityType.loadEntityRecursive(flight.shell, level, loaded -> {
            loaded.moveTo(flight.position.x, flight.position.y, flight.position.z, loaded.getYRot(), loaded.getXRot());
            return loaded;
        });
        if (!(entity instanceof FastThrowableProjectile shell)) return true;
        shell.setDeltaMovement(flight.velocity);
        // SBW does not save gravity; the shell would otherwise return with its class default.
        shell.setCustomGravity(flight.gravity);
        level.addFreshEntity(shell);
        return true;
    }
}
