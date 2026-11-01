package dev.frontier.aviation.control.client;

import dev.frontier.aviation.control.ControlStatus;
import dev.frontier.aviation.control.FlightSnapshot;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Terminal native-authority handoff cannot be undone by delayed packets. */
public final class FlightSnapshotPolicy {
    public enum Decision { APPLY, RETIRE, IGNORE }
    private final Map<UUID, Long> retiredEpochs = new HashMap<>();

    public Decision accept(FlightSnapshot incoming, FlightSnapshot previous) {
        long epoch = incoming.epoch();
        if (epoch <= retiredEpochs.getOrDefault(incoming.vehicleId(), -1L)) return Decision.IGNORE;
        if (previous != null && epoch < previous.epoch()) return Decision.IGNORE;
        // Terminal BUSY can follow a normal snapshot in the SAME simulation tick.
        // It must win before the ordinary serverTick duplicate rejection.
        if (incoming.telemetry().status() == ControlStatus.BUSY && incoming.controllerId() == null) {
            retiredEpochs.merge(incoming.vehicleId(), epoch, Math::max);
            return Decision.RETIRE;
        }
        // Revocation can follow an accepted snapshot in the same server tick.
        // Apply it before the normal per-tick duplicate check so the old pilot
        // immediately stops submitting frames for the closed epoch.
        if (previous != null && epoch == previous.epoch() && previous.controllerId() != null
            && incoming.controllerId() == null && incoming.telemetry().status() == ControlStatus.RELEASED
            && incoming.telemetry().serverTick() >= previous.telemetry().serverTick()) return Decision.APPLY;
        if (previous != null && epoch == previous.epoch()
            && incoming.telemetry().serverTick() <= previous.telemetry().serverTick()) return Decision.IGNORE;
        return Decision.APPLY;
    }
    public void clear() { retiredEpochs.clear(); }
}
