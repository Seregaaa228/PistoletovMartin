package dev.frontier.aviation.weapons;

/** Execution families; ammunition, ballistics and damage stay in the native weapon data. */
public enum WeaponKind {
    CANNON,
    GUIDED_MISSILE,
    /** Unpowered standoff bomb; released through the same native guided shooter as a missile. */
    GLIDE_BOMB;

    /** Kinds fired through the native guided launcher with a reticle point or tracked identity. */
    public boolean guided() {
        return this == GUIDED_MISSILE || this == GLIDE_BOMB;
    }
}
