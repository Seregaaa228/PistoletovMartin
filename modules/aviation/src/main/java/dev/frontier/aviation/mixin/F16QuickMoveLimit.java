package dev.frontier.aviation.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.inventory.menu.AbstractVehicleContainerMenu;
import dev.frontier.aviation.ordnance.F16Loadout;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** SBW quick-move uses Slot.set directly, so route ordnance through the capped handler. */
@Mixin(value = AbstractVehicleContainerMenu.class, remap = false)
public abstract class F16QuickMoveLimit extends AbstractContainerMenu {
    protected F16QuickMoveLimit(MenuType<?> type, int id) { super(type, id); }
    @Shadow public abstract VehicleEntity getVehicle();

    @Inject(method = "m_7648_(Lnet/minecraft/world/entity/player/Player;I)Lnet/minecraft/world/item/ItemStack;",
        at = @At("HEAD"), cancellable = true, require = 1)
    private void aviation$quickMoveOrdnance(Player player, int index, CallbackInfoReturnable<ItemStack> result) {
        VehicleEntity plane = getVehicle();
        if (plane == null || !F16Loadout.supports(plane) || index < 0 || index >= 36 || index >= slots.size()) return;
        var sourceSlot = slots.get(index);
        ItemStack source = sourceSlot.getItem();
        if (source.isEmpty() || F16Loadout.type(source) == null) return;
        ItemStack original = source.copy();
        ItemStack remainder = source.copy();
        var inventory = plane.getInventory();
        for (int slot = 0; slot < inventory.getSlots() && !remainder.isEmpty(); slot++)
            remainder = inventory.insertItem(slot, remainder, false);
        int moved = original.getCount() - remainder.getCount();
        if (moved <= 0) { result.setReturnValue(ItemStack.EMPTY); return; }
        source.shrink(moved);
        if (source.isEmpty()) sourceSlot.setByPlayer(ItemStack.EMPTY);
        else sourceSlot.setChanged();
        sourceSlot.onTake(player, source);
        result.setReturnValue(original);
    }
}
