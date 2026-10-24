package dev.frontier.arcade.smoke;

import dev.frontier.arcade.ArcadeVehicles;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Real client camera observations for the opt-in isolated UAV smoke. */
@Mod.EventBusSubscriber(modid=ArcadeVehicles.ID,value=Dist.CLIENT)
public final class UavSmokeClient {
    private static boolean opened;
    private static int ticks, finishing;
    private UavSmokeClient() { }
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event){
        if(!Boolean.getBoolean("frontier.uavSmoke")||event.phase!=TickEvent.Phase.END)return;
        Minecraft mc=Minecraft.getInstance();
        if(!opened&&mc.screen instanceof TitleScreen&&mc.getOverlay()==null){
            opened=true;mc.options.setCameraType(CameraType.FIRST_PERSON);
            mc.createWorldOpenFlows().createFreshLevel("Uav-Test",new LevelSettings("Uav Test",GameType.CREATIVE,false,Difficulty.NORMAL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(20261024L,true,false),registry->registry.registryOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions());
        }
        if(mc.player==null||mc.level==null)return;
        short keys=UavSmoke.flying?UavSmoke.scriptedInput:0;
        mc.options.keyUp.setDown((keys&4)!=0);mc.options.keyLeft.setDown((keys&1)!=0);mc.options.keyJump.setDown((keys&16)!=0);
        mc.options.keyDown.setDown(false);mc.options.keyRight.setDown(false);mc.options.keyShift.setDown(false);
        if(++ticks>2350){UavSmoke.timeout();mc.stop();}
        if(UavSmoke.done&&++finishing>35)mc.stop();
    }
    @SubscribeEvent public static void render(RenderLevelStageEvent event){
        if(!Boolean.getBoolean("frontier.uavSmoke")||!UavSmoke.flying||UavSmoke.activeUuid==null||UavSmoke.flightAge<12)return;
        Minecraft mc=Minecraft.getInstance();if(mc.player==null||mc.level==null)return;
        for(var entity:mc.level.entitiesForRendering()){
            if(!entity.getUUID().equals(UavSmoke.activeUuid))continue;
            var camera=mc.gameRenderer.getMainCamera().getPosition();var stack=mc.player.getMainHandItem();
            boolean linked=stack.hasTag()&&((stack.getTag().getBoolean("Using")&&UavSmoke.activeUuid.toString().equals(stack.getTag().getString("LinkedDrone")))
                ||stack.getTag().getBoolean("FrontierUavUsing")&&stack.getTag().hasUUID("FrontierUavLinkedUav")&&UavSmoke.activeUuid.equals(stack.getTag().getUUID("FrontierUavLinkedUav")));
            UavSmoke.observeClient(UavSmoke.activeId,camera.distanceTo(entity.position()),camera.distanceTo(mc.player.getEyePosition()),linked);
            if(UavSmoke.flightAge>90&&linked&&camera.distanceTo(entity.position())<8)UavSmoke.observeLateTerrain(UavSmoke.activeId,mc.level.hasChunkAt(entity.blockPosition()));
            break;
        }
    }
}
