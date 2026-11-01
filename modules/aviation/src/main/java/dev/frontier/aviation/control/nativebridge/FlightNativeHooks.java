package dev.frontier.aviation.control.nativebridge;

import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import net.minecraft.world.entity.Entity;

/** Common-side callback boundary; owns no lease or control model. */
public final class FlightNativeHooks {
    public interface Callbacks {
        boolean manages(Entity vehicle);
        void beforePhysics(Entity vehicle);
        void afterPhysics(Entity vehicle);
        default long simulationTick(Entity vehicle) { return vehicle.level().getGameTime(); }
    }
    private static volatile Callbacks callbacks;
    private static final Map<Entity, Long> entered = new WeakHashMap<>();
    private static final Map<Entity, Long> completed = new WeakHashMap<>();
    private FlightNativeHooks() {}
    public static void install(Callbacks value) { callbacks = Objects.requireNonNull(value); clear(); }
    public static boolean manages(Entity vehicle) {
        var handler = callbacks;
        return handler != null && NativeFlightAdapter.INSTANCE.supports(vehicle)
            && handler.manages(vehicle);
    }
    public static void beforePhysics(Entity vehicle) {
        if (!manages(vehicle)) return;
        long tick = callbacks.simulationTick(vehicle);
        synchronized (entered) {
            if (Objects.equals(entered.get(vehicle), tick)) return;
            entered.put(vehicle, tick);
        }
        callbacks.beforePhysics(vehicle);
    }
    public static void afterPhysics(Entity vehicle) {
        var handler = callbacks;
        if (handler == null) return;
        long tick = handler.simulationTick(vehicle);
        synchronized (entered) {
            if (!Objects.equals(entered.get(vehicle), tick) || Objects.equals(completed.get(vehicle), tick)) return;
            completed.put(vehicle, tick);
        }
        handler.afterPhysics(vehicle);
    }
    public static void clear(Entity vehicle) {
        synchronized (entered) { entered.remove(vehicle); completed.remove(vehicle); }
        NativeFlightAdapter.INSTANCE.clear(vehicle);
    }
    public static void clear() {
        synchronized (entered) { entered.clear(); completed.clear(); }
        NativeFlightAdapter.INSTANCE.clear();
    }
}
