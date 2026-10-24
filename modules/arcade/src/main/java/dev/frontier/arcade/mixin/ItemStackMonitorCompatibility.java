package dev.frontier.arcade.mixin;

import com.atsuishio.superbwarfare.init.ModItems;
import dev.frontier.arcade.control.PocketControllerItem;
import dev.frontier.arcade.control.StationLinkItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Only two addon transports satisfy the native monitor predicate; item identity stays intact. */
@Mixin(value = ItemStack.class, remap = false)
public abstract class ItemStackMonitorCompatibility {
    @Inject(method = "m_150930_(Lnet/minecraft/world/item/Item;)Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void frontier$monitorTransport(Item compared, CallbackInfoReturnable<Boolean> result) {
        ItemStack stack = (ItemStack)(Object)this;
        if (!stack.isEmpty() && (stack.getItem() instanceof PocketControllerItem || stack.getItem() instanceof StationLinkItem)
            && compared == ModItems.MONITOR.get()) result.setReturnValue(true);
    }
}
