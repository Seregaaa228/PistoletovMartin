package dev.frontier.aviation.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.inventory.handler.VehicleContainerHandler;
import dev.frontier.aviation.ordnance.F16Loadout;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Retains Forge's insertion/simulation semantics and returns every unaccepted item. */
@Mixin(value = VehicleContainerHandler.class, remap = false)
public abstract class F16InventoryLimit extends ItemStackHandler {
    @Shadow @Final private VehicleEntity vehicle;

    @Inject(method = "isItemValid", at = @At("HEAD"), cancellable = true)
    private void aviation$allowPartialStack(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> result) {
        if (stack.isEmpty() || !F16Loadout.supports(vehicle) || F16Loadout.type(stack) == null) return;
        ItemStack one = stack.copy();
        one.setCount(1);
        result.setReturnValue(vehicle.canPlaceItem(slot, one) && F16Loadout.insertionLimit(vehicle, one) > 0);
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (stack.isEmpty() || !F16Loadout.supports(vehicle) || F16Loadout.type(stack) == null)
            return super.insertItem(slot, stack, simulate);
        // VehicleEntity.canPlaceItem validates the whole offered stack before
        // Forge trims it, so pass only what this individual slot can accept.
        ItemStack current = getStackInSlot(slot);
        int capacity = Math.max(0, Math.min(getSlotLimit(slot), stack.getMaxStackSize()) - current.getCount());
        int allowed = Math.min(capacity, F16Loadout.insertionLimit(vehicle, stack));
        if (allowed == 0) return stack;
        if (allowed >= stack.getCount()) return super.insertItem(slot, stack, simulate);
        ItemStack permitted = stack.copy();
        permitted.setCount(allowed);
        ItemStack remainder = super.insertItem(slot, permitted, simulate);
        ItemStack result = stack.copy();
        result.setCount(stack.getCount() - allowed + remainder.getCount());
        return result;
    }
}
