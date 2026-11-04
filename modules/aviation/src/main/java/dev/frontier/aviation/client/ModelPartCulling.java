package dev.frontier.aviation.client;

import java.util.Map;
import java.util.WeakHashMap;
import org.joml.Matrix4f;

/**
 * Frustum test for one piece of a bedrock model before its vertices are written. SBW draws
 * every bone of a vehicle each frame, transforming and copying every quad on the CPU; from
 * an aircraft's cockpit most of the airframe (tail, engines, underside) is outside the view.
 * A piece whose bounding box lies wholly outside one clip plane is skipped; anything that
 * may be visible is drawn as before. Off with -Dfrontier.modelPartCulling=false.
 */
public final class ModelPartCulling {
    public static final boolean ENABLED = !"false".equals(System.getProperty("frontier.modelPartCulling"));
    private static final Map<Object, float[]> BOUNDS = new WeakHashMap<>();
    private static final float[] NONE = new float[0];
    private static final Matrix4f CLIP = new Matrix4f();

    private ModelPartCulling() {}

    /** {minX, minY, minZ, maxX, maxY, maxZ} of x,y,z triples, or an empty array for none. */
    public static float[] bounds(float[] positions) {
        if (positions == null || positions.length < 3) return NONE;
        float[] box = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i + 2 < positions.length; i += 3) {
            for (int axis = 0; axis < 3; axis++) {
                box[axis] = Math.min(box[axis], positions[i + axis]);
                box[axis + 3] = Math.max(box[axis + 3], positions[i + axis]);
            }
        }
        return box;
    }

    /** True when the box, taken through {@code clip} (projection x view x pose), is wholly outside one plane. */
    public static boolean outside(float[] box, Matrix4f clip) {
        if (box.length != 6) return false;
        // Clip-space planes w+x, w-x, w+y, w-y, w+z, w-z; bit set while every corner is outside.
        int allOutside = 0b111111;
        for (int corner = 0; corner < 8 && allOutside != 0; corner++) {
            float x = box[(corner & 1) == 0 ? 0 : 3], y = box[(corner & 2) == 0 ? 1 : 4], z = box[(corner & 4) == 0 ? 2 : 5];
            float cx = clip.m00() * x + clip.m10() * y + clip.m20() * z + clip.m30();
            float cy = clip.m01() * x + clip.m11() * y + clip.m21() * z + clip.m31();
            float cz = clip.m02() * x + clip.m12() * y + clip.m22() * z + clip.m32();
            float cw = clip.m03() * x + clip.m13() * y + clip.m23() * z + clip.m33();
            int out = (cw + cx < 0 ? 1 : 0) | (cw - cx < 0 ? 2 : 0) | (cw + cy < 0 ? 4 : 0)
                | (cw - cy < 0 ? 8 : 0) | (cw + cz < 0 ? 16 : 0) | (cw - cz < 0 ? 32 : 0);
            allOutside &= out;
        }
        return allOutside != 0;
    }

    /** Render thread only: whether the piece keyed by {@code key} with these positions is off screen. */
    public static boolean offScreen(Object key, float[] positions, Matrix4f projection, Matrix4f modelView, Matrix4f pose) {
        float[] box = BOUNDS.computeIfAbsent(key, ignored -> bounds(positions));
        return outside(box, CLIP.set(projection).mul(modelView).mul(pose));
    }
}
