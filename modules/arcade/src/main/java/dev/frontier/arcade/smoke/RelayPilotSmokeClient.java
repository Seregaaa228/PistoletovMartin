package dev.frontier.arcade.smoke;
import dev.frontier.arcade.ArcadeVehicles;
import dev.frontier.arcade.geranium.GeraniumNet;
import dev.frontier.arcade.geranium.GeraniumClient;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;
import dev.frontier.arcade.relay.RelayClient;
import java.nio.file.Files;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.*;
import net.minecraftforge.fml.common.Mod;
@Mod.EventBusSubscriber(modid=ArcadeVehicles.ID,value=Dist.CLIENT)
public final class RelayPilotSmokeClient {
 private static boolean opened,manualPhoto,orbitPhoto;private static int ticks,finishing,bSent,rSent;
 @SubscribeEvent public static void tick(TickEvent.ClientTickEvent e){
  if(!Boolean.getBoolean("frontier.relayPilotSmoke"))return;Minecraft mc=Minecraft.getInstance();
  if(e.phase==TickEvent.Phase.START){keys(mc);return;}
  if(!opened&&mc.screen instanceof TitleScreen&&mc.getOverlay()==null){opened=true;mc.options.setCameraType(CameraType.FIRST_PERSON);
   if(Files.exists(mc.gameDirectory.toPath().resolve("saves/Geranium-Test/level.dat")))mc.createWorldOpenFlows().loadLevel(mc.screen,"Geranium-Test");
   else mc.createWorldOpenFlows().createFreshLevel("Geranium-Test",new LevelSettings("Geranium Test",GameType.CREATIVE,false,Difficulty.NORMAL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(20261024L,true,false),r->r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions());}
  if(mc.player==null||mc.level==null)return;keys(mc);
  if(RelayClient.currentTarget()!=null){RelayPilotSmoke.bindingsVerified=bindings(mc);if(mc.screen!=null)RelayPilotSmoke.actionMenuOpened=true;}
  if(RelayPilotSmoke.control&&!RelayPilotSmoke.done){
   com.atsuishio.superbwarfare.network.NetworkRegistry.PACKET_HANDLER.sendToServer(new com.atsuishio.superbwarfare.network.message.send.VehicleMovementMessage(RelayPilotSmoke.keys));
   com.atsuishio.superbwarfare.network.NetworkRegistry.PACKET_HANDLER.sendToServer(new com.atsuishio.superbwarfare.network.message.send.MouseMoveMessage(RelayPilotSmoke.mouseX,RelayPilotSmoke.mouseY));RelayPilotSmoke.packets++;
   if(bSent<RelayPilotSmoke.bRequests){bSent++;KeyMapping.click(RelayClient.TOGGLE_ORBIT.getKey());}if(rSent<RelayPilotSmoke.rRequests){rSent++;KeyMapping.click(RelayClient.STABILIZE.getKey());}
  }
  if(++ticks>2100&&!RelayPilotSmoke.done)RelayPilotSmoke.timeout();
  if(RelayPilotSmoke.done&&Boolean.getBoolean("frontier.combinedSmoke")){keys(mc);System.setProperty("frontier.relayPilotSmoke","false");System.setProperty("frontier.geraniumSmoke","true");return;}
  if(RelayPilotSmoke.done&&++finishing>35)mc.stop();
 }
 private static boolean bindings(Minecraft mc){
  if(RelayClient.TOGGLE_ORBIT.getKey().getValue()!=GLFW.GLFW_KEY_I||RelayClient.STABILIZE.getKey().getValue()!=GLFW.GLFW_KEY_F6||GeraniumClient.STABILIZE.getKey().getValue()!=GLFW.GLFW_KEY_F6)return false;
  boolean waypoint=false,recruits=false;
  for(KeyMapping mapping:mc.options.keyMappings){
   if(mapping.getName().equals("gui.xaero_new_waypoint"))waypoint=mapping.getKey().getValue()==GLFW.GLFW_KEY_B;
   if(mapping.getName().equals("key.recruits.command_screen_key"))recruits=mapping.getKey().getValue()==GLFW.GLFW_KEY_R;
   if(mapping==RelayClient.TOGGLE_ORBIT||mapping==RelayClient.STABILIZE||mapping==GeraniumClient.STABILIZE)continue;
   if(mapping.getKey().equals(RelayClient.TOGGLE_ORBIT.getKey())||mapping.getKey().equals(RelayClient.STABILIZE.getKey()))return false;
  }
  return waypoint&&recruits;
 }
 private static void keys(Minecraft mc){short k=RelayPilotSmoke.control&&!RelayPilotSmoke.done?RelayPilotSmoke.keys:0;mc.options.keyJump.setDown((k&16)!=0);mc.options.keyUp.setDown((k&4)!=0);mc.options.keyDown.setDown((k&8)!=0);mc.options.keyLeft.setDown((k&1)!=0);mc.options.keyRight.setDown((k&2)!=0);mc.options.keyShift.setDown(false);}
 @SubscribeEvent(priority=EventPriority.LOWEST) public static void photo(RenderGuiEvent.Post e){
  if(!Boolean.getBoolean("frontier.relayPilotSmoke"))return;Minecraft mc=Minecraft.getInstance();var target=RelayClient.currentTarget();if(target==null)return;
  if(RelayPilotSmoke.phase==1&&RelayPilotSmoke.age>25){double d=mc.gameRenderer.getMainCamera().getPosition().distanceTo(target.nosePosition(mc.getFrameTime()));RelayPilotSmoke.cameraSamples++;RelayPilotSmoke.bestCameraError=Math.min(RelayPilotSmoke.bestCameraError,d);}
  if(!manualPhoto&&RelayPilotSmoke.phase==1&&RelayPilotSmoke.age>120){manualPhoto=true;Screenshot.grab(mc.gameDirectory,"relay-manual.png",mc.getMainRenderTarget(),c->System.out.println(c.getString()));}
  if(!orbitPhoto&&RelayPilotSmoke.phase==2&&RelayPilotSmoke.age>110){orbitPhoto=true;Screenshot.grab(mc.gameDirectory,"relay-orbit.png",mc.getMainRenderTarget(),c->System.out.println(c.getString()));}
 }
}
