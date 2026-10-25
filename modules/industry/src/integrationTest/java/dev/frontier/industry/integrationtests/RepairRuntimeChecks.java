package dev.frontier.industry.integrationtests;

import dev.frontier.industry.IndustryContent;
import dev.frontier.industry.SiteType;
import dev.frontier.industry.repair.RepairTransactions;
import dev.frontier.industry.engine.IndustryData;
import dev.frontier.industry.engine.IndustryData.Site;
import dev.frontier.industry.engine.IndustryData.Worker;
import dev.frontier.industry.engine.IndustryRules;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Native ItemStack/NBT checks. Invoke only in the separate fresh-world test fixture. */
public final class RepairRuntimeChecks {
    private RepairRuntimeChecks() {}
    public static Map<String, Boolean> run() {
        Map<String, Boolean> result = new LinkedHashMap<>();
        IndustryData data = new IndustryData();
        UUID owner = UUID.randomUUID();
        Site site = new Site(data, UUID.randomUUID(), owner, SiteType.EQUIPMENT_FACTORY,
            new ResourceLocation("minecraft:overworld"), BlockPos.ZERO);
        data.sites.put(site.id, site);
        site.repairRequested = site.repairKit = true; site.repairProgress = IndustryRules.REPAIR_TICKS - 1;
        check(result, "cancel returns exactly one reserved kit", RepairTransactions.cancel(data, site) == RepairTransactions.Cancellation.CANCELLED
            && !site.repairRequested && !site.repairKit && site.repairProgress == 0
            && site.inventory.getStackInSlot(0).is(IndustryContent.REPAIR_KIT.get()) && site.inventory.getStackInSlot(0).getCount() == 1);
        check(result, "second cancel cannot duplicate a kit", RepairTransactions.cancel(data, site) == RepairTransactions.Cancellation.NO_ORDER
            && site.inventory.getStackInSlot(0).getCount() == 1);
        for (int slot = 0; slot < IndustryRules.INPUTS; slot++) site.inventory.setStackInSlot(slot, new ItemStack(Items.COBBLESTONE, 64));
        site.repairRequested = site.repairKit = true; site.repairProgress = 123;
        CompoundTag stock = site.inventory.serializeNBT().copy();
        check(result, "refund congestion preserves complete repair transaction", RepairTransactions.cancel(data, site) == RepairTransactions.Cancellation.NO_SPACE
            && site.repairRequested && site.repairKit && site.repairProgress == 123 && stock.equals(site.inventory.serializeNBT()));
        site.repairKit = false;
        check(result, "unreserved order cancels without granting a kit", RepairTransactions.cancel(data, site) == RepairTransactions.Cancellation.CANCELLED
            && stock.equals(site.inventory.serializeNBT()) && !site.repairRequested && site.repairProgress == 0);
        site.repairRequested = site.repairKit = true; site.repairProgress = 1799; site.operating = true;
        Worker worker = new Worker(UUID.randomUUID(), owner, "factory_repair", site.dimension); worker.site = site.id;
        data.workers.put(worker.id, worker);
        IndustryData loaded = IndustryData.load(data.save(new CompoundTag()));
        Site restored = loaded.sites.get(site.id); Worker restoredWorker = loaded.workers.get(worker.id);
        check(result, "repair progress reservation and assignment round trip", restored != null && restored.repairRequested && restored.repairKit && restored.repairProgress == 1799
            && restoredWorker != null && site.id.equals(restoredWorker.site) && owner.equals(restoredWorker.owner) && "factory_repair".equals(restoredWorker.role));
        check(result, "server work animation is recomputed after load", restored != null && !restored.operating && !restored.loaded);
        return Map.copyOf(result);
    }
    private static void check(Map<String, Boolean> result, String name, boolean passed) { result.put("repair " + name, passed); }
}
