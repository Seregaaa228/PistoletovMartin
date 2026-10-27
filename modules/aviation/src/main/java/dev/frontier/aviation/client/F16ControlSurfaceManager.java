package dev.frontier.aviation.client;

import dev.sbw.deepstrike.vehicle.Su24mEntity;

/** Compatibility facade for renderer callers. */
public final class F16ControlSurfaceManager {
    public static F16ControlSurfaces.Axes surfaces(Su24mEntity plane, float partialTick) {
        return F16VisualStateManager.surfaces(plane, partialTick);
    }
    private F16ControlSurfaceManager() {}
}
