package dev.frontier.arcade.shop;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Server-owned offers for the installed native aviation mods. Prices are emeralds.
 * The caller owns payment and delivery; clients supply only an offer ID.
 */
public final class NativeAviationCatalog {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String VEHICLE_CLASS = "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity";
    private static final String CONTAINER_CLASS = "com.atsuishio.superbwarfare.item.container.ContainerBlockItem";
    public enum Kind { AIRCRAFT, AMMUNITION, EQUIPMENT }

    /** Immutable metadata. No mutable ItemStack or client-controlled NBT is retained. */
    public record Offer(String id, String title, int price, Kind kind, int count) {
        public Offer {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(kind, "kind");
            if (price <= 0 || count <= 0) throw new IllegalArgumentException("Invalid offer quantity or price");
        }
    }

    private record Definition(Offer offer, boolean aircraft) { }

    private static final Map<String, Definition> DEFINITIONS = definitions();
    private static final List<Offer> OFFERS = DEFINITIONS.values().stream().map(Definition::offer).toList();

    private NativeAviationCatalog() { }

    public static List<Offer> offers() {
        return OFFERS;
    }

    public static Optional<Offer> offer(String id) {
        Definition definition = DEFINITIONS.get(id);
        return definition == null ? Optional.empty() : Optional.of(definition.offer());
    }

    /**
     * Builds a fresh output using the server whitelist. Unknown/unavailable IDs return EMPTY.
     * Call on the server thread and validate the result before charging the player.
     * Aircraft are native containers with one full finite tank and no ammunition.
     * Native equipment uses its original deployer and original behavior.
     */
    public static ItemStack createStack(ServerLevel level, String id) {
        Definition definition = DEFINITIONS.get(id);
        if (definition == null || level == null || !level.getServer().isSameThread()) return ItemStack.EMPTY;
        try {
            if (!definition.aircraft()) return createItem(definition.offer());
            return createAircraft(level, definition.offer());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            LOGGER.warn("Cannot create native aviation offer {}", id, failure);
            return ItemStack.EMPTY;
        }
    }

    private static ItemStack createItem(Offer offer) {
        ResourceLocation id = ResourceLocation.tryParse(offer.id());
        if (id == null || !ForgeRegistries.ITEMS.containsKey(id)) return ItemStack.EMPTY;
        Item item = ForgeRegistries.ITEMS.getValue(id);
        if (item == null || item == Items.AIR) return ItemStack.EMPTY;
        ItemStack result = new ItemStack(item, offer.count());
        return offer.count() <= result.getMaxStackSize() ? result : ItemStack.EMPTY;
    }

    private static ItemStack createAircraft(ServerLevel level, Offer offer) throws ReflectiveOperationException {
        ResourceLocation id = ResourceLocation.tryParse(offer.id());
        if (id == null || !ForgeRegistries.ENTITY_TYPES.containsKey(id)) return ItemStack.EMPTY;
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(id);
        if (type == null) return ItemStack.EMPTY;

        // Never add this assembly instance to the world or tick it.
        Entity entity = type.create(level);
        if (entity == null) return ItemStack.EMPTY;
        try {
            Class<?> vehicleClass = Class.forName(VEHICLE_CLASS);
            if (!vehicleClass.isInstance(entity)) return ItemStack.EMPTY;
            Object nativeStorage = vehicleClass.getMethod("getEnergyStorage").invoke(entity);
            if (!(nativeStorage instanceof IEnergyStorage energy)) return ItemStack.EMPTY;
            int capacity = energy.getMaxEnergyStored();
            // A missing SBW data definition defaults to MAX_VALUE. Fail closed instead of
            // selling that placeholder or adding an infinite/refill override.
            if (capacity <= 0 || capacity == Integer.MAX_VALUE) return ItemStack.EMPTY;
            energy.receiveEnergy(capacity - energy.getEnergyStored(), false);
            if (energy.getEnergyStored() != capacity) return ItemStack.EMPTY;

            Method pack = Class.forName(CONTAINER_CLASS).getMethod("createInstance", Entity.class);
            Object packed = pack.invoke(null, entity);
            if (!(packed instanceof ItemStack stack) || stack.isEmpty()) return ItemStack.EMPTY;
            CompoundTag block = BlockItem.getBlockEntityData(stack);
            if (block == null || !offer.id().equals(block.getString("EntityType"))
                || !block.contains("Entity", Tag.TAG_COMPOUND)) return ItemStack.EMPTY;

            CompoundTag data = block.getCompound("Entity");
            if (!data.contains("Energy", Tag.TAG_INT) || data.getInt("Energy") != capacity) return ItemStack.EMPTY;
            // GunData's absent Ammo/VirtualAmmo values default to zero. Loading an empty
            // WeaponState rebuilds the original native weapon definitions, without rounds.
            data.put("WeaponState", new CompoundTag());
            data.putInt("DecoyCount", 0);
            CompoundTag inventory = data.getCompound("Inventory");
            inventory.put("Items", new ListTag());
            data.put("Inventory", inventory);
            data.remove("Items");
            // A factory may copy its output template. Deployment must retain the freshly
            // constructed native entity UUID rather than cloning the packing instance.
            for(String key:List.of("UUID","Pos","Motion","Rotation","Passengers"))data.remove(key);
            return stack.copy();
        } finally {
            entity.invalidateCaps();
        }
    }

    private static Map<String, Definition> definitions() {
        Map<String, Definition> map = new LinkedHashMap<>();
        aircraft(map, "a_10a", "A-10: штурмовик", 384);
        aircraft(map, "j_16", "J-16: истребитель", 448);
        aircraft(map, "ac_130h", "AC-130H: огневая поддержка", 640);
        aircraft(map, "ju_87", "Ju-87: бомбардировщик", 192);
        aircraft(map, "ah_6", "AH-6: лёгкий вертолёт", 256);
        aircraft(map, "mi_28", "Ми-28: ударный вертолёт", 448);
        aircraft(map, "kv_16", "KV-16 Ghost Fighter", 160);
        aircraft(map, "tom_6", "Tom F6F", 96);

        ammo(map, "superbwarfare:small_shell_ap", "Малокалиберные бронебойные снаряды", 12, 64);
        ammo(map, "superbwarfare:small_shell_he", "Малокалиберные фугасные снаряды", 12, 64);
        ammo(map, "superbwarfare:medium_shell_ap", "Средние бронебойные снаряды", 16, 32);
        ammo(map, "superbwarfare:medium_shell_he", "Средние фугасные снаряды", 16, 32);
        ammo(map, "superbwarfare:large_shell_ap", "Крупнокалиберные бронебойные снаряды", 16, 8);
        ammo(map, "superbwarfare:large_shell_he", "Крупнокалиберные фугасные снаряды", 16, 8);
        ammo(map, "superbwarfare:small_rocket", "Малые неуправляемые ракеты", 16, 16);
        ammo(map, "superbwarfare:medium_anti_ground_missile", "Управляемые ракеты по наземным целям", 16, 4);
        ammo(map, "superbwarfare:large_anti_ground_missile", "Тяжёлые ракеты по наземным целям", 20, 2);
        ammo(map, "superbwarfare:medium_anti_air_missile", "Ракеты воздух–воздух", 16, 4);
        ammo(map, "superbwarfare:small_aerial_bomb", "Малые авиабомбы", 8, 4);
        ammo(map, "superbwarfare:medium_aerial_bomb", "Средние авиабомбы", 12, 2);
        ammo(map, "superbwarfare:large_aerial_bomb", "Тяжёлая авиабомба", 16, 1);
        ammo(map, "superbwarfare:rifle_ammo", "Винтовочные патроны", 4, 64);
        ammo(map, "superbwarfare:heavy_ammo", "Крупнокалиберные патроны", 8, 64);
        ammo(map, "superbwarfare:mortar_shell", "Миномётный снаряд", 2, 1);
        ammo(map, "superbwarfare:flying_flare_ammo", "Авиационные тепловые ловушки", 8, 64);
        ammo(map, "minecraft:melon", "Дыня: боеприпас Tom F6F", 4, 1);

        ammo(map, "superbwarfare:rgo_grenade", "Граната РГО: подвес Mavic", 2, 1);
        ammo(map, "superbwarfare:rpg_rocket_standard", "PG-7: боеприпас FPV", 4, 1);
        ammo(map, "superbwarfare:rpg_rocket_tbg", "TBG: боеприпас FPV", 6, 1);

        equipment(map, "superbwarfare:drone", "Разведывательный дрон SBW", 32);
        equipment(map, "frontier_arcade_vehicles:pocket_controller", "Карманный пульт: Mavic и FPV", 16);
        equipment(map, "frontier_arcade_vehicles:ground_station", "Наземная станция: крупные БПЛА", 32);
        equipment(map, "uncomplicatedfpv:mavic_drone_no_drop", "Mavic: разведывательный", 64);
        equipment(map, "uncomplicatedfpv:mavic_drone_with_drop", "Mavic: со сбросом", 96);
        equipment(map, "uncomplicatedfpv:fpv_drone", "FPV: ударный", 64);
        equipment(map, "uncomplicatedfpv:shahed136", "Шахед-136", 128);
        equipment(map, "uncomplicatedfpv:radio", "Радиостанция Шахеда", 16);
        equipment(map, "uncomplicatedfpv:reb", "Стационарный РЭБ", 96);
        equipment(map, "uncomplicatedfpv:reb_mini", "Малый РЭБ", 64);
        equipment(map, "frontier_arcade_vehicles:geranium_2_drone", "Герань-2: чёрный Шахед FPV", 160);
        equipment(map, "frontier_arcade_vehicles:relay_drone", "Связист: самолёт-ретранслятор", 192);
        equipment(map, "frontier_arcade_vehicles:uav_camera", "Камера БПЛА", 16);
        return Collections.unmodifiableMap(map);
    }

    private static void aircraft(Map<String, Definition> map, String path, String title, int price) {
        add(map, new Offer("superbwarfare:" + path, title, price, Kind.AIRCRAFT, 1), true);
    }

    private static void ammo(Map<String, Definition> map, String id, String title, int price, int count) {
        add(map, new Offer(id, title, price, Kind.AMMUNITION, count), false);
    }

    private static void equipment(Map<String, Definition> map, String id, String title, int price) {
        add(map, new Offer(id, title, price, Kind.EQUIPMENT, 1), false);
    }

    private static void add(Map<String, Definition> map, Offer offer, boolean aircraft) {
        if (map.putIfAbsent(offer.id(), new Definition(offer, aircraft)) != null) {
            throw new IllegalStateException("Duplicate native aviation offer " + offer.id());
        }
    }
}
