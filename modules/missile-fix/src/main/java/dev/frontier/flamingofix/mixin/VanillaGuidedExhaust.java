package dev.frontier.flamingofix.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.sbw.deepstrike.game.GuidedMissileEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DeepStrike's own engine flame (~500 vertices, as on the aircraft) only near the camera;
 * past that it is a few pixels and the bounded vanilla trail carries the missile.
 */
@Mixin(targets = "dev.sbw.deepstrike.client.ClientSetup", remap = false)
public abstract class VanillaGuidedExhaust {
    @Unique private static final double flamingo$FLAME_DISTANCE = 96;

    @Inject(method = "renderExhaust(Ldev/sbw/deepstrike/game/GuidedMissileEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Z)V",
            at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void flamingo$nearFlame(GuidedMissileEntity missile, float partial, PoseStack pose, MultiBufferSource buffers,
            boolean flag, CallbackInfo result) {
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        if (missile.position().distanceToSqr(camera) > flamingo$FLAME_DISTANCE * flamingo$FLAME_DISTANCE) result.cancel();
    }

    @Inject(method = "renderDistantExhaust", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void flamingo$noDistantExhaust(CallbackInfo result) { result.cancel(); }
}
