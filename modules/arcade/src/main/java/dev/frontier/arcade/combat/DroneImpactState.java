package dev.frontier.arcade.combat;

/** Transient server guard; a spent entity is discarded rather than saved. */
public interface DroneImpactState {
    boolean frontier$claimImpact();
}
