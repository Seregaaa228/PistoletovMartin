package dev.frontier.aviation.ordnance;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import dev.frontier.aviation.aircraft.AircraftCatalog;
import dev.frontier.aviation.ordnance.StoreStations.Layout;
import dev.frontier.aviation.ordnance.StoreStations.Store;
import dev.sbw.deepstrike.vehicle.Su24mEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Derives the physical load of an owned jet (Su-25, F-16, J-10) from its native
 * stores, magazines and vehicle inventory. Nothing is persisted here: DeepStrike's
 * racks, SBW's magazines and the vehicle inventory stay the saved state.
 */
public final class StoreLoadout {
    /** Ammunition item of each native gun, in StoreStations.GUNS order (visual kinds 2..5). */
    private static final List<String> AMMO = List.of("superbwarfare:large_anti_ground_missile",
        "superbwarfare:large_anti_air_missile", "superbwarfare:medium_anti_air_missile",
        "superbwarfare:medium_anti_ground_missile");
    private static final String STORM_SHADOW_ITEM = "deepstrike:storm_shadow";

    private StoreLoadout() {}

    private static String typeId(Entity plane) {
        var key = ForgeRegistries.ENTITY_TYPES.getKey(plane.getType());
        return key == null ? "" : key.toString();
    }

    public static Optional<Layout> layout(Entity plane) {
        return plane instanceof Su24mEntity ? StoreStations.forType(typeId(plane)) : Optional.empty();
    }

    public static boolean supports(VehicleEntity plane) {
        return layout(plane).isPresent();
    }

    /** Whether the airframe's catalog loadout fires this native gun. */
    private static boolean carries(Entity plane, String gun) {
        return AircraftCatalog.find(typeId(plane)).flatMap(d -> d.weapons().nativeGun(gun)).isPresent();
    }

    private static Store storeOfGun(int index) {
        return StoreStations.GUNS.get(index).equals("LightAAMissile") ? Store.LIGHT_AIR_TO_AIR : Store.UNDERWING;
    }

    /** The store class an item occupies on this airframe, or null when it carries none. */
    public static Store type(VehicleEntity plane, ItemStack stack) {
        if (stack.isEmpty() || !supports(plane)) return null;
        var key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (key == null) return null;
        String id = key.toString();
        if (id.equals(STORM_SHADOW_ITEM)) return Store.STORM_SHADOW;
        int gun = AMMO.indexOf(id);
        return gun >= 0 && carries(plane, StoreStations.GUNS.get(gun)) ? storeOfGun(gun) : null;
    }

    private static void add(List<Store> stores, Store store, int count) {
        // One past the maximum already proves overflow and keeps legacy oversized stacks bounded.
        for (int n = 0; n < count && stores.size() <= StoreStations.MAX_STATIONS; n++) stores.add(store);
    }

    private static Store[] fixed(Su24mEntity plane, Layout layout) {
        var result = new Store[layout.size()];
        for (int rack = 0; rack < layout.racks(); rack++)
            if (plane.loaded(rack)) result[layout.rackStation(rack)] = Store.STORM_SHADOW;
        int legacy = legacyRackStation(plane, layout);
        if (legacy >= 0) result[legacy] = Store.STORM_SHADOW;
        return result;
    }

    /**
     * A one-rack jet (J-10) saved with a store on DeepStrike's second rack: it occupies the
     * belly station until unloaded, so nothing is loaded over it. -1 when there is none.
     */
    private static int legacyRackStation(Su24mEntity plane, Layout layout) {
        if (layout.racks() != 1 || !plane.loaded(1) || plane.loaded(0)) return -1;
        return layout.rackStation(0);
    }

    private static List<Store> stores(VehicleEntity plane) {
        var stores = new ArrayList<Store>();
        var inventory = plane.getInventory();
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            var store = type(plane, stack);
            if (store != null) add(stores, store, stack.getCount());
        }
        for (int gun = 0; gun < StoreStations.GUNS.size(); gun++) {
            String name = StoreStations.GUNS.get(gun);
            var data = carries(plane, name) ? plane.getGunData(name) : null;
            if (data != null) add(stores, storeOfGun(gun), Math.max(0, data.ammo.get()));
        }
        return stores;
    }

    /** Number of additional items which fit, including all native magazines. */
    public static int insertionLimit(VehicleEntity plane, ItemStack stack) {
        var type = type(plane, stack);
        var layout = layout(plane);
        if (layout.isEmpty() || type == null) return stack.getCount();
        var stores = stores(plane);
        var fixed = fixed((Su24mEntity) plane, layout.get());
        int accepted = 0;
        while (accepted < stack.getCount() && stores.size() < layout.get().size()) {
            stores.add(type);
            if (layout.get().assign(fixed, stores).isEmpty()) break;
            accepted++;
        }
        return accepted;
    }

    /**
     * Rounds a native reload may move into this missile magazine. Each gun's Magazine is the
     * most of that store the jet can hang (J-10: 2 large AAM, 2 wire AGM, 2 light AAM), not a
     * share of the stations: the guns share them (J-10: the two inner pylons take either
     * the AAM or the AGM) and the allocator above limits the combined load (J-10: four
     * missiles and the belly store on five stations). Items in the jet's inventory are
     * already part of that load, so moving them is never limited; only rounds from nowhere
     * (a creative ammo box, which would fill every magazine to 2 + 2 + 2) need a free
     * station. A save holding more than fits keeps what it has; nothing more is added.
     */
    public static int reloadLimit(VehicleEntity plane, com.atsuishio.superbwarfare.data.gun.GunData gun, int available) {
        if (available <= 0 || plane.level().isClientSide || !supports(plane)) return available;
        int index = -1;
        for (int i = 0; i < StoreStations.GUNS.size(); i++) {
            String name = StoreStations.GUNS.get(i);
            if (carries(plane, name) && plane.getGunData(name) == gun) index = i;
        }
        if (index < 0) return available;
        var item = ForgeRegistries.ITEMS.getValue(net.minecraft.resources.ResourceLocation.tryParse(AMMO.get(index)));
        if (item == null) return available;
        int held = 0;
        var inventory = plane.getInventory();
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.is(item)) held += Math.max(0, stack.getCount());
        }
        int extra = Math.min(StoreStations.MAX_STATIONS, Math.max(0, available - held));
        int fresh = extra == 0 ? 0 : insertionLimit(plane, new ItemStack(item, extra));
        return Math.min(available, held + fresh);
    }

    /** Also guards КАБ loading: any tablet store needs a free rack station. */
    public static boolean canLoadStormShadow(Su24mEntity plane, int rack) {
        var layout = layout(plane);
        if (layout.isEmpty()) return true;
        int station = layout.get().rackStation(rack);
        if (station < 0) return false;
        var fixed = fixed(plane, layout.get());
        if (fixed[station] != null) return false;
        fixed[station] = Store.STORM_SHADOW;
        return layout.get().assign(fixed, stores(plane)).isPresent();
    }

    public static boolean fits(Su24mEntity plane) {
        return layout(plane).map(layout -> layout.assign(fixed(plane, layout), stores(plane)).isPresent()).orElse(true);
    }

    /** Compact render-only snapshot: three bits per station, never a source of items. */
    public static int visualBits(Su24mEntity plane) {
        var found = layout(plane);
        if (found.isEmpty()) return 0;
        Layout layout = found.get();
        int[] slots = new int[layout.size()];
        for (int rack = 0; rack < layout.racks(); rack++)
            if (plane.loaded(rack)) slots[layout.rackStation(rack)] = StoreStations.RACK;
        int legacy = legacyRackStation(plane, layout);
        if (legacy >= 0) slots[legacy] = StoreStations.RACK;
        int[] count = new int[StoreStations.GUNS.size()];
        for (int gun = 0; gun < count.length; gun++) count[gun] = magazine(plane, StoreStations.GUNS.get(gun));
        int storm = 0;
        var inventory = plane.getInventory();
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            var stack = inventory.getStackInSlot(slot);
            var store = type(plane, stack);
            if (store == null) continue;
            var key = ForgeRegistries.ITEMS.getKey(stack.getItem());
            int n = Math.min(StoreStations.MAX_STATIONS, Math.max(0, stack.getCount()));
            if (store == Store.STORM_SHADOW) storm = Math.min(layout.racks(), storm + n);
            else {
                int gun = AMMO.indexOf(String.valueOf(key));
                count[gun] = Math.min(StoreStations.MAX_STATIONS, count[gun] + n);
            }
        }
        for (int i : layout.order(Store.STORM_SHADOW)) {
            if (storm > 0 && slots[i] == 0) { slots[i] = StoreStations.RACK; storm--; }
        }
        // Underwing stations: AGM-65, then the wire-guided AGM, then the large AAM.
        int[] underwing = {StoreStations.AGM_65, StoreStations.WIRE_AGM, StoreStations.LARGE_AAM};
        for (int i : layout.order(Store.UNDERWING)) {
            if (slots[i] != 0) continue;
            for (int kind : underwing) {
                if (count[kind - StoreStations.AGM_65] > 0) { slots[i] = kind; count[kind - StoreStations.AGM_65]--; break; }
            }
        }
        int light = StoreStations.LIGHT_AAM - StoreStations.AGM_65;
        for (int i : layout.order(Store.LIGHT_AIR_TO_AIR)) {
            if (slots[i] == 0 && count[light] > 0) { slots[i] = StoreStations.LIGHT_AAM; count[light]--; }
        }
        int bits = 0;
        for (int i = 0; i < slots.length; i++) bits |= slots[i] << (i * 3);
        return bits;
    }

    private static int magazine(VehicleEntity plane, String gunName) {
        var gun = carries(plane, gunName) ? plane.getGunData(gunName) : null;
        return gun == null ? 0 : Math.max(0, Math.min(StoreStations.MAX_STATIONS, gun.ammo.get()));
    }
}
