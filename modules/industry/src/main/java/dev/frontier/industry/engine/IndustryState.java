package dev.frontier.industry.engine;

import dev.frontier.industry.SiteType;
import java.util.UUID;

/** Immutable server view shared by menus, contextual tooltips and automation. */
public record IndustryState(UUID id, SiteType type, String status, int health,
                            boolean loaded, boolean functional, boolean powered,
                            int energy, int energyCapacity, boolean networkConnected,
                            String networkStatus, int workers, int repairWorkers,
                            int repairProgress, int repairTotal, boolean repairRequested,
                            String currentOrder, int quantity, int progress, int total) {
}
