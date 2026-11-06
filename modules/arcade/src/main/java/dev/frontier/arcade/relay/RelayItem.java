package dev.frontier.arcade.relay;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/** Withdrawn relay plane: kept registered for old inventories, it can no longer be placed. */
public final class RelayItem extends Item {
    public RelayItem() { super(new Item.Properties().stacksTo(1)); }

    @Override public InteractionResult useOn(UseOnContext context) {
        var player = context.getPlayer();
        if (player != null && !context.getLevel().isClientSide)
            player.displayClientMessage(Component.translatable("message.frontier_arcade_vehicles.relay_withdrawn"), true);
        return InteractionResult.FAIL;
    }

    @Override public void appendHoverText(ItemStack stack, Level level, List<Component> lines, TooltipFlag flags) {
        lines.add(Component.translatable("message.frontier_arcade_vehicles.relay_withdrawn"));
    }
}
