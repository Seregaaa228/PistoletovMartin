package dev.frontier.industry.integration.catalog;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import dev.frontier.industry.IndustryCatalog;
import dev.frontier.industry.SiteType;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

/** A bounded datapack contract that KubeJS can generate without arbitrary Java access. */
public final class IndustryRecipeReloadListener extends SimpleJsonResourceReloadListener {
    private static volatile Publication current = new Publication(Map.of(), Set.of());

    public IndustryRecipeReloadListener() {
        super(new Gson(), "frontier_industry/production");
    }

    public static Map<String, IndustryCatalog.Recipe> recipes() {
        return current.recipes();
    }

    public static boolean hidden(String id) {
        return current.hidden().contains(id);
    }

    public static void clear() {
        current = new Publication(Map.of(), Set.of());
        IndustryCatalog.invalidate();
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager, ProfilerFiller profiler) {
        Map<String, IndustryCatalog.Recipe> recipes = new LinkedHashMap<>();
        Set<String> hidden = new LinkedHashSet<>();
        for (var entry : resources.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            String id = entry.getKey().toString();
            if (recipes.size() + hidden.size() >= 512) {
                LogUtils.getLogger().error("Industry production template limit (512) reached; rejected {}", id);
                continue;
            }
            try {
                validateId(entry.getKey());
                JsonObject json = entry.getValue().getAsJsonObject();
                if (json.has("enabled") && !json.get("enabled").getAsBoolean()) {
                    hidden.add(id);
                    continue;
                }
                recipes.put(id, parse(entry.getKey(), json));
            } catch (RuntimeException invalid) {
                hidden.add(id);
                LogUtils.getLogger().error("Rejected Industry production template {}: {}", id, invalid.getMessage());
            }
        }
        current = new Publication(Map.copyOf(recipes), Set.copyOf(hidden));
        IndustryCatalog.invalidate();
        LogUtils.getLogger().info("Loaded {} Industry production templates; {} disabled/rejected", recipes.size(), hidden.size());
    }

    public static IndustryCatalog.Recipe parse(ResourceLocation id, JsonObject json) {
        validateId(id);
        if (!json.keySet().stream().allMatch(Set.of("factory", "title", "output", "cost", "ticks", "fePerTick", "enabled")::contains))
            throw new IllegalArgumentException("unknown template field");
        SiteType factory = SiteType.valueOf(requiredString(json, "factory"));
        if (!factory.isFactory()) throw new IllegalArgumentException("factory must name one of the four factories");
        String title = requiredString(json, "title");
        if (title.isBlank() || title.length() > 96) throw new IllegalArgumentException("title length must be 1..96");
        ItemStack output = stack(json.getAsJsonObject("output"));
        var ingredients = json.getAsJsonArray("cost");
        if (ingredients == null || ingredients.isEmpty() || ingredients.size() > 12)
            throw new IllegalArgumentException("cost must contain 1..12 nonempty ingredients");
        List<ItemStack> cost = new java.util.ArrayList<>();
        for (JsonElement ingredient : ingredients) cost.add(stack(ingredient.getAsJsonObject()));
        return new IndustryCatalog.Recipe(id.toString(), title, factory, output, cost,
            integer(json, "ticks", 1, 72000), integer(json, "fePerTick", 1, 128));
    }

    private static void validateId(ResourceLocation id) {
        if (id.toString().length() > 128)
            throw new IllegalArgumentException("template ID exceeds the 128-character network contract");
    }

    private static ItemStack stack(JsonObject json) {
        if (json == null || !json.keySet().stream().allMatch(Set.of("item", "count")::contains))
            throw new IllegalArgumentException("stack supports item and count only");
        String rawId = requiredString(json, "item");
        ResourceLocation id = rawId.contains(":") ? ResourceLocation.tryParse(rawId) : null;
        if (id == null || !ForgeRegistries.ITEMS.containsKey(id))
            throw new IllegalArgumentException("unknown item ID");
        var item = ForgeRegistries.ITEMS.getValue(id);
        if (item == null || item == Items.AIR) throw new IllegalArgumentException("air/empty item is forbidden");
        int count = integer(json, "count", 1, 64);
        if (count > item.getMaxStackSize()) throw new IllegalArgumentException("count exceeds item stack size");
        return new ItemStack(item, count);
    }

    private static String requiredString(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonPrimitive() || !json.getAsJsonPrimitive(key).isString())
            throw new IllegalArgumentException("missing string " + key);
        return json.get(key).getAsString();
    }

    private static int integer(JsonObject json, String key, int minimum, int maximum) {
        if (!json.has(key) || !json.get(key).isJsonPrimitive() || !json.getAsJsonPrimitive(key).isNumber())
            throw new IllegalArgumentException("missing integer " + key);
        try {
            int value = json.get(key).getAsBigDecimal().intValueExact();
            if (value < minimum || value > maximum) throw new IllegalArgumentException(key + " outside bounds");
            return value;
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException(key + " must be an integer", invalid);
        }
    }

    private record Publication(Map<String, IndustryCatalog.Recipe> recipes, Set<String> hidden) {}
}
