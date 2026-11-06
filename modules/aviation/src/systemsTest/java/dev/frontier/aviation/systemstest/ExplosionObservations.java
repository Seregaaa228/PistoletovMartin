package dev.frontier.aviation.systemstest;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Test-only observation of actual native explosion calls, never an explosion trigger. */
public final class ExplosionObservations {
    private static final Map<UUID, Integer> COUNTS = new HashMap<>();
    private ExplosionObservations() {}

    public static void observed(VehicleEntity vehicle) {
        if (vehicle.getServer() != null && FreshSystemsProfile.authorized(vehicle.getServer()))
            COUNTS.merge(vehicle.getUUID(), 1, Integer::sum);
    }

    static int count(UUID entity) { return COUNTS.getOrDefault(entity, 0); }
    static void clear() { COUNTS.clear(); }
}
