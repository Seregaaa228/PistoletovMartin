package dev.frontier.industry.engine;

import dev.frontier.industry.SiteType;
import dev.frontier.industry.buildings.OutputWarehouse;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Focused persistence and key-isolation checks for the P0 physical warehouse contract. */
public final class WarehouseInventoryContractChecks {
    private WarehouseInventoryContractChecks() {}

    public static void run() {
        var data = new IndustryData();
        var site = new IndustryData.Site(data, UUID.randomUUID(), UUID.randomUUID(), SiteType.EQUIPMENT_FACTORY,
            new ResourceLocation("minecraft:overworld"), BlockPos.ZERO);
        site.blueprintVersion = 314;
        data.sites.put(site.id, site);
        var first = new BlockPos(2, 1, 4);
        var second = new BlockPos(3, 1, 4);
        OutputWarehouse.inventory(site, first).setStackInSlot(0, new ItemStack(Items.DIAMOND, 3));
        if (!OutputWarehouse.inventory(site, second).getStackInSlot(0).isEmpty()) throw new IllegalStateException("container inventories share storage");
        var loaded = IndustryData.load(data.save(new CompoundTag())).sites.get(site.id);
        if (loaded == null || loaded.containerInventories.isEmpty()) throw new IllegalStateException("container inventory was not persisted");
        if (OutputWarehouse.inventory(loaded, first).getStackInSlot(0).getCount() != 3) throw new IllegalStateException("container inventory lost contents");
        var box = new net.minecraftforge.items.ItemStackHandler(IndustryRules.CONTAINER_SLOTS);
        if (!StockTransactions.outputFits(box, new ItemStack(Items.DIAMOND, 10))) throw new IllegalStateException("empty physical container rejected output");
        if (!StockTransactions.putOutputs(box, new ItemStack(Items.DIAMOND, 10)) || box.getStackInSlot(0).getCount()!=10) throw new IllegalStateException("production did not reach the nine-slot container");
        var a = new net.minecraftforge.items.ItemStackHandler(IndustryRules.CONTAINER_SLOTS);
        var b = new net.minecraftforge.items.ItemStackHandler(IndustryRules.CONTAINER_SLOTS);
        for (int i=0;i<IndustryRules.CONTAINER_SLOTS;i++) { a.setStackInSlot(i,new ItemStack(Items.DIAMOND,64)); b.setStackInSlot(i,new ItemStack(Items.DIAMOND,64)); }
        a.getStackInSlot(0).shrink(3); b.getStackInSlot(0).shrink(4);
        var pair=java.util.List.of(a,b);var product=new ItemStack(Items.DIAMOND,7);
        if (!StockTransactions.distributedOutputs(pair,product,false) || a.getStackInSlot(0).getCount()!=61 || b.getStackInSlot(0).getCount()!=60) throw new IllegalStateException("simulation changed distributed output");
        if (!StockTransactions.distributedOutputs(pair,product,true) || a.getStackInSlot(0).getCount()!=64 || b.getStackInSlot(0).getCount()!=64) throw new IllegalStateException("output not split between containers");
        if (StockTransactions.distributedOutputs(pair,new ItemStack(Items.DIAMOND,1),true) || a.getStackInSlot(0).getCount()!=64 || b.getStackInSlot(0).getCount()!=64) throw new IllegalStateException("full distributed output changed inventory");
        var legacy = new net.minecraftforge.items.ItemStackHandler(IndustryRules.SLOTS);
        if (!StockTransactions.putOutputs(legacy, new ItemStack(Items.IRON_INGOT, 5)) || legacy.getStackInSlot(IndustryRules.INPUTS).getCount()!=5) throw new IllegalStateException("legacy output mapping changed");
    }
}
