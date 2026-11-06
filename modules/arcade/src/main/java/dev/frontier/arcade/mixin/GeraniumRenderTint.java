package dev.frontier.arcade.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.frontier.arcade.geranium.GeraniumEntity;
import dev.frontier.arcade.geranium.GeraniumVariant;
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

/**
 * Original Shahed UV/alpha and geometry, tinted only for our station aircraft: the jet Geranium-2 is
 * dark grey with a painted band, a metal nozzle and a hot core; the Shahed-136 keeps the original skin.
 * Added cubes sample the texture's white pixel, so their colour comes from here.
 */
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
        if (entity instanceof GeraniumEntity geranium) {
            // Use an absolute tint per bone: parent color must not multiply once more per child.
            boolean jet=geranium.variant()==GeraniumVariant.JET;
            switch (bone.getName()) {
                case "geranium_lens" -> { red=.08f; green=.85f; blue=1f; }
                case "nose_camera" -> { red=green=.14f; blue=.15f; }
                case "jet_exhaust" -> { red=1f; green=.4f; blue=.1f; } // hot core, recessed in the nozzle ring
                case "jet_nozzle" -> { red=green=.34f; blue=.36f; } // bare metal
                case "jet_intake" -> { red=.27f; green=.28f; blue=.30f; }
                case "jet_duct" -> { red=green=blue=.05f; }
                case "jet_fan" -> { red=green=.38f; blue=.40f; }
                case "jet_band" -> { red=.78f; green=.56f; blue=.22f; } // painted band ahead of the wing
                default -> { if (jet) { red=.21f; green=.22f; blue=.24f; } } // dark grey; the Shahed keeps the renderer colour
            }
        }
        super.renderRecursively(pose,(AddonDroneEntity)entity,bone,type,buffers,vertices,again,partial,light,overlay,red,green,blue,alpha);
    }
}
