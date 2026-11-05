package dev.frontier.flamingofix.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * A large missile blast after the flash: a billowing fireball (a core and six rising lobes,
 * one additive mesh), a ground dust wave with a low wall (one translucent mesh) and a smoke
 * column fed for 14 s with vanilla campfire signal smoke. About 900 quads for under 2 s;
 * the column is capped per tick and thinned with the particle setting and distance.
 */
public final class BlastFireball {
    private static final class Blast {
        final Vec3 center; final float radius; final long born, seed;
        long emitted;
        Blast(Vec3 center, float radius, long born, long seed) {
            this.center = center; this.radius = radius; this.born = born; this.seed = seed; this.emitted = born - 1;
        }
    }
    private static final List<Blast> ACTIVE = new ArrayList<>();
    private static final int MAX = 8, FIRE_TICKS = 40, WAVE_TICKS = 34, SMOKE_TICKS = 280, LOBES = 6;
    private static final int COLUMN_PER_TICK = 12;

    public static void add(Vec3 center, float radius, long gameTime, long seed) {
        if (ACTIVE.size() >= MAX) ACTIVE.remove(0);
        ACTIVE.add(new Blast(center, radius, gameTime, seed));
    }

    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || ACTIVE.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) { ACTIVE.clear(); return; }
        long tick = mc.level.getGameTime();
        float now = tick + event.getPartialTick();
        ACTIVE.removeIf(blast -> now - blast.born > SMOKE_TICKS || now < blast.born - 1);
        if (ACTIVE.isEmpty()) return;
        Vec3 camera = event.getCamera().getPosition();
        if (!mc.isPaused()) feedColumns(mc, tick, camera);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        PoseStack pose = event.getPoseStack();
        boolean fire = false, dust = false;
        VertexConsumer out = buffers.getBuffer(RenderType.lightning());
        for (Blast blast : ACTIVE) {
            float age = now - blast.born;
            if (age >= FIRE_TICKS) continue;
            fire = true;
            pose.pushPose();
            pose.translate(blast.center.x - camera.x, blast.center.y - camera.y, blast.center.z - camera.z);
            fireball(out, pose.last().pose(), blast, age / FIRE_TICKS);
            pose.popPose();
        }
        if (fire) buffers.endBatch(RenderType.lightning());
        out = buffers.getBuffer(RenderType.debugQuads());
        for (Blast blast : ACTIVE) {
            float age = now - blast.born;
            if (age >= WAVE_TICKS) continue;
            dust = true;
            pose.pushPose();
            pose.translate(blast.center.x - camera.x, blast.center.y - camera.y, blast.center.z - camera.z);
            wave(out, pose.last().pose(), blast.radius, age / WAVE_TICKS);
            pose.popPose();
        }
        if (dust) buffers.endBatch(RenderType.debugQuads());
    }

    /** Dense right after the blast, thinning out; signal smoke climbs ~40 blocks and lingers. */
    private static void feedColumns(Minecraft mc, long tick, Vec3 camera) {
        ParticleStatus setting = mc.options.particles().get();
        if (setting == ParticleStatus.MINIMAL) return;
        int budget = setting == ParticleStatus.ALL ? COLUMN_PER_TICK : COLUMN_PER_TICK / 2;
        for (Blast blast : ACTIVE) {
            if (blast.emitted >= tick) continue;
            long age = tick - blast.born;
            blast.emitted = tick;
            if (age >= SMOKE_TICKS) continue;
            double distance = blast.center.distanceToSqr(camera);
            if (distance > 384 * 384) continue;
            RandomSource random = RandomSource.create(blast.seed * 31 + age);
            int signal = age < 40 ? 3 : age < 120 ? 2 : (age & 1) == 0 ? 1 : 0;
            int dark = age < 60 ? 2 : age < 160 && (age & 1) == 0 ? 1 : 0;
            if (distance > 192 * 192) { signal = (signal + 1) / 2; dark = 0; }
            if (setting == ParticleStatus.DECREASED) { signal = (signal + 1) / 2; dark /= 2; }
            float spread = blast.radius * 0.22f;
            for (int i = 0; i < signal && budget > 0; i++, budget--) {
                mc.particleEngine.createParticle(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE,
                        blast.center.x + random.nextGaussian() * spread, blast.center.y + 1 + random.nextDouble() * 3,
                        blast.center.z + random.nextGaussian() * spread,
                        random.nextGaussian() * 0.012, 0.1 + random.nextDouble() * 0.1, random.nextGaussian() * 0.012);
            }
            for (int i = 0; i < dark && budget > 0; i++, budget--) {
                double angle = random.nextDouble() * Math.PI * 2, offset = blast.radius * (0.2 + 0.6 * random.nextDouble());
                mc.particleEngine.createParticle(ParticleTypes.LARGE_SMOKE,
                        blast.center.x + Math.cos(angle) * offset, blast.center.y + 0.5 + random.nextDouble() * 2,
                        blast.center.z + Math.sin(angle) * offset, 0, 0.04 + random.nextDouble() * 0.05, 0);
            }
        }
    }

    /** A core and lobes swelling and rising, cooling from white-yellow to dark red. */
    private static void fireball(VertexConsumer out, Matrix4f m, Blast blast, float t) {
        float grow = 1 - (1 - t) * (1 - t) * (1 - t);
        float fade = t < 0.12f ? 1 : 1 - (t - 0.12f) / 0.88f;
        float cool = Math.min(1, t * 1.5f);
        int alpha = (int) (210 * fade * fade);
        float r = blast.radius;
        sphere(out, m, 0, r * (0.35f + 0.45f * grow), 0, r * (0.35f + 0.8f * grow), 0.7f, 8, 16, cool, alpha, blast.seed);
        RandomSource random = RandomSource.create(blast.seed);
        for (int lobe = 0; lobe < LOBES; lobe++) {
            double angle = random.nextDouble() * Math.PI * 2;
            float reach = r * (0.4f + 0.35f * random.nextFloat()) * (0.5f + 0.5f * grow);
            float rise = r * (0.25f + 0.4f * random.nextFloat() + 0.7f * t);
            float size = r * (0.3f + 0.25f * random.nextFloat()) * (0.4f + 0.6f * grow);
            // Lobes lag the core: they cool and fade a little sooner.
            sphere(out, m, (float) Math.cos(angle) * reach, rise, (float) Math.sin(angle) * reach, size, 0.85f, 6, 10,
                    Math.min(1, cool * 1.2f), (int) (alpha * 0.85f), blast.seed + lobe * 7919L);
        }
    }

    private static void sphere(VertexConsumer out, Matrix4f m, float cx, float cy, float cz, float r, float squash,
            int rings, int segments, float cool, int alpha, long seed) {
        if (alpha <= 0) return;
        for (int lat = 0; lat < rings; lat++) {
            double a0 = Math.PI * lat / rings - Math.PI / 2, a1 = Math.PI * (lat + 1) / rings - Math.PI / 2;
            float y0 = cy + (float) Math.sin(a0) * r * squash, y1 = cy + (float) Math.sin(a1) * r * squash;
            float c0 = (float) Math.cos(a0) * r, c1 = (float) Math.cos(a1) * r;
            for (int seg = 0; seg < segments; seg++) {
                double b0 = 2 * Math.PI * seg / segments, b1 = 2 * Math.PI * (seg + 1) / segments;
                float x0 = (float) Math.cos(b0), z0 = (float) Math.sin(b0), x1 = (float) Math.cos(b1), z1 = (float) Math.sin(b1);
                // Per-face brightness breaks the smooth ball into billowing patches.
                float patch = 0.5f + 0.5f * hash(seed, lat, seg);
                int red = (int) (255 * patch), green = (int) ((230 - 180 * cool) * patch), blue = (int) (150 * (1 - cool) * patch);
                twoSided(out, m, cx + x0 * c0, y0, cz + z0 * c0, cx + x1 * c0, y0, cz + z1 * c0,
                        cx + x1 * c1, y1, cz + z1 * c1, cx + x0 * c1, y1, cz + z0 * c1, red, green, blue, alpha, alpha);
            }
        }
    }

    /** Ground dust racing out to four blast radii, with a low wall at its front. */
    private static void wave(VertexConsumer out, Matrix4f m, float radius, float t) {
        float ease = 1 - (1 - t) * (1 - t);
        float outer = radius * (0.7f + 3.3f * ease), inner = outer * 0.7f, wall = 1.5f + radius * 0.2f * (1 - t);
        int alpha = (int) (120 * (1 - t) * (1 - t));
        int segments = 48;
        for (int seg = 0; seg < segments; seg++) {
            double b0 = 2 * Math.PI * seg / segments, b1 = 2 * Math.PI * (seg + 1) / segments;
            float x0 = (float) Math.cos(b0), z0 = (float) Math.sin(b0), x1 = (float) Math.cos(b1), z1 = (float) Math.sin(b1);
            quad(out, m, x0 * inner, 0.3f, z0 * inner, x1 * inner, 0.3f, z1 * inner, x1 * outer, 0.3f, z1 * outer,
                    x0 * outer, 0.3f, z0 * outer, 150, 132, 108, 0, alpha);
            quad(out, m, x0 * outer, 0.3f, z0 * outer, x1 * outer, 0.3f, z1 * outer, x1 * outer, wall, z1 * outer,
                    x0 * outer, wall, z0 * outer, 150, 132, 108, alpha, 0);
        }
    }

    private static float hash(long seed, int a, int b) {
        long h = seed ^ (a * 0x9E3779B97F4A7C15L) ^ (b * 0xC2B2AE3D27D4EB4FL);
        h ^= h >>> 31; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 29;
        return (h & 0xFFFF) / 65535f;
    }

    /** One winding; for render types without face culling. Alpha a is on 1-2, b on 3-4. */
    private static void quad(VertexConsumer out, Matrix4f m, float x1, float y1, float z1, float x2, float y2, float z2,
            float x3, float y3, float z3, float x4, float y4, float z4, int r, int g, int b, int a, int a2) {
        out.vertex(m, x1, y1, z1).color(r, g, b, a).endVertex();
        out.vertex(m, x2, y2, z2).color(r, g, b, a).endVertex();
        out.vertex(m, x3, y3, z3).color(r, g, b, a2).endVertex();
        out.vertex(m, x4, y4, z4).color(r, g, b, a2).endVertex();
    }

    /** Both windings: the lightning render type culls back faces. */
    private static void twoSided(VertexConsumer out, Matrix4f m, float x1, float y1, float z1, float x2, float y2, float z2,
            float x3, float y3, float z3, float x4, float y4, float z4, int r, int g, int b, int a, int a2) {
        quad(out, m, x1, y1, z1, x2, y2, z2, x3, y3, z3, x4, y4, z4, r, g, b, a, a2);
        quad(out, m, x4, y4, z4, x3, y3, z3, x2, y2, z2, x1, y1, z1, r, g, b, a2, a);
    }

    private BlastFireball() {}
}
