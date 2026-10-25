package dev.frontier.industry.repair;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/** Presentation-only flag. No inventory, energy, timer or production order is stored here. */
public final class PressWorkstationEntity extends BlockEntity implements GeoBlockEntity {
    private static final RawAnimation WORK = RawAnimation.begin().thenLoop("animation.press.work");
    private static final RawAnimation STOP = RawAnimation.begin().thenLoop("animation.press.stop");
    private final AnimatableInstanceCache animationCache = GeckoLibUtil.createInstanceCache(this);
    private boolean operating;
    public PressWorkstationEntity(BlockPos pos, BlockState state) { super(RepairContent.PRESS.get(), pos, state); }
    public boolean operating() { return operating; }
    public void setOperating(boolean value) {
        if (level == null || level.isClientSide || operating == value) return;
        operating = value;
        // Send only on a transition. A new tracking client receives the current flag in the chunk tag.
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }
    @Override public CompoundTag getUpdateTag() {
        CompoundTag tag = new CompoundTag(); tag.putBoolean("Operating", operating); return tag;
    }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    @Override public void load(CompoundTag tag) {
        super.load(tag);
        // Never resume a saved visual as server work. The site simulation supplies it after load.
        operating = level != null && level.isClientSide && tag.getBoolean("Operating");
    }
    @Override public void handleUpdateTag(CompoundTag tag) { operating = tag.getBoolean("Operating"); }
    @Override public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "work", 0, state -> state.setAndContinue(operating ? WORK : STOP)));
    }
    @Override public AnimatableInstanceCache getAnimatableInstanceCache() { return animationCache; }
}
