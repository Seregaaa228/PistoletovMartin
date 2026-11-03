package dev.frontier.aviation.weapons;

import com.atsuishio.superbwarfare.entity.projectile.SmallCannonShellEntity;
import dev.frontier.aviation.LightAviation;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.Comparator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Aircraft cannon shells flown by the server itself, not as entities, like DeepStrike's
 * missile flights. An entity shell only moves inside the simulation distance (6 chunks in
 * a typical world), so a shell fired at a house 100+ blocks away froze in the air and only
 * hit once the jet flew close. These shells advance every tick in any loaded chunk, so they
 * reach everything the pilot can see.
 *
 * The native shell is kept, never added to the world: at impact it is placed on the hit
 * point and runs its own hit, so damage, the blast and AircraftCannon's block rules stay
 * exactly the native ones. Clients draw a tracer from one packet per shot (CannonNetwork).
 */
@Mod.EventBusSubscriber(modid = LightAviation.ID)
public final class CannonShells {
    /** Native shells blow up 1024 blocks from their owner; also a bound for strays. */
    private static final double MAX_DISTANCE = 1024;
    private static final int MAX_TICKS = 400;
    /**
     * A shell's path may cross chunks the server has not loaded (a target seen only through
     * a far-render mod, or beyond a small view distance): they are loaded in the background
     * with a short-lived ticket while the shell waits, never synchronously on the tick.
     */
    private static final TicketType<ChunkPos> TICKET = TicketType.create("frontier_aviation_cannon",
        Comparator.comparingLong(ChunkPos::toLong), 200);
    private static final int MAX_WAIT_TICKS = 100;
    /** Players this far from an impact see a forced puff; nearer ones see the native blast. */
    private static final double FAR_PUFF_FROM = 32, FAR_PUFF_TO = 512;

    private static final class Shell {
        final SmallCannonShellEntity entity;
        final Entity plane;
        final Vec3 origin;
        final float gravity;
        Vec3 position, velocity;
        int ticks, waiting;

        Shell(SmallCannonShellEntity entity, Entity plane) {
            this.entity = entity;
            this.plane = plane;
            origin = entity.position();
            position = origin;
            velocity = entity.getDeltaMovement();
            gravity = entity.getCustomGravity();
        }
    }

    private static final Map<ServerLevel, List<Shell>> FLYING = new WeakHashMap<>();
    /** Diagnostics: impacts, shells given up while waiting for terrain, and spent in the air. */
    public static volatile long impacts, waitedOut, spent;

    private CannonShells() {}

    private static final String AIRCRAFT_ROUND = "frontierAircraftRound";

    /** A shell flown here (it shows the light impact effect, see ControlledAircraftCannon). */
    public static boolean isAircraftRound(SmallCannonShellEntity shell) {
        return shell.getPersistentData().getBoolean(AIRCRAFT_ROUND);
    }

    /** Takes over a native aircraft shell before it joins the world; it never becomes an entity. */
    public static void launch(ServerLevel level, SmallCannonShellEntity shell, Entity plane) {
        shell.getPersistentData().putBoolean(AIRCRAFT_ROUND, true);
        Shell flight = new Shell(shell, plane);
        FLYING.computeIfAbsent(level, ignored -> new ArrayList<>()).add(flight);
        CannonNetwork.tracer(plane, flight.origin, flight.velocity, flight.gravity);
    }

    @SubscribeEvent
    public static void tick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;
        List<Shell> shells = FLYING.get(level);
        if (shells == null || shells.isEmpty()) return;
        for (Iterator<Shell> it = shells.iterator(); it.hasNext(); ) {
            if (advance(level, it.next())) it.remove();
        }
    }

    /** One tick of flight; true once the shell is gone. */
    private static boolean advance(ServerLevel level, Shell shell) {
        Vec3 from = shell.position, to = from.add(shell.velocity);
        if (++shell.ticks > MAX_TICKS || to.distanceToSqr(shell.origin) > MAX_DISTANCE * MAX_DISTANCE) {
            spent++;
            return true;
        }
        if (!pathLoaded(level, from, to)) {
            shell.ticks--; // Waiting for terrain is not flight time.
            if (++shell.waiting <= MAX_WAIT_TICKS) return false;
            waitedOut++;
            return true;
        }
        Entity owner = shell.entity.getOwner();
        BlockHitResult block = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY,
            owner != null ? owner : shell.plane));
        Vec3 end = block.getType() == HitResult.Type.MISS ? to : block.getLocation();
        EntityHitResult entity = ProjectileUtil.getEntityHitResult(level, shell.plane, from, end,
            new AABB(from, end).inflate(1), target -> hittable(shell, target), 0.3F);
        HitResult hit = entity != null ? entity : block.getType() == HitResult.Type.MISS ? null : block;
        if (hit == null) {
            shell.position = to;
            shell.velocity = shell.velocity.add(0, -shell.gravity, 0);
            return false;
        }
        impact(level, shell, hit);
        impacts++;
        return true;
    }

    /** True when every chunk along the segment is loaded; asks for the missing ones. */
    private static boolean pathLoaded(ServerLevel level, Vec3 from, Vec3 to) {
        boolean loaded = true;
        int steps = Math.max(1, (int) Math.ceil(from.distanceTo(to) / 8));
        long previous = Long.MIN_VALUE;
        for (int i = 0; i <= steps; i++) {
            ChunkPos chunk = new ChunkPos(BlockPos.containing(from.lerp(to, (double) i / steps)));
            if (chunk.toLong() == previous) continue;
            previous = chunk.toLong();
            if (!level.hasChunk(chunk.x, chunk.z)) {
                level.getChunkSource().addRegionTicket(TICKET, chunk, 0, chunk);
                loaded = false;
            }
        }
        return loaded;
    }

    private static boolean hittable(Shell shell, Entity target) {
        if (!target.isAlive() || target.isSpectator() || !target.isPickable()) return false;
        // Never the firing jet, its crew or anything riding with it.
        return target.getRootVehicle() != shell.plane.getRootVehicle();
    }

    private static void impact(ServerLevel level, Shell shell, HitResult hit) {
        SmallCannonShellEntity shot = shell.entity;
        Vec3 at = hit.getLocation();
        shot.setPos(at.x, at.y, at.z);
        shot.setDeltaMovement(shell.velocity);
        // Native afterHitEntity only explodes once the shell has ticked.
        shot.tickCount = Math.max(1, shell.ticks);
        shot.onHit(hit);
        farPuff(level, at);
    }

    private static void farPuff(ServerLevel level, Vec3 at) {
        for (ServerPlayer player : level.players()) {
            double distance = player.position().distanceTo(at);
            if (distance < FAR_PUFF_FROM || distance > FAR_PUFF_TO) continue;
            level.sendParticles(player, ParticleTypes.EXPLOSION, true, at.x, at.y, at.z, 1, 0, 0, 0, 0);
            level.sendParticles(player, ParticleTypes.LARGE_SMOKE, true, at.x, at.y + .5, at.z, 4, .4, .4, .4, .02);
        }
    }

    /** Shells in flight in a level, for tests. */
    public static int flying(ServerLevel level) {
        List<Shell> shells = FLYING.get(level);
        return shells == null ? 0 : shells.size();
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) { FLYING.clear(); }
}
