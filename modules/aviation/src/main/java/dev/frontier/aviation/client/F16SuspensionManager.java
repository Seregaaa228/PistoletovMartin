package dev.frontier.aviation.client;

import dev.sbw.deepstrike.vehicle.Su24mEntity;

/** Compatibility facade for renderer callers. */
public final class F16SuspensionManager {
    public static float compression(Su24mEntity plane, float partialTick) {
        return F16VisualStateManager.compression(plane, partialTick);
    }

    public static float wheelAngle(Su24mEntity plane, float partialTick, boolean nose) {
        return F16VisualStateManager.wheelAngle(plane, partialTick, nose);
    }

    private F16SuspensionManager() {}
}
