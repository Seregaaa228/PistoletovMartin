package dev.frontier.arcade.geranium;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Server-created entity, bounded fuel only; item data cannot inject entity UUID/owner/passengers. */
public final class GeraniumItem extends Item {
    private final GeraniumVariant variant;
    public GeraniumItem(GeraniumVariant variant) { super(new Item.Properties().stacksTo(1)); this.variant = variant; }
    @Override public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel(); var player = context.getPlayer();
        if (player == null) return InteractionResult.FAIL;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        var at = context.getClickedPos().relative(context.getClickedFace());
        var entity = variant.type().create(level); if (entity == null) return InteractionResult.FAIL;
        entity.setPos(at.getX() + .5, at.getY() + .01, at.getZ() + .5); entity.setYRot(player.getYRot()); entity.setXRot(0);
        entity.setOwner(player.getUUID());
        int charge = context.getItemInHand().hasTag() && context.getItemInHand().getTag().contains("GeraniumEnergy", 3)
            ? context.getItemInHand().getTag().getInt("GeraniumEnergy") : GeraniumEntity.BATTERY;
        entity.setEnergy(Math.max(0, Math.min(GeraniumEntity.BATTERY, charge)));
        if (!level.getWorldBorder().isWithinBounds(entity.getBoundingBox()) || !level.noCollision(entity, entity.getBoundingBox())) { entity.invalidateCaps(); return InteractionResult.FAIL; }
        if (!level.addFreshEntity(entity)) { entity.invalidateCaps(); return InteractionResult.FAIL; }
        if (!player.getAbilities().instabuild) context.getItemInHand().shrink(1);
        player.displayClientMessage(Component.literal(variant.title() + " на позиции. Выбери аппарат на наземной станции и нажми «Подключиться»."), true);
        return InteractionResult.CONSUME;
    }
    @Override public void appendHoverText(ItemStack stack, Level level, List<Component> lines, TooltipFlag flags) {
        lines.add(Component.literal(variant == GeraniumVariant.JET
            ? "Чёрный реактивный ударный БПЛА с носовой FPV-камерой"
            : "Винтовой ударный БПЛА: медленнее Герани-2, дольше летает"));
        lines.add(Component.literal("Только ручное управление: поставь на землю и выбери на наземной станции"));
        lines.add(Component.literal("SPACE — запуск; мышь — крен/тангаж; A/D — крен; W/S — газ; F5 — вид"));
        lines.add(Component.literal("F6 — выровнять; ЛКМ — подрыв; Shift+ПКМ на земле — подобрать"));
    }
}
