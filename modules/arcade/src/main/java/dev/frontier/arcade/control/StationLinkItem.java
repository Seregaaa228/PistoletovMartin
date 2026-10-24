package dev.frontier.arcade.control;

import com.atsuishio.superbwarfare.item.misc.MonitorItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Hidden, session-bound monitor credential. It cannot be dropped as a usable item. */
public final class StationLinkItem extends MonitorItem {
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide && player instanceof ServerPlayer server) ControlSessions.disconnectStation(server);
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }
    @Override public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        ControlSessions.monitorTick(stack, level, entity, slot, selected);
    }
    @Override public boolean onDroppedByPlayer(ItemStack stack, Player player) { return false; }
}
