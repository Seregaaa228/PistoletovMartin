package dev.frontier.industry;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** One real native DSM tablet serves as the HQ, industrial tool and authorized missile controller. */
@Mod.EventBusSubscriber(modid = IndustryMod.ID)
public final class PdaInteractions {
    private PdaInteractions() {}
    private static ItemStack migrate(ServerPlayer player, net.minecraft.world.InteractionHand hand) {
        ItemStack old = player.getItemInHand(hand);
        if (old.is(IndustryContent.TABLET.get()) && IndustryContent.preferredTablet() != IndustryContent.TABLET.get()) {
            ItemStack replacement = new ItemStack(IndustryContent.preferredTablet());
            if (old.hasTag()) replacement.setTag(old.getTag().copy());
            player.setItemInHand(hand, replacement); player.getInventory().setChanged();
            return replacement;
        }
        return old;
    }
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void item(PlayerInteractEvent.RightClickItem event) {
        if (!IndustryContent.isTablet(event.getItemStack())) return;
        if (event.getEntity() instanceof ServerPlayer player) {
            migrate(player, event.getHand()); OperationsNet.open(player);
        }
        event.setCanceled(true); event.setCancellationResult(InteractionResult.SUCCESS);
    }
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void block(PlayerInteractEvent.RightClickBlock event) {
        if (!IndustryContent.isTablet(event.getItemStack()) || !(event.getEntity() instanceof ServerPlayer player)) return;
        var id = IndustryEngine.siteAt(player.serverLevel(), event.getPos());
        var clicked = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(player.serverLevel().getBlockState(event.getPos()).getBlock());
        if (id != null && IndustryEngine.owns(player, id) && clicked != null && clicked.getNamespace().equals(IndustryMod.ID)) {
            ItemStack pda = migrate(player, event.getHand());
            var tag = pda.getOrCreateTag();
            if (player.isShiftKeyDown()) {
                if (tag.hasUUID("LinkSource")) {
                    if (IndustryEngine.link(player, tag.getUUID("LinkSource"), id)) {
                        tag.putUUID("LinkSource", id); player.displayClientMessage(Component.literal("Линия подключена."), true);
                    }
                } else { tag.putUUID("LinkSource", id); player.displayClientMessage(Component.literal("Выбрано начало линии. Shift + ПКМ по следующему объекту."), true); }
            } else { tag.remove("LinkSource"); IndustryMenus.open(player, id); }
            event.setCanceled(true); event.setCancellationResult(InteractionResult.CONSUME);
        } else if (!player.isShiftKeyDown()) {
            migrate(player, event.getHand()); OperationsNet.open(player);
            event.setCanceled(true); event.setCancellationResult(InteractionResult.CONSUME);
        }
        // Shift-click on native DSM installations remains its real link/bind interaction.
    }
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void entity(PlayerInteractEvent.EntityInteract event) {
        if (!IndustryContent.isTablet(event.getItemStack()) || !(event.getEntity() instanceof ServerPlayer player)
                || !(event.getTarget() instanceof LivingEntity living)) return;
        if (!(living instanceof WorkerEntity) && !(living instanceof com.talhanation.recruits.entities.AbstractRecruitEntity)) return;
        if (player.isShiftKeyDown()) {
            ItemStack pda = migrate(player, event.getHand());
            if (IndustryEngine.selectWorker(player, living, pda)) {
                event.setCanceled(true); event.setCancellationResult(InteractionResult.CONSUME);
            }
        } else if (living instanceof WorkerEntity) {
            migrate(player, event.getHand()); OperationsNet.open(player);
            event.setCanceled(true); event.setCancellationResult(InteractionResult.CONSUME);
        }
    }
}
