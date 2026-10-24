package dev.frontier.arcade.smoke;

import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import dev.frontier.arcade.ArcadeVehicles;
import dev.frontier.arcade.geranium.GeraniumNet;
import dev.frontier.arcade.relay.*;
import java.nio.file.Files;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

/** Native owner-monitor flight, orbit transitions and loss-of-radio acceptance in a disposable world. */
@Mod.EventBusSubscriber(modid=ArcadeVehicles.ID)
public final class RelayPilotSmoke {
 public static volatile boolean done,control,bindingsVerified;
 public static volatile boolean actionMenuOpened;
 public static volatile int phase,age,bRequests,rRequests,cameraSamples,packets;
 public static volatile short keys;
 public static volatile double mouseX,mouseY,bestCameraError=Double.MAX_VALUE;
 private static final Map<String,Boolean> checks=new LinkedHashMap<>();
 private static final Map<String,Object> details=new LinkedHashMap<>();
 private static final BlockPos PAD=new BlockPos(0,80,0);
 private static ServerPlayer player;private static ServerLevel level;private static RelayEntity plane;
 private static ItemStack monitor;private static ListTag inventory;private static GameType mode;private static Vec3 oldPlayer,origin,previous,orbitCenter,releasePosition;
 private static int ticks,selected,initialEnergy,blasts;
 private static float previousYaw,previousRoll,initialYaw;
 private static double maxStep,maxYawStep,maxRollStep,maxBank,maxTurn,maxHeight,minRadius=Double.MAX_VALUE,maxRadius,maxHeightError;
 private static net.minecraftforge.common.ForgeConfigSpec.ConfigValue<Double> jamSetting;
 private static net.minecraftforge.common.ForgeConfigSpec.ConfigValue<Double> jamCube;private static double originalCube;
 private static double originalJam;private static net.minecraft.world.entity.Entity jammer;
 private static void check(String name,boolean ok){checks.put(name,ok);System.out.println("RELAY_PILOT_CHECK "+(ok?"PASS ":"FAIL ")+name);}
 @SubscribeEvent public static void tick(TickEvent.ServerTickEvent e){
  if(!Boolean.getBoolean("frontier.relayPilotSmoke")||e.phase!=TickEvent.Phase.END||done)return;
  var server=net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();if(player==null){if(server.getPlayerList().getPlayers().isEmpty())return;player=server.getPlayerList().getPlayers().get(0);level=player.serverLevel();}
  try{ticks++;age++;
   if(ticks==1){initialize();return;}
   if(plane!=null&&!plane.isRemoved())observe();
   if(phase==0&&age==50){
    check("placed relay waits on base without automatic takeoff",!plane.isLaunched()&&!plane.isRelayActive()&&plane.position().distanceTo(origin)<.1&&plane.getEnergy()==RelayEntity.BATTERY);
    plane.setEnergy(28731);plane.setHealth(13);var saved=plane.saveWithoutId(new CompoundTag());RelayEntity copy=RelayContent.TYPE.get().create(level);
    try{copy.load(saved);check("parked owner health and finite energy survive save",copy.ownedBy(player)&&copy.getEnergy()==28731&&copy.getHealth()==13&&!copy.isLaunched());}finally{copy.invalidateCaps();}
    player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);player.setShiftKeyDown(true);var picked=plane.interact(player,InteractionHand.MAIN_HAND);player.setShiftKeyDown(false);
    ItemStack recovered=ItemStack.EMPTY;int count=0;for(ItemStack stack:player.getInventory().items)if(stack.is(RelayContent.ITEM.get())){recovered=stack.copy();count+=stack.getCount();}
    check("parked recovery returns one partially charged deployer",picked.consumesAction()&&plane.isRemoved()&&count==1&&recovered.getOrCreateTag().getInt("RelayEnergy")==28731);
    player.getInventory().clearContent();place(recovered);check("redeploy retains remaining energy and health",plane.getEnergy()==28731&&plane.getHealth()==13&&!plane.isLaunched());
    plane.setEnergy(RelayEntity.BATTERY);plane.setHealth(20);initialEnergy=plane.getEnergy();bind();origin=plane.position();previous=origin;initialYaw=plane.getYRot();phase=1;age=0;control=true;
   }else if(phase==1){
    if(plane.isRemoved())throw new IllegalStateException("aircraft crashed in manual launch");
    keys=(short)(age<12?16:age<35?4:age>=80&&age<115?1:0);
    mouseY=age>=45&&age<55?-.4:0;mouseX=age>=120&&age<135?1:0;
    if(age==160)rRequests++;
    if(age==195){
     check("I and F6 load without conflicts and ignore obsolete saved B R bindings",bindingsVerified);
     check("relay hotkeys do not open waypoint or recruit command screens",!actionMenuOpened);
     check("real SBW client keys launch fixed-wing relay from base",plane.isLaunched()&&plane.isRelayActive()&&!plane.isOrbit()&&packets>100&&plane.position().distanceTo(origin)>40&&maxHeight>origin.y+5);
     check("manual flight uses bank and smooth continuous movement",maxBank>6&&maxTurn>4&&maxYawStep<3&&maxRollStep<2&&maxStep<1.5);
     check("nose camera follows actual piloted airframe",cameraSamples>10&&bestCameraError<.6);
     check("real F6 key levels flight without braking",Math.abs(plane.getXRot())<1&&plane.flightSpeed()>.35);
     check("powered manual flight consumes finite battery",plane.getEnergy()>0&&plane.getEnergy()<initialEnergy-400);
     details.put("manual",Map.of("distance",plane.position().distanceTo(origin),"heightGain",maxHeight-origin.y,"maxBank",maxBank,"maxYawStep",maxYawStep,"maxRollStep",maxRollStep,"maxStep",maxStep,"cameraError",bestCameraError));
     keys=0;mouseX=mouseY=0;bRequests++;
    }
    if(age>=200&&plane.isOrbit()){orbitCenter=plane.orbitCenter();phase=2;age=0;check("real I key captures airborne orbit without relocating aircraft",maxStep<1.5&&plane.flightSpeed()>.35);}
    if(age>240)throw new IllegalStateException("I request did not select orbit");
   }else if(phase==2){
    keys=0;mouseX=mouseY=0;
    if(age>200){double radius=Math.hypot(plane.getX()-orbitCenter.x,plane.getZ()-orbitCenter.z);minRadius=Math.min(minRadius,radius);maxRadius=Math.max(maxRadius,radius);maxHeightError=Math.max(maxHeightError,Math.abs(plane.getY()-orbitCenter.y));}
    if(age==300){releasePosition=plane.position();monitor.getItem().use(level,player,InteractionHand.MAIN_HAND);player.inventoryMenu.broadcastChanges();}
    if(age==320){check("monitor exit leaves powered relay orbiting over assigned area",!plane.activeMonitor()&&plane.isOrbit()&&plane.isRelayActive()&&plane.position().distanceTo(releasePosition)>5&&plane.orbitCenter().distanceTo(orbitCenter)<.1);check("remote view returns to operator body",!RemoteDroneView.active(player));}
    if(age==450){monitor.getItem().use(level,player,InteractionHand.MAIN_HAND);player.inventoryMenu.broadcastChanges();}
    if(age==460)check("monitor can resume observing an existing orbit",plane.activeMonitor()&&plane.isOrbit());
    if(age==900){
     check("sustained native orbit is wide stable and moving",plane.isOrbit()&&minRadius>60&&maxRadius<85&&plane.flightSpeed()>.35);
     check("orbit altitude remains stable without vertical oscillation",maxHeightError<3);
     details.put("orbit",Map.of("minRadius",minRadius,"maxRadius",maxRadius,"heightError",maxHeightError,"center",orbitCenter.toString()));
     persistence();bRequests++;phase=3;age=0;
    }
   }else if(phase==3){
    if(age==12)check("second real I key returns manual control smoothly",plane.activeMonitor()&&!plane.isOrbit()&&plane.flightSpeed()>.35&&maxStep<1.5);
    mouseX=age>=15&&age<30?1:0;mouseY=0;keys=0;
    if(age==40){jamSetting=ru.lavafrai.uncomplicatedfpv.config.ServerConfig.REB_MINI_SIGNAL_LOSS_DB;originalJam=jamSetting.get();jamSetting.set(120d);jamCube=ru.lavafrai.uncomplicatedfpv.config.ServerConfig.REB_MINI_CUBE_SIZE;originalCube=jamCube.get();jamCube.set(40d);
     var j=ru.lavafrai.uncomplicatedfpv.registry.ModEntityTypes.REB_MINI.get().create(level);j.setPos(plane.position().add(0,-6,0));j.setEnabled(true);level.addFreshEntity(j);jammer=j;phase=4;age=0;control=false;}
   }else if(phase==4&&age==15){
    check("native radio loss pauses viewing preserves pairing and selects powered orbit",!plane.activeMonitor()&&plane.isOrbit()&&plane.isRelayActive()&&plane.getPower()>.05&&!monitor.getOrCreateTag().getBoolean("Using")&&monitor.getTag().getBoolean("Linked")&&plane.getStringUUID().equals(monitor.getTag().getString("LinkedDrone")));
    jammer.discard();jammer=null;jamSetting.set(originalJam);jamSetting=null;jamCube.set(originalCube);jamCube=null;
    Vec3 at=plane.position();BlockPos witness=BlockPos.containing(at).offset(0,-2,0);level.setBlockAndUpdate(witness,Blocks.STONE.defaultBlockState());
    plane.destroy();plane.destroy();check("relay has one weak fuel blast and leaves nearby blocks intact",blasts==1&&plane.isRemoved()&&level.getBlockState(witness).is(Blocks.STONE));
    RelayRadioChecks.run(player,RelayPilotSmoke::check,details);finish();
   }
   if(ticks>1650)throw new IllegalStateException("pilot test timed out phase"+phase);
  }catch(Throwable failure){failure.printStackTrace();details.put("exception",failure.toString());check("pilot scenarios complete without exception",false);finish();}
 }
 private static void initialize(){
  check("only disposable acceptance save is opened","Geranium Test".equals(level.getServer().getWorldData().getLevelName()));
  inventory=player.getInventory().save(new ListTag());mode=player.gameMode.getGameModeForPlayer();oldPlayer=player.position();selected=player.getInventory().selected;
  player.setGameMode(GameType.CREATIVE);player.getAbilities().flying=true;player.onUpdateAbilities();player.getInventory().clearContent();player.getInventory().selected=0;player.connection.send(new ClientboundSetCarriedItemPacket(0));
  level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,level.getServer());level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,level.getServer());level.setDayTime(6000);level.setWeatherParameters(6000,0,false,false);
  for(BlockPos p:BlockPos.betweenClosed(-16,80,-16,16,80,180))level.setBlockAndUpdate(p,Blocks.STONE.defaultBlockState());player.teleportTo(level,.5,81,-8.5,0,0);
  place(new ItemStack(RelayContent.ITEM.get()));origin=previous=plane.position();previousYaw=plane.getYRot();previousRoll=plane.getRoll(1);
  var visitor=FakePlayerFactory.get(level,new GameProfile(UUID.randomUUID(),"RelayIntruder"));visitor.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation("superbwarfare:monitor"))));
  check("foreign player cannot bind own relay monitor",plane.interact(visitor,InteractionHand.MAIN_HAND)==InteractionResult.FAIL&&!visitor.getMainHandItem().getOrCreateTag().getBoolean("Linked"));
  plane.setAmmo(10);check("relay cannot be armed",plane.getAmmo()==0);phase=0;age=0;
 }
 private static void place(ItemStack stack){
  player.setItemInHand(InteractionHand.MAIN_HAND,stack);boolean creative=player.getAbilities().instabuild;player.getAbilities().instabuild=false;
  InteractionResult r;try{r=stack.useOn(new UseOnContext(player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(PAD).add(0,.5,0),Direction.UP,PAD,false)));}finally{player.getAbilities().instabuild=creative;}
  plane=level.getEntitiesOfClass(RelayEntity.class,new AABB(PAD).inflate(5),p->p.isAlive()&&!p.isRemoved()).stream().findFirst().orElseThrow();
  check("native deployer consumes exactly one survival item",r.consumesAction()&&stack.isEmpty());player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);player.inventoryMenu.broadcastChanges();
 }
 private static void bind(){monitor=new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation("superbwarfare:monitor")));player.setItemInHand(InteractionHand.MAIN_HAND,monitor);plane.interact(player,InteractionHand.MAIN_HAND);monitor.getItem().use(level,player,InteractionHand.MAIN_HAND);player.inventoryMenu.broadcastChanges();check("native owner monitor binds relay and enters piloting",plane.activeMonitor()&&monitor.getOrCreateTag().getBoolean("Linked"));}
 private static void observe(){
  if(previous!=null){maxStep=Math.max(maxStep,previous.distanceTo(plane.position()));maxYawStep=Math.max(maxYawStep,Math.abs(net.minecraft.util.Mth.wrapDegrees(plane.getYRot()-previousYaw)));maxRollStep=Math.max(maxRollStep,Math.abs(plane.getRoll(1)-previousRoll));}
  previous=plane.position();previousYaw=plane.getYRot();previousRoll=plane.getRoll(1);maxBank=Math.max(maxBank,Math.abs(plane.getRoll(1)));maxTurn=Math.max(maxTurn,Math.abs(net.minecraft.util.Mth.wrapDegrees(plane.getYRot()-initialYaw)));maxHeight=Math.max(maxHeight,plane.getY());
 }
 private static void persistence(){
  var saved=plane.saveWithoutId(new CompoundTag());var copy=RelayContent.TYPE.get().create(level);
  try{copy.load(saved);var rt=copy.saveWithoutId(new CompoundTag());check("new flight mode center velocity and charge persist",copy.ownedBy(player)&&copy.getEnergy()==plane.getEnergy()&&copy.isOrbit()&&saved.getCompound("RelayFlight").equals(rt.getCompound("RelayFlight")));
   var legacy=saved.copy();var f=legacy.getCompound("RelayFlight");for(String key:List.of("Version","Orbit","Throttle","VelocityX","VelocityY","VelocityZ"))f.remove(key);copy.load(legacy);check("old v1.5 airborne relay migrates to autonomous orbit",copy.isOrbit()&&copy.isLaunched()&&copy.position().distanceTo(plane.position())<.01&&copy.getEnergy()==plane.getEnergy());
  }finally{copy.invalidateCaps();}
 }
 @SubscribeEvent public static void explosion(ExplosionEvent.Detonate e){if(!Boolean.getBoolean("frontier.relayPilotSmoke")||e.getLevel()!=level||!(e.getExplosion() instanceof com.atsuishio.superbwarfare.tools.CustomExplosion b)||!(b.getDirectSourceEntity() instanceof RelayEntity))return;blasts++;check("native relay explosion remains weak",b.getRadius()==2&&b.getDamage()==8);}
 public static void timeout(){if(level!=null)level.getServer().execute(()->{if(!done){check("client completed without timeout",false);finish();}});}
 private static void finish(){if(done)return;done=true;control=false;keys=0;mouseX=mouseY=0;if(jammer!=null)jammer.discard();if(jamSetting!=null)jamSetting.set(originalJam);if(jamCube!=null)jamCube.set(originalCube);if(plane!=null&&!plane.isRemoved())plane.discard();
  if(inventory!=null){player.getInventory().clearContent();player.getInventory().load(inventory);player.getInventory().selected=selected;player.setGameMode(mode);player.teleportTo(level,oldPlayer.x,oldPlayer.y,oldPlayer.z,0,0);player.inventoryMenu.broadcastChanges();}
  try{Files.writeString(FMLPaths.GAMEDIR.get().resolve("relay-pilot-results.json"),new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("allPassed",!checks.containsValue(false),"checks",checks,"details",details)));}catch(Exception ex){ex.printStackTrace();}System.out.println("RELAY_PILOT_DONE "+checks.size()+" "+(!checks.containsValue(false)));
 }
}
