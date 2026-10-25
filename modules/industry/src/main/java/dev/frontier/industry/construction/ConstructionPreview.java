package dev.frontier.industry.construction;
import dev.frontier.industry.*;
import dev.frontier.industry.engine.Blueprint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid=IndustryMod.ID,value=Dist.CLIENT)
public final class ConstructionPreview {
    private static BlockPos last=BlockPos.ZERO;
    private static SiteType lastType;
    private static int checked=-100;
    private static boolean free;
    private static net.minecraft.client.multiplayer.ClientLevel checkedLevel;
    private static void clear() { lastType=null;checked=-100;checkedLevel=null; }
    @SubscribeEvent public static void render(RenderLevelStageEvent e){
        if(e.getStage()!=RenderLevelStageEvent.Stage.AFTER_PARTICLES)return;
        var mc=Minecraft.getInstance();if(mc.level==null||!ConstructionAccess.canBuild(mc.player)||mc.getCameraEntity()!=mc.player||mc.screen!=null||!(mc.hitResult instanceof BlockHitResult hit)){clear();return;}
        var type=IndustryContent.deployedType(mc.player.getMainHandItem());if(type==null)type=IndustryContent.deployedType(mc.player.getOffhandItem());if(type==null){clear();return;}
        BlockPos origin=hit.getBlockPos().relative(hit.getDirection());var bounds=Blueprint.bounds(type);
        if(mc.level!=checkedLevel||!origin.equals(last)||type!=lastType||mc.player.tickCount-checked>=10){
            free=true;outer:for(int x=bounds.minX();x<=bounds.maxX();x++)for(int z=bounds.minZ();z<=bounds.maxZ();z++)for(int y=bounds.minY();y<=bounds.maxY();y++){
                var pos=origin.offset(x,y,z);if(!mc.level.hasChunkAt(pos)||!mc.level.getWorldBorder().isWithinBounds(pos)||!mc.level.getBlockState(pos).isAir()){free=false;break outer;}
            }
            last=origin;lastType=type;checked=mc.player.tickCount;checkedLevel=mc.level;
        }
        var camera=e.getCamera().getPosition();var pose=e.getPoseStack();pose.pushPose();pose.translate(-camera.x,-camera.y,-camera.z);
        var buffer=mc.renderBuffers().bufferSource();var lines=buffer.getBuffer(RenderType.lines());
        var box=new AABB(origin.offset(bounds.minX(),0,bounds.minZ()),origin.offset(bounds.maxX()+1,1,bounds.maxZ()+1));
        LevelRenderer.renderLineBox(pose,lines,box,free?.25f:1f,free?1f:.25f,.35f,.9f);
        pose.popPose();buffer.endBatch(RenderType.lines());
    }
}
