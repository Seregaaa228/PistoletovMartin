package dev.frontier.aviation.client;

import org.joml.Matrix4f;

/** Pure clip-space geometry: no game, no GPU. */
public final class ModelPartCullingTest {
    private static int checks;

    public static void main(String[] args) {
        bounds();
        cockpitView();
        sweepAroundTheCamera();
        System.out.println("ModelPartCullingTest: " + checks + " checks passed (clip-space boxes, no game frames)");
    }

    /** 70 degree vertical FOV, 16:9, near 0.05, far 1024: Minecraft's level projection shape. */
    private static Matrix4f projection() {
        return new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.05f, 1024f);
    }

    private static boolean outside(float x0, float y0, float z0, float x1, float y1, float z1, Matrix4f view) {
        return ModelPartCulling.outside(new float[] {x0, y0, z0, x1, y1, z1}, new Matrix4f(projection()).mul(view));
    }

    private static void bounds() {
        float[] box = ModelPartCulling.bounds(new float[] {1, -2, 3, -4, 5, -6, 0, 0, 0});
        check(box.length == 6 && box[0] == -4 && box[1] == -2 && box[2] == -6 && box[3] == 1 && box[4] == 5 && box[5] == 3,
            "bounds are per-axis min and max");
        check(ModelPartCulling.bounds(new float[0]).length == 0, "no positions, no box");
        check(!ModelPartCulling.outside(new float[0], projection()), "an empty box is never culled");
    }

    /** The camera looks down -Z. */
    private static void cockpitView() {
        Matrix4f view = new Matrix4f();
        check(!outside(-1, -1, -10, 1, 1, -8, view), "a part ahead is drawn");
        check(outside(-1, -1, 5, 1, 1, 9, view), "a part behind the camera (tail) is skipped");
        check(outside(-1, -40, -12, 1, -30, -10, view), "a part far below the view is skipped");
        check(outside(30, -1, -10, 40, 1, -8, view), "a part far to the side is skipped");
        check(!outside(-2, -2, -2, 2, 2, 2, view), "a part around the camera (cockpit) is drawn");
        check(!outside(-1, -1, -10, 60, 1, -8, view), "a part crossing the view edge is drawn");
        check(!outside(-1, -1, -20, 1, 1, 20, view), "a part reaching from behind into view is drawn");
        check(outside(-1, -1, -2000, 1, 1, -1500, view), "a part beyond the far plane is skipped");
    }

    /** A unit box at distance 10 in every direction: culled exactly when it is out of the frustum. */
    private static void sweepAroundTheCamera() {
        for (int degrees = 0; degrees < 360; degrees += 5) {
            Matrix4f view = new Matrix4f().rotateY((float) Math.toRadians(degrees));
            boolean culled = outside(-0.5f, -0.5f, -10.5f, 0.5f, 0.5f, -9.5f, view);
            // Horizontal half-FOV is about 51 degrees at 16:9; the box adds about 3 degrees.
            float off = Math.abs(((degrees + 180) % 360) - 180);
            if (off < 50) check(!culled, "box at " + degrees + " degrees is in view");
            if (off > 58) check(culled, "box at " + degrees + " degrees is out of view");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
