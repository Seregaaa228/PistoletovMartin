package dev.frontier.arcade.smoke;
import dev.frontier.arcade.ArcadeVehicles;
import java.nio.file.Files;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
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
public final class RelaySmokeClient {
 private static boolean opened,photo,video;private static int ticks,finishing;
 @SubscribeEvent public static void tick(TickEvent.ClientTickEvent e){
  if(!Boolean.getBoolean("frontier.relaySmoke") || e.phase!=TickEvent.Phase.END)return;Minecraft mc=Minecraft.getInstance();
  if(!opened && mc.screen instanceof TitleScreen && mc.getOverlay()==null){opened=true;
   if(Files.exists(mc.gameDirectory.toPath().resolve("saves/Geranium-Test/level.dat")))mc.createWorldOpenFlows().loadLevel(mc.screen,"Geranium-Test");
   else mc.createWorldOpenFlows().createFreshLevel("Geranium-Test",new LevelSettings("Geranium Test",GameType.CREATIVE,false,Difficulty.NORMAL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(20261024L,true,false),r->r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions());}
  if(mc.player==null || mc.level==null)return;
  if(++ticks>2200 && !RelaySmoke.done)RelaySmoke.timeout();if(RelaySmoke.done && Boolean.getBoolean("frontier.combinedSmoke")){System.setProperty("frontier.relaySmoke","false");System.setProperty("frontier.geraniumSmoke","true");return;}if(RelaySmoke.done && ++finishing>30)mc.stop();
 }
 @SubscribeEvent(priority=EventPriority.LOWEST) public static void photo(RenderGuiEvent.Post e){
  if(!Boolean.getBoolean("frontier.relaySmoke"))return;Minecraft mc=Minecraft.getInstance();if(mc.level==null||mc.player==null)return;
  if(!photo && RelaySmoke.phase==1 && RelaySmoke.age>175){photo=true;Screenshot.grab(mc.gameDirectory,"relay-flight.png",mc.getMainRenderTarget(),c->System.out.println(c.getString()));}
  if((RelaySmoke.phase==2 || RelaySmoke.phase==4) && RelaySmoke.age>10 && RelaySmoke.age<80 && RelaySmoke.remoteDrone!=null){
   var target=dev.frontier.arcade.geranium.GeraniumClient.currentTarget();
   if(target!=null && target.getUUID().equals(RelaySmoke.remoteDrone)){
    if(mc.level.hasChunkAt(target.blockPosition()) && mc.level.getBlockState(new net.minecraft.core.BlockPos(RelaySmoke.landmarkX,80,0)).is(net.minecraft.world.level.block.Blocks.GOLD_BLOCK))RelaySmoke.remoteChunkSamples++;
    var camera=mc.gameRenderer.getMainCamera().getPosition();double err=camera.distanceTo(target.nosePosition(mc.getFrameTime()));
    if(camera.distanceTo(mc.player.getEyePosition())>400){RelaySmoke.remoteCameraSamples++;RelaySmoke.bestCameraError=Math.min(RelaySmoke.bestCameraError,err);}
    if(!video && RelaySmoke.age>55){video=true;Screenshot.grab(mc.gameDirectory,"relay-fpv.png",mc.getMainRenderTarget(),c->System.out.println(c.getString()));}
   }
  }
 }
}
