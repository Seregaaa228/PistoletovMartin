package dev.frontier.industry.combat;

import net.minecraft.core.BlockPos;
import dev.frontier.industry.engine.ClaimPermissions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.List;

public final class LauncherDeployerItem extends Item {
    private final LauncherEntity.Kind kind;
    public LauncherDeployerItem(LauncherEntity.Kind kind, Properties properties) { super(properties); this.kind = kind; }
    @Override public InteractionResult useOn(UseOnContext context) {
        if (!(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
        ServerLevel level = player.serverLevel();
        BlockPos position = context.getClickedPos().relative(context.getClickedFace());
        AABB bounds = new AABB(position.getX() - 0.9, position.getY(), position.getZ() - 0.9,
                position.getX() + 1.9, position.getY() + 2.4, position.getZ() + 1.9);
        if (player.distanceToSqr(position.getCenter()) > 64 || !level.getWorldBorder().isWithinBounds(position)
                || !player.mayUseItemAt(position, context.getClickedFace(), context.getItemInHand())
                || !level.hasChunkAt(position.offset(-2, 0, -2)) || !level.hasChunkAt(position.offset(2, 0, 2))
                || position.getY() < level.getMinBuildHeight() || position.getY() + 3 >= level.getMaxBuildHeight()
                || !level.noCollision(bounds) || !level.getEntities(null, bounds).isEmpty()
                || !level.getBlockState(position.below()).isFaceSturdy(level, position.below(), net.minecraft.core.Direction.UP)) {
            player.displayClientMessage(Component.translatable("combat.frontier_industry.place_failed"), true);
            return InteractionResult.FAIL;
        }
        for (int dx : new int[]{-1, 1}) for (int dz : new int[]{-1, 1}) {
            BlockPos corner = position.offset(dx, 0, dz);
            if (!ClaimPermissions.allows(level, player.getUUID(), player.getTeam() == null ? "" : player.getTeam().getName(), corner)) {
                player.displayClientMessage(Component.translatable("combat.frontier_industry.place_failed"), true);
                return InteractionResult.FAIL;
            }
        }
        LauncherEntity station = CombatContent.LAUNCHER.get().create(level);
        if (station == null) return InteractionResult.FAIL;
        station.configure(kind, player.getUUID());
        station.moveTo(position.getX() + 0.5, position.getY(), position.getZ() + 0.5, player.getYRot(), 0);
        if (!level.addFreshEntity(station)) return InteractionResult.FAIL;
        if (!player.isCreative()) context.getItemInHand().shrink(1);
        player.displayClientMessage(Component.translatable("combat.frontier_industry.placed"), false);
        return InteractionResult.CONSUME;
    }
    @Override public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("combat.frontier_industry.deployer_tip"));
        tooltip.add(Component.translatable(kind == LauncherEntity.Kind.BALLISTIC ? "combat.frontier_industry.ballistic_tip" : "combat.frontier_industry.defense_tip"));
    }
}
