package dev.frontier.aviation.ordnance;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import dev.frontier.aviation.AircraftProfile;
import dev.sbw.deepstrike.vehicle.Su24mEntity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/** Derives the physical load from native stores, magazines and vehicle inventory. */
public final class F16Loadout {
    private F16Loadout() {}

    public static boolean supports(VehicleEntity plane) {
        return plane instanceof Su24mEntity && AircraftProfile.of(plane) == AircraftProfile.F16;
    }

    public static F16Stations.Store type(ItemStack stack) {
        if (stack.isEmpty()) return null;
        var key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (key == null) return null;
        return switch (key.toString()) {
            case "deepstrike:storm_shadow" -> F16Stations.Store.STORM_SHADOW;
            case "superbwarfare:large_anti_ground_missile", "superbwarfare:medium_anti_ground_missile",
                 "superbwarfare:large_anti_air_missile" -> F16Stations.Store.UNDERWING;
            case "superbwarfare:medium_anti_air_missile" -> F16Stations.Store.LIGHT_AIR_TO_AIR;
            default -> null;
        };
    }

    private static void add(List<F16Stations.Store> stores, F16Stations.Store store, int count) {
        // Seven already proves overflow and keeps legacy oversized stacks bounded.
        for (int n = 0; n < count && stores.size() <= F16Stations.COUNT; n++) stores.add(store);
    }

    private static F16Stations.Store[] fixed(Su24mEntity plane) {
        var result = new F16Stations.Store[F16Stations.COUNT];
        if (plane.loaded(0)) result[0] = F16Stations.Store.STORM_SHADOW;
        if (plane.loaded(1)) result[1] = F16Stations.Store.STORM_SHADOW;
        return result;
    }

    private static List<F16Stations.Store> stores(VehicleEntity plane) {
        var stores = new ArrayList<F16Stations.Store>();
        var inventory = plane.getInventory();
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            var store = type(stack);
            if (store != null) add(stores, store, stack.getCount());
        }
        for (String name : List.of("Missile", "MediumAGMissile", "AAMissile", "LightAAMissile")) {
            var gun = plane.getGunData(name);
            if (gun != null) add(stores, name.equals("LightAAMissile")
                ? F16Stations.Store.LIGHT_AIR_TO_AIR : F16Stations.Store.UNDERWING,
                Math.max(0, gun.ammo.get()));
        }
        return stores;
    }

    /** Number of additional items which fit, including all native magazines. */
    public static int insertionLimit(VehicleEntity plane, ItemStack stack) {
        var type = type(stack);
        if (!supports(plane) || type == null) return stack.getCount();
        var stores = stores(plane);
        var fixed = fixed((Su24mEntity) plane);
        int accepted = 0;
        while (accepted < stack.getCount() && stores.size() < F16Stations.COUNT) {
            stores.add(type);
            if (F16Stations.assign(fixed, stores).isEmpty()) break;
            accepted++;
        }
        return accepted;
    }

    public static boolean canLoadStormShadow(Su24mEntity plane, int slot) {
        if (!supports(plane)) return true;
        if (slot < 0 || slot > 1) return false;
        var fixed = fixed(plane);
        if (fixed[slot] != null) return false;
        fixed[slot] = F16Stations.Store.STORM_SHADOW;
        return F16Stations.assign(fixed, stores(plane)).isPresent();
    }

    public static boolean fits(Su24mEntity plane) {
        return F16Stations.assign(fixed(plane), stores(plane)).isPresent();
    }

    /** Compact render-only snapshot: three bits per station, never a source of items. */
    public static int visualBits(Su24mEntity plane) {
        int[] slots = new int[F16Stations.COUNT];
        if (plane.loaded(0)) slots[0] = 1;
        if (plane.loaded(1)) slots[1] = 1;
        int ground = magazine(plane, "Missile");
        int mediumGround = magazine(plane, "MediumAGMissile");
        int air = magazine(plane, "AAMissile");
        int light = magazine(plane, "LightAAMissile");
        int storm = 0;
        var inventory = plane.getInventory();
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            var stack = inventory.getStackInSlot(slot);
            var key = stack.isEmpty() ? null : ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (key == null) continue;
            int count = Math.min(6, Math.max(0, stack.getCount()));
            switch (key.toString()) {
                case "superbwarfare:large_anti_ground_missile" -> ground = Math.min(6, ground + count);
                case "superbwarfare:medium_anti_ground_missile" -> mediumGround = Math.min(6, mediumGround + count);
                case "superbwarfare:large_anti_air_missile" -> air = Math.min(6, air + count);
                case "superbwarfare:medium_anti_air_missile" -> light = Math.min(6, light + count);
                case "deepstrike:storm_shadow" -> storm = Math.min(2, storm + count);
            }
        }
        for (int i = 0; i < 2 && storm > 0; i++) {
            if (slots[i] == 0) { slots[i] = 1; storm--; }
        }
        // Native aircraft missile magazines fire from the outer pair first.
        for (int i : new int[]{2, 3, 0, 1}) {
            if (slots[i] != 0) continue;
            if (ground > 0) { slots[i] = 2; ground--; }
            else if (mediumGround > 0) { slots[i] = 5; mediumGround--; }
            else if (air > 0) { slots[i] = 3; air--; }
        }
        for (int i : new int[]{4, 5, 0, 1, 2, 3}) {
            if (slots[i] == 0 && light > 0) { slots[i] = 4; light--; }
        }
        int bits = 0;
        for (int i = 0; i < slots.length; i++) bits |= slots[i] << (i * 3);
        return bits;
    }

    private static int magazine(VehicleEntity plane, String gunName) {
        var gun = plane.getGunData(gunName);
        return gun == null ? 0 : Math.max(0, Math.min(F16Stations.COUNT, gun.ammo.get()));
    }
}
