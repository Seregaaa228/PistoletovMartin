package dev.frontier.industry.vehicle;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.BoatRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "frontier_industry", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class VehicleClient {
    @SubscribeEvent public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(VehicleContent.GUNBOAT.get(), CannonBoatRenderer::new);
    }

    private static final class CannonBoatRenderer extends BoatRenderer {
        CannonBoatRenderer(EntityRendererProvider.Context context) { super(context, false); }
        @Override public void render(Boat boat, float yaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
            super.render(boat, yaw, partial, pose, buffers, light);
            pose.pushPose();
            pose.mulPose(Axis.YP.rotationDegrees(180 - yaw));
            pose.translate(-0.25, 0.48, -0.85);
            pose.scale(0.5F, 0.5F, 0.7F);
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(Blocks.DISPENSER.defaultBlockState(), pose, buffers, light, OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
    }
}
