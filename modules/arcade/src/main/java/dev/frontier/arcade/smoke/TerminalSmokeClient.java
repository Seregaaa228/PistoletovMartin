package dev.frontier.arcade.smoke;
import dev.frontier.arcade.ArcadeVehicles;
import dev.frontier.arcade.relay.RelayClient;
import dev.frontier.arcade.station.*;
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
public final class TerminalSmokeClient {
 private static boolean opened,modelPhoto,menuPhoto;private static int selectedContainer=-1,usesSent;
 @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event){
  if(!Boolean.getBoolean("frontier.terminalSmoke"))return;var mc=Minecraft.getInstance();
  if(event.phase==TickEvent.Phase.START){setKeys(mc);return;}
  if(!opened&&mc.screen instanceof TitleScreen&&mc.getOverlay()==null){opened=true;mc.options.setCameraType(CameraType.FIRST_PERSON);
   if(Files.exists(mc.gameDirectory.toPath().resolve("saves/Geranium-Test/level.dat")))mc.createWorldOpenFlows().loadLevel(mc.screen,"Geranium-Test");
   else mc.createWorldOpenFlows().createFreshLevel("Geranium-Test",new LevelSettings("Geranium Test",GameType.CREATIVE,false,Difficulty.NORMAL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(20261024L,true,false),r->r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions());}
  if(mc.player==null||mc.level==null)return;setKeys(mc);
  if(usesSent<TerminalSmoke.useRequests&&mc.gameMode!=null){usesSent++;mc.gameMode.useItem(mc.player,net.minecraft.world.InteractionHand.MAIN_HAND);}
  if(TerminalSmoke.control){var channel=com.atsuishio.superbwarfare.network.NetworkRegistry.PACKET_HANDLER;channel.sendToServer(new com.atsuishio.superbwarfare.network.message.send.VehicleMovementMessage(TerminalSmoke.keys));TerminalSmoke.packets++;}
  if(TerminalSmoke.phase>=4&&TerminalSmoke.phase<=7)mc.options.renderDistance().set(2);
  if(mc.player.containerMenu instanceof StationMenu menu&&TerminalSmoke.choice!=null&&selectedContainer!=menu.containerId&&TerminalSmoke.age>25){selectedContainer=menu.containerId;System.out.println("TERMINAL_CLIENT_SELECT "+menu.containerId+" screen="+(mc.screen==null?"null":mc.screen.getClass().getName())+" target="+TerminalSmoke.choice+" rows="+menu.entries());StationNetwork.connect(menu,TerminalSmoke.choice);}
  if(TerminalSmoke.done&&!Boolean.getBoolean("frontier.combinedSmoke")){mc.stop();return;}
  if(TerminalSmoke.done){mc.options.renderDistance().set(6);System.setProperty("frontier.terminalSmoke","false");System.setProperty("frontier.relayPilotSmoke","true");}
 }
 private static void setKeys(Minecraft mc){short k=TerminalSmoke.control?TerminalSmoke.keys:0;mc.options.keyJump.setDown((k&16)!=0);mc.options.keyUp.setDown((k&4)!=0);mc.options.keyDown.setDown(false);mc.options.keyLeft.setDown(false);mc.options.keyRight.setDown(false);mc.options.keyShift.setDown(false);}
 @SubscribeEvent(priority=EventPriority.LOWEST) public static void screen(net.minecraftforge.client.event.ScreenEvent.Render.Post event){
  if(!Boolean.getBoolean("frontier.terminalSmoke")||menuPhoto||!(event.getScreen() instanceof dev.frontier.arcade.station.client.StationScreen))return;
  var mc=Minecraft.getInstance();menuPhoto=true;TerminalSmoke.menuObserved=true;Screenshot.grab(mc.gameDirectory,"terminal-station-menu.png",mc.getMainRenderTarget(),c->System.out.println(c.getString()));
 }
 @SubscribeEvent(priority=EventPriority.LOWEST) public static void render(RenderGuiEvent.Post event){
  if(!Boolean.getBoolean("frontier.terminalSmoke"))return;var mc=Minecraft.getInstance();
  if(!modelPhoto&&TerminalSmoke.phase==0&&TerminalSmoke.age>=15){modelPhoto=true;Screenshot.grab(mc.gameDirectory,"terminal-models.png",mc.getMainRenderTarget(),c->System.out.println(c.getString()));}
  var relay=RelayClient.currentTarget();if(relay!=null&&TerminalSmoke.phase==5){TerminalSmoke.cameraSamples++;TerminalSmoke.bestCameraError=Math.min(TerminalSmoke.bestCameraError,mc.gameRenderer.getMainCamera().getPosition().distanceTo(relay.nosePosition(mc.getFrameTime())));}
 }
}
