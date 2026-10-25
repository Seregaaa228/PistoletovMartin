package dev.frontier.industry.repair;

import dev.frontier.industry.IndustryContent;
import dev.frontier.industry.engine.IndustryData;
import dev.frontier.industry.engine.IndustryData.Site;
import dev.frontier.industry.engine.StockTransactions;
import java.util.List;
import net.minecraft.world.item.ItemStack;

/** Server ledger transaction; kit refund preflight precedes all repair-state changes. */
public final class RepairTransactions {
    public enum Cancellation { CANCELLED, NO_ORDER, NO_SPACE }
    private RepairTransactions() {}
    public static Cancellation cancel(IndustryData data, Site site) {
        if (!site.repairRequested && !site.repairKit) return Cancellation.NO_ORDER;
        if (site.repairKit && !StockTransactions.refundInputs(site.inventory, List.of(new ItemStack(IndustryContent.REPAIR_KIT.get()))))
            return Cancellation.NO_SPACE;
        site.repairKit = false; site.repairRequested = false; site.repairProgress = 0; data.setDirty();
        return Cancellation.CANCELLED;
    }
}
