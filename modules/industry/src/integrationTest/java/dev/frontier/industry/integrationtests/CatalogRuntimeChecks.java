package dev.frontier.industry.integrationtests;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.frontier.industry.IndustryCatalog;
import dev.frontier.industry.IndustryEngine;
import dev.frontier.industry.SiteType;
import dev.frontier.industry.engine.IndustryData;
import dev.frontier.industry.integration.catalog.IndustryRecipeReloadListener;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Registry/NBT contracts; run only in the separate opt-in helper after server load. */
public final class CatalogRuntimeChecks {
    private CatalogRuntimeChecks() {}

    public static Map<String, Boolean> run() {
        Map<String, Boolean> checks = new LinkedHashMap<>();
        ResourceLocation id = new ResourceLocation("frontier_industry", "contract_test");
        JsonObject valid = JsonParser.parseString("""
            {"title":"Проверка","factory":"EQUIPMENT_FACTORY","output":{"item":"minecraft:iron_sword","count":1},
             "cost":[{"item":"minecraft:iron_ingot","count":2}],"ticks":600,"fePerTick":16}
            """).getAsJsonObject();
        var recipe = IndustryRecipeReloadListener.parse(id, valid);
        boolean longIdRejected = false;
        try { IndustryRecipeReloadListener.parse(new ResourceLocation("frontier_industry", "a".repeat(129)), valid); }
        catch (IllegalArgumentException invalid) { longIdRejected = true; }
        checks.put("reject template IDs that cannot fit the existing network packet", longIdRejected);
        checks.put("template parses exact server-owned values", recipe.factory() == SiteType.EQUIPMENT_FACTORY
            && recipe.output().is(Items.IRON_SWORD) && recipe.cost().get(0).getCount() == 2 && recipe.ticks() == 600 && recipe.fePerTick() == 16);
        for (String mutation : List.of("cost", "ticks", "fraction", "fePerTick", "factory", "unknown_item", "count", "nbt")) {
            JsonObject json = valid.deepCopy();
            switch (mutation) {
                case "cost" -> json.add("cost", new com.google.gson.JsonArray());
                case "ticks" -> json.addProperty("ticks", 0);
                case "fraction" -> json.addProperty("ticks", 1.5);
                case "fePerTick" -> json.addProperty("fePerTick", -1);
                case "factory" -> json.addProperty("factory", "POWER_STATION");
                case "unknown_item" -> json.getAsJsonObject("output").addProperty("item", "unknown:missing");
                case "count" -> json.getAsJsonObject("output").addProperty("count", 65);
                case "nbt" -> json.getAsJsonObject("output").addProperty("nbt", "{free:1}");
            }
            boolean rejected = false;
            try { IndustryRecipeReloadListener.parse(id, json); }
            catch (RuntimeException invalid) { rejected = true; }
            checks.put("reject invalid template " + mutation, rejected);
        }
        IndustryData data = new IndustryData();
        var site = new IndustryData.Site(data, UUID.randomUUID(), UUID.randomUUID(), SiteType.EQUIPMENT_FACTORY,
            new ResourceLocation("minecraft:overworld"), BlockPos.ZERO);
        data.sites.put(site.id, site);
        var order = new IndustryData.Order(recipe.id(), 3);
        order.paid.addAll(recipe.cost()); order.reserved = true; order.progress = 250; order.reserve(recipe);
        site.orders.add(order);
        recipe.output().setCount(0); recipe.cost().get(0).setCount(0);
        checks.put("recipe and reserved snapshots are defensive copies", order.paidRecipe(site.type).output().getCount() == 1
            && order.paidRecipe(site.type).cost().get(0).getCount() == 2);
        var restored = IndustryData.load(data.save(new CompoundTag())).sites.get(site.id).orders.peek();
        var effective = IndustryEngine.productionRecipe(restored, site.type);
        checks.put("removed template cannot alter paid output after NBT restart", effective != null && effective.output().is(Items.IRON_SWORD)
            && effective.ticks() == 600 && effective.fePerTick() == 16 && restored.progress == 250 && restored.quantity == 3);
        checks.put("reserved template cannot execute in another factory", IndustryEngine.productionRecipe(restored, SiteType.DRONE_FACTORY) == null);
        restored.clearReservation();
        checks.put("unpaid remainder never uses old paid snapshot", IndustryEngine.productionRecipe(restored, site.type) == null);
        var old = new IndustryData.Order("minecraft:iron_sword", 1);
        old.reserved = true; old.paid.add(new ItemStack(Items.IRON_INGOT, 12));
        checks.put("old paid order recovers original complete built-in catalog", IndustryEngine.productionRecipe(old, site.type) != null);
        CompoundTag saved = data.save(new CompoundTag());
        CompoundTag savedOrder = saved.getList("Sites", 10).getCompound(0).getList("Orders", 10).getCompound(0);
        savedOrder.getCompound("PaidTemplate").putInt("Ticks", 0);
        var corrupt = IndustryData.load(saved).sites.get(site.id).orders.peek();
        checks.put("corrupt paid snapshot is retained for refund without fallback", corrupt.reserved && corrupt.paid.size() == 1
            && IndustryEngine.productionRecipe(corrupt, site.type) == null);
        return checks;
    }
}
