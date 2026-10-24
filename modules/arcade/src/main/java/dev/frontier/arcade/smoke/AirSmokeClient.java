package dev.frontier.arcade.smoke;

import dev.frontier.arcade.ArcadeVehicles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.*;

@Mod.EventBusSubscriber(modid=ArcadeVehicles.ID,value=Dist.CLIENT)
public final class AirSmokeClient {
    private static boolean opened;
    private static int ticks, finish;
    private static final Set<String> rendered=new HashSet<>();
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent e) {
        if(!Boolean.getBoolean("frontier.airSmoke")||e.phase!=TickEvent.Phase.END)return;
        var mc=Minecraft.getInstance();
        if(!opened&&mc.screen instanceof TitleScreen&&mc.getOverlay()==null) {
            opened=true;mc.createWorldOpenFlows().createFreshLevel("Air-Test",new LevelSettings("Air Test",GameType.CREATIVE,false,Difficulty.NORMAL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(20261024L,true,false),r->r.registryOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions());
        }
        if(mc.player==null||mc.level==null)return;
        mc.options.keyUp.setDown(mc.player.getVehicle()!=null);
        if(!AirSmoke.creativeChecked) {
            CreativeModeTabs.tryRebuildTabContents(mc.player.connection.enabledFeatures(),true,mc.level.registryAccess());
            int blocked=0,air=0,monitor=0;
            for(var tab:CreativeModeTabs.allTabs())for(var stack:tab.getDisplayItems()) {
                if(ArcadeVehicles.isReplaced(stack))blocked++;
                if(stack.hasTag()&&stack.getTag().getCompound("BlockEntityTag").getString("EntityType").equals("superbwarfare:a_10a"))air++;
                if(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem()).toString().equals("superbwarfare:monitor"))monitor++;
            }
            AirSmoke.creativeOkay=blocked==0&&air>0&&monitor>0;AirSmoke.creativeChecked=true;
        }
        for(var entity:mc.level.entitiesForRendering())if(entity.getClass().getName().startsWith("com.atsuishio.superbwarfare.entity.vehicle.")&&mc.getEntityRenderDispatcher().getRenderer(entity)!=null)rendered.add(net.minecraft.world.entity.EntityType.getKey(entity.getType()).toString());
        AirSmoke.aircraftRendered=rendered.size();
        var cameraTarget=dev.frontier.arcade.uav.UavCameraClient.currentTarget();
        if(cameraTarget!=null&&mc.gameRenderer.getMainCamera().getPosition().distanceTo(cameraTarget.position())<3.0
                &&mc.gameRenderer.getMainCamera().getPosition().distanceTo(mc.player.position())>10.0)AirSmoke.shahedCameraObserved=true;
        if(++ticks>3200) {System.err.println("AIR timeout");mc.stop();}
        if(AirSmoke.done&&++finish>35)mc.stop();
    }
}
