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
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Flies parked rounds on the client so they stay visible: SBW's stepping (move, then gravity), one trail per round.
 * A rocket shows its motor flame and a long smoke trail; a shell only a faint streak, as a real shell shows. Trails
 * are drawn only within sight of the camera and stop at the ground, on the server's word, or after a minute.
 */
@Mod.EventBusSubscriber(modid = ArcadeVehicles.ID, value = Dist.CLIENT)
public final class ArtilleryTracersClient {
    private static final double SIGHT_SQ = 512 * 512;
    private static final int MAX_AGE = 1200, MAX_TRACERS = 256;
    private static final List<Tracer> TRACERS = new ArrayList<>();

    private static final class Tracer {
        final int id;
        final boolean rocket;
        final float gravity;
        Vec3 position, velocity;
        int age;

        Tracer(int id, Vec3 position, Vec3 velocity, float gravity, boolean rocket) {
            this.id = id; this.position = position; this.velocity = velocity; this.gravity = gravity; this.rocket = rocket;
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
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || TRACERS.isEmpty()) return;
        var mc = Minecraft.getInstance();
        var level = mc.level;
        if (level == null || mc.isPaused()) return;
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        TRACERS.removeIf(tracer -> {
            Vec3 from = tracer.position;
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
                level.addParticle(ParticleTypes.FLAME, true, tracer.position.x, tracer.position.y, tracer.position.z, 0, 0, 0);
            } else if (tracer.age % 2 == 0) {
                level.addParticle(ParticleTypes.SMOKE, true, tracer.position.x, tracer.position.y, tracer.position.z, 0, 0, 0);
            }
            return false;
        });
    }

    @SubscribeEvent
    public static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        TRACERS.clear();
    }
}
