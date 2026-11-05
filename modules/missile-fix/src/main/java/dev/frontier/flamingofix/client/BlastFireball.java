package dev.frontier.flamingofix.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.sbw.deepstrike.game.MissileKind;
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
 * The look of a missile blast after DeepStrike's flash, per missile: a billowing fireball (core
 * and rising lobes, one additive mesh), a ground dust wave with a low wall, a smoke column fed
 * with vanilla campfire signal smoke, and for a strategic burst a mushroom (stem, cap torus).
 * Meshes are a few hundred to ~1500 quads for seconds; the column is capped per tick and
 * thinned with distance and the particle setting.
 */
public final class BlastFireball {
    /**
     * @param radius     visual blast radius in blocks
     * @param fireTicks  fireball life
     * @param waveReach  dust wave reach in radii
     * @param columnTicks how long the column is fed; 0 for a short black puff only
     * @param rise       upward speed range of column smoke, blocks per tick
     * @param visible    beyond this the blast is not drawn or fed
     */
    public record Style(float radius, int fireTicks, int lobes, int waveTicks, float waveReach, int columnTicks,
            int dense, int darkTicks, double rise, float visible, boolean mushroom) {
        int life() { return Math.max(Math.max(fireTicks, waveTicks), Math.max(columnTicks, mushroom ? 600 : darkTicks)); }
    }

    /** Proportional to DeepStrike's own crater sizes, and the server's default blast scale for Storm Shadow. */
    public static Style style(MissileKind kind, String warhead) {
        if (kind == null) return null;
        Style style;
        if (kind == MissileKind.PEACEKEEPER || "nuclear".equals(warhead)) {
            // Matches the server's default 1.5 blast scale for Peacekeeper.
            style = new Style(kind.craterRadius * 1.2f * 1.5f, 180, 12, 90, 8, 700, 5, 260, 0.35, 1024, true);
        } else if (kind == MissileKind.FP5) {
            style = new Style(kind.craterRadius * 0.9f, 52, 8, 40, 4.5f, 400, 4, 160, 0.17, 512, false);
        } else if (kind == MissileKind.STORM_SHADOW) {
            style = new Style(kind.craterRadius * 2 * 0.9f, 40, 6, 34, 4, 280, 3, 110, 0.15, 384, false);
        } else {
            style = new Style(kind.craterRadius * 0.8f, 16, 2, 16, 3, 0, 0, 50, 0.08, 192, false);
        }
        if ("thermobaric".equals(warhead)) {
            style = new Style(style.radius() * 1.3f, (int) (style.fireTicks() * 1.5f), style.lobes() + 2, style.waveTicks(),
                    style.waveReach() + 1, style.columnTicks(), style.dense(), style.darkTicks(), style.rise(), style.visible(), style.mushroom());
        }
        return style;
    }

    private static final class Blast {
        final Vec3 center; final Style style; final long born, seed;
        long emitted;
        Blast(Vec3 center, Style style, long born, long seed) {
            this.center = center; this.style = style; this.born = born; this.seed = seed; this.emitted = born - 1;
        }
    }
    private static final List<Blast> ACTIVE = new ArrayList<>();
    private static final int MAX = 8, COLUMN_PER_TICK = 16, CONDENSATION_TICKS = 30;

    public static void add(Vec3 center, Style style, long gameTime, long seed) {
        if (ACTIVE.size() >= MAX) ACTIVE.remove(0);
        ACTIVE.add(new Blast(center, style, gameTime, seed));
    }

    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || ACTIVE.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) { ACTIVE.clear(); return; }
        long tick = mc.level.getGameTime();
        float now = tick + event.getPartialTick();
        ACTIVE.removeIf(blast -> now - blast.born > blast.style.life() || now < blast.born - 1);
        if (ACTIVE.isEmpty()) return;
        Vec3 camera = event.getCamera().getPosition();
        if (!mc.isPaused()) feed(mc, tick, camera);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        PoseStack pose = event.getPoseStack();
        VertexConsumer out = buffers.getBuffer(RenderType.lightning());
        boolean any = false;
        for (Blast blast : ACTIVE) {
            float age = now - blast.born;
            if (age >= blast.style.fireTicks() || !visible(blast, camera)) continue;
            any = true;
            pose.pushPose();
            pose.translate(blast.center.x - camera.x, blast.center.y - camera.y, blast.center.z - camera.z);
            fireball(out, pose.last().pose(), blast, age);
            pose.popPose();
        }
        if (any) buffers.endBatch(RenderType.lightning());
        out = buffers.getBuffer(RenderType.debugQuads());
        any = false;
        for (Blast blast : ACTIVE) {
            float age = now - blast.born;
            if (!visible(blast, camera)) continue;
            pose.pushPose();
            pose.translate(blast.center.x - camera.x, blast.center.y - camera.y, blast.center.z - camera.z);
            if (age < blast.style.waveTicks()) { wave(out, pose.last().pose(), blast.style, age / blast.style.waveTicks()); any = true; }
            if (blast.style.mushroom() && age < CONDENSATION_TICKS) { condensation(out, pose.last().pose(), blast, age); any = true; }
            if (blast.style.mushroom()) { mushroom(out, pose.last().pose(), blast, age); any = true; }
            pose.popPose();
        }
        if (any) buffers.endBatch(RenderType.debugQuads());
    }

    private static boolean visible(Blast blast, Vec3 camera) {
        return blast.center.distanceToSqr(camera) <= (double) blast.style.visible() * blast.style.visible();
    }

    /** Dense right after the blast, thinning out; signal smoke climbs high and lingers. */
    private static void feed(Minecraft mc, long tick, Vec3 camera) {
        ParticleStatus setting = mc.options.particles().get();
        if (setting == ParticleStatus.MINIMAL) return;
        int budget = setting == ParticleStatus.ALL ? COLUMN_PER_TICK : COLUMN_PER_TICK / 2;
        for (Blast blast : ACTIVE) {
            if (blast.emitted >= tick) continue;
            long age = tick - blast.born;
            blast.emitted = tick;
            Style style = blast.style;
            double distance = blast.center.distanceToSqr(camera);
            if (distance > Math.min(style.visible(), 512) * Math.min(style.visible(), 512)) continue;
            RandomSource random = RandomSource.create(blast.seed * 31 + age);
            int signal = 0;
            if (age < style.columnTicks()) {
                double left = 1 - (double) age / style.columnTicks();
                signal = (int) Math.round(style.dense() * (0.25 + 0.75 * left * left));
                if (signal == 0 && (age & 1) == 0) signal = 1;
            }
            int dark = age < style.darkTicks() ? (age < style.darkTicks() / 3 ? 2 : (age & 1) == 0 ? 1 : 0) : 0;
            if (distance > 192 * 192) { signal = (signal + 1) / 2; dark = 0; }
            if (setting == ParticleStatus.DECREASED) { signal = (signal + 1) / 2; dark /= 2; }
            float r = style.radius();
            float spread = r * (style.mushroom() ? 0.15f : 0.22f);
            for (int i = 0; i < signal && budget > 0; i++, budget--) {
                mc.particleEngine.createParticle(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE,
                        blast.center.x + random.nextGaussian() * spread, blast.center.y + 1 + random.nextDouble() * 3,
                        blast.center.z + random.nextGaussian() * spread,
                        random.nextGaussian() * 0.012, style.rise() * (0.7 + 0.6 * random.nextDouble()), random.nextGaussian() * 0.012);
            }
            for (int i = 0; i < dark && budget > 0; i++, budget--) {
                double angle = random.nextDouble() * Math.PI * 2, offset = r * (0.2 + 0.6 * random.nextDouble());
                mc.particleEngine.createParticle(ParticleTypes.LARGE_SMOKE,
                        blast.center.x + Math.cos(angle) * offset, blast.center.y + 0.5 + random.nextDouble() * 2,
                        blast.center.z + Math.sin(angle) * offset, 0, 0.04 + random.nextDouble() * 0.05, 0);
            }
        }
    }

    /** A core and lobes swelling and rising, cooling from white-yellow to dark red; a strategic one climbs into the cap. */
    private static void fireball(VertexConsumer out, Matrix4f m, Blast blast, float age) {
        Style style = blast.style;
        float t = age / style.fireTicks();
        float grow = 1 - (1 - t) * (1 - t) * (1 - t);
        float fade = t < 0.12f ? 1 : 1 - (t - 0.12f) / 0.88f;
        float cool = Math.min(1, t * 1.5f);
        int alpha = (int) (210 * fade * fade);
        float r = style.radius();
        float lift = style.mushroom() ? capHeight(style, age) - r * 0.45f : 0;
        if (style.mushroom() && age < 8) {
            // The first instants: a white flash much brighter than the fire.
            sphere(out, m, 0, r * 0.3f, 0, r * (0.5f + age / 8f), 0.9f, 8, 16, 0, (int) (255 * (1 - age / 8f)), blast.seed, true);
        }
        sphere(out, m, 0, r * (0.35f + 0.45f * grow) + lift, 0, r * (0.35f + 0.8f * grow), 0.7f, 8, 16, cool, alpha, blast.seed, false);
        RandomSource random = RandomSource.create(blast.seed);
        for (int lobe = 0; lobe < style.lobes(); lobe++) {
            double angle = random.nextDouble() * Math.PI * 2;
            float reach = r * (0.4f + 0.35f * random.nextFloat()) * (0.5f + 0.5f * grow);
            float rise = r * (0.25f + 0.4f * random.nextFloat() + 0.7f * t) + lift;
            float size = r * (0.3f + 0.25f * random.nextFloat()) * (0.4f + 0.6f * grow);
            // Lobes lag the core: they cool and fade a little sooner.
            sphere(out, m, (float) Math.cos(angle) * reach, rise, (float) Math.sin(angle) * reach, size, 0.85f, 6, 10,
                    Math.min(1, cool * 1.2f), (int) (alpha * 0.85f), blast.seed + lobe * 7919L, false);
        }
    }

    /** Height of the mushroom cap above the ground: climbs fast while burning, then drifts up. */
    private static float capHeight(Style style, float age) {
        float r = style.radius();
        float climb = Math.min(1, age / style.fireTicks());
        climb = 1 - (1 - climb) * (1 - climb);
        return r * (0.45f + 2.4f * climb) + Math.max(0, age - style.fireTicks()) * r * 0.0015f;
    }

    /** Stem and cap: glowing at first, then dark grey-brown smoke fading over the last third. */
    private static void mushroom(VertexConsumer out, Matrix4f m, Blast blast, float age) {
        Style style = blast.style;
        float r = style.radius();
        float life = 600;
        float fade = age < life * 2 / 3 ? 1 : 1 - (age - life * 2 / 3) / (life / 3);
        if (fade <= 0) return;
        float glow = Math.max(0, 1 - age / (style.fireTicks() * 1.5f));
        float cap = capHeight(style, age);
        float spread = Math.min(1, age / 140f);
        float major = r * (0.55f + 0.85f * spread), minor = major * 0.42f;
        int alpha = (int) (175 * fade);
        // Stem: a tapered tube from the ground into the cap.
        int segments = 16, rings = 6;
        for (int ring = 0; ring < rings; ring++) {
            float h0 = cap * ring / rings, h1 = cap * (ring + 1) / rings;
            float w0 = r * (0.3f - 0.12f * ring / rings), w1 = r * (0.3f - 0.12f * (ring + 1) / rings);
            for (int seg = 0; seg < segments; seg++) {
                double b0 = 2 * Math.PI * seg / segments, b1 = 2 * Math.PI * (seg + 1) / segments;
                float shade = 0.75f + 0.25f * hash(blast.seed, ring, seg);
                int[] c = smoke(shade, glow * (1 - (float) ring / rings));
                quad(out, m, (float) Math.cos(b0) * w0, h0, (float) Math.sin(b0) * w0, (float) Math.cos(b1) * w0, h0, (float) Math.sin(b1) * w0,
                        (float) Math.cos(b1) * w1, h1, (float) Math.sin(b1) * w1, (float) Math.cos(b0) * w1, h1, (float) Math.sin(b0) * w1,
                        c[0], c[1], c[2], (int) (alpha * 0.7f), (int) (alpha * 0.7f));
            }
        }
        // Cap: a torus rolling over the top of the stem.
        int around = 24, tube = 10;
        for (int i = 0; i < around; i++) {
            double u0 = 2 * Math.PI * i / around, u1 = 2 * Math.PI * (i + 1) / around;
            for (int j = 0; j < tube; j++) {
                double v0 = 2 * Math.PI * j / tube, v1 = 2 * Math.PI * (j + 1) / tube;
                float shade = 0.65f + 0.35f * hash(blast.seed + 17, i, j);
                // The underside keeps the fire's glow longest.
                float under = (float) Math.max(0, -Math.sin((v0 + v1) / 2));
                int[] c = smoke(shade, glow * (0.4f + 0.6f * under));
                float[] p00 = torus(major, minor, u0, v0, cap), p10 = torus(major, minor, u1, v0, cap);
                float[] p11 = torus(major, minor, u1, v1, cap), p01 = torus(major, minor, u0, v1, cap);
                quad(out, m, p00[0], p00[1], p00[2], p10[0], p10[1], p10[2], p11[0], p11[1], p11[2], p01[0], p01[1], p01[2],
                        c[0], c[1], c[2], alpha, alpha);
            }
        }
    }

    /** The shock front's condensation shell: a white sphere racing out past the fireball and thinning. */
    private static void condensation(VertexConsumer out, Matrix4f m, Blast blast, float age) {
        float t = age / CONDENSATION_TICKS, r = blast.style.radius() * (0.6f + 2.4f * (1 - (1 - t) * (1 - t)));
        int alpha = (int) (110 * (1 - t) * (1 - t));
        if (alpha <= 0) return;
        int rings = 10, segments = 20;
        for (int lat = 0; lat < rings; lat++) {
            double a0 = Math.PI * lat / rings - Math.PI / 2, a1 = Math.PI * (lat + 1) / rings - Math.PI / 2;
            // Only the upper half; the ground takes the rest.
            if (a1 <= 0) continue;
            float y0 = (float) Math.sin(a0) * r, y1 = (float) Math.sin(a1) * r;
            float c0 = (float) Math.cos(a0) * r, c1 = (float) Math.cos(a1) * r;
            for (int seg = 0; seg < segments; seg++) {
                double b0 = 2 * Math.PI * seg / segments, b1 = 2 * Math.PI * (seg + 1) / segments;
                float x0 = (float) Math.cos(b0), z0 = (float) Math.sin(b0), x1 = (float) Math.cos(b1), z1 = (float) Math.sin(b1);
                quad(out, m, x0 * c0, Math.max(0, y0), z0 * c0, x1 * c0, Math.max(0, y0), z1 * c0, x1 * c1, y1, z1 * c1,
                        x0 * c1, y1, z0 * c1, 236, 238, 244, alpha, alpha);
            }
        }
    }

    private static float[] torus(float major, float minor, double u, double v, float height) {
        double ring = major + minor * Math.cos(v);
        return new float[] {(float) (Math.cos(u) * ring), height + (float) (Math.sin(v) * minor * 0.8), (float) (Math.sin(u) * ring)};
    }

    /** Grey-brown smoke, lit orange by the fire while {@code glow} > 0. */
    private static int[] smoke(float shade, float glow) {
        float r = 92 * shade, g = 82 * shade, b = 74 * shade;
        r += (255 - r) * glow; g += (140 - g) * glow; b += (40 - b) * glow;
        return new int[] {(int) r, (int) g, (int) b};
    }

    private static void sphere(VertexConsumer out, Matrix4f m, float cx, float cy, float cz, float r, float squash,
            int rings, int segments, float cool, int alpha, long seed, boolean white) {
        if (alpha <= 0 || r <= 0) return;
        for (int lat = 0; lat < rings; lat++) {
            double a0 = Math.PI * lat / rings - Math.PI / 2, a1 = Math.PI * (lat + 1) / rings - Math.PI / 2;
            float y0 = cy + (float) Math.sin(a0) * r * squash, y1 = cy + (float) Math.sin(a1) * r * squash;
            float c0 = (float) Math.cos(a0) * r, c1 = (float) Math.cos(a1) * r;
            for (int seg = 0; seg < segments; seg++) {
                double b0 = 2 * Math.PI * seg / segments, b1 = 2 * Math.PI * (seg + 1) / segments;
                float x0 = (float) Math.cos(b0), z0 = (float) Math.sin(b0), x1 = (float) Math.cos(b1), z1 = (float) Math.sin(b1);
                // Per-face brightness breaks the smooth ball into billowing patches.
                float patch = white ? 1 : 0.5f + 0.5f * hash(seed, lat, seg);
                int red = (int) (255 * patch), green = white ? 255 : (int) ((230 - 180 * cool) * patch);
                int blue = white ? 240 : (int) (150 * (1 - cool) * patch);
                twoSided(out, m, cx + x0 * c0, y0, cz + z0 * c0, cx + x1 * c0, y0, cz + z1 * c0,
                        cx + x1 * c1, y1, cz + z1 * c1, cx + x0 * c1, y1, cz + z0 * c1, red, green, blue, alpha, alpha);
            }
        }
    }

    /** Ground dust racing out, with a low wall at its front. */
    private static void wave(VertexConsumer out, Matrix4f m, Style style, float t) {
        float radius = style.radius();
        float ease = 1 - (1 - t) * (1 - t);
        float outer = radius * (0.7f + (style.waveReach() - 0.7f) * ease), inner = outer * 0.7f;
        float wall = 1.5f + radius * 0.2f * (1 - t);
        int alpha = (int) (120 * (1 - t) * (1 - t));
        int segments = style.mushroom() ? 72 : 48;
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
