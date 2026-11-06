package dev.frontier.aviation.destruction;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import dev.frontier.aviation.aircraft.AircraftCatalog;
import dev.frontier.aviation.aircraft.AircraftDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Completes the native aircraft destruction transaction once, including airborne deaths.
 * Native DestroyInfo, explosion damage, passenger policy and wreckage loot are retained.
 */
public final class AircraftTerminalDestruction {
    private static final String TERMINAL = "frontierAircraftTerminalComplete";
    private AircraftTerminalDestruction() {}

    /** True tells the native base tick to stop, avoiding its later overkill explosion/loot path. */
    public static boolean completeIfTerminal(VehicleEntity vehicle) {
        if (vehicle.level().isClientSide || vehicle.getHealth() > 0 || !vehicle.isWreck()) return false;
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(vehicle.getType());
        if (id == null || !destroyOnDeath(id.toString())) return false;
        // Native DestroyInfo.NoWreck can already discard an airborne aircraft
        // inside destroy() without ever producing its main explosion.
        if (vehicle.isRemoved() && vehicle.getSympatheticDetonated()) return true;
        if (vehicle.getPersistentData().getBoolean(TERMINAL)) {
            vehicle.discard();
            return true;
        }
        // Mark before native callbacks: an explosion can synchronously damage
        // this same vehicle. Neither re-entry nor a saved terminal marker may
        // repeat passenger damage, the explosion or loot.
        vehicle.getPersistentData().putBoolean(TERMINAL, true);
        try {
            if (!vehicle.getSympatheticDetonated()) {
                vehicle.setSympatheticDetonated(true);
                var info = vehicle.computed().getDestroyInfo();
                if (info.getExplodePassengers()) {
                    if (vehicle.getCrash() && info.getCrashPassengers()) vehicle.crashPassengers();
                    else vehicle.explodePassengers();
                }
                vehicle.vehicleExplosion(info);
            }
        } finally {
            try {
                vehicle.ejectPassengers();
                vehicle.generateWreckageLoot();
            } finally {
                // Emits ordinary removal/tracking events and revokes the lease.
                vehicle.discard();
            }
        }
        return true;
    }

    /** Kept separate so catalog selection can be checked without constructing a vehicle. */
    public static boolean destroyOnDeath(String registryId) {
        if (registryId == null || registryId.isEmpty()) return false;
        return AircraftCatalog.find(registryId)
                .map(AircraftDefinition::destroyOnDeath)
                .orElse(false);
    }
}
