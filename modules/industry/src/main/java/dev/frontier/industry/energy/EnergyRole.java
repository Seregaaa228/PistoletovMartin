package dev.frontier.industry.energy;

import dev.frontier.industry.SiteType;
import net.minecraft.core.Direction;

/** Physical controller ports; logical station/substation links remain required. */
public enum EnergyRole {
    GENERATOR, DISTRIBUTOR, CONSUMER;

    public static EnergyRole of(SiteType type) {
        return switch (type) {
            case POWER_STATION -> GENERATOR;
            case SUBSTATION -> DISTRIBUTOR;
            default -> CONSUMER;
        };
    }

    public boolean receives(Direction side) {
        return this == CONSUMER || this == DISTRIBUTOR && (side == null || side == Direction.UP);
    }

    public boolean extracts(Direction side) {
        return this == GENERATOR || this == DISTRIBUTOR && side != Direction.UP;
    }
}
