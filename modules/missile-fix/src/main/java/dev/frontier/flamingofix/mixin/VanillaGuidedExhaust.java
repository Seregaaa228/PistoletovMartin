package dev.frontier.flamingofix.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Guided missiles keep bounded vanilla trails; aircraft use a separate native flame pipeline. */
@Mixin(targets = "dev.sbw.deepstrike.client.ClientSetup", remap = false)
public abstract class VanillaGuidedExhaust {
    @Inject(method = {"renderExhaust(Ldev/sbw/deepstrike/game/GuidedMissileEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Z)V",
                     "renderDistantExhaust"},
            at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void flamingo$ordinaryExhaust(CallbackInfo result) { result.cancel(); }
}
