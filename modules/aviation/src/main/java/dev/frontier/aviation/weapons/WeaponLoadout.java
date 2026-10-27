package dev.frontier.aviation.weapons;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/** Immutable per-model slots. Native GunData remains the only magazine/heat/reload owner. */
public record WeaponLoadout(List<WeaponMount> mounts) {
    public static final WeaponLoadout NONE = new WeaponLoadout(List.of());
    public static final WeaponMount CANNON = new WeaponMount("cannon", "Cannon", WeaponKind.CANNON, 1, 1, 256);
    public static final WeaponLoadout CANNON_ONLY = new WeaponLoadout(List.of(CANNON));
    public static final WeaponLoadout F16 = new WeaponLoadout(List.of(CANNON,
        new WeaponMount("missile", "Missile", WeaponKind.GUIDED_MISSILE, 2, 20, 256)));

    public WeaponLoadout {
        mounts = List.copyOf(mounts);
        var ids = new HashSet<String>();
        var guns = new HashSet<String>();
        for (WeaponMount mount : mounts) {
            if (!ids.add(mount.id()) || !guns.add(mount.nativeGun()))
                throw new IllegalArgumentException("duplicate weapon slot or native gun");
        }
    }

    public Optional<WeaponMount> find(String slotId) {
        return mounts.stream().filter(mount -> mount.id().equals(slotId)).findFirst();
    }

    public Optional<WeaponMount> nativeGun(String name) {
        return mounts.stream().filter(mount -> mount.nativeGun().equals(name)).findFirst();
    }
}
