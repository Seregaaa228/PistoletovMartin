package dev.frontier.arcade.airdefense.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.frontier.arcade.airdefense.SamMissile;
import dev.frontier.arcade.airdefense.SamMissileEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * A missile drawn from flat-shaded quads, no texture: a light grey octagonal body, dark nose and
 * fins, and while the motor burns an additive plume and a glow that stays visible far out.
 * Sizes per type (the 48N6 is long and thick, the Pantsir's dart is slim).
 */
public final class SamMissileRenderer extends EntityRenderer<SamMissileEntity> {
    private static final double PLUME_SIGHT = 256 * 256;

    public SamMissileRenderer(EntityRendererProvider.Context context) { super(context); }

    @Override
    public ResourceLocation getTextureLocation(SamMissileEntity entity) { return TextureAtlas.LOCATION_BLOCKS; }

    @Override
    public void render(SamMissileEntity missile, float yaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        Vec3 dir = missile.direction();
        if (dir.lengthSqr() < 1e-6) return;
        SamMissile type = missile.missileType();
        double length = switch (type) { case M48N6 -> 7.5; case V601 -> 6.0; case M9M96 -> 4.75; case M57E6 -> 3.2; };
        double radius = switch (type) { case M48N6 -> 0.26; case V601 -> 0.22; case M9M96 -> 0.13; case M57E6 -> 0.09; };
        Vec3 up = Math.abs(dir.y) > 0.95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 u = dir.cross(up).normalize(), v = dir.cross(u).normalize();
        Matrix4f m = pose.last().pose();
        VertexConsumer body = buffers.getBuffer(RenderType.debugQuads());
        Vec3 tail = dir.scale(-length / 2), head = dir.scale(length / 2 - radius * 3);
        for (int side = 0; side < 8; side++) {
            double a0 = side * Math.PI / 4, a1 = (side + 1) * Math.PI / 4;
            Vec3 r0 = u.scale(Math.cos(a0) * radius).add(v.scale(Math.sin(a0) * radius));
            Vec3 r1 = u.scale(Math.cos(a1) * radius).add(v.scale(Math.sin(a1) * radius));
            int shade = 170 + (int) (60 * Math.max(0, r0.add(r1).normalize().y));
            quad(body, m, tail.add(r0), tail.add(r1), head.add(r1), head.add(r0), shade, shade, shade - 6, 255);
            // Nose cone.
            Vec3 tip = dir.scale(length / 2);
            quad(body, m, head.add(r0), head.add(r1), tip, tip, 70, 74, 70, 255);
        }
        // Cruciform fins at the tail, both windings.
        for (int fin = 0; fin < 4; fin++) {
            Vec3 out = fin % 2 == 0 ? u : v;
            out = fin >= 2 ? out.scale(-1) : out;
            Vec3 a = tail.add(out.scale(radius)), b = tail.add(dir.scale(length * 0.18)).add(out.scale(radius));
            Vec3 c = tail.add(dir.scale(length * 0.08)).add(out.scale(radius * 4)), d = tail.add(out.scale(radius * 4));
            quad(body, m, a, b, c, d, 90, 96, 90, 255);
            quad(body, m, d, c, b, a, 90, 96, 90, 255);
        }
        if (missile.burning() && entityRenderDispatcher.distanceToSqr(missile) < PLUME_SIGHT) {
            VertexConsumer hot = buffers.getBuffer(RenderType.lightning());
            float flicker = 0.85f + 0.15f * (float) Math.sin((missile.tickCount + partial) * 2.9 + missile.getId());
            float size = type.plume;
            Vec3 nozzle = tail;
            for (int blade = 0; blade < 3; blade++) {
                double angle = blade * Math.PI / 3;
                Vec3 side = u.scale(Math.cos(angle)).add(v.scale(Math.sin(angle)));
                Vec3 end = nozzle.subtract(dir.scale(3.2 * size * flicker));
                Vec3 w = side.scale(0.38 * size);
                quad(hot, m, nozzle.add(w), nozzle.subtract(w), end.subtract(w.scale(0.15)), end.add(w.scale(0.15)), 255, 150, 40, 220);
                quad(hot, m, nozzle.subtract(w), nozzle.add(w), end.add(w.scale(0.15)), end.subtract(w.scale(0.15)), 255, 150, 40, 220);
            }
            // Glow facing the camera, larger with distance so the motor can be followed by eye.
            Vec3 toCamera = entityRenderDispatcher.camera.getPosition().subtract(missile.getPosition(partial)).normalize();
            Vec3 gu = toCamera.cross(Math.abs(toCamera.y) > 0.95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize();
            Vec3 gv = toCamera.cross(gu).normalize();
            double grow = Math.max(1, Math.sqrt(entityRenderDispatcher.distanceToSqr(missile)) / 64) * 0.55 * size * flicker;
            Vec3 a = nozzle.add(gu.scale(grow)).add(gv.scale(grow)), b = nozzle.subtract(gu.scale(grow)).add(gv.scale(grow));
            Vec3 c = nozzle.subtract(gu.scale(grow)).subtract(gv.scale(grow)), d = nozzle.add(gu.scale(grow)).subtract(gv.scale(grow));
            quad(hot, m, a, b, c, d, 255, 210, 140, 200);
            quad(hot, m, b, a, d, c, 255, 210, 140, 200);
        }
        super.render(missile, yaw, partial, pose, buffers, light);
    }

    private static void quad(VertexConsumer out, Matrix4f m, Vec3 a, Vec3 b, Vec3 c, Vec3 d, int r, int g, int bl, int alpha) {
        out.vertex(m, (float) a.x, (float) a.y, (float) a.z).color(r, g, bl, alpha).endVertex();
        out.vertex(m, (float) b.x, (float) b.y, (float) b.z).color(r, g, bl, alpha).endVertex();
        out.vertex(m, (float) c.x, (float) c.y, (float) c.z).color(r, g, bl, alpha).endVertex();
        out.vertex(m, (float) d.x, (float) d.y, (float) d.z).color(r, g, bl, alpha).endVertex();
    }
}
