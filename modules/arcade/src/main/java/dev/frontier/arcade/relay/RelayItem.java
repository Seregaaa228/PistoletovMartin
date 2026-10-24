package dev.frontier.arcade.relay;

import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/** Placement accepts only bounded consumable state; item NBT cannot inject an entity or its owner. */
public final class RelayItem extends Item {
    public RelayItem() { super(new Item.Properties().stacksTo(1)); }

    @Override public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel(); var player = context.getPlayer();
        if (player == null || context.getClickedFace() != Direction.UP) return InteractionResult.FAIL;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if(RemoteDroneView.ownedCount(player.getUUID())>=3 || RemoteDroneView.totalCount()>=24){
            player.displayClientMessage(Component.literal("Лимит: 3 ретранслятора у игрока, 24 в мире."),true);return InteractionResult.FAIL;
        }
        var at = context.getClickedPos().above();
        if (at.getY() > level.getMaxBuildHeight() - 12) return InteractionResult.FAIL;
        var entity = RelayContent.TYPE.get().create(level);
        if (entity == null) return InteractionResult.FAIL;
        entity.setPos(at.getX() + .5, at.getY() + .03, at.getZ() + .5);
        entity.setYRot(player.getYRot()); entity.setXRot(0); entity.setOwner(player.getUUID());
        var tag = context.getItemInHand().getTag();
        int charge = tag != null && tag.contains("RelayEnergy", 3) ? tag.getInt("RelayEnergy") : RelayEntity.BATTERY;
        entity.setEnergy(RelayOrbitModel.boundedEnergy(charge));
        if (tag != null && tag.contains("RelayHealth", 5)) {
            float health = tag.getFloat("RelayHealth");
            if (Float.isFinite(health)) entity.setHealth(Math.max(1, Math.min(20, health)));
        }
        if (!level.getWorldBorder().isWithinBounds(entity.getBoundingBox()) || !level.noCollision(entity, entity.getBoundingBox())) {
            entity.invalidateCaps(); return InteractionResult.FAIL;
        }
        if (!level.addFreshEntity(entity)) { entity.invalidateCaps(); return InteractionResult.FAIL; }
        if (!player.getAbilities().instabuild) context.getItemInHand().shrink(1);
        player.displayClientMessage(Component.literal("Самолёт установлен. Выбери его на наземной станции: Space — взлёт, I — круг, F6 — горизонт."), true);
        return InteractionResult.CONSUME;
    }

    @Override public void appendHoverText(ItemStack stack, Level level, List<Component> lines, TooltipFlag flags) {
        lines.add(Component.translatable("tooltip.frontier_arcade_vehicles.relay_drone.role"));
        lines.add(Component.translatable("tooltip.frontier_arcade_vehicles.relay_drone.deploy"));
        lines.add(Component.translatable("tooltip.frontier_arcade_vehicles.relay_drone.orbit"));
        lines.add(Component.translatable("tooltip.frontier_arcade_vehicles.relay_drone.signal"));
        lines.add(Component.translatable("tooltip.frontier_arcade_vehicles.relay_drone.recover"));
    }
}
