package dev.frontier.industry;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.builder.AmmoItemBuilder;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.*;

/** Server-owned production catalog. Client requests never carry output NBT or prices. */
public final class IndustryCatalog {
    public record Recipe(String id, String title, SiteType factory, ItemStack output,
                         List<ItemStack> cost, int ticks, int fePerTick) {
        public Recipe {
            output = output.copy();
            cost = cost.stream().map(ItemStack::copy).toList();
        }
        @Override public ItemStack output() { return output.copy(); }
        @Override public List<ItemStack> cost() { return cost.stream().map(ItemStack::copy).toList(); }
    }
    private static List<Recipe> cached;
    private static List<Recipe> builtIn = List.of();
    private static int gunCount = -1, itemCount = -1;
    public static synchronized void invalidate() { cached = null; }
    public static synchronized List<Recipe> recipes() {
        int guns = TimelessAPI.getAllCommonGunIndex().size(), items = ForgeRegistries.ITEMS.getKeys().size();
        if (cached != null && guns == gunCount && items == itemCount) return cached;
        Map<String, Recipe> result = new TreeMap<>();
        for (Item item : ForgeRegistries.ITEMS.getValues()) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            if (id == null || item == Items.AIR || id.getNamespace().equals("tacz")) continue;
            SiteType kind = classify(id, item);
            if (kind != null) add(result, id.toString(), item.getDescription().getString(), kind, new ItemStack(item));
        }
        addEnabledArmoredVehicles(result);
        addNativeAviation(result);
        for (var entry : TimelessAPI.getAllCommonGunIndex()) {
            var modes = entry.getValue().getGunData().getFireModeSet();
            var builder = GunItemBuilder.create().setId(entry.getKey()).setAmmoCount(0).setAmmoInBarrel(false);
            if (!modes.isEmpty()) builder.setFireMode(modes.get(0));
            ItemStack gun = builder.build();
            if (!gun.isEmpty()) add(result, entry.getKey().toString(), entry.getKey().getPath().replace('_', ' '), SiteType.EQUIPMENT_FACTORY, gun);
        }
        for (var entry : TimelessAPI.getAllCommonAmmoIndex()) {
            ItemStack ammo = AmmoItemBuilder.create().setId(entry.getKey()).setCount(16).build();
            if (!ammo.isEmpty()) add(result, "ammo/" + entry.getKey(), "Патроны " + entry.getKey().getPath(), SiteType.EQUIPMENT_FACTORY, ammo);
        }
        builtIn = List.copyOf(result.values());
        result.putAll(dev.frontier.industry.integration.catalog.IndustryRecipeReloadListener.recipes());
        cached = List.copyOf(result.values()); gunCount = guns; itemCount = items;
        return cached;
    }
    private static void add(Map<String, Recipe> map, String id, String title, SiteType kind, ItemStack stack) {
        List<ItemStack> cost; int ticks, fe;
        switch (kind) {
            case DRONE_FACTORY -> { cost = List.of(new ItemStack(Items.IRON_INGOT, 8), new ItemStack(Items.COPPER_INGOT, 4), new ItemStack(Items.REDSTONE, 4)); ticks = 900; fe = 32; }
            case VEHICLE_FACTORY -> { cost = List.of(new ItemStack(Items.IRON_INGOT, 64), new ItemStack(Items.COPPER_INGOT, 16), new ItemStack(Items.REDSTONE, 16), new ItemStack(Items.GLASS, 16)); ticks = 2400; fe = 64; }
            case MISSILE_FACTORY -> { cost = List.of(new ItemStack(Items.IRON_INGOT, 24), new ItemStack(Items.COPPER_INGOT, 8), new ItemStack(Items.REDSTONE, 12), new ItemStack(Items.GUNPOWDER, 16)); ticks = 1800; fe = 64; }
            default -> { cost = List.of(new ItemStack(Items.IRON_INGOT, id.startsWith("ammo/") ? 2 : 12), new ItemStack(Items.REDSTONE, 2), new ItemStack(Items.GUNPOWDER, id.startsWith("ammo/") ? 4 : 2)); ticks = id.startsWith("ammo/") ? 200 : 600; fe = 16; }
        }
        map.put(id, new Recipe(id, title, kind, stack.copy(), cost, ticks, fe));
    }
    private static void addEnabledArmoredVehicles(Map<String, Recipe> result) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("frontier_arcade_vehicles")) return;
        try {
            Object hidden = Class.forName("dev.frontier.arcade.ArcadeVehicles").getField("REPLACED").get(null);
            var create = Class.forName("com.atsuishio.superbwarfare.item.container.ContainerBlockItem")
                .getMethod("createInstance", net.minecraft.world.entity.EntityType.class);
            for (String id : List.of("bmp_2", "bradley", "lav_25")) {
                if (!(hidden instanceof Set<?> excluded) || excluded.contains(id)) continue;
                var type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("superbwarfare", id));
                if (type == null) continue;
                Object made = create.invoke(null, type);
                if (made instanceof ItemStack stack && !stack.isEmpty())
                    add(result, "superbwarfare:" + id, switch (id) { case "bmp_2" -> "БМП-2"; case "bradley" -> "Bradley"; default -> "LAV-25"; }, SiteType.VEHICLE_FACTORY, stack);
            }
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            com.mojang.logging.LogUtils.getLogger().warn("Native armored vehicle production API unavailable", unavailable);
        }
    }
    private static void addNativeAviation(Map<String, Recipe> result) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("frontier_arcade_vehicles")) return;
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        try {
            Class<?> api = Class.forName("dev.frontier.arcade.shop.NativeAviationCatalog");
            Object offers = api.getMethod("offers").invoke(null);
            if (!(offers instanceof List<?> list)) return;
            for (Object offer : list) {
                String id = (String) offer.getClass().getMethod("id").invoke(offer);
                String name = (String) offer.getClass().getMethod("title").invoke(offer);
                String kind = offer.getClass().getMethod("kind").invoke(offer).toString();
                Object made = api.getMethod("createStack", net.minecraft.server.level.ServerLevel.class, String.class).invoke(null, server.overworld(), id);
                if (!(made instanceof ItemStack stack) || stack.isEmpty()) continue;
                String key = id.toLowerCase(Locale.ROOT);
                SiteType factory = kind.equals("AIRCRAFT") ? SiteType.VEHICLE_FACTORY : kind.equals("AMMUNITION") ? SiteType.EQUIPMENT_FACTORY
                    : key.contains("drone") || key.contains("uav") || key.contains("shahed") || key.contains("mavic") || key.contains("reb") || key.contains("camera") || key.contains("monitor")
                    ? SiteType.DRONE_FACTORY : SiteType.EQUIPMENT_FACTORY;
                add(result, id, name, factory, stack);
            }
        } catch (ClassNotFoundException legacyArcade) { /* The released1.1 pack has no modern aircraft shop. */ }
        catch (ReflectiveOperationException | LinkageError unavailable) {
            com.mojang.logging.LogUtils.getLogger().warn("Native aviation factory API unavailable", unavailable);
        }
    }
    public static Recipe resolve(String query, SiteType type) {
        String q = normalize(query);
        if (q.isBlank() || q.length() > 128) return null;
        var allowed = recipes().stream().filter(r -> r.factory == type && visible(r)).toList();
        for (Recipe r : allowed) if (normalize(r.id).equals(q)) return r;
        var matches = allowed.stream().filter(r -> normalize(r.title).equals(q)
            || normalize(r.id.substring(r.id.lastIndexOf(':') + 1)).equals(q)).toList();
        if (matches.size() == 1) return matches.get(0);
        matches = allowed.stream().filter(r -> normalize(r.title).contains(q) || normalize(r.id).contains(q)).toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }
    public static String normalize(String s) { return s.trim().toLowerCase(Locale.ROOT).replace('ё', 'е').replace("ак47", "ak47").replace("ак-47", "ak47"); }
    /** Keep legacy recipes for already-paid saved queues, while every new order uses native ready-made weapons. */
    public static boolean visible(Recipe recipe) {
        return !legacyCombat(recipe.id()) && !dev.frontier.industry.integration.catalog.IndustryRecipeReloadListener.hidden(recipe.id());
    }
    /** Only the original complete catalog can recover a paid order written before template snapshots. */
    public static Recipe legacyRecipe(String id, SiteType type) {
        recipes();
        return builtIn.stream().filter(recipe -> recipe.id().equals(id) && recipe.factory() == type).findFirst().orElse(null);
    }
    public static boolean legacyCombat(String id) {
        return net.minecraftforge.fml.ModList.get().isLoaded("deepstrike") && id.startsWith("frontier_industry:")
            && Set.of("ballistic_missile", "interceptor_ammo", "missile_launcher", "s300_launcher", "s400_launcher", "patriot_launcher")
                .contains(id.substring(id.indexOf(':') + 1));
    }
    private static SiteType classify(ResourceLocation id, Item item) {
        String p = id.getPath(), namespace = id.getNamespace(), clazz = item.getClass().getName().toLowerCase(Locale.ROOT);
        String simple = item.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if(namespace.equals("frontier_aviation")&&Set.of("su25","f16","j10").contains(p))return SiteType.VEHICLE_FACTORY;
        if (namespace.equals("deepstrike") && Set.of("fp5", "storm_shadow", "jagm", "peacekeeper", "spike_nlos_missile",
            "fp5_launcher", "jagm_launcher", "peacekeeper_platform", "mim_104_patriot_truck", "iron_dome", "an_mpq_65",
            "pac_2", "pac_3", "tamir", "jagm_camera_tablet", "deepstrike_laptop", "air_defense_terminal", "missile_assembly_station").contains(p))
            return SiteType.MISSILE_FACTORY;
        if (namespace.equals(IndustryMod.ID)) {
            if (p.equals("vehicle_gunboat_deployer")) return SiteType.VEHICLE_FACTORY;
            if (Set.of("ballistic_missile", "interceptor_ammo", "missile_launcher", "s300_launcher", "s400_launcher", "patriot_launcher").contains(p)) return SiteType.MISSILE_FACTORY;
            return null;
        }
        if (namespace.equals("uncomplicatedfpv") && (p.contains("drone") || p.contains("shahed") || p.contains("reb"))) return SiteType.DRONE_FACTORY;
        if (namespace.equals("superbwarfare") && (p.equals("drone") || p.equals("swarm_drone"))) return SiteType.DRONE_FACTORY;
        if (namespace.equals("simpleplanes") && simple.equals("planeitem")) return SiteType.VEHICLE_FACTORY;
        if (namespace.startsWith("trajan") && (p.contains("shell") || p.contains("bullet"))) return SiteType.EQUIPMENT_FACTORY;
        if (namespace.equals("trajanstanks")) return simple.equals("tankspawnegg") || simple.equals("atspawnegg") ? SiteType.VEHICLE_FACTORY : null;
        if (namespace.equals("trajanscore") && p.contains("example")) return null;
        // These SBW items require external entity/gun NBT; a blank stack is not a usable vehicle/weapon.
        if (namespace.equals("superbwarfare") && Set.of("container", "small_container", "lucky_container", "vehicle_gun").contains(p)) return null;
        if (item instanceof BoatItem || clazz.contains("vehicleitem") || clazz.contains("aircraftitem")) return SiteType.VEHICLE_FACTORY;
        if (item instanceof ArmorItem || item instanceof ShieldItem || item instanceof SwordItem || item instanceof ProjectileWeaponItem
            || item instanceof TridentItem) return SiteType.EQUIPMENT_FACTORY;
        if (clazz.contains("ammoitem") || clazz.contains("shellitem") || clazz.contains("bulletitem")
            || clazz.contains("weaponitem") || clazz.endsWith("gunitem")) return SiteType.EQUIPMENT_FACTORY;
        if (namespace.equals("superbwarfare") && (clazz.contains("gunitem") || clazz.contains("ammo") || clazz.contains("armor")
            || p.contains("grenade") || p.contains("rocket") || p.contains("shell") || p.contains("bullet") || p.contains("ammo"))) return SiteType.EQUIPMENT_FACTORY;
        return null;
    }
}
