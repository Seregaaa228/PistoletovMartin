package dev.frontier.flamingofix.client;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;

/** The original collector's class is package-private; resolve its method once. */
public final class NativeAircraftLighting {
    private static final Method COLLECT;
    static {
        try {
            Class<?> type = Class.forName("dev.sbw.deepstrike.client.Su24mExhaust");
            COLLECT = type.getDeclaredMethod("collect", List.class, ClientLevel.class, float.class, float.class);
            COLLECT.setAccessible(true);
        } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public static void collect(List<?> lights, ClientLevel level, float ambient, float partial) {
        try { COLLECT.invoke(null, lights, level, ambient, partial); }
        catch (ReflectiveOperationException failure) {
            Throwable cause = failure instanceof InvocationTargetException target ? target.getCause() : failure;
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Pinned DeepStrike aircraft light API failed", cause);
        }
    }
    private NativeAircraftLighting() { }
}
