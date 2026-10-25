package dev.frontier.flamingofix;

import dev.sbw.deepstrike.game.MissileKind;
import dev.sbw.deepstrike.model.ModelAssets;
import dev.sbw.deepstrike.model.ModelGeometry;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A single terrain segment's exact native mesh, constructed only if a non-air
 * voxel shape needs narrow-phase contact. No world cache or quantized poses.
 */
public final class DeferredMissileMotion {
    private static final MethodHandle MOTION, CONTACT, EMPTY_MESH;
    private static final boolean MEASURE = Boolean.getBoolean("frontier.combatAcceptance");
    private static final AtomicLong DEFERRED = new AtomicLong(), MATERIALIZED = new AtomicLong();

    private final MissileKind kind;
    private final Vec3 before, movement;
    private final float yaw, pitch;
    private final double padding;
    private final Predicate<String> visibility;
    private volatile Object mesh;

    static {
        try {
            Class<?> shape = Class.forName("dev.sbw.deepstrike.game.MissileShapeSweep");
            Class<?> mesh = Class.forName("dev.sbw.deepstrike.game.MissileShapeSweep$Mesh");
            Class<?> contact = Class.forName("dev.sbw.deepstrike.game.MissileShapeSweep$Contact");
            MOTION = MethodHandles.privateLookupIn(shape, MethodHandles.lookup()).findStatic(shape, "motion",
                    MethodType.methodType(mesh, MissileKind.class, Vec3.class, Vec3.class,
                            float.class, float.class, double.class, Predicate.class))
                    .asType(MethodType.methodType(Object.class, MissileKind.class, Vec3.class, Vec3.class,
                            float.class, float.class, double.class, Predicate.class));
            var lookup = MethodHandles.privateLookupIn(mesh, MethodHandles.lookup());
            CONTACT = lookup.findVirtual(mesh, "contact", MethodType.methodType(contact, AABB.class, Vec3.class, Vec3.class))
                    .asType(MethodType.methodType(Object.class, Object.class, AABB.class, Vec3.class, Vec3.class));
            EMPTY_MESH = lookup.findConstructor(mesh, MethodType.methodType(void.class, List.class, double.class))
                    .asType(MethodType.methodType(Object.class, List.class, double.class));
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    public DeferredMissileMotion(MissileKind kind, Vec3 before, Vec3 movement, float yaw, float pitch,
            double padding, Predicate<String> visible) {
        this.kind = kind;
        this.before = before;
        this.movement = movement;
        this.yaw = yaw;
        this.pitch = pitch;
        this.padding = padding;
        // Evaluate the original predicate once, in native bone order, now.
        // Neither an entity reference nor its later booster/wing state escapes.
        var bones = ModelGeometry.get(ModelAssets.missile(kind)).bones;
        String[] names = new String[bones.size()];
        int count = 0;
        for (Object entry : bones) {
            String name = ((ModelGeometry.Bone) entry).name();
            if (visible.test(name)) names[count++] = name;
        }
        final int visibleCount = count;
        this.visibility = name -> {
            for (int i = 0; i < visibleCount; i++) if (names[i].equals(name)) return true;
            return false;
        };
        if (MEASURE) DEFERRED.incrementAndGet();
    }

    public static Object create(MissileKind kind, Vec3 before, Vec3 movement, float yaw, float pitch,
            double padding, Predicate<String> visible) {
        var deferred = new DeferredMissileMotion(kind, before, movement, yaw, pitch, padding, visible);
        try {
            Object marker = (Object) EMPTY_MESH.invokeExact((List<?>) List.of(), 0.0);
            ((DeferredMissileMesh) marker).frontier$defer(deferred);
            return marker;
        } catch (Throwable error) {
            throw new IllegalStateException("Pinned native deferred terrain mesh contract failed", error);
        }
    }

    Object geometry() {
        Object result = mesh;
        if (result == null) {
            synchronized (this) {
                result = mesh;
                if (result == null) {
                    try {
                        result = (Object) MOTION.invokeExact(kind, before, movement, yaw, pitch, padding, (Predicate<?>) visibility);
                    } catch (Throwable error) {
                        throw new IllegalStateException("Pinned native missile motion contract failed", error);
                    }
                    mesh = result;
                    if (MEASURE) MATERIALIZED.incrementAndGet();
                }
            }
        }
        return result;
    }

    public Object contact(AABB obstacle, Vec3 from, Vec3 to) {
        try {
            return (Object) CONTACT.invokeExact(geometry(), obstacle, from, to);
        } catch (Throwable error) {
            throw new IllegalStateException("Pinned native deferred contact contract failed", error);
        }
    }

    public boolean isMaterialized() { return mesh != null; }

    /** Counters are active only in the explicitly opted-in combat test JVM. */
    public static long deferredCount() { return DEFERRED.get(); }
    public static long materializedCount() { return MATERIALIZED.get(); }
}
