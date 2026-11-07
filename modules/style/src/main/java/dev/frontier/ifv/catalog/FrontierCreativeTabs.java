package dev.frontier.ifv.catalog;

import dev.frontier.ifv.LowpolyIfv;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackLinkedSet;
import net.minecraft.world.item.Items;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Additional navigable tabs; native tabs and original NBT-bearing stacks remain available. */
public final class FrontierCreativeTabs {
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, LowpolyIfv.ID);
    private static final Set<String> SOURCES = Set.of("superbwarfare", "deepstrike", "uncomplicatedfpv", "tacz", "tacteq", "frontier_arcade_vehicles", "frontier_industry", "frontier_aviation", "frontier_nations");
    /** The arcade module's quick-deploy item, looked up by name so this module does not depend on it. */
    private static final ResourceLocation DEPLOY_ITEM = new ResourceLocation("frontier_arcade_vehicles", "vehicle_deploy");
    /** Mirrors the arcade module's VehicleDeploy.ORDER: the vehicles it has icons for, in catalog order. */
    private static final List<String> DEPLOY_ORDER = List.of(
            "superbwarfare:t_90a", "superbwarfare:m_1a_2", "superbwarfare:ztz_99a", "superbwarfare:yx_100",
            "superbwarfare:prism_tank", "superbwarfare:bmp_2", "superbwarfare:bradley", "superbwarfare:lav_25",
            "superbwarfare:plz_05", "frontier_arcade_vehicles:msta_s", "frontier_arcade_vehicles:bohdana",
            "frontier_arcade_vehicles:grad", "superbwarfare:tow", "superbwarfare:lav_ad", "superbwarfare:hpj_11",
            "superbwarfare:ah_6", "superbwarfare:mi_28", "superbwarfare:speedboat", "superbwarfare:tiny_speedboat");
    /** Our own vehicles: no SBW crate to replace, so the catalog adds their quick-deploy items itself. */
    private static final List<String> OWN_VEHICLES = List.of(
            "frontier_arcade_vehicles:msta_s", "frontier_arcade_vehicles:bohdana", "frontier_arcade_vehicles:grad");
    private static CreativeModeTab.ItemDisplayParameters cachedParameters;
    private static Map<Group, Set<ItemStack>> cached;

    private enum Group {
        FACTORIES("Заводы и промышленность", Items.BLAST_FURNACE),
        CONTROLS("Пульты и управление", Items.COMPASS),
        WEAPONS("Оружие", Items.CROSSBOW),
        AMMO("Боеприпасы", Items.ARROW),
        MISSILES("Ракеты и бомбы", Items.FIREWORK_ROCKET),
        AIRCRAFT("Самолёты и вертолёты", Items.ELYTRA),
        SHIPS("Корабли и катера", Items.OAK_BOAT),
        VEHICLES("Наземная техника", Items.MINECART),
        AIR_DEFENSE("ПВО и радары", Items.SPYGLASS),
        DRONES("Дроны и БПЛА", Items.PHANTOM_MEMBRANE),
        ARMOR("Броня и экипировка", Items.IRON_CHESTPLATE),
        OTHER("Детали и прочее", Items.REDSTONE);

        final String title;
        final net.minecraft.world.item.Item icon;
        Group(String title, net.minecraft.world.item.Item icon) { this.title = title; this.icon = icon; }
    }

    private FrontierCreativeTabs() {}

    public static void register(IEventBus bus) {
        // First of the catalog: every vehicle that goes straight onto the ground, no crate or crowbar.
        TABS.register("catalog_0_quick_vehicles", () -> CreativeModeTab.builder()
                .title(Component.literal("Frontier · Вся техника — сразу в бой"))
                .icon(() -> {
                    ItemStack icon = deployStack("superbwarfare:plz_05");
                    return icon.isEmpty() ? new ItemStack(Items.MINECART) : icon;
                })
                .displayItems((parameters, output) -> output.acceptAll(quickVehicles(contents(parameters))))
                .build());
        for (Group group : Group.values()) {
            TABS.register("catalog_" + group.name().toLowerCase(Locale.ROOT), () -> CreativeModeTab.builder()
                    .title(Component.literal("Frontier · " + group.title))
                    .icon(() -> new ItemStack(group.icon))
                    .displayItems((parameters, output) -> output.acceptAll(contents(parameters).get(group)))
                    .build());
        }
        TABS.register(bus);
    }

    private static synchronized Map<Group, Set<ItemStack>> contents(CreativeModeTab.ItemDisplayParameters parameters) {
        if (parameters == cachedParameters && cached != null) return cached;
        Map<Group, Set<ItemStack>> groups = new EnumMap<>(Group.class);
        for (Group group : Group.values()) groups.put(group, ItemStackLinkedSet.createTypeAndTagSet());
        for (CreativeModeTab tab : CreativeModeTabsSnapshot.sourceTabs()) {
            ResourceLocation tabId = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
            // Build each original tab with the same feature flags and registry context. This retains
            // mod-generated variants (TaCZ guns/ammo and vehicle crates), not just bare items.
            tab.buildContents(parameters);
            for (ItemStack stack : tab.getDisplayItems()) {
                ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
                if (itemId == null || !SOURCES.contains(itemId.getNamespace()) || StylePolicy.blocked(stack)) continue;
                ItemStack copy = shown(stack);
                ResourceLocation shownId = ForgeRegistries.ITEMS.getKey(copy.getItem());
                groups.get(classify(copy, shownId, tabId)).add(copy);
            }
        }
        for (String vehicle : OWN_VEHICLES) {
            ItemStack deploy = deployStack(vehicle);
            if (!deploy.isEmpty()) groups.get(Group.VEHICLES).add(deploy);
        }
        for (Group group : Group.values()) groups.put(group, deployFirst(groups.get(group)));
        cachedParameters = parameters;
        cached = groups;
        return groups;
    }

    /**
     * The stack the catalog shows for a source stack: an SBW vehicle crate becomes the arcade module's quick-deploy
     * item for the same vehicle when there is one; everything else is shown as it is.
     */
    private static ItemStack shown(ItemStack stack) {
        ItemStack copy = stack.copy();
        copy.setCount(1);
        CompoundTag crate = stack.getTagElement("BlockEntityTag");
        ResourceLocation vehicle = crate == null ? null : ResourceLocation.tryParse(crate.getString("EntityType"));
        if (vehicle == null || !DEPLOY_ORDER.contains(vehicle.toString())) return copy;
        ItemStack deploy = deployStack(vehicle.toString());
        return deploy.isEmpty() ? copy : deploy;
    }

    private static ItemStack deployStack(String vehicle) {
        Item item = ForgeRegistries.ITEMS.getValue(DEPLOY_ITEM);
        if (item == null || item == Items.AIR) return ItemStack.EMPTY;
        ItemStack stack = new ItemStack(item);
        stack.getOrCreateTag().putString("Vehicle", vehicle);
        return stack;
    }

    private static int deployIndex(ItemStack stack) {
        if (!DEPLOY_ITEM.equals(ForgeRegistries.ITEMS.getKey(stack.getItem())) || !stack.hasTag()) return -1;
        ResourceLocation vehicle = ResourceLocation.tryParse(stack.getTag().getString("Vehicle"));
        return vehicle == null ? -1 : DEPLOY_ORDER.indexOf(vehicle.toString());
    }

    /** Quick-deploy vehicles lead their tab, in catalog order; the rest keep their source order. */
    private static Set<ItemStack> deployFirst(Set<ItemStack> stacks) {
        Set<ItemStack> ordered = ItemStackLinkedSet.createTypeAndTagSet();
        stacks.stream().filter(stack -> deployIndex(stack) >= 0)
                .sorted(Comparator.comparingInt(FrontierCreativeTabs::deployIndex)).forEach(ordered::add);
        ordered.addAll(stacks);
        return ordered;
    }

    /** Every quick-deploy vehicle and every aircraft (all aircraft items deploy directly). */
    private static Set<ItemStack> quickVehicles(Map<Group, Set<ItemStack>> groups) {
        Set<ItemStack> all = ItemStackLinkedSet.createTypeAndTagSet();
        for (Group group : List.of(Group.VEHICLES, Group.AIR_DEFENSE, Group.SHIPS))
            groups.get(group).stream().filter(stack -> deployIndex(stack) >= 0).forEach(all::add);
        all.addAll(groups.get(Group.AIRCRAFT));
        return all;
    }

    /** Hide an old tab only after every visible stack is represented in the new tabs. */
    public static boolean hideRedundantTab(CreativeModeTab tab) {
        ResourceLocation tabId = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
        if (cached == null || tabId == null || !SOURCES.contains(tabId.getNamespace())
                || tab.getDisplayItems().isEmpty()) return false;
        for (ItemStack stack : tab.getDisplayItems()) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (id == null || !SOURCES.contains(id.getNamespace())) return false;
            ItemStack candidate = shown(stack);
            ResourceLocation shownId = ForgeRegistries.ITEMS.getKey(candidate.getItem());
            if (!cached.get(classify(candidate, shownId, tabId)).contains(candidate)) return false;
        }
        return true;
    }

    private static Group classify(ItemStack stack, ResourceLocation id, ResourceLocation tab) {
        String path = id.getPath().toLowerCase(Locale.ROOT);
        String tag = stack.hasTag() ? stack.getTag().toString().toLowerCase(Locale.ROOT) : "";
        String text = path + " " + tag;
        String tabName = tab == null ? "" : tab.toString().toLowerCase(Locale.ROOT);
        String className = stack.getItem().getClass().getName().toLowerCase(Locale.ROOT);
        if (CatalogRules.isFactory(id.getNamespace(), path)) return Group.FACTORIES;
        if (CatalogRules.isArmor(id.getNamespace(), path, className, stack.getItem() instanceof ArmorItem)) return Group.ARMOR;
        if (has(text, "controller", "remote", "monitor", "tablet", "radio", "laptop", "terminal", "ground_station", "pda")) return Group.CONTROLS;
        if (has(text, "patriot", "iron_dome", "s300", "s400", "s_300", "s_400", "air_defense", "radar", "an_mpq", "lav_ad", "avenger", "hpj_11")) return Group.AIR_DEFENSE;
        if (has(text, "drone", "mavic", "fpv", "uav", "shahed", "geranium", "reb", "relay_plane")) return Group.DRONES;
        if (has(text, "missile", "rocket", "warhead", "bomb", "storm_shadow", "peacekeeper", "jagm", "pac_2", "pac_3", "tamir")) return Group.MISSILES;
        if (has(text, "speedboat", "boat", "ship", "gunboat", "vessel")) return Group.SHIPS;
        if (has(text, "su25", "su_25", "su24", "su_24", "f16", "f_16", "j10", "j_10", "aircraft", "helicopter", "plane", "ah_6", "mi_28", "ju_87", "a_10", "j_16", "ac_130")) return Group.AIRCRAFT;
        if (has(text, "bmp_2", "bradley", "lav_25", "tank", "vehicle", "truck", "jeep", "apc", "chassis", "carrier", "launcher", "tow")) return Group.VEHICLES;
        if (has(text, "ammo", "bullet", "cartridge", "shell", "magazine", "grenade") || tabName.contains("ammo")) return Group.AMMO;
        if (id.getNamespace().equals("tacz") && !has(tabName, "attachment", "other", "ammo")) return Group.WEAPONS;
        if (has(className, "gunitem", "weaponitem") || has(path, "rifle", "pistol", "shotgun", "sniper", "rpg", "igla")) return Group.WEAPONS;
        return Group.OTHER;
    }

    private static boolean has(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }

    private static final class CreativeModeTabsSnapshot {
        static List<CreativeModeTab> sourceTabs() {
            return BuiltInRegistries.CREATIVE_MODE_TAB.stream()
                    .filter(tab -> {
                        ResourceLocation id = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
                        return id != null && SOURCES.contains(id.getNamespace()) && !id.getNamespace().equals(LowpolyIfv.ID);
                    }).toList();
        }
    }
}
