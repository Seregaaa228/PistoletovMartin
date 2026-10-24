package dev.frontier.industry.engine;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;

/** Server-thread transactions. Build a complete plan before touching the native handler. */
public final class StockTransactions {
    private StockTransactions() {}

    public static boolean takeInputs(ItemStackHandler inventory, List<ItemStack> costs) {
        List<ItemStack> planned = copy(inventory, 0, IndustryRules.INPUTS);
        for (ItemStack cost : costs) {
            if (cost.isEmpty() || cost.getCount() <= 0) return false;
            int remaining = cost.getCount();
            for (ItemStack available : planned) {
                if (!ItemStack.isSameItemSameTags(available, cost)) continue;
                int take = Math.min(remaining, available.getCount());
                available.shrink(take);
                remaining -= take;
                if (remaining == 0) break;
            }
            if (remaining != 0) return false;
        }
        apply(inventory, 0, planned);
        return true;
    }

    public static boolean putOutputs(ItemStackHandler inventory, ItemStack output) {
        return put(inventory, IndustryRules.INPUTS, IndustryRules.SLOTS, List.of(output), true);
    }
    public static boolean outputFits(ItemStackHandler inventory, ItemStack output) {
        return put(inventory, IndustryRules.INPUTS, IndustryRules.SLOTS, List.of(output), false);
    }
    public static boolean refundInputs(ItemStackHandler inventory, List<ItemStack> stacks) {
        return put(inventory, 0, IndustryRules.INPUTS, stacks, true);
    }
    /** Pull only the deficit from actual source stack references; accepted counts alone debit the source. */
    public static int supplyMissingInputs(ItemStackHandler inventory, List<ItemStack> sources, List<ItemStack> costs, int units) {
        if (units < 1 || units > 64 || costs.isEmpty()) return 0;
        List<ItemStack> required = new ArrayList<>();
        // Aggregate duplicate ingredients before counting stock so they cannot share the same available items twice.
        for (ItemStack cost : costs) {
            if (cost.isEmpty() || cost.getCount() <= 0) return 0;
            long count = (long) cost.getCount() * units;
            ItemStack combined = required.stream().filter(stack -> ItemStack.isSameItemSameTags(stack, cost)).findFirst().orElse(null);
            if (combined != null) count += combined.getCount();
            if (count > Integer.MAX_VALUE) return 0;
            if (combined == null) required.add(cost.copyWithCount((int) count));
            else combined.setCount((int) count);
        }
        int transferred = 0;
        for (ItemStack ingredient : required) {
            long missing = ingredient.getCount();
            for (int slot = 0; slot < IndustryRules.INPUTS; slot++) {
                ItemStack present = inventory.getStackInSlot(slot);
                if (ItemStack.isSameItemSameTags(present, ingredient)) missing -= present.getCount();
            }
            for (ItemStack source : sources) {
                if (missing <= 0) break;
                if (source.isEmpty() || !ItemStack.isSameItemSameTags(source, ingredient)) continue;
                int requested = (int) Math.min(missing, source.getCount());
                ItemStack remaining = source.copyWithCount(requested);
                for (int slot = 0; slot < IndustryRules.INPUTS && !remaining.isEmpty(); slot++) remaining = inventory.insertItem(slot, remaining, false);
                int moved = requested - remaining.getCount();
                if (moved > 0) { source.shrink(moved); missing -= moved; transferred += moved; }
            }
        }
        return transferred;
    }
    private static boolean put(ItemStackHandler inventory, int first, int end, List<ItemStack> stacks, boolean commit) {
        List<ItemStack> planned = copy(inventory, first, end);
        for (ItemStack supplied : stacks) {
            ItemStack remaining = supplied.copy();
            if (remaining.isEmpty()) continue;
            for (int pass = 0; pass < 2 && !remaining.isEmpty(); pass++) {
                for (int n = 0; n < planned.size() && !remaining.isEmpty(); n++) {
                    ItemStack available = planned.get(n);
                    if (pass == 0 && (available.isEmpty() || !ItemStack.isSameItemSameTags(available, remaining))) continue;
                    if (pass == 1 && !available.isEmpty()) continue;
                    int limit = Math.min(inventory.getSlotLimit(first + n), remaining.getMaxStackSize());
                    int moved = Math.min(remaining.getCount(), limit - available.getCount());
                    if (moved <= 0) continue;
                    if (available.isEmpty()) planned.set(n, remaining.copyWithCount(moved));
                    else available.grow(moved);
                    remaining.shrink(moved);
                }
            }
            if (!remaining.isEmpty()) return false;
        }
        if (commit) apply(inventory, first, planned);
        return true;
    }
    private static List<ItemStack> copy(ItemStackHandler inventory, int first, int end) {
        List<ItemStack> result = new ArrayList<>();
        for (int slot = first; slot < end; slot++) result.add(inventory.getStackInSlot(slot).copy());
        return result;
    }
    private static void apply(ItemStackHandler inventory, int first, List<ItemStack> planned) {
        for (int n = 0; n < planned.size(); n++) inventory.setStackInSlot(first + n, planned.get(n));
    }
}
