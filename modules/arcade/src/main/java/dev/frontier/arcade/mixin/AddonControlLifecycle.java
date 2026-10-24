package dev.frontier.arcade.mixin;

import dev.frontier.arcade.control.ControlSessions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.lavafrai.uncomplicatedfpv.entity.AddonDroneEntity;

@Mixin(value = AddonDroneEntity.class, remap = false)
public abstract class AddonControlLifecycle {
    @Inject(method = "m_6096_", at = @At("HEAD"), cancellable = true, remap = false)
    private void frontier$bind(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> result) {
        InteractionResult handled = ControlSessions.interact((AddonDroneEntity)(Object)this, player, hand);
        if (handled != null) result.setReturnValue(handled);
    }
    @Inject(method = "m_6096_", at = @At("RETURN"), remap = false)
    private void frontier$bound(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> result) {
        ControlSessions.afterInteract((AddonDroneEntity)(Object)this, player, hand);
    }
    @Inject(method = "m_6075_", at = @At("HEAD"), remap = false)
    private void frontier$owner(CallbackInfo callback) { ControlSessions.rememberNativeOwner((AddonDroneEntity)(Object)this); }
    @Inject(method = "handleSignalLoss", at = @At("HEAD"), remap = false)
    private void frontier$rememberPair(ServerPlayer source, CallbackInfo callback) { ControlSessions.beforeSignalLoss((AddonDroneEntity)(Object)this); }
    @Inject(method = "handleSignalLoss", at = @At("RETURN"), remap = false)
    private void frontier$keepPair(ServerPlayer source, CallbackInfo callback) { ControlSessions.afterSignalLoss((AddonDroneEntity)(Object)this); }
    @Inject(method = "m_142687_", at = @At("HEAD"), remap = false)
    private void frontier$removed(Entity.RemovalReason reason, CallbackInfo callback) {
        if (reason == Entity.RemovalReason.KILLED || reason == Entity.RemovalReason.DISCARDED) ControlSessions.forgetPair((AddonDroneEntity)(Object)this);
    }
}
