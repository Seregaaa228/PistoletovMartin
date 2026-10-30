package dev.frontier.aviation;

import dev.frontier.aviation.control.nativebridge.FlightNativeHooks;
import dev.frontier.aviation.control.net.FlightNetwork;
import dev.frontier.aviation.control.server.FlightControlEvents;
import dev.frontier.aviation.control.server.FlightControlService;
import java.util.Objects;
import net.minecraft.world.entity.Entity;

/** Common bootstrap: dedicated servers never load a Minecraft client class. */
public final class FlightControls {
    private static volatile FlightNativeHooks.Callbacks client;
    private static boolean initialized;

    private FlightControls() { }

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        FlightNetwork.register();
        FlightControlEvents.register();
        FlightNativeHooks.install(new FlightNativeHooks.Callbacks() {
            @Override public long simulationTick(Entity vehicle) {
                var handler = client;
                return vehicle.level().isClientSide && handler != null
                    ? handler.simulationTick(vehicle) : vehicle.level().getGameTime();
            }
            @Override public boolean manages(Entity vehicle) {
                if (vehicle.level().isClientSide) {
                    var handler = client;
                    return handler != null && handler.manages(vehicle);
                }
                return vehicle.getServer() != null
                        && FlightControlService.get(vehicle.getServer()).manages(vehicle);
            }

            @Override public void beforePhysics(Entity vehicle) {
                if (vehicle.level().isClientSide) {
                    var handler = client;
                    if (handler != null) handler.beforePhysics(vehicle);
                } else if (vehicle.getServer() != null) {
                    FlightControlService.get(vehicle.getServer()).beforePhysics(vehicle);
                }
            }

            @Override public void afterPhysics(Entity vehicle) {
                if (vehicle.level().isClientSide) {
                    var handler = client;
                    if (handler != null) handler.afterPhysics(vehicle);
                } else if (vehicle.getServer() != null) {
                    FlightControlService.get(vehicle.getServer()).afterPhysics(vehicle);
                }
            }
        });
    }

    public static void installClient(FlightNativeHooks.Callbacks handler) {
        client = Objects.requireNonNull(handler);
    }
}
