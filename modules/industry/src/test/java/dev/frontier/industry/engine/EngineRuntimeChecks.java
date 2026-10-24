package dev.frontier.industry.engine;

import dev.frontier.industry.SiteType;
import dev.frontier.industry.IndustryContent;
import dev.frontier.industry.IndustryEngine;
import dev.frontier.industry.engine.IndustryData.Order;
import dev.frontier.industry.engine.IndustryData.Site;
import dev.frontier.industry.engine.IndustryData.Worker;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.items.ItemStackHandler;

/** Native Minecraft/NBT checks. Called only by the root's separate copied-world test build. */
public final class EngineRuntimeChecks {
    private EngineRuntimeChecks() {}
    public static Map<String, Boolean> run() {
        Map<String, Boolean> result = new LinkedHashMap<>();
        result.putAll(BlueprintLayoutChecks.run());
        IndustryData data = new IndustryData();
        UUID id = UUID.randomUUID(), owner = UUID.randomUUID();
        Site site = new Site(data, id, owner, SiteType.EQUIPMENT_FACTORY, new ResourceLocation("minecraft:overworld"), new BlockPos(12, 90, -15));
        data.sites.put(id, site);
        ItemStackHandler inventory = site.inventory;
        inventory.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 7));
        inventory.setStackInSlot(1, new ItemStack(Items.IRON_INGOT, 5));
        inventory.setStackInSlot(2, new ItemStack(Items.REDSTONE, 3));
        CompoundTag before = inventory.serializeNBT().copy();
        check(result, "missing one ingredient rolls entire debit back", !StockTransactions.takeInputs(inventory,
            List.of(new ItemStack(Items.IRON_INGOT, 10), new ItemStack(Items.REDSTONE, 4))) && before.equals(inventory.serializeNBT()));
        check(result, "split input stacks reserve exactly once", StockTransactions.takeInputs(inventory,
            List.of(new ItemStack(Items.IRON_INGOT, 10), new ItemStack(Items.REDSTONE, 3)))
            && inventory.getStackInSlot(0).isEmpty() && inventory.getStackInSlot(1).getCount() == 2 && inventory.getStackInSlot(2).isEmpty());
        before = inventory.serializeNBT().copy();
        check(result, "duplicate ingredient entries cannot overspend same stack", !StockTransactions.takeInputs(inventory,
            List.of(new ItemStack(Items.IRON_INGOT, 2), new ItemStack(Items.IRON_INGOT, 1))) && before.equals(inventory.serializeNBT()));
        for (int slot = 12; slot < 24; slot++) inventory.setStackInSlot(slot, new ItemStack(Items.COBBLESTONE, 64));
        before = inventory.serializeNBT().copy();
        ItemStack tagged = new ItemStack(Items.DIAMOND_SWORD); tagged.setHoverName(Component.literal("NBT output sentinel")); tagged.setDamageValue(17);
        check(result, "output jam is fully transactional", !StockTransactions.putOutputs(inventory, tagged) && before.equals(inventory.serializeNBT()));
        inventory.setStackInSlot(23, ItemStack.EMPTY);
        check(result, "output commits native stack and NBT", StockTransactions.putOutputs(inventory, tagged) && ItemStack.matches(inventory.getStackInSlot(23), tagged));
        check(result, "external insertion cannot inject output slots", inventory.insertItem(12, new ItemStack(Items.DIAMOND), false).getCount() == 1);
        check(result, "native output extraction remains usable", inventory.extractItem(22, 64, true).getCount() == 64);
        site.energy = 19001; site.burn = 1599; site.upstream = UUID.randomUUID();
        site.repairRequested = true; site.repairKit = true; site.repairProgress = 1799;
        Order order = new Order("minecraft:diamond_sword", 2); order.reserved = true; order.progress = 123;
        order.paid.add(new ItemStack(Items.IRON_INGOT, 12)); site.orders.add(order);
        Worker worker = new Worker(UUID.randomUUID(), owner, "factory_repair", site.dimension);
        worker.site = id; worker.sourceId = UUID.randomUUID(); worker.source = new CompoundTag(); worker.source.putString("id", "recruits:bowman");
        worker.source.putUUID("UUID", worker.sourceId); worker.source.putString("EquipmentSentinel", "kept in custody"); data.workers.put(worker.id, worker);
        worker.nativeDebited = true; worker.nativeGroupRemoved = true; worker.nativeGroup = UUID.randomUUID(); worker.nativeTeam = "saved_country"; worker.nativeFactionDebited = true;
        CompoundTag saved = data.save(new CompoundTag());
        IndustryData loaded = IndustryData.load(saved.copy()); Site restored = loaded.sites.get(id);
        check(result, "native 24-slot inventory survives SavedData round trip", restored != null && restored.inventory.getSlots() == 24 && inventory.serializeNBT().equals(restored.inventory.serializeNBT()));
        check(result, "finite FE and source link persist", restored != null && restored.energy == 19001 && restored.burn == 1599 && site.upstream.equals(restored.upstream));
        Order resumed = restored == null ? null : restored.orders.peek();
        check(result, "paid in-flight unit survives without another debit", resumed != null && resumed.reserved && resumed.progress == 123 && resumed.quantity == 2 && resumed.paid.get(0).getCount() == 12);
        check(result, "repair kit reservation and tick1799 persist", restored != null && restored.repairRequested && restored.repairKit && restored.repairProgress == 1799);
        Worker restoredWorker = loaded.workers.get(worker.id);
        check(result, "owner source UUID equipment custody and role persist", restoredWorker != null && restoredWorker.owner.equals(owner) && restoredWorker.sourceId.equals(worker.sourceId)
            && restoredWorker.source.equals(worker.source) && restoredWorker.role.equals("factory_repair") && restoredWorker.site.equals(id));
        check(result, "native ledger debit credit group faction journal persists", restoredWorker != null && restoredWorker.nativeDebited && !restoredWorker.nativeCredited
            && restoredWorker.nativeGroupRemoved && worker.nativeGroup.equals(restoredWorker.nativeGroup) && restoredWorker.nativeFactionDebited && restoredWorker.nativeTeam.equals("saved_country"));
        check(result, "loading never marks an absent chunk or worker as active production", restored != null && !restored.loaded && !restored.powered && restored.liveProduction == 0);
        CompoundTag broken = saved.copy(); CompoundTag brokenSite = broken.getList("Sites", 10).getCompound(0);
        brokenSite.putInt("Energy", Integer.MAX_VALUE); brokenSite.putInt("Burn", -20); brokenSite.getCompound("Inventory").putInt("Size", 999);
        Site bounded = IndustryData.load(broken).sites.get(id);
        check(result, "saved oversized energy and inventory obey hard limits", bounded != null && bounded.energy == IndustryRules.BUFFER && bounded.burn == 0 && bounded.inventory.getSlots() == 24);
        CompoundTag oldSave = saved.copy(); oldSave.getList("Sites", 10).getCompound(0).remove("BlueprintVersion");
        Site legacy = IndustryData.load(oldSave).sites.get(id);
        check(result, "pre-redesign NBT keeps legacy footprint and controller", legacy != null && legacy.blueprintVersion == Blueprint.LEGACY_VERSION
            && Blueprint.bounds(legacy.type, legacy.blueprintVersion).width() == 7
            && Blueprint.controller(legacy.type, legacy.origin, legacy.blueprintVersion).equals(legacy.origin.offset(1, 1, -3)));
        site.blueprintVersion = Blueprint.CURRENT_VERSION;
        Site newRoundTrip = IndustryData.load(data.save(new CompoundTag())).sites.get(id);
        check(result, "new building version persists without expanding old sites", newRoundTrip != null && newRoundTrip.blueprintVersion == Blueprint.CURRENT_VERSION
            && Blueprint.bounds(newRoundTrip.type, newRoundTrip.blueprintVersion).width() == 15 && legacy != null && legacy.blueprintVersion == Blueprint.LEGACY_VERSION);
        if (legacy != null) { legacy.total = legacy.matching = 100; legacy.loaded = legacy.controller = true; legacy.machineryIntact = false; }
        if (newRoundTrip != null) { newRoundTrip.total = newRoundTrip.matching = 100; newRoundTrip.loaded = newRoundTrip.controller = true; newRoundTrip.machineryIntact = false; }
        check(result, "legacy integrity contract stays unchanged while new machinery damage stops grid", legacy != null && legacy.functional() && newRoundTrip != null && !newRoundTrip.functional());
        for (int slot = 0; slot < 12; slot++) inventory.setStackInSlot(slot, new ItemStack(Items.COBBLESTONE, 64));
        before = inventory.serializeNBT().copy();
        check(result, "cancel refund backpressure preserves paid inputs", !StockTransactions.refundInputs(inventory, order.paid) && before.equals(inventory.serializeNBT()));
        ItemStack canceledKit = new ItemStack(IndustryContent.REPAIR_KIT.get(), 16);
        canceledKit.getOrCreateTag().putUUID("IndustryRepairSite", UUID.randomUUID());
        IndustryEngine.cancelRepair(null, canceledKit);
        inventory.setStackInSlot(0, canceledKit);
        check(result, "cancelled manual kit remains exact native NPC supply", !canceledKit.hasTag()
            && StockTransactions.takeInputs(inventory, List.of(new ItemStack(IndustryContent.REPAIR_KIT.get())))
            && inventory.getStackInSlot(0).getCount() == 15);
        ItemStack namedKit = new ItemStack(IndustryContent.REPAIR_KIT.get(), 2);
        namedKit.setHoverName(Component.literal("Keep unrelated NBT"));
        CompoundTag unrelated = namedKit.getTag().copy(); namedKit.getOrCreateTag().putUUID("IndustryRepairSite", UUID.randomUUID());
        IndustryEngine.cancelRepair(null, namedKit);
        check(result, "manual cancellation strips only transient target NBT", unrelated.equals(namedKit.getTag()) && namedKit.getCount() == 2);
        supplyChecks(result);
        return Map.copyOf(result);
    }
    private static void supplyChecks(Map<String, Boolean> result) {
        ItemStackHandler stock = new ItemStackHandler(24);
        stock.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 4));
        stock.setStackInSlot(12, new ItemStack(Items.DIAMOND, 17));
        ItemStack iron = new ItemStack(Items.IRON_INGOT, 20), extra = new ItemStack(Items.REDSTONE, 3);
        List<ItemStack> source = List.of(iron, extra);
        check(result, "supply aggregates duplicate costs and fills only missing quantity", StockTransactions.supplyMissingInputs(stock, source,
            List.of(new ItemStack(Items.IRON_INGOT, 2), new ItemStack(Items.IRON_INGOT, 3)), 2) == 6
            && iron.getCount() == 14 && stock.getStackInSlot(0).getCount() == 10 && extra.getCount() == 3);
        check(result, "repeated supply is idempotent and never touches outputs", StockTransactions.supplyMissingInputs(stock, source,
            List.of(new ItemStack(Items.IRON_INGOT, 5)), 2) == 0 && iron.getCount() == 14 && stock.getStackInSlot(12).getCount() == 17);
        stock.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 63));
        for (int slot = 1; slot < 12; slot++) stock.setStackInSlot(slot, new ItemStack(Items.COBBLESTONE, 64));
        check(result, "partial supply debits only a single accepted item", StockTransactions.supplyMissingInputs(stock, source,
            List.of(new ItemStack(Items.IRON_INGOT, 64)), 2) == 1 && iron.getCount() == 13 && stock.getStackInSlot(0).getCount() == 64);
        check(result, "full input stock cannot debit or redirect into outputs", StockTransactions.supplyMissingInputs(stock, source,
            List.of(new ItemStack(Items.IRON_INGOT, 64)), 2) == 0 && iron.getCount() == 13 && stock.getStackInSlot(12).getCount() == 17);
        stock = new ItemStackHandler(24);
        ItemStack plain = new ItemStack(Items.IRON_INGOT, 12), named = new ItemStack(Items.IRON_INGOT, 12);
        named.setHoverName(Component.literal("NBT-bound ingredient"));
        ItemStack required = named.copyWithCount(3);
        check(result, "supply preserves exact native NBT and never substitutes plain items", StockTransactions.supplyMissingInputs(stock, List.of(plain, named), List.of(required), 1) == 3
            && plain.getCount() == 12 && named.getCount() == 9 && ItemStack.isSameItemSameTags(stock.getStackInSlot(0), required));
        ItemStack kits = new ItemStack(IndustryContent.REPAIR_KIT.get(), 5), kit = new ItemStack(IndustryContent.REPAIR_KIT.get());
        check(result, "repair convenience pulls exactly one paid kit", StockTransactions.supplyMissingInputs(stock, List.of(kits), List.of(kit), 1) == 1 && kits.getCount() == 4);
        check(result, "existing repair kit prevents a second pull", StockTransactions.supplyMissingInputs(stock, List.of(kits), List.of(kit), 1) == 0 && kits.getCount() == 4);
        check(result, "supply rejects zero and oversized quantities without debit", StockTransactions.supplyMissingInputs(stock, List.of(kits), List.of(kit), 0) == 0
            && StockTransactions.supplyMissingInputs(stock, List.of(kits), List.of(kit), 65) == 0 && kits.getCount() == 4);
    }
    private static void check(Map<String, Boolean> result, String name, boolean condition) { result.put("engine " + name, condition); }
}
