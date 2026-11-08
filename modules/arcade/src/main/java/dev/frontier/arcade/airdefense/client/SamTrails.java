package dev.frontier.arcade.airdefense.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.frontier.arcade.ArcadeVehicles;
import dev.frontier.arcade.airdefense.SamMissileEntity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

/**
 * Smoke and fire drawn as geometry rather than particles: a missile flies 7-11 blocks a tick, so
 * particles leave a dotted line, while a ribbon through its recorded positions reads as one
 * continuous trail from far away. Each trail widens and fades over a few seconds; launch and
 * burst add a flash and a ball of smoke that hangs. A few quads per segment, nothing on the
 * network, and hard caps on how many trails and puffs are kept.
 */
@Mod.EventBusSubscriber(modid = ArcadeVehicles.ID, value = Dist.CLIENT)
public final class SamTrails {
    private static final int MAX_TRAILS = 48, MAX_POINTS = 48, MAX_PUFFS = 64;
    /** Ticks a smoke segment lives, flashes and smoke balls live. */
    private static final int SMOKE_LIFE = 90, FLASH_LIFE = 6, BALL_LIFE = 140;
    private static final double SIGHT = 640;

    private record Point(Vec3 at, long born) { }
    private record Puff(Vec3 at, long born, float size, boolean flash) { }
    private static final Map<Integer, ArrayDeque<Point>> TRAILS = new HashMap<>();
    private static final List<Puff> PUFFS = new ArrayList<>();

    private SamTrails() { }

    /** The missile's nozzle this tick, while its motor burns. */
    static void record(SamMissileEntity missile, Vec3 nozzle) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || !missile.burning()) return;
        if (!TRAILS.containsKey(missile.getId()) && TRAILS.size() >= MAX_TRAILS) return;
        var points = TRAILS.computeIfAbsent(missile.getId(), id -> new ArrayDeque<>());
        points.addLast(new Point(nozzle, mc.level.getGameTime()));
        while (points.size() > MAX_POINTS) points.removeFirst();
    }

    /** A flash, and optionally a ball of smoke left behind it. */
    static void flash(Vec3 at, float size, boolean smoke) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        long now = mc.level.getGameTime();
        if (PUFFS.size() >= MAX_PUFFS) PUFFS.remove(0);
        PUFFS.add(new Puff(at, now, size, true));
        if (smoke) PUFFS.add(new Puff(at, now, size * 1.4f, false));
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null || TRAILS.isEmpty() && PUFFS.isEmpty()) return;
        float time = mc.level.getGameTime() + event.getPartialTick();
        Vec3 camera = event.getCamera().getPosition();
        Matrix4f m = event.getPoseStack().last().pose();
        var buffers = mc.renderBuffers().bufferSource();
        VertexConsumer smoke = buffers.getBuffer(RenderType.debugQuads());
        boolean any = false;
        for (Iterator<ArrayDeque<Point>> it = TRAILS.values().iterator(); it.hasNext(); ) {
            var points = it.next();
            while (!points.isEmpty() && time - points.peekFirst().born > SMOKE_LIFE) points.removeFirst();
            if (points.size() < 2) { if (points.isEmpty()) it.remove(); continue; }
            Point previous = null;
            for (Point p : points) {
                if (previous != null && p.at.distanceTo(camera) < SIGHT) { ribbon(smoke, m, camera, previous, p, time); any = true; }
                previous = p;
            }
        }
        for (Iterator<Puff> it = PUFFS.iterator(); it.hasNext(); ) {
            Puff puff = it.next();
            float age = time - puff.born;
            if (age > (puff.flash ? FLASH_LIFE : BALL_LIFE)) { it.remove(); continue; }
            if (!puff.flash && puff.at.distanceTo(camera) < SIGHT) { ball(smoke, m, camera, puff, age); any = true; }
        }
        if (any) buffers.endBatch(RenderType.debugQuads());
        VertexConsumer hot = buffers.getBuffer(RenderType.lightning());
        boolean flashes = false;
        for (Puff puff : PUFFS) {
            float age = time - puff.born;
            if (!puff.flash || age > FLASH_LIFE) continue;
            float fade = 1 - age / FLASH_LIFE;
            // Seen from afar the flash keeps a readable size on screen.
            float grow = (float) Math.max(1, puff.at.distanceTo(camera) / 80);
            billboard(hot, m, puff.at.subtract(camera), camera, puff.size * (0.6f + 0.6f * fade) * grow, 255, 200, 120, (int) (230 * fade));
            flashes = true;
        }
        if (flashes) buffers.endBatch(RenderType.lightning());
    }

    /** One trail segment: a camera-facing band, wider and fainter as the smoke ages. */
    private static void ribbon(VertexConsumer out, Matrix4f m, Vec3 camera, Point a, Point b, float time) {
        float ageA = (time - a.born) / SMOKE_LIFE, ageB = (time - b.born) / SMOKE_LIFE;
        Vec3 pa = a.at.subtract(camera), pb = b.at.subtract(camera);
        Vec3 along = pb.subtract(pa);
        if (along.lengthSqr() < 1e-6) return;
        Vec3 view = pa.add(pb).scale(0.5);
        Vec3 side = along.cross(view);
        if (side.lengthSqr() < 1e-6) return;
        side = side.normalize();
        double wa = 0.35 + 2.2 * Math.sqrt(ageA), wb = 0.35 + 2.2 * Math.sqrt(ageB);
        // Smoke drifts up a little as it ages.
        pa = pa.add(0, ageA * 1.5, 0);
        pb = pb.add(0, ageB * 1.5, 0);
        int alphaA = (int) (190 * (1 - ageA) * (1 - ageA)), alphaB = (int) (190 * (1 - ageB) * (1 - ageB));
        int shade = 236 - (int) (30 * ageA);
        // Two halves, dense along the middle and clear at the edges: soft, not a flat strip.
        for (int half = -1; half <= 1; half += 2) {
            vertex(out, m, pa.add(side.scale(wa * half)), shade, 0);
            vertex(out, m, pa, shade, alphaA);
            vertex(out, m, pb, shade, alphaB);
            vertex(out, m, pb.add(side.scale(wb * half)), shade, 0);
        }
    }

    /** The burst's dark smoke ball: grows quickly, then hangs and thins out. */
    private static void ball(VertexConsumer out, Matrix4f m, Vec3 camera, Puff puff, float age) {
        float life = age / BALL_LIFE;
        float size = puff.size * (0.6f + 1.4f * (float) Math.sqrt(Math.min(1, age / 20f)) + 0.4f * life);
        int shade = 70 + (int) (60 * life);
        int alpha = (int) (200 * (1 - life) * (1 - life));
        Vec3 at = puff.at.subtract(camera).add(0, life * 3, 0);
        // A lumpy cloud: a few soft discs offset around the centre.
        for (int lump = 0; lump < 5; lump++) {
            double a = lump * 1.3 + puff.born % 7;
            Vec3 offset = new Vec3(Math.cos(a), Math.sin(a * 0.7) * 0.6, Math.sin(a)).scale(lump == 0 ? 0 : size * 0.45);
            billboard(out, m, at.add(offset), camera, size * (lump == 0 ? 1f : 0.7f), shade, shade, shade - 4, lump == 0 ? alpha : alpha * 3 / 4);
        }
    }

    /** A camera-facing soft disc: full colour at the centre fading to clear at the rim (12 sectors). */
    private static void billboard(VertexConsumer out, Matrix4f m, Vec3 at, Vec3 camera, float size, int r, int g, int b, int alpha) {
        Vec3 toCamera = at.scale(-1).normalize();
        Vec3 up = Math.abs(toCamera.y) > 0.95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 u = toCamera.cross(up).normalize(), v = toCamera.cross(u).normalize();
        for (int i = 0; i < 12; i++) {
            double a0 = i * Math.PI / 6, a1 = (i + 1) * Math.PI / 6;
            Vec3 r0 = at.add(u.scale(Math.cos(a0) * size)).add(v.scale(Math.sin(a0) * size));
            Vec3 r1 = at.add(u.scale(Math.cos(a1) * size)).add(v.scale(Math.sin(a1) * size));
            out.vertex(m, (float) at.x, (float) at.y, (float) at.z).color(r, g, b, alpha).endVertex();
            out.vertex(m, (float) at.x, (float) at.y, (float) at.z).color(r, g, b, alpha).endVertex();
            out.vertex(m, (float) r1.x, (float) r1.y, (float) r1.z).color(r, g, b, 0).endVertex();
            out.vertex(m, (float) r0.x, (float) r0.y, (float) r0.z).color(r, g, b, 0).endVertex();
        }
    }

    private static void vertex(VertexConsumer out, Matrix4f m, Vec3 p, int shade, int alpha) {
        out.vertex(m, (float) p.x, (float) p.y, (float) p.z).color(shade, shade, shade - 4, Math.max(0, alpha)).endVertex();
    }

    /** Leaving a world drops everything. */
    static void clear() {
        TRAILS.clear();
        PUFFS.clear();
    }
}
