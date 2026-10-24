package dev.frontier.flamingofix.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.frontier.flamingofix.client.LightweightPatriotEffects;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The caller still records trails and retains the native sensor path. */
@Mixin(targets = "dev.sbw.deepstrike.client.PatriotRemoteEffects", remap = false)
public abstract class LightweightPatriotBodies {
    @Inject(method = "renderPacBody(ILdev/sbw/deepstrike/client/PatriotRemoteEffects$Flight;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/world/phys/Vec3;F)V",
            at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void flamingo$ordinaryBody(int id, @Coerce Object flight, PoseStack pose,
            MultiBufferSource.BufferSource buffers, Vec3 camera, float partial, CallbackInfo result) {
        if (LightweightPatriotEffects.render(id, (NativePatriotFlight)flight, pose, buffers, camera)) {
            result.cancel();
        }
    }

    @Inject(method = "renderNativeBody(ILdev/sbw/deepstrike/client/PatriotRemoteEffects$Flight;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/world/phys/Vec3;F)V",
            at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void flamingo$ordinaryManpad(int id, @Coerce Object flight, PoseStack pose,
            MultiBufferSource.BufferSource buffers, Vec3 camera, float partial, CallbackInfo result) {
        if (LightweightPatriotEffects.renderNative(id, (NativePatriotFlight)flight, pose, buffers, camera)) {
            result.cancel();
        }
    }
}
