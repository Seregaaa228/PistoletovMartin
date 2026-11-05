package dev.frontier.flamingofix.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.sbw.deepstrike.client.FlightUi;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

/**
 * DeepStrike's map lines without the per-pixel cost. Its {@code FlightUi.line} is a Bresenham loop
 * with one {@code fill} per pixel, and every unmanaged fill flushes: the range rings on the launch
 * laptop's map were tens of thousands of draw calls a frame. Here a line is one 1-unit-wide quad
 * over the same pixels, and a circle is drawn managed, so it costs one draw call.
 */
public final class FlightUiLines {
    private static boolean managed;

    /** @return false when called from inside our own managed circle: let DeepStrike's body run */
    public static boolean circle(GuiGraphics g, int x, int y, float radius, int color, boolean dashed) {
        if (managed) return false;
        managed = true;
        try { g.drawManaged(() -> FlightUi.circle(g, x, y, radius, color, dashed)); }
        finally { managed = false; }
        return true;
    }

    public static void line(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
        if (managed) { quad(g, x0, y0, x1, y1, color); return; }
        g.drawManaged(() -> quad(g, x0, y0, x1, y1, color));
    }

    /** From pixel centre to pixel centre, half a unit past each end and to each side: the pixels Bresenham fills. */
    private static void quad(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
        float dx = x1 - x0, dy = y1 - y0, length = (float) Math.sqrt(dx * dx + dy * dy);
        float ux = length < 1e-3f ? 1 : dx / length, uy = length < 1e-3f ? 0 : dy / length;
        float ax = x0 + 0.5f - ux * 0.5f, ay = y0 + 0.5f - uy * 0.5f, bx = x1 + 0.5f + ux * 0.5f, by = y1 + 0.5f + uy * 0.5f;
        float nx = -uy * 0.5f, ny = ux * 0.5f;
        int a = color >>> 24, r = color >> 16 & 255, gr = color >> 8 & 255, b = color & 255;
        Matrix4f m = g.pose().last().pose();
        VertexConsumer out = g.bufferSource().getBuffer(RenderType.gui());
        // fill's winding, (x1,y1) (x1,y2) (x2,y2) (x2,y1), so the GUI type's face culling keeps it.
        out.vertex(m, ax - nx, ay - ny, 0).color(r, gr, b, a).endVertex();
        out.vertex(m, ax + nx, ay + ny, 0).color(r, gr, b, a).endVertex();
        out.vertex(m, bx + nx, by + ny, 0).color(r, gr, b, a).endVertex();
        out.vertex(m, bx - nx, by - ny, 0).color(r, gr, b, a).endVertex();
    }

    private FlightUiLines() {}
}
