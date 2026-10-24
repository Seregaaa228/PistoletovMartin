package dev.frontier.arcade.smoke;

import com.atsuishio.superbwarfare.entity.vehicle.DroneEntity;
import com.google.gson.GsonBuilder;
import dev.frontier.arcade.ArcadeVehicles;
import dev.frontier.arcade.control.*;
import dev.frontier.arcade.geranium.*;
import dev.frontier.arcade.relay.*;
import dev.frontier.arcade.station.*;
import java.nio.file.Files;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import ru.lavafrai.uncomplicatedfpv.entity.AddonDroneEntity;

/** Actual registered station/pocket use, native input and distant camera bootstrapping; disposable save only. */
@Mod.EventBusSubscriber(modid=ArcadeVehicles.ID)
public final class TerminalSmoke {
 public static volatile boolean done,control,menuObserved;
 public static volatile int phase,age,packets,cameraSamples,useRequests;
 public static volatile short keys;
 public static volatile UUID choice;
 public static volatile double bestCameraError=Double.MAX_VALUE;
 private static final BlockPos DESK=new BlockPos(0,80,0);
 private static final Map<String,Boolean> checks=new LinkedHashMap<>();
 private static final Map<String,Object> details=new LinkedHashMap<>();
 private static ServerPlayer player;private static ServerLevel level;private static ListTag inventory;private static GameType mode;private static Vec3 oldPlayer;
 private static int selected,ticks;private static ItemStack pocket,sword;private static RelayEntity relay,foreign;private static GeraniumEntity strike;private static AddonDroneEntity mavic;
 private static Vec3 flightStart;private static Entity jammer;private static boolean jamConfigured;private static double jamLoss,jamCube;
 private static void check(String name,boolean pass){checks.put(name,pass);System.out.println("TERMINAL_CHECK "+(pass?"PASS ":"FAIL ")+name);}
 @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event){
  if(!Boolean.getBoolean("frontier.terminalSmoke")||done||event.phase!=TickEvent.Phase.END)return;
  var server=net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();if(player==null){if(server.getPlayerList().getPlayers().isEmpty())return;player=server.getPlayerList().getPlayers().get(0);level=player.serverLevel();}
  try{ticks++;age++;if(ticks==1){initialize();return;}
   if(phase==0&&age==40){
    check("pocket binding preserves owned Mavic identity",pocket.getOrCreateTag().getBoolean("Linked")&&pocket.getOrCreateTag().getString("LinkedDrone").equals(mavic.getStringUUID()));
    useRequests++;flightStart=mavic.position();control=true;phase=1;age=0;
   }else if(phase==1){keys=(short)(age<10?16:age<35?4:0);
    if(age==65){check("pocket drives actual native Mavic through SBW input",pocket.getOrCreateTag().getBoolean("Using")&&packets>30&&mavic.position().distanceTo(flightStart)>3&&RemoteDroneView.active(player));
     check("pocket receiver is accepted by native monitor predicate",pocket.is(com.atsuishio.superbwarfare.init.ModItems.MONITOR.get()));
     check("native monitor alias does not affect unrelated items",!new ItemStack(Items.DIRT).is(com.atsuishio.superbwarfare.init.ModItems.MONITOR.get()));
     pocket.getItem().use(level,player,InteractionHand.MAIN_HAND);control=false;keys=0;mavic.setPos(200,120,.5);
     player.setItemInHand(InteractionHand.MAIN_HAND,sword.copy());player.inventoryMenu.broadcastChanges();choice=strike.getUUID();station().open(player);phase=2;age=0;
    }
   }else if(phase==2){
    if(age==1||age==70){var rows=ControlSessions.ownedRecon(player,DESK);var report=new LinkedHashMap<String,Object>();report.put("playerPos",player.position().toString());report.put("menu",player.containerMenu.getClass().getName());report.put("stationCanUse",station().canUse(player));report.put("rows",rows);report.put("mavicPresent",level.getEntity(mavic.getUUID())==mavic);report.put("mavicPos",mavic.position().toString());report.put("mavicHealth",mavic.getHealth());report.put("mavicEnergy",mavic.getEnergy());report.put("mavicOwner",String.valueOf(ControlSessions.ownerId(mavic)));report.put("mavicController",mavic.getEntityData().get(DroneEntity.CONTROLLER));report.put("held",player.getMainHandItem().toString());details.put("selectionAt"+age,report);System.out.println("TERMINAL_SELECTION "+new GsonBuilder().create().toJson(report));}
    if(ControlSessions.stationActive(player)){flightStart=strike.position();check("station connects to own Geranium through real menu packet",player.getMainHandItem().is(ControlContent.STATION_LINK.get())&&strike.activeMonitor());phase=3;age=0;control=true;}
    else if(age>120)throw new IllegalStateException("station Geranium selection timed out");
   }else if(phase==3){keys=(short)(age<12?16:age<40?4:0);
    if(age==65){check("desktop station native input launches and moves Geranium",strike.isLaunched()&&strike.position().distanceTo(flightStart)>8&&RemoteDroneView.active(player));
     check("station screen rendered a real owned-aircraft snapshot",menuObserved);
     ControlSessions.disconnectStation(player);control=false;keys=0;check("leaving station restores original tagged item exactly",ItemStack.isSameItemSameTags(player.getMainHandItem(),sword)&&player.getMainHandItem().getCount()==1);
     strike.discard();choice=relay.getUUID();phase=4;age=0;
    }
   }else if(phase==4){
    if(age==25){check("airborne native Mavic remains loaded after pocket view exit",level.getEntity(mavic.getUUID())==mavic);player.setItemInHand(InteractionHand.MAIN_HAND,pocket);player.inventoryMenu.broadcastChanges();useRequests++;}
    if(age==60){check("pocket can reconnect to distant Mavic without ground rebinding",pocket.getOrCreateTag().getBoolean("Using")&&RemoteDroneView.active(player)&&mavic.getSignalQuality()>0);pocket.getItem().use(level,player,InteractionHand.MAIN_HAND);player.setItemInHand(InteractionHand.MAIN_HAND,sword.copy());player.inventoryMenu.broadcastChanges();station().open(player);phase=5;age=0;}
   }
   else if(phase==5){
    if(ControlSessions.stationActive(player)&&age>=100){
     check("distant station relay reentry survives client entity loading",relay.activeMonitor()&&RemoteDroneView.active(player)&&cameraSamples>10&&bestCameraError<.6);
     check("distant receiver uses finite real RF quality",relay.getSignalQuality()>0&&relay.getSignalQuality()<=1&&relay.position().distanceTo(player.position())>150);
     details.put("distantCamera",Map.of("samples",cameraSamples,"error",bestCameraError,"distance",relay.position().distanceTo(player.position()),"quality",relay.getSignalQuality()));
     jamLoss=ru.lavafrai.uncomplicatedfpv.config.ServerConfig.REB_MINI_SIGNAL_LOSS_DB.get();jamCube=ru.lavafrai.uncomplicatedfpv.config.ServerConfig.REB_MINI_CUBE_SIZE.get();
     jamConfigured=true;ru.lavafrai.uncomplicatedfpv.config.ServerConfig.REB_MINI_SIGNAL_LOSS_DB.set(120d);ru.lavafrai.uncomplicatedfpv.config.ServerConfig.REB_MINI_CUBE_SIZE.set(40d);
     var j=ru.lavafrai.uncomplicatedfpv.registry.ModEntityTypes.REB_MINI.get().create(level);j.setPos(relay.position().add(0,-6,0));j.setEnabled(true);level.addFreshEntity(j);jammer=j;phase=6;age=0;
    }else if(age>180)throw new IllegalStateException("distant relay camera timed out");
   }else if(phase==6){
    if(age==15){check("RF loss leaves relay orbiting and returns original held item",!ControlSessions.stationActive(player)&&relay.isRelayActive()&&relay.isOrbit()&&ItemStack.isSameItemSameTags(player.getMainHandItem(),sword));
     check("station cannot bypass active REB to reconnect",!ControlSessions.connectStation(player,DESK,relay.getUUID()));
     clearJammer();
    }
    if(age==30){choice=relay.getUUID();station().open(player);phase=7;age=0;}
   }else if(phase==7){
    if(age==100){check("same station reconnects after RF recovery without ground rebinding",ControlSessions.stationActive(player)&&relay.activeMonitor());
     level.destroyBlock(DESK,false);phase=8;age=0;
    }
   }else if(phase==8&&age==10){
    check("destroying station ends remote control and restores original item",!ControlSessions.stationActive(player)&&ItemStack.isSameItemSameTags(player.getMainHandItem(),sword));
    int copies=player.getInventory().items.stream().filter(s->ItemStack.isSameItemSameTags(s,sword)).mapToInt(ItemStack::getCount).sum();
    check("station transitions neither duplicate original nor retain virtual controller",copies==1&&player.getInventory().items.stream().noneMatch(s->s.is(ControlContent.STATION_LINK.get())));
    var saved=relay.saveWithoutId(new CompoundTag());relay.readAdditionalSaveData(saved);placeDesk();
    check("station can repair native pairing after saved relay reload",ControlSessions.connectStation(player,DESK,relay.getUUID()));phase=9;age=0;
   }else if(phase==9&&age==25){
    check("reloaded owner relay becomes controllable without entity interaction",ControlSessions.stationActive(player)&&relay.activeMonitor());
    player.teleportTo(level,20,81,-3,0,0);phase=10;age=0;
   }else if(phase==10&&age==10){
    check("walking away from physical station ends session and returns item",!ControlSessions.stationActive(player)&&ItemStack.isSameItemSameTags(player.getMainHandItem(),sword));
    for(Entity fixture:Arrays.asList(relay,foreign,mavic,strike))if(fixture!=null&&!fixture.isRemoved())fixture.discard();
    RelayRadioChecks.run(player,TerminalSmoke::check,details);finish();
   }
   if(ticks>1100)throw new IllegalStateException("terminal test timeout phase"+phase);
  }catch(Throwable failure){failure.printStackTrace();details.put("exception",failure.toString());check("terminal scenarios finish without exception",false);finish();}
 }
 private static StationBlockEntity station(){return (StationBlockEntity)level.getBlockEntity(DESK);}
 private static void placeDesk(){level.setBlockAndUpdate(DESK,StationContent.GROUND_STATION.get().defaultBlockState());station().setOwner(player.getUUID());}
 private static void initialize(){
  check("terminal tests only open disposable acceptance world","Geranium Test".equals(level.getServer().getWorldData().getLevelName()));
  inventory=player.getInventory().save(new ListTag());mode=player.gameMode.getGameModeForPlayer();oldPlayer=player.position();selected=player.getInventory().selected;
  player.setGameMode(GameType.CREATIVE);player.getAbilities().flying=true;player.onUpdateAbilities();player.getInventory().clearContent();player.getInventory().selected=0;player.connection.send(new ClientboundSetCarriedItemPacket(0));
  level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,level.getServer());level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,level.getServer());level.setDayTime(6000);level.setWeatherParameters(6000,0,false,false);
  for(BlockPos p:BlockPos.betweenClosed(-10,79,-10,18,79,25))level.setBlockAndUpdate(p,Blocks.STONE.defaultBlockState());player.teleportTo(level,.5,81,-3.5,0,14);
  var blockStack=new ItemStack(StationContent.ITEM.get());player.setItemInHand(InteractionHand.MAIN_HAND,blockStack);
  blockStack.useOn(new UseOnContext(player,InteractionHand.MAIN_HAND,new BlockHitResult(new Vec3(.5,80,.5),Direction.UP,DESK.below(),false)));
  check("actual station BlockItem placement stores its owner",station()!=null&&station().ownedBy(player));
  var tag=station().saveWithoutMetadata();var clone=new StationBlockEntity(DESK,StationContent.GROUND_STATION.get().defaultBlockState());clone.load(tag);check("station owner persists in native block NBT",player.getUUID().equals(clone.ownerId()));
  for(int x=0;x<=320;x+=16)level.getChunkAt(new BlockPos(x,120,0));
  relay=RelayContent.TYPE.get().create(level);relay.setOwner(player.getUUID());relay.setEnergy(RelayEntity.BATTERY);relay.setPos(300,115,.5);level.addFreshEntity(relay);relay.launch();
  foreign=RelayContent.TYPE.get().create(level);foreign.setOwner(UUID.randomUUID());foreign.setEnergy(RelayEntity.BATTERY);foreign.setPos(6,80,5);level.addFreshEntity(foreign);
  mavic=ru.lavafrai.uncomplicatedfpv.registry.ModEntityTypes.MAVIC_DRONE_NO_DROP.get().create(level);mavic.setEnergy(32000);mavic.setPos(3,81,10);level.addFreshEntity(mavic);
  strike=GeraniumContent.TYPE.get().create(level);strike.setOwner(player.getUUID());strike.setEnergy(GeraniumEntity.BATTERY);strike.setPos(7,81,10);level.addFreshEntity(strike);
  check("station lists big own aircraft and excludes foreign or Mavic",ControlSessions.ownedRecon(player,DESK).stream().anyMatch(e->e.id().equals(strike.getUUID()))&&ControlSessions.ownedRecon(player,DESK).stream().noneMatch(e->e.id().equals(foreign.getUUID())||e.id().equals(mavic.getUUID())));
  check("station refuses foreign target",!ControlSessions.connectStation(player,DESK,foreign.getUUID()));
  check("station refuses small Mavic target",!ControlSessions.connectStation(player,DESK,mavic.getUUID()));
  var legacy=new ItemStack(com.atsuishio.superbwarfare.init.ModItems.MONITOR.get());player.setItemInHand(InteractionHand.MAIN_HAND,legacy);
  check("legacy monitor cannot physically bind large aircraft",ControlSessions.interact(relay,player,InteractionHand.MAIN_HAND)==InteractionResult.FAIL&&ControlSessions.interact(strike,player,InteractionHand.MAIN_HAND)==InteractionResult.FAIL);
  legacy.getOrCreateTag().putString("LinkedDrone",relay.getStringUUID());legacy.getOrCreateTag().putBoolean("Linked",true);
  check("legacy monitor cannot remotely bypass desktop restriction",ControlSessions.useLegacy(player,legacy).getResult()==InteractionResult.FAIL&&!legacy.getOrCreateTag().getBoolean("Using"));
  check("old large-aircraft monitor is released for small-drone reuse",!legacy.getOrCreateTag().getBoolean("Linked")&&!legacy.getOrCreateTag().hasUUID(ControlSessions.PAIR));
  check("empty legacy monitor does not auto-bind to a nearby large aircraft",ControlSessions.useLegacy(player,legacy)==null&&!legacy.getOrCreateTag().getBoolean("Linked"));
  var ordinary=new ItemStack(com.atsuishio.superbwarfare.init.ModItems.MONITOR.get());ordinary.getOrCreateTag().putString("LinkedDrone",UUID.randomUUID().toString());ordinary.getOrCreateTag().putBoolean("Linked",true);ordinary.getOrCreateTag().putBoolean("Using",true);
  ControlSessions.monitorTick(ordinary,level,player,0,true);check("unrelated SBW monitor keeps native lifecycle",ordinary.getOrCreateTag().getBoolean("Using"));
  check("native battery compatibility neither refills charge nor rejects original Mavic",mavic.getEnergy()==0&&mavic.getMaxEnergy()==Integer.MAX_VALUE&&ControlSessions.isPowered(mavic)&&ControlSessions.batteryPercent(mavic)==-1);
  for(String smallId:List.of("mavic_drone_with_drop","fpv_drone")){
   var small=(AddonDroneEntity)ForgeRegistries.ENTITY_TYPES.getValue(new net.minecraft.resources.ResourceLocation("uncomplicatedfpv",smallId)).create(level);small.setPos(3,81,9);
   var smallPocket=new ItemStack(ControlContent.POCKET.get());player.setItemInHand(InteractionHand.MAIN_HAND,smallPocket);ControlSessions.interact(small,player,InteractionHand.MAIN_HAND);
   check("pocket supports original "+smallId,smallPocket.getOrCreateTag().getBoolean("Linked")&&ControlSessions.monitorFor(player,smallPocket,small)&&player.getUUID().equals(ControlSessions.ownerId(small)));
   small.discard();small.invalidateCaps();
  }
  int relayCharge=relay.getEnergy();relay.setEnergy(0);strike.setEnergy(0);
  check("native battery compatibility never bypasses finite large-aircraft batteries",!ControlSessions.isPowered(relay)&&!ControlSessions.isPowered(strike));relay.setEnergy(relayCharge);strike.setEnergy(GeraniumEntity.BATTERY);
  pocket=new ItemStack(ControlContent.POCKET.get());player.setItemInHand(InteractionHand.MAIN_HAND,pocket);
  ControlSessions.interact(relay,player,InteractionHand.MAIN_HAND);check("pocket cannot bind relay",!pocket.getOrCreateTag().getBoolean("Linked"));
  ControlSessions.interact(strike,player,InteractionHand.MAIN_HAND);check("pocket cannot bind big Geranium",!pocket.getOrCreateTag().getBoolean("Linked"));
  ControlSessions.interact(mavic,player,InteractionHand.MAIN_HAND);player.inventoryMenu.broadcastChanges();
  sword=new ItemStack(Items.DIAMOND_SWORD);sword.setHoverName(Component.literal("Terminal roundtrip test"));sword.getOrCreateTag().putInt("AcceptanceToken",7301);
  phase=0;age=0;
 }
 private static void clearJammer(){if(jammer!=null){jammer.discard();jammer=null;}if(jamConfigured){jamConfigured=false;ru.lavafrai.uncomplicatedfpv.config.ServerConfig.REB_MINI_SIGNAL_LOSS_DB.set(jamLoss);ru.lavafrai.uncomplicatedfpv.config.ServerConfig.REB_MINI_CUBE_SIZE.set(jamCube);}}
 private static void finish(){if(done)return;done=true;control=false;keys=0;clearJammer();ControlSessions.disconnectStation(player);
  for(Entity e:Arrays.asList(relay,foreign,mavic,strike))if(e!=null&&!e.isRemoved())e.discard();if(level.getBlockState(DESK).is(StationContent.GROUND_STATION.get()))level.removeBlock(DESK,false);
  if(inventory!=null){player.closeContainer();player.getInventory().clearContent();player.getInventory().load(inventory);player.getInventory().selected=selected;player.setGameMode(mode);player.teleportTo(level,oldPlayer.x,oldPlayer.y,oldPlayer.z,0,0);player.inventoryMenu.broadcastChanges();}
  try{Files.writeString(FMLPaths.GAMEDIR.get().resolve("terminal-results.json"),new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("allPassed",!checks.containsValue(false),"checks",checks,"details",details)));}catch(Exception failure){failure.printStackTrace();}System.out.println("TERMINAL_DONE "+checks.size()+" "+!checks.containsValue(false));
 }
}
