package dev.frontier.aviation;

import com.mojang.blaze3d.vertex.PoseStack;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import net.minecraft.client.renderer.MultiBufferSource;

/** Cached access to DSM's package-private client classes; no per-frame class or method lookup. */
final class NativeAircraftEffects {
    private static final Field SHADER;
    private static final Method FIRE, EXTERNAL_SHADERS;
    static {
        try {
            Class<?> fire = Class.forName("dev.sbw.deepstrike.client.Su24OriginalFire");
            SHADER = fire.getDeclaredField("shader"); SHADER.setAccessible(true);
            FIRE = fire.getDeclaredMethod("renderAircraft", PoseStack.class, MultiBufferSource.class,
                    float.class, float.class, double.class); FIRE.setAccessible(true);
            Class<?> pipeline = Class.forName("dev.sbw.deepstrike.client.EngineFlamePostPass");
            EXTERNAL_SHADERS = pipeline.getDeclaredMethod("shaders"); EXTERNAL_SHADERS.setAccessible(true);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
    static boolean available() {
        try { return SHADER.get(null) != null && !Boolean.TRUE.equals(EXTERNAL_SHADERS.invoke(null)); }
        catch (ReflectiveOperationException failure) { throw failure(failure); }
    }
    static void render(PoseStack pose, MultiBufferSource buffers, float age, float power, double distance) {
        try { FIRE.invoke(null, pose, buffers, age, power, distance); }
        catch (ReflectiveOperationException failure) { throw failure(failure); }
    }
    private static RuntimeException failure(ReflectiveOperationException failure) {
        Throwable cause = failure instanceof InvocationTargetException target ? target.getCause() : failure;
        if (cause instanceof RuntimeException runtime) return runtime;
        if (cause instanceof Error error) throw error;
        return new IllegalStateException("Pinned DeepStrike aircraft effect API failed", cause);
    }
    private NativeAircraftEffects() { }
}
