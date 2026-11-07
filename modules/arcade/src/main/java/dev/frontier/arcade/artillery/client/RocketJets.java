package dev.frontier.arcade.artillery.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.frontier.arcade.ArcadeVehicles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

/**
 * The rocket motor's jet, drawn behind every rocket in flight like a jet's afterburner: a white-hot core inside an
 * orange plume, three crossed blades so it reads from any side, and a glow at the nozzle, all additive so it shines
 * against the sky and can be followed far off. Twelve quads a rocket, no textures, no packets.
 */
@Mod.EventBusSubscriber(modid = ArcadeVehicles.ID, value = Dist.CLIENT)
public final class RocketJets {
    /** Beyond this a rocket is a few pixels; its glow still draws but the plume is skipped. */
    private static final double PLUME_SQ = 192 * 192, SIGHT_SQ = 640 * 640;
    /**
     * From the rocket's position back to its nozzle, blocks. SBW's medium-rocket model sits ahead of the entity's
     * position, its tail about 0.6 blocks in front of it, so the nozzle is ahead too (negative).
     */
    private static final double NOZZLE = -0.5;

    private RocketJets() { }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Vec3 camera = event.getCamera().getPosition();
        float partial = event.getPartialTick();
        float time = mc.level.getGameTime() + partial;
        var buffers = mc.renderBuffers().bufferSource();
        Matrix4f m = event.getPoseStack().last().pose();
        // Two passes: the orange plume blended normally, so it stays orange against a bright sky; then the white-hot
        // core and the nozzle glow added on top, which is what makes it shine.
        for (int pass = 0; pass < 2; pass++) {
            boolean hot = pass == 1;
            RenderType type = hot ? RenderType.lightning() : RenderType.debugQuads();
            VertexConsumer out = buffers.getBuffer(type);
            boolean[] any = {false};
            ArtilleryTracersClient.forEachJet(partial, (position, direction, seed, age) -> {
                Vec3 nozzle = position.subtract(direction.scale(NOZZLE));
                double distanceSq = nozzle.distanceToSqr(camera);
                if (distanceSq > SIGHT_SQ) return;
                Vec3 at = nozzle.subtract(camera);
                // Flicker: each rocket breathes on its own phase.
                float flicker = 0.85f + 0.15f * (float) Math.sin(time * 2.7 + seed * 1.3) + 0.08f * (float) Math.sin(time * 7.1 + seed);
                // Boost: the first half second the motor burns long and fierce, then settles to cruise.
                float boost = 1 + 0.8f * Math.max(0, 1 - age / 10);
                if (hot) {
                    // Far away the glow keeps a readable size on screen.
                    float grow = (float) Math.max(1, Math.sqrt(distanceSq) / 96);
                    glow(out, m, at, 0.22f * grow * flicker * boost);
                    if (distanceSq <= PLUME_SQ) plume(out, m, at, direction, 1.4f * flicker * boost, 0.14f * boost, 255, 225, 150, 150);
                } else if (distanceSq <= PLUME_SQ) {
                    plume(out, m, at, direction, 3.4f * flicker * boost, 0.32f * boost, 255, 110, 20, 210);
                }
                any[0] = true;
            });
            if (hot) for (var flash : ArtilleryTracersClient.FLASHES) {
                // The launch flash at the tube mouth: big and white, gone in a few ticks.
                float fade = 1 - (time - flash.born()) / ArtilleryTracersClient.FLASH_TICKS;
                if (fade <= 0 || flash.at().distanceToSqr(camera) > SIGHT_SQ) continue;
                glow(out, m, flash.at().subtract(camera), 1.6f * fade + 0.4f);
                any[0] = true;
            }
            if (any[0]) buffers.endBatch(type);
        }
    }

    /** A tapering flame along {@code -direction}: three blades 60 degrees apart, bright at the nozzle, fading out. */
    private static void plume(VertexConsumer out, Matrix4f m, Vec3 at, Vec3 direction, float length, float width,
                              int r, int g, int b, int alpha) {
        Vec3 up = Math.abs(direction.y) > 0.95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 u = direction.cross(up).normalize(), v = direction.cross(u).normalize();
        Vec3 end = at.subtract(direction.scale(length));
        for (int blade = 0; blade < 3; blade++) {
            double angle = blade * Math.PI / 3;
            Vec3 side = u.scale(Math.cos(angle)).add(v.scale(Math.sin(angle)));
            Vec3 a = at.add(side.scale(width)), d = at.subtract(side.scale(width));
            Vec3 bEnd = end.add(side.scale(width * 0.15)), cEnd = end.subtract(side.scale(width * 0.15));
            // Both windings: seen from either side of the blade.
            quad(out, m, a, d, cEnd, bEnd, r, g, b, alpha, 0);
            quad(out, m, d, a, bEnd, cEnd, r, g, b, alpha, 0);
        }
    }

    /** A camera-facing glow at the nozzle. */
    private static void glow(VertexConsumer out, Matrix4f m, Vec3 at, float size) {
        Vec3 toCamera = at.scale(-1).normalize();
        Vec3 up = Math.abs(toCamera.y) > 0.95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 u = toCamera.cross(up).normalize().scale(size), v = toCamera.cross(u.normalize()).normalize().scale(size);
        Vec3 p1 = at.add(u).add(v), p2 = at.subtract(u).add(v), p3 = at.subtract(u).subtract(v), p4 = at.add(u).subtract(v);
        quad(out, m, p1, p2, p3, p4, 255, 170, 70, 150, 150);
        quad(out, m, p2, p1, p4, p3, 255, 170, 70, 150, 150);
    }

    /** Quad a-b-c-d; a and b are at the hot end (alpha {@code hot}), c and d at the far end ({@code tail} alpha). */
    private static void quad(VertexConsumer out, Matrix4f m, Vec3 a, Vec3 b, Vec3 c, Vec3 d, int r, int g, int bl,
                             int hot, int tail) {
        out.vertex(m, (float) a.x, (float) a.y, (float) a.z).color(r, g, bl, hot).endVertex();
        out.vertex(m, (float) b.x, (float) b.y, (float) b.z).color(r, g, bl, hot).endVertex();
        out.vertex(m, (float) c.x, (float) c.y, (float) c.z).color(r, g, bl, tail).endVertex();
        out.vertex(m, (float) d.x, (float) d.y, (float) d.z).color(r, g, bl, tail).endVertex();
    }
}
