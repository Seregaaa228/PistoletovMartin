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
    private static final double MAX_DRAW_DISTANCE = 768, MAX_LENGTH = 14, WIDTH = .1, NEAR_CLIP = 6;
    /** Width grows with distance so a far tracer stays a 2-3 pixel line. */
    private static final double WIDTH_PER_BLOCK = .0035;
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
        // Plain translucent, not additive: an additive streak washes out to white on a bright sky.
        VertexConsumer out = buffers.getBuffer(RenderType.debugQuads());
        Matrix4f matrix = pose.last().pose();
        for (Flight f : FLIGHTS) {
            Vec3 head = f.previous.lerp(f.position, partial);
            if (head.distanceToSqr(camera) > MAX_DRAW_DISTANCE * MAX_DRAW_DISTANCE) continue;
            Vec3 back = f.position.subtract(f.previous);
            double length = back.length();
            if (length < 1.0E-4) continue;
            Vec3 dir = back.scale(1 / length);
            Vec3 tail = head.subtract(dir.scale(Math.min(MAX_LENGTH, length)));
            // Not right at the eye: a round leaving the pilot's own gun otherwise smears across the view.
            if (head.distanceToSqr(camera) < NEAR_CLIP * NEAR_CLIP) continue;
            if (tail.distanceToSqr(camera) < NEAR_CLIP * NEAR_CLIP) tail = camera.add(head.subtract(camera).normalize().scale(NEAR_CLIP));
            Vec3 toCamera = camera.subtract(head);
            Vec3 side = dir.cross(toCamera);
            if (side.lengthSqr() < 1.0E-8) continue;
            double width = Math.max(WIDTH, toCamera.length() * WIDTH_PER_BLOCK);
            side = side.normalize().scale(width);
            quad(out, matrix, head.subtract(camera), tail.subtract(camera), side);
        }
        buffers.endBatch(RenderType.debugQuads());
    }

    /** Bright yellow head fading to a translucent orange tail; the render type draws both sides. */
    private static void quad(VertexConsumer out, Matrix4f m, Vec3 head, Vec3 tail, Vec3 side) {
        corner(out, m, head.subtract(side), true);
        corner(out, m, head.add(side), true);
        corner(out, m, tail.add(side), false);
        corner(out, m, tail.subtract(side), false);
    }

    private static void corner(VertexConsumer out, Matrix4f m, Vec3 p, boolean head) {
        if (head) out.vertex(m, (float) p.x, (float) p.y, (float) p.z).color(1f, .9f, .35f, 1f).endVertex();
        else out.vertex(m, (float) p.x, (float) p.y, (float) p.z).color(1f, .45f, .05f, .25f).endVertex();
    }
}
