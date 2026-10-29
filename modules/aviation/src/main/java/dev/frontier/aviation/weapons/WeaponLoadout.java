package dev.frontier.aviation.weapons;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Immutable per-model slots. Native GunData remains the only magazine/heat/reload owner. */
public final class WeaponLoadout {
    public static final WeaponLoadout NONE = new WeaponLoadout(List.of());
    public static final WeaponMount CANNON = new WeaponMount("cannon", "Cannon", WeaponKind.CANNON, 1, 1, 256);
    public static final WeaponLoadout CANNON_ONLY = new WeaponLoadout(List.of(CANNON));
    public static final WeaponLoadout F16 = new WeaponLoadout(List.of(CANNON,
        new WeaponMount("missile", "Missile", WeaponKind.GUIDED_MISSILE, 2, 20, 256),
        new WeaponMount("aamissile", "AAMissile", WeaponKind.GUIDED_MISSILE, 2, 20, 256),
        new WeaponMount("light_aamissile", "LightAAMissile", WeaponKind.GUIDED_MISSILE, 2, 20, 256),
        new WeaponMount("medium_agmissile", "MediumAGMissile", WeaponKind.GUIDED_MISSILE, 2, 20, 256)));

    private final List<WeaponMount> mounts;
    private final Map<String, WeaponMount> byId;
    private final Map<String, WeaponMount> byNativeGun;

    public WeaponLoadout(List<WeaponMount> mounts) {
        this.mounts = List.copyOf(mounts);
        var ids = new HashSet<String>();
        var guns = new HashSet<String>();
        var idIndex = new HashMap<String, WeaponMount>();
        var gunIndex = new HashMap<String, WeaponMount>();
        for (WeaponMount mount : this.mounts) {
            if (!ids.add(mount.id()) || !guns.add(mount.nativeGun()))
                throw new IllegalArgumentException("duplicate weapon slot or native gun");
            idIndex.put(mount.id(), mount);
            gunIndex.put(mount.nativeGun(), mount);
        }
        this.byId = Map.copyOf(idIndex);
        this.byNativeGun = Map.copyOf(gunIndex);
    }

    public List<WeaponMount> mounts() { return mounts; }

    public Optional<WeaponMount> find(String slotId) {
        return Optional.ofNullable(byId.get(slotId));
    }

    public Optional<WeaponMount> nativeGun(String name) {
        return Optional.ofNullable(byNativeGun.get(name));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WeaponLoadout that && mounts.equals(that.mounts);
    }

    @Override
    public int hashCode() { return mounts.hashCode(); }

    @Override
    public String toString() { return "WeaponLoadout" + mounts; }
}
