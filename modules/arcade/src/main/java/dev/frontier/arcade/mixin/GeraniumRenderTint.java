package dev.frontier.arcade.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.frontier.arcade.geranium.GeraniumEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.lavafrai.uncomplicatedfpv.entity.AddonDroneEntity;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/** Original Shahed UV/alpha and geometry, tinted only for the additional Geranium type. */
@Mixin(targets="ru.lavafrai.uncomplicatedfpv.client.renderer.AddonDroneRenderer",remap=false)
public abstract class GeraniumRenderTint extends GeoEntityRenderer<AddonDroneEntity> {
    protected GeraniumRenderTint(EntityRendererProvider.Context context, GeoModel<AddonDroneEntity> model) { super(context,model); }
    @Redirect(
        method="renderRecursively(Lcom/mojang/blaze3d/vertex/PoseStack;Lru/lavafrai/uncomplicatedfpv/entity/AddonDroneEntity;Lsoftware/bernie/geckolib/cache/object/GeoBone;Lnet/minecraft/client/renderer/RenderType;Lnet/minecraft/client/renderer/MultiBufferSource;Lcom/mojang/blaze3d/vertex/VertexConsumer;ZFIIFFFF)V",
        at=@At(value="INVOKE",target="Lsoftware/bernie/geckolib/renderer/GeoEntityRenderer;renderRecursively(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/entity/Entity;Lsoftware/bernie/geckolib/cache/object/GeoBone;Lnet/minecraft/client/renderer/RenderType;Lnet/minecraft/client/renderer/MultiBufferSource;Lcom/mojang/blaze3d/vertex/VertexConsumer;ZFIIFFFF)V",remap=false),
        remap=false,require=1)
    private void frontier$nativeMeshColor(GeoEntityRenderer<?> renderer, PoseStack pose, Entity entity, GeoBone bone,
        RenderType type, MultiBufferSource buffers, VertexConsumer vertices, boolean again, float partial,
        int light, int overlay, float red, float green, float blue, float alpha) {
        if (entity instanceof GeraniumEntity) {
            boolean lens=bone.getName().equals("geranium_lens");
            // Use an absolute tint per bone: parent color must not multiply once more per child.
            red=lens?.08f:.18f; green=lens?.85f:.18f; blue=lens?1f:.20f;
        }
        super.renderRecursively(pose,(AddonDroneEntity)entity,bone,type,buffers,vertices,again,partial,light,overlay,red,green,blue,alpha);
    }
}
