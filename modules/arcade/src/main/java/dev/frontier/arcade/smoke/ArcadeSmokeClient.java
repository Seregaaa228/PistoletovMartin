package dev.frontier.arcade.smoke;

import com.mojang.blaze3d.platform.InputConstants;
import dev.frontier.arcade.ArcadeVehicles;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import java.util.HashSet;
import java.util.Set;

@Mod.EventBusSubscriber(modid=ArcadeVehicles.ID,value=Dist.CLIENT)
public final class ArcadeSmokeClient {
    private static boolean opened,shot,photo;
    private static int titleTicks,readyTicks,finishTicks;
    private static final Set<Class<?>> RENDERED=new HashSet<>();
    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if(!Boolean.getBoolean("frontier.arcadeSmoke")||event.phase!=TickEvent.Phase.END)return;
        Minecraft mc=Minecraft.getInstance();
        if(!opened&&mc.screen instanceof TitleScreen&&mc.getOverlay()==null&&++titleTicks>3) {
            opened=true;
            if(java.nio.file.Files.exists(mc.gameDirectory.toPath().resolve("saves/Arcade-Test/level.dat")))
                mc.createWorldOpenFlows().loadLevel(mc.screen,"Arcade-Test");
            else mc.createWorldOpenFlows().createFreshLevel("Arcade-Test",new LevelSettings("Arcade Test",GameType.CREATIVE,false,Difficulty.NORMAL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(20261023L,true,false),WorldPresets::createNormalWorldDimensions);
        }
        if(mc.player==null||mc.level==null)return;
        if(++readyTicks>1800) {System.err.println("ARCADE smoke timeout");mc.stop();return;}
        mc.options.keyUp.setDown(false);mc.options.keyRight.setDown(false);
        if(mc.player.getVehicle()!=null) {
            Entity vehicle=mc.player.getVehicle();
            if(ArcadeSmoke.stage==1) {
                mc.options.keyUp.setDown(ArcadeSmoke.age<110);
                mc.options.keyRight.setDown(ArcadeSmoke.age>=60&&ArcadeSmoke.age<110);
                if(ArcadeSmoke.age>=150&&!shot) {
                    shot=true;KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_G));
                    MinecraftForge.EVENT_BUS.post(new InputEvent.Key(GLFW.GLFW_KEY_G,0,GLFW.GLFW_PRESS,0));
                }
            } else if(ArcadeSmoke.stage==2) {
                try {
                    // Exercise the same key mappings and native packets as real play.
                    if(ArcadeSmoke.age<50&&ArcadeSmoke.age%3==0)
                        KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_UP));
                    Class<?> input=Class.forName("xyz.przemyk.simpleplanes.client.ClientEventHandler");
                    ((KeyMapping)input.getField("pitchUp").get(null)).setDown(ArcadeSmoke.age>=40&&ArcadeSmoke.age<135);
                } catch(Exception failure){failure.printStackTrace();}
            }
        }
        if(ArcadeSmoke.stage==3&&!photo&&ArcadeSmoke.age>40) {
            net.minecraft.world.item.CreativeModeTabs.tryRebuildTabContents(mc.player.connection.enabledFeatures(),true,mc.level.registryAccess());
            int old=0,planes=0,tanks=0,monitors=0;
            for(var tab:net.minecraft.world.item.CreativeModeTabs.allTabs())for(var stack:tab.getDisplayItems()) {
                if(ArcadeVehicles.isReplaced(stack))old++;
                var id=net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
                if(id==null)continue;
                if(id.getNamespace().equals("simpleplanes"))planes++;
                if(id.getNamespace().equals("trajanstanks"))tanks++;
                if(id.toString().equals("superbwarfare:monitor"))monitors++;
            }
            ArcadeSmoke.creativeVerified=old==0&&planes>0&&tanks>0&&monitors>0;
            photo=true;Screenshot.grab(mc.gameDirectory,"arcade-vehicles.png",mc.getMainRenderTarget(),c->System.out.println("ARCADE screenshot "+c.getString()));
        }
        for(Entity entity:mc.level.entitiesForRendering()) {
            String name=entity.getClass().getName();
            if(name.contains("trajanstanks.entity.tanks")||name.equals("xyz.przemyk.simpleplanes.entities.PlaneEntity")) {
                if(mc.getEntityRenderDispatcher().getRenderer(entity)!=null)RENDERED.add(entity.getClass());
            }
        }
        ArcadeSmoke.renderedTypes=RENDERED.size();
        if(ArcadeSmoke.done&&++finishTicks>40)mc.stop();
    }
}
