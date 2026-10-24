package dev.frontier.flamingofix.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** RemoteFlights already supplies an ordinary model fallback when this pass declines. */
@Mixin(targets = "dev.sbw.deepstrike.client.DistantFlightRenderer", remap = false)
public abstract class LightweightDistantMissiles {
    @Inject(method = "render(Ldev/sbw/deepstrike/game/GuidedMissileEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;DDDD)Z",
            at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void flamingo$ordinaryModel(CallbackInfoReturnable<Boolean> result) {
        result.setReturnValue(false);
    }
}
