package dev.frontier.arcade.control;

import com.atsuishio.superbwarfare.item.misc.MonitorItem;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Strike-aircraft terminal; native monitor packets remain the flight transport. */
public final class PocketControllerItem extends MonitorItem {
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResultHolder.pass(player.getItemInHand(hand));
        if (level.isClientSide) {
            super.use(level, player, hand);
            return InteractionResultHolder.sidedSuccess(player.getMainHandItem(), true);
        }
        return ControlSessions.usePocket(player, player.getMainHandItem());
    }
    @Override public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        // Native inventoryTick clears Using before a distant aircraft has reached the client.
        if (level.isClientSide && !selected) { super.inventoryTick(stack, level, entity, slot, false); return; }
        ControlSessions.monitorTick(stack, level, entity, slot, selected);
    }
}
