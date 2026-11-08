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
 * A missile drawn from shaded quads, no texture: a twelve-sided body with its band, an ogive nose,
 * fins (and the V-601's wings, the canards of the agile rounds), and while the motor burns an
 * additive plume and a glow that stays visible far out. Size and paint per round.
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
        Look look = Look.of(type);
        double length = look.length, radius = look.radius;
        Vec3 up = Math.abs(dir.y) > 0.95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 u = dir.cross(up).normalize(), v = dir.cross(u).normalize();
        Matrix4f m = pose.last().pose();
        VertexConsumer body = buffers.getBuffer(RenderType.debugQuads());
        Vec3 tail = dir.scale(-length / 2), shoulder = dir.scale(length / 2 - radius * 4.5), tip = dir.scale(length / 2);
        Vec3 ogive = dir.scale(length / 2 - radius * 1.8);
        Vec3 band0 = dir.scale(length * (look.band - 0.5)), band1 = band0.add(dir.scale(radius * 1.6));
        // Twelve-sided body, lit from above; a coloured band; a two-step ogive nose.
        for (int side = 0; side < 12; side++) {
            double a0 = side * Math.PI / 6, a1 = (side + 1) * Math.PI / 6;
            Vec3 r0 = u.scale(Math.cos(a0) * radius).add(v.scale(Math.sin(a0) * radius));
            Vec3 r1 = u.scale(Math.cos(a1) * radius).add(v.scale(Math.sin(a1) * radius));
            float light01 = (float) (0.72 + 0.28 * Math.max(0, r0.add(r1).normalize().y));
            int[] c = shade(look.body, light01), b = shade(look.bandColor, light01), n = shade(look.nose, light01);
            quad(body, m, tail.add(r0), tail.add(r1), band0.add(r1), band0.add(r0), c[0], c[1], c[2], 255);
            quad(body, m, band0.add(r0), band0.add(r1), band1.add(r1), band1.add(r0), b[0], b[1], b[2], 255);
            quad(body, m, band1.add(r0), band1.add(r1), shoulder.add(r1), shoulder.add(r0), c[0], c[1], c[2], 255);
            Vec3 o0 = ogive.add(r0.scale(0.62)), o1 = ogive.add(r1.scale(0.62));
            quad(body, m, shoulder.add(r0), shoulder.add(r1), o1, o0, n[0], n[1], n[2], 255);
            quad(body, m, o0, o1, tip, tip, n[0] * 4 / 5, n[1] * 4 / 5, n[2] * 4 / 5, 255);
        }
        // Tail fins, and wings or canards where the real round has them; both windings.
        fins(body, m, u, v, dir, tail, radius, length * 0.16, radius * look.fin, look.finColor);
        if (look.wing > 0) fins(body, m, u, v, dir, dir.scale(length * 0.05), radius, length * 0.14, radius * look.wing, look.finColor);
        if (look.canard > 0) fins(body, m, u, v, dir, shoulder.subtract(dir.scale(radius * 4)), radius, radius * 2.2, radius * look.canard, look.finColor);
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

    /** Per-round appearance: size, paint, band position (fraction from the tail), fin spans in radii. */
    private record Look(double length, double radius, int body, int nose, int bandColor, double band, int finColor,
                        double fin, double wing, double canard) {
        static Look of(SamMissile type) {
            return switch (type) {
                case V601 -> new Look(6.0, 0.22, 0xC8CAC4, 0x3C403C, 0xF2F2EE, 0.72, 0xB4B6B0, 4.2, 3.4, 1.8);
                case M48N6 -> new Look(7.5, 0.26, 0xD8D8D0, 0x4A4E48, 0x5E6A44, 0.55, 0x9EA096, 2.6, 0, 0);
                case M9M96 -> new Look(4.75, 0.13, 0xD2D4CC, 0x3A3E3A, 0xB04A3A, 0.6, 0x9EA096, 3.0, 0, 1.6);
                case M57E6 -> new Look(3.2, 0.09, 0x56643E, 0x2E3428, 0xD8D0B0, 0.5, 0x48543A, 3.2, 0, 1.4);
                case PAC2 -> new Look(5.3, 0.21, 0xE6E6E0, 0x585E58, 0x343832, 0.8, 0xC8C8C0, 2.8, 0, 0);
                case PAC3 -> new Look(5.2, 0.13, 0xE6E6E0, 0x2E302E, 0xC0A040, 0.75, 0xC8C8C0, 2.6, 0, 1.2);
            };
        }
    }

    private static int[] shade(int rgb, float light) {
        return new int[]{(int) (((rgb >> 16) & 255) * light), (int) (((rgb >> 8) & 255) * light), (int) ((rgb & 255) * light)};
    }

    /** Four fins at {@code at}, chord {@code chord} back from there, span {@code span} outward. */
    private static void fins(VertexConsumer out, Matrix4f m, Vec3 u, Vec3 v, Vec3 dir, Vec3 at, double radius, double chord,
                             double span, int color) {
        int r = (color >> 16) & 255, g = (color >> 8) & 255, b = color & 255;
        for (int fin = 0; fin < 4; fin++) {
            Vec3 axis = fin % 2 == 0 ? u : v;
            axis = fin >= 2 ? axis.scale(-1) : axis;
            Vec3 a = at.add(axis.scale(radius)), bb = at.add(dir.scale(chord)).add(axis.scale(radius));
            Vec3 c = at.add(dir.scale(chord * 0.45)).add(axis.scale(span)), d = at.add(axis.scale(span));
            quad(out, m, a, bb, c, d, r, g, b, 255);
            quad(out, m, d, c, bb, a, r * 4 / 5, g * 4 / 5, b * 4 / 5, 255);
        }
    }

    private static void quad(VertexConsumer out, Matrix4f m, Vec3 a, Vec3 b, Vec3 c, Vec3 d, int r, int g, int bl, int alpha) {
        out.vertex(m, (float) a.x, (float) a.y, (float) a.z).color(r, g, bl, alpha).endVertex();
        out.vertex(m, (float) b.x, (float) b.y, (float) b.z).color(r, g, bl, alpha).endVertex();
        out.vertex(m, (float) c.x, (float) c.y, (float) c.z).color(r, g, bl, alpha).endVertex();
        out.vertex(m, (float) d.x, (float) d.y, (float) d.z).color(r, g, bl, alpha).endVertex();
    }
}
