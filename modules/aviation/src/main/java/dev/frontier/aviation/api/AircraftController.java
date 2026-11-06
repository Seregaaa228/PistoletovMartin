package dev.frontier.aviation.api;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import dev.frontier.aviation.control.ControlLease;
import dev.frontier.aviation.control.ControlStatus;
import dev.frontier.aviation.control.FlightSnapshot;
import dev.frontier.aviation.control.PilotCommand;
import dev.frontier.aviation.control.ReleaseReason;
import dev.frontier.aviation.control.server.FlightControlService;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Server-thread input port for a player or a seated NPC. AI chooses intentions;
 * this port never writes orientation, position, engine power or ammunition.
 * Submit one increasing-sequence command per tick, including neutral ticks.
 * Reacquire after a timeout, seat change or rejected epoch; never reuse old leases.
 */
public final class AircraftController {
    private AircraftController() {}

    public static Optional<ControlLease> acquire(LivingEntity pilot, VehicleEntity aircraft) {
        return service(pilot).acquire(pilot, aircraft);
    }

    public static ControlStatus submit(LivingEntity pilot, ControlLease lease, PilotCommand command) {
        return service(pilot).submit(pilot, lease, Objects.requireNonNull(command));
    }

    public static Optional<FlightSnapshot> observe(VehicleEntity aircraft) {
        return service(aircraft).snapshot(aircraft);
    }

    public static void release(LivingEntity pilot, ControlLease lease) {
        var service = service(pilot);
        if (lease != null && lease.controllerId().equals(pilot.getUUID()))
            service.release(lease, ReleaseReason.REQUESTED);
    }

    /** Exact live lease, not merely an entity UUID or a previously acquired seat. */
    public static boolean owns(LivingEntity pilot, VehicleEntity aircraft, ControlLease lease) {
        var service = service(pilot);
        if (lease == null || !lease.controllerId().equals(pilot.getUUID())
                || !lease.vehicleId().equals(aircraft.getUUID()) || !service.owns(pilot, aircraft)) return false;
        return service.snapshot(aircraft).filter(snapshot -> snapshot.epoch() == lease.epoch()
            && pilot.getUUID().equals(snapshot.controllerId())).isPresent();
    }

    private static FlightControlService service(Entity entity) {
        Objects.requireNonNull(entity);
        if (!(entity.level() instanceof ServerLevel level))
            throw new IllegalStateException("AircraftController requires a logical server");
        return FlightControlService.get(level.getServer());
    }
}
