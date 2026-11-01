package dev.frontier.aviation.aircraft;

import dev.frontier.aviation.control.FlightProfile;
import dev.frontier.aviation.handling.HandlingProfile;
import dev.frontier.aviation.weapons.WeaponLoadout;
import java.util.Objects;

/** One model's capabilities. A null model adapts an already registered native entity. */
public record AircraftDefinition(String typeId, FlightProfile flight, HandlingProfile handling,
                                 WeaponLoadout weapons, boolean destroyOnDeath, AircraftModel model) {
    public AircraftDefinition {
        Objects.requireNonNull(typeId, "typeId");
        Objects.requireNonNull(flight, "flight");
        Objects.requireNonNull(handling, "handling");
        Objects.requireNonNull(weapons, "weapons");
        if (!typeId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
            throw new IllegalArgumentException("aircraft entity ID must be namespaced");
    }

    public boolean registersModel() { return model != null; }
}
