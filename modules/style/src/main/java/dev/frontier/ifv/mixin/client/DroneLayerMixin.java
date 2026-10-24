package dev.frontier.ifv.mixin.client;

import com.atsuishio.superbwarfare.client.layer.vehicle.DroneLayer;
import com.atsuishio.superbwarfare.entity.vehicle.DroneEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.frontier.ifv.client.StyleGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import software.bernie.geckolib.cache.object.BakedGeoModel;

/** Removes the extra energy-swirl pass solely for the native recon entity type. */
@Mixin(value = DroneLayer.class, remap = false)
public abstract class DroneLayerMixin {
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/atsuishio/superbwarfare/entity/vehicle/DroneEntity;Lsoftware/bernie/geckolib/cache/object/BakedGeoModel;Lnet/minecraft/client/renderer/RenderType;Lnet/minecraft/client/renderer/MultiBufferSource;Lcom/mojang/blaze3d/vertex/VertexConsumer;FII)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void frontierStyle$nativeRecon(PoseStack poseStack, DroneEntity animatable,
                                          BakedGeoModel bakedModel, RenderType renderType,
                                          MultiBufferSource bufferSource, VertexConsumer buffer,
                                          float partialTick, int packedLight, int packedOverlay,
                                          CallbackInfo callback) {
        if (StyleGraphics.hideReconGlow(animatable)) callback.cancel();
    }
}
