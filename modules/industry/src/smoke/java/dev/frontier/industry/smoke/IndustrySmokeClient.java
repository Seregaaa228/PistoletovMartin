package dev.frontier.industry.smoke;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Button;
import dev.frontier.industry.client.FactoryScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
@Mod.EventBusSubscriber(modid="frontier_industry",value=Dist.CLIENT)
public final class IndustrySmokeClient {
 private static boolean scheduled,opened,ordered,stopping;private static int titleTicks,readyTicks,guiTicks,doneTicks;
 private static boolean enabled(){return Boolean.getBoolean("frontier.industrySmokeTest");}
 @SubscribeEvent public static void screen(ScreenEvent.Opening e){if(enabled()&&e.getNewScreen() instanceof TitleScreen)scheduled=true;}
 @SubscribeEvent public static void tick(TickEvent.ClientTickEvent e){
  if(!enabled())return;var mc=Minecraft.getInstance();
  if(e.phase==TickEvent.Phase.START){mc.options.keyUse.setDown(Boolean.getBoolean("frontier.industrySmokeHoldRepair"));return;}
  if(!scheduled&&mc.screen!=null&&mc.screen.getClass().getSimpleName().equals("AccessibilityOnboardingScreen")){mc.options.onboardAccessibility=false;mc.options.save();mc.setScreen(new TitleScreen());}
  if(scheduled&&!opened&&mc.screen instanceof TitleScreen&&mc.getOverlay()==null&&++titleTicks>2){opened=true;mc.createWorldOpenFlows().loadLevel(mc.screen,"Industry-Test");}
  if(mc.player==null||mc.level==null)return;readyTicks++;
  if((Boolean.getBoolean("frontier.industrySmokeGuiOrder")||Boolean.getBoolean("frontier.industrySmokeGuiPreview"))&&mc.screen instanceof FactoryScreen screen){
   guiTicks++;
   if(guiTicks==15&&Boolean.getBoolean("frontier.industrySmokeGuiPreview")){
    var boxes=screen.children().stream().filter(c->c instanceof EditBox).map(c->(EditBox)c).toList();
    if(!boxes.isEmpty()){
     var input=boxes.get(0);screen.setFocused(input);input.setFocused(true);input.setValue("");
     screen.keyPressed(69,0,0);screen.charTyped('e',0);
     System.setProperty("frontier.industrySmokeGuiKeyGuard",String.valueOf(mc.screen==screen&&input.getValue().equals("e")));
     input.setValue("minecraft:iron_swo");screen.keyPressed(258,0,0);
     System.setProperty("frontier.industrySmokeGuiTab",String.valueOf(input.getValue().equals("minecraft:iron_sword")));
    }
   }
   if(guiTicks==30&&!ordered&&Boolean.getBoolean("frontier.industrySmokeGuiOrder")){
    var boxes=screen.children().stream().filter(c->c instanceof EditBox).map(c->(EditBox)c).toList();
    if(boxes.size()==2){boxes.get(0).setFocused(true);boxes.get(0).setValue("minecraft:iron_sword");boxes.get(1).setValue("1");
     screen.children().stream().filter(c->c instanceof Button b&&b.getMessage().getString().equals("Заказать")).map(c->(Button)c).findFirst().orElseThrow().onPress();ordered=true;
     LogUtils.getLogger().info("INDUSTRY_GUI order submitted through actual screen button");}
   }
   if(guiTicks==40)Screenshot.grab(mc.gameDirectory,"industry-factory-ui.png",mc.getMainRenderTarget(),c->LogUtils.getLogger().info("INDUSTRY_UI_SCREENSHOT {}",c.getString()));
  }
  if(readyTicks==400||readyTicks==2200||readyTicks==4400)Screenshot.grab(mc.gameDirectory,"industry-"+readyTicks+".png",mc.getMainRenderTarget(),c->LogUtils.getLogger().info("INDUSTRY_SCREENSHOT {}",c.getString()));
  if(Boolean.getBoolean("frontier.industrySmokeDone"))doneTicks++;
  if(!stopping&&(doneTicks>50||readyTicks>=Integer.getInteger("frontier.industrySmokeTicks",7500))){stopping=true;LogUtils.getLogger().info("INDUSTRY_TEST clean client stop");mc.stop();}
 }
}
