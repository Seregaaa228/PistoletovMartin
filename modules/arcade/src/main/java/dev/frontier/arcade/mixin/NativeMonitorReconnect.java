package dev.frontier.arcade.mixin;

import com.atsuishio.superbwarfare.item.misc.MonitorItem;
import dev.frontier.arcade.control.ControlSessions;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Compatibility is scoped to owner-verified UF pairings; other native monitors are untouched. */
@Mixin(value = MonitorItem.class, remap = false)
public abstract class NativeMonitorReconnect {
    @Inject(method = "m_7203_", at = @At("HEAD"), cancellable = true, remap = false)
    private void frontier$reconnect(Level level, Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResultHolder<ItemStack>> callback) {
        if (level.isClientSide || hand != InteractionHand.MAIN_HAND) return;
        InteractionResultHolder<ItemStack> result = ControlSessions.useLegacy(player, player.getMainHandItem());
        if (result != null) callback.setReturnValue(result);
    }
    @Inject(method = "m_6883_", at = @At("HEAD"), cancellable = true, remap = false)
    private void frontier$bootstrap(ItemStack stack, Level level, Entity entity, int slot, boolean selected, CallbackInfo callback) {
        if (ControlSessions.protectNativeTick(stack, level, entity, slot, selected)) callback.cancel();
    }
}
