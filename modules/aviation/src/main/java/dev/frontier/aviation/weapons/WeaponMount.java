package dev.frontier.aviation.weapons;

import java.util.Objects;

/** Stable intent slot mapped to a named native weapon, independent of its seat-list index. */
public record WeaponMount(String id, String nativeGun, WeaponKind kind, int maximumBurst,
                          int minimumIntervalTicks, double maximumRange) {
    public WeaponMount {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(nativeGun, "nativeGun");
        Objects.requireNonNull(kind, "kind");
        if (!id.matches("[a-z0-9_./-]+") || nativeGun.isBlank())
            throw new IllegalArgumentException("weapon names must be nonempty stable IDs");
        if (maximumBurst < 1 || maximumBurst > 16 || minimumIntervalTicks < 1
                || !Double.isFinite(maximumRange) || maximumRange <= 0 || maximumRange > 8192)
            throw new IllegalArgumentException("invalid weapon limits");
    }
}
