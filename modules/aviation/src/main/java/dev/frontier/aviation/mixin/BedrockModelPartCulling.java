package dev.frontier.aviation.mixin;

import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakedGeometryChunk;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.frontier.aviation.client.ModelPartCulling;
import net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * SBW's bedrock vehicle models reach Embeddium through this writer, one geometry piece per
 * bone. A piece wholly outside the view is reported as written without touching its
 * vertices (see {@link ModelPartCulling}).
 */
@Mixin(targets = "com.github.mcmodderanchor.simplebedrockmodel.v2.client.compat.sodium.SodiumBakedChunkWriter", remap = false)
public abstract class BedrockModelPartCulling {
    @Inject(method = "writeQuads(Lcom/github/mcmodderanchor/simplebedrockmodel/v2/common/model/baked/BakedGeometryChunk;"
            + "Lnet/caffeinemc/mods/sodium/api/vertex/buffer/VertexBufferWriter;IIFFFFLorg/joml/Matrix4f;Lorg/joml/Matrix3f;Z)Z",
            at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private void aviation$skipOffScreen(BakedGeometryChunk chunk, VertexBufferWriter writer, int light, int overlay,
            float red, float green, float blue, float alpha, Matrix4f pose, Matrix3f normal, boolean flag,
            CallbackInfoReturnable<Boolean> result) {
        if (!ModelPartCulling.ENABLED || !RenderSystem.isOnRenderThread() || !chunk.hasQuads()) return;
        if (ModelPartCulling.offScreen(chunk, chunk.quads().positions(), RenderSystem.getProjectionMatrix(),
                RenderSystem.getModelViewMatrix(), pose)) result.setReturnValue(true);
    }
}
