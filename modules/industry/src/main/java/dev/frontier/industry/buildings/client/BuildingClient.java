package dev.frontier.industry.buildings.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.frontier.industry.IndustryMod;
import dev.frontier.industry.buildings.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid=IndustryMod.ID,bus=Mod.EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
public final class BuildingClient {
    public static dev.frontier.industry.engine.BlueprintBounds previewBounds(dev.frontier.industry.SiteType type){return dev.frontier.industry.architecture.FinalIndustryArchitecture.bounds(type,BuildingOrientation.forPlayer(Minecraft.getInstance().player));}
    @SubscribeEvent public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(FunctionalBuildings.SIGN_ENTITY.get(),SignRenderer::new);
        event.registerBlockEntityRenderer(FunctionalBuildings.GUN_DISPLAY.get(),GunDisplayRenderer::new);
    }
    public static final class SignRenderer implements BlockEntityRenderer<BuildingNameplate.SignEntity> {
        public SignRenderer(BlockEntityRendererProvider.Context context){}
        @Override public void render(BuildingNameplate.SignEntity sign,float tick,PoseStack pose,MultiBufferSource buffer,int light,int overlay) {
            if(!(sign.getBlockState().getBlock() instanceof BuildingNameplate block))return;
            pose.pushPose();pose.translate(.5,.5,.5);
            var facing=sign.getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING);
            pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));pose.translate(0,0,-.34);
            pose.scale(.018f,-.018f,.018f);
            var font=Minecraft.getInstance().font;String[] rows=block.textKeys();
            for(int i=0;i<rows.length;i++) {
                var text=net.minecraft.network.chat.Component.translatable(rows[i]);
                float scale=Math.min(1f,142f/Math.max(1,font.width(text)));
                pose.pushPose();pose.translate(0,i==0?-11:4,0);pose.scale(scale,scale,1);
                font.drawInBatch(text,-font.width(text)/2f,0,i==0?0xE6EDF3:0xFFBB48,false,pose.last().pose(),buffer,Font.DisplayMode.NORMAL,0,15728880);
                pose.popPose();
            }
            pose.popPose();
        }
    }
}
