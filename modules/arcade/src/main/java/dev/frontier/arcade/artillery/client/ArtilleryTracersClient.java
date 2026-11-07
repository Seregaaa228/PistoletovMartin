package dev.frontier.arcade.artillery.client;

import dev.frontier.arcade.ArcadeVehicles;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import com.atsuishio.superbwarfare.entity.projectile.MediumRocketEntity;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Flies parked rounds on the client so they stay visible: SBW's stepping (move, then gravity), one trail per round.
 * A rocket shows a long smoke trail (its motor jet is drawn by RocketJets); a shell only a faint streak, as a real shell shows. Trails
 * are drawn only within sight of the camera and stop at the ground, on the server's word, or after a minute.
 */
@Mod.EventBusSubscriber(modid = ArcadeVehicles.ID, value = Dist.CLIENT)
public final class ArtilleryTracersClient {
    private static final double SIGHT_SQ = 512 * 512;
    private static final int MAX_AGE = 1200, MAX_TRACERS = 256;
    private static final List<Tracer> TRACERS = new ArrayList<>();
    /** SBW rockets in flight near this client: they get a motor flame at the tail (SBW draws only their smoke). */
    private static final List<MediumRocketEntity> ROCKETS = new ArrayList<>();
    private static final double FLAME_SIGHT_SQ = 256 * 256;
    /** Rockets whose launch backblast was already drawn. */
    private static final java.util.Set<MediumRocketEntity> LAUNCHED = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    /** Distance from a rocket leaving the tube to the back of the Grad's pack, where the backblast comes out. */
    private static final double PACK_LENGTH = 4.6;

    private static final class Tracer {
        final int id;
        final boolean rocket;
        final float gravity;
        Vec3 previous, position, velocity;
        int age;

        Tracer(int id, Vec3 position, Vec3 velocity, float gravity, boolean rocket) {
            this.id = id; this.previous = position; this.position = position; this.velocity = velocity;
            this.gravity = gravity; this.rocket = rocket;
        }
    }

    private ArtilleryTracersClient() { }

    public static void start(int id, Vec3 position, Vec3 velocity, float gravity, boolean rocket) {
        if (TRACERS.size() < MAX_TRACERS) TRACERS.add(new Tracer(id, position, velocity, gravity, rocket));
    }

    public static void end(int id) {
        TRACERS.removeIf(tracer -> tracer.id == id);
    }

    @SubscribeEvent
    public static void join(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() && event.getEntity() instanceof MediumRocketEntity rocket && ROCKETS.size() < MAX_TRACERS)
            ROCKETS.add(rocket);
    }

    /**
     * Per live rocket: the launch backblast once (fire and smoke blown out of the back of the pack, as from a real
     * Grad), then a smoke puff behind it each tick. The motor jet itself is drawn by {@link RocketJets}.
     */
    private static void flames(net.minecraft.client.multiplayer.ClientLevel level, Vec3 camera) {
        var random = level.getRandom();
        ROCKETS.removeIf(rocket -> {
            if (rocket.isRemoved() || rocket.level() != level) return true;
            Vec3 motion = rocket.getDeltaMovement();
            if (motion.lengthSqr() < 0.25 || rocket.position().distanceToSqr(camera) > FLAME_SIGHT_SQ) return false;
            Vec3 ahead = motion.normalize();
            if (rocket.tickCount < 6 && LAUNCHED.add(rocket)) {
                Vec3 rear = rocket.position().subtract(ahead.scale(PACK_LENGTH + rocket.tickCount * motion.length()));
                for (int i = 0; i < 14; i++) {
                    Vec3 v = ahead.scale(-0.45 - random.nextDouble() * 0.5).add(random.nextGaussian() * 0.06, random.nextGaussian() * 0.06, random.nextGaussian() * 0.06);
                    level.addParticle(i % 3 == 0 ? ParticleTypes.LAVA : ParticleTypes.FLAME, true, rear.x, rear.y, rear.z, v.x, v.y, v.z);
                }
                for (int i = 0; i < 8; i++) {
                    Vec3 v = ahead.scale(-0.15 - random.nextDouble() * 0.2).add(random.nextGaussian() * 0.04, 0.03 + random.nextDouble() * 0.04, random.nextGaussian() * 0.04);
                    level.addParticle(ParticleTypes.LARGE_SMOKE, true, rear.x, rear.y, rear.z, v.x, v.y, v.z);
                }
            } else {
                LAUNCHED.add(rocket);
            }
            Vec3 tail = rocket.position().subtract(ahead.scale(1.4));
            level.addParticle(ParticleTypes.SMOKE, true, tail.x, tail.y, tail.z, 0, 0.02, 0);
            return false;
        });
    }

    /** Live rockets and parked rockets in flight, for {@link RocketJets}: position this frame and flight direction. */
    static void forEachJet(float partial, JetSink sink) {
        for (MediumRocketEntity rocket : ROCKETS) {
            if (rocket.isRemoved()) continue;
            Vec3 motion = rocket.getDeltaMovement();
            if (motion.lengthSqr() < 0.25) continue;
            sink.accept(rocket.getPosition(partial), motion.normalize(), rocket.getId());
        }
        for (Tracer tracer : TRACERS)
            if (tracer.rocket) sink.accept(tracer.previous.lerp(tracer.position, partial), tracer.velocity.normalize(), tracer.id);
    }

    interface JetSink { void accept(Vec3 position, Vec3 direction, int seed); }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || TRACERS.isEmpty() && ROCKETS.isEmpty()) return;
        var mc = Minecraft.getInstance();
        var level = mc.level;
        if (level == null || mc.isPaused()) return;
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        flames(level, camera);
        TRACERS.removeIf(tracer -> {
            Vec3 from = tracer.position;
            tracer.previous = from;
            tracer.position = from.add(tracer.velocity);
            tracer.velocity = tracer.velocity.add(0, -tracer.gravity, 0);
            if (++tracer.age > MAX_AGE || tracer.position.y < level.getMinBuildHeight()) return true;
            var column = level.getChunkSource().getChunkNow((int) Math.floor(tracer.position.x) >> 4, (int) Math.floor(tracer.position.z) >> 4);
            if (column != null && tracer.position.y <= column.getHeight(Heightmap.Types.MOTION_BLOCKING,
                (int) Math.floor(tracer.position.x) & 15, (int) Math.floor(tracer.position.z) & 15) + 1) return true;
            if (tracer.position.distanceToSqr(camera) > SIGHT_SQ) return false;
            if (tracer.rocket) {
                // A puff every ~4 blocks so a fast rocket leaves an unbroken line; at most four per tick.
                int puffs = (int) Math.min(4, Math.ceil(tracer.velocity.length() / 4));
                for (int i = 0; i < puffs; i++) {
                    Vec3 at = from.lerp(tracer.position, (double) i / puffs);
                    level.addParticle(ParticleTypes.LARGE_SMOKE, true, at.x, at.y, at.z, 0, 0.01, 0);
                }
            } else if (tracer.age % 2 == 0) {
                level.addParticle(ParticleTypes.SMOKE, true, tracer.position.x, tracer.position.y, tracer.position.z, 0, 0, 0);
            }
            return false;
        });
    }

    @SubscribeEvent
    public static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        TRACERS.clear();
        ROCKETS.clear();
    }
}
