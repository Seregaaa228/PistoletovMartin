package dev.frontier.flamingofix.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * A short-lived fireball and ground shock ring for a large missile blast: one additive
 * mesh each (about 180 two-sided quads), no textures, lights or per-frame particles.
 */
public final class BlastFireball {
    private record Blast(Vec3 center, float radius, long born) { }
    private static final List<Blast> ACTIVE = new ArrayList<>();
    private static final int MAX = 8, FIRE_TICKS = 34, RING_TICKS = 26, SEGMENTS = 16, RINGS = 8;

    public static void add(Vec3 center, float radius, long gameTime) {
        if (ACTIVE.size() >= MAX) ACTIVE.remove(0);
        ACTIVE.add(new Blast(center, radius, gameTime));
    }

    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || ACTIVE.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) { ACTIVE.clear(); return; }
        float now = mc.level.getGameTime() + event.getPartialTick();
        ACTIVE.removeIf(blast -> now - blast.born() > Math.max(FIRE_TICKS, RING_TICKS) || now < blast.born() - 1);
        if (ACTIVE.isEmpty()) return;
        Vec3 camera = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer out = buffers.getBuffer(RenderType.lightning());
        PoseStack pose = event.getPoseStack();
        for (Blast blast : ACTIVE) {
            float age = now - blast.born();
            pose.pushPose();
            pose.translate(blast.center().x - camera.x, blast.center().y - camera.y, blast.center().z - camera.z);
            Matrix4f matrix = pose.last().pose();
            if (age < FIRE_TICKS) fireball(out, matrix, blast.radius(), age / FIRE_TICKS);
            if (age < RING_TICKS) ring(out, matrix, blast.radius(), age / RING_TICKS);
            pose.popPose();
        }
        buffers.endBatch(RenderType.lightning());
    }

    /** Swells fast, then cools from white-yellow to dark red while fading. */
    private static void fireball(VertexConsumer out, Matrix4f m, float radius, float t) {
        float r = radius * (0.35f + 0.85f * (1 - (1 - t) * (1 - t) * (1 - t)));
        float fade = t < 0.15f ? 1 : 1 - (t - 0.15f) / 0.85f;
        float cool = Math.min(1, t * 1.6f);
        int red = 255, green = (int) (235 - 175 * cool), blue = (int) (170 * (1 - cool));
        int alpha = (int) (200 * fade * fade);
        for (int lat = 0; lat < RINGS; lat++) {
            double a0 = Math.PI * lat / RINGS - Math.PI / 2, a1 = Math.PI * (lat + 1) / RINGS - Math.PI / 2;
            // A squashed sphere sitting on the ground reads as a blast, not a ball.
            float y0 = (float) (Math.sin(a0) * r * 0.75 + r * 0.45), y1 = (float) (Math.sin(a1) * r * 0.75 + r * 0.45);
            float c0 = (float) Math.cos(a0) * r, c1 = (float) Math.cos(a1) * r;
            for (int seg = 0; seg < SEGMENTS; seg++) {
                double b0 = 2 * Math.PI * seg / SEGMENTS, b1 = 2 * Math.PI * (seg + 1) / SEGMENTS;
                float x00 = (float) Math.cos(b0), z00 = (float) Math.sin(b0), x01 = (float) Math.cos(b1), z01 = (float) Math.sin(b1);
                quad(out, m, x00 * c0, y0, z00 * c0, x01 * c0, y0, z01 * c0, x01 * c1, y1, z01 * c1, x00 * c1, y1, z00 * c1,
                        red, green, blue, alpha, alpha);
            }
        }
    }

    /** A flat dust ring racing out to three blast radii. */
    private static void ring(VertexConsumer out, Matrix4f m, float radius, float t) {
        float outer = radius * (0.6f + 2.6f * t), inner = outer * 0.82f, y = 0.4f;
        int alpha = (int) (150 * (1 - t) * (1 - t));
        int segments = SEGMENTS * 3;
        for (int seg = 0; seg < segments; seg++) {
            double b0 = 2 * Math.PI * seg / segments, b1 = 2 * Math.PI * (seg + 1) / segments;
            float x0 = (float) Math.cos(b0), z0 = (float) Math.sin(b0), x1 = (float) Math.cos(b1), z1 = (float) Math.sin(b1);
            quad(out, m, x0 * inner, y, z0 * inner, x1 * inner, y, z1 * inner, x1 * outer, y, z1 * outer, x0 * outer, y, z0 * outer,
                    255, 214, 160, alpha, 0);
        }
    }

    /** Both windings: the lightning render type culls back faces. Alpha a is on 1-2, b on 3-4. */
    private static void quad(VertexConsumer out, Matrix4f m, float x1, float y1, float z1, float x2, float y2, float z2,
            float x3, float y3, float z3, float x4, float y4, float z4, int r, int g, int b, int a, int a2) {
        out.vertex(m, x1, y1, z1).color(r, g, b, a).endVertex();
        out.vertex(m, x2, y2, z2).color(r, g, b, a).endVertex();
        out.vertex(m, x3, y3, z3).color(r, g, b, a2).endVertex();
        out.vertex(m, x4, y4, z4).color(r, g, b, a2).endVertex();
        out.vertex(m, x4, y4, z4).color(r, g, b, a2).endVertex();
        out.vertex(m, x3, y3, z3).color(r, g, b, a2).endVertex();
        out.vertex(m, x2, y2, z2).color(r, g, b, a).endVertex();
        out.vertex(m, x1, y1, z1).color(r, g, b, a).endVertex();
    }

    private BlastFireball() {}
}
