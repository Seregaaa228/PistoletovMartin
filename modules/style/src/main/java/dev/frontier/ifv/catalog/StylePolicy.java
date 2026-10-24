package dev.frontier.ifv.catalog;

import com.atsuishio.superbwarfare.item.gun.GunItem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.InvocationTargetException;
import java.util.Set;

/** Acquisition-only policy. Nothing here unregisters, alters or discards world content. */
public final class StylePolicy {
    public static final Set<String> KEPT_ENTITY_PATHS = Set.of(
        "bmp_2", "bradley", "lav_25", "lav_ad", "hpj_11", "mortar", "tow",
        "speedboat", "tiny_speedboat", "drone", "ah_6", "mi_28",
        "vehicle_assembling_table"
    );

    /** The complete native vehicle acquisition catalog for the pinned SBW version. */
    public static final Set<String> VEHICLE_ENTITY_PATHS = Set.of(
        "type_63", "mk_42", "hpj_11", "mle_1934", "bl_132", "annihilator",
        "laser_tower", "waveforce_tower", "tow", "speedboat", "tiny_speedboat",
        "wheel_chair", "lav_150", "lav_ad", "lav_25", "bmp_2", "bradley",
        "ztz_99a", "t_90a", "m_1a_2", "yx_100", "prism_tank", "plz_05",
        "fh_77bw", "tom_6", "ah_6", "mi_28", "kv_16", "ju_87", "a_10a",
        "j_16", "ac_130h", "air_sheep", "happiest_ghast", "kirov", "drone",
        "mortar", "vehicle_assembling_table", "sodayo_pick_up", "sodayo_pick_up_hmg",
        "sodayo_pick_up_rocket", "sodayo_pick_up_tow", "truck"
    );

    public static final Set<String> KEPT_GUN_PATHS = Set.of("rpg", "igla_9k38", "repair_tool");
    public static final Set<String> REMOVED_MODS = Set.of("trajanstanks", "simpleplanes");

    // Diagnostic flags are passive observations for the isolated acceptance harness.
    public static volatile boolean arcadeEntityHookSeen;
    public static volatile boolean arcadeStackHookSeen;
    public static volatile boolean offersHookSeen;
    public static volatile boolean offerLookupHookSeen;
    public static volatile boolean createStackHookSeen;
    public static volatile boolean industryVisibilityHookSeen;
    public static volatile boolean industryLegacyAssemblySeen;
    public static volatile boolean nativeAssemblyMenuHookSeen;
    public static volatile boolean nativeAssemblyScreenHookSeen;
    public static volatile boolean containerPoolHookSeen;
    public static volatile boolean nativeJeiRecipeHookSeen;
    public static volatile boolean creativeFilterSeen;
    public static volatile int hiddenOfferCount;
    public static volatile int hiddenCreativeCount;
    public static volatile int hiddenContainerPoolEntries;
    public static volatile int hiddenNativeJeiRecipes;

    private StylePolicy() { }

    /** Entity IDs are typed separately from items: projectile/ammunition names can overlap. */
    public static boolean blockedEntity(ResourceLocation id) {
        return id != null && (REMOVED_MODS.contains(id.getNamespace())
            || (id.getNamespace().equals("superbwarfare")
                && VEHICLE_ENTITY_PATHS.contains(id.getPath())
                && !KEPT_ENTITY_PATHS.contains(id.getPath())));
    }

    public static boolean blockedEntity(String id) {
        return id != null && blockedEntity(ResourceLocation.tryParse(id));
    }

    /** Ammo, armor, repairs, batteries, supply devices and other namespaces remain usable. */
    public static boolean blocked(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Item item = stack.getItem();
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(item);
        if (itemId != null && REMOVED_MODS.contains(itemId.getNamespace())) return true;
        if (blockedGun(item, itemId)) return true;
        if (item instanceof SpawnEggItem egg) {
            EntityType<?> type = egg.getType(stack.getTag());
            if (type != null && blockedEntity(EntityType.getKey(type))) return true;
        }

        CompoundTag root = stack.getTag();
        if (root == null) return false;
        if (blockedPayload(root)) return true;
        if (root.contains("BlockEntityTag", Tag.TAG_COMPOUND)
            && blockedPayload(root.getCompound("BlockEntityTag"))) return true;
        return root.contains("EntityTag", Tag.TAG_COMPOUND)
            && blockedPayload(root.getCompound("EntityTag"));
    }

    private static boolean blockedGun(Item item, ResourceLocation id) {
        return id != null && id.getNamespace().equals("superbwarfare")
            && item instanceof GunItem && !KEPT_GUN_PATHS.contains(id.getPath());
    }

    private static boolean blockedPayload(CompoundTag tag) {
        if (blockedEntity(tag.getString("EntityType")) || blockedEntity(tag.getString("id"))) return true;
        return tag.contains("Entity", Tag.TAG_COMPOUND)
            && blockedEntity(tag.getCompound("Entity").getString("id"));
    }

    /** Used by both native shop metadata and server stack construction. */
    public static boolean blockedOffer(String offerId) {
        ResourceLocation id = offerId == null ? null : ResourceLocation.tryParse(offerId);
        if (id == null) return true;
        if (blockedEntity(id)) return true;
        return blockedGun(ForgeRegistries.ITEMS.getValue(id), id);
    }

    public static boolean blockedOfferRecord(Object offer) {
        if (offer == null) return true;
        try {
            Object id = offer.getClass().getMethod("id").invoke(offer);
            return !(id instanceof String value) || blockedOffer(value);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            // The known server catalog is an immutable Offer record. Unknown shapes do
            // not become an unreviewed acquisition route through this adapter.
            return true;
        }
    }

    /** Read a copy of the stable public Recipe.output() without linking optional Industry. */
    public static boolean blockedIndustryRecipe(Object recipe) {
        if (recipe == null) return false;
        try {
            Object output = recipe.getClass().getMethod("output").invoke(recipe);
            return output instanceof ItemStack stack && blocked(stack);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | LinkageError unavailable) {
            // A future incompatible Industry version keeps its own visibility behavior.
            return false;
        }
    }

    /**
     * Industry reconstructs saved paid queues from its complete immutable recipe map.
     * Its private catalog builder may inspect/assemble a template for a hidden offer;
     * visible() still rejects that template for every new order. Stack-local caller
     * detection avoids persistent bypass state if catalog construction throws.
     */
    public static boolean industryRecipeAssembly() {
        boolean assembling = StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
            frame.getClassName().equals("dev.frontier.industry.IndustryCatalog")
                && frame.getMethodName().equals("addNativeAviation")));
        if (assembling) industryLegacyAssemblySeen = true;
        return assembling;
    }

    /** Native assembly has no paid queue; this output check runs before payment. */
    public static boolean blockedNativeAssemblingRecipe(Object recipe) {
        if (recipe == null) return false;
        try {
            Object result = recipe.getClass().getField("result").get(recipe);
            if (result == null) return false;
            Object output = result.getClass().getMethod("getResult").invoke(result);
            return output instanceof ItemStack stack && blocked(stack);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return false;
        }
    }

    /** Retain the actual weighted pair; only omit forbidden entity IDs from a copy. */
    public static boolean blockedContainerPoolEntry(Object pair) {
        if (pair == null) return false;
        try {
            Object first = pair.getClass().getMethod("first").invoke(pair);
            return first instanceof String id && blockedEntity(id);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return false;
        }
    }
}
