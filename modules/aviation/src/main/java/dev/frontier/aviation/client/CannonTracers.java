package dev.frontier.aviation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.frontier.aviation.weapons.CannonNetwork;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import org.joml.Matrix4f;

/**
 * Cannon tracers: each shot packet is flown here along the same gravity-bent path as the
 * server shell and drawn as one glowing streak (a camera-facing quad, interpolated between
 * ticks) instead of particles, so a long burst costs a few vertices per round per frame.
 * A tracer stops where it meets terrain, with a small puff, or burns out.
 */
public final class CannonTracers {
    /** Tracers burn out after ~3 s in the air, like real tracer composition. */
    private static final int MAX_TICKS = 60, MAX_TRACERS = 384;
    private static final double MAX_DRAW_DISTANCE = 768, MAX_LENGTH = 9, WIDTH = .09;
    private static final List<Flight> FLIGHTS = new ArrayList<>();

    private static final class Flight {
        Vec3 previous, position, velocity;
        final float gravity;
        int ticks;
        boolean ended;
        Flight(CannonNetwork.Tracer t) {
            position = new Vec3(t.x(), t.y(), t.z());
            previous = position;
            velocity = new Vec3(t.vx(), t.vy(), t.vz());
            gravity = t.gravity();
        }
    }

    private CannonTracers() {}

    public static void register() {
        CannonNetwork.installReceiver(tracer -> {
            if (FLIGHTS.size() < MAX_TRACERS) FLIGHTS.add(new Flight(tracer));
        });
        MinecraftForge.EVENT_BUS.addListener(CannonTracers::tick);
        MinecraftForge.EVENT_BUS.addListener(CannonTracers::render);
    }

    private static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var minecraft = Minecraft.getInstance();
        var level = minecraft.level;
        if (level == null) { FLIGHTS.clear(); return; }
        if (minecraft.isPaused()) return;
        for (Iterator<Flight> it = FLIGHTS.iterator(); it.hasNext(); ) {
            Flight f = it.next();
            if (f.ended) { it.remove(); continue; }
            Vec3 from = f.position, to = from.add(f.velocity);
            var hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, minecraft.player));
            f.previous = from;
            if (hit.getType() != HitResult.Type.MISS) {
                Vec3 at = hit.getLocation();
                f.position = at;
                f.ended = true; // Drawn up to the impact for one more tick.
                level.addAlwaysVisibleParticle(ParticleTypes.SMOKE, true, at.x, at.y, at.z, 0, .03, 0);
                continue;
            }
            f.position = to;
            f.velocity = f.velocity.add(0, -f.gravity, 0);
            if (++f.ticks > MAX_TICKS) it.remove();
        }
    }

    private static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || FLIGHTS.isEmpty()) return;
        Vec3 camera = event.getCamera().getPosition();
        float partial = event.getPartialTick();
        PoseStack pose = event.getPoseStack();
        var buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer out = buffers.getBuffer(RenderType.lightning());
        Matrix4f matrix = pose.last().pose();
        for (Flight f : FLIGHTS) {
            Vec3 head = f.previous.lerp(f.position, partial);
            if (head.distanceToSqr(camera) > MAX_DRAW_DISTANCE * MAX_DRAW_DISTANCE) continue;
            Vec3 back = f.position.subtract(f.previous);
            double length = back.length();
            if (length < 1.0E-4) continue;
            Vec3 dir = back.scale(1 / length);
            Vec3 tail = head.subtract(dir.scale(Math.min(MAX_LENGTH, length)));
            Vec3 toCamera = camera.subtract(head);
            Vec3 side = dir.cross(toCamera);
            if (side.lengthSqr() < 1.0E-8) continue;
            // Constant apparent width: a far tracer is still a visible line.
            double width = WIDTH * Math.max(1, Math.sqrt(toCamera.length()) / 4);
            side = side.normalize().scale(width);
            quad(out, matrix, head.subtract(camera), tail.subtract(camera), side);
        }
        buffers.endBatch(RenderType.lightning());
    }

    /** Bright yellow-white head fading to an orange tail. */
    private static void quad(VertexConsumer out, Matrix4f m, Vec3 head, Vec3 tail, Vec3 side) {
        out.vertex(m, (float) (head.x - side.x), (float) (head.y - side.y), (float) (head.z - side.z)).color(1f, .95f, .6f, 1f).endVertex();
        out.vertex(m, (float) (head.x + side.x), (float) (head.y + side.y), (float) (head.z + side.z)).color(1f, .95f, .6f, 1f).endVertex();
        out.vertex(m, (float) (tail.x + side.x), (float) (tail.y + side.y), (float) (tail.z + side.z)).color(1f, .45f, .1f, 0f).endVertex();
        out.vertex(m, (float) (tail.x - side.x), (float) (tail.y - side.y), (float) (tail.z - side.z)).color(1f, .45f, .1f, 0f).endVertex();
    }
}
