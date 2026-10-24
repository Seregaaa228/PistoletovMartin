package dev.frontier.arcade.smoke;

import com.google.gson.GsonBuilder;
import dev.frontier.arcade.ArcadeVehicles;
import dev.frontier.arcade.geranium.*;
import dev.frontier.arcade.relay.*;
import dev.frontier.arcade.shop.NativeAviationCatalog;
import java.nio.file.Files;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

/** Disposable-world acceptance: real autonomous ticks and native remote chunk/entity subscription. */
@Mod.EventBusSubscriber(modid=ArcadeVehicles.ID)
public final class RelaySmoke {
    public static volatile boolean done;
    public static volatile int phase,age;
    public static volatile UUID photoPlane,remoteDrone;
    public static volatile int remoteChunkSamples,remoteCameraSamples,landmarkX=480;
    public static volatile double bestCameraError=Double.MAX_VALUE;
    private static final Map<String,Boolean> CHECKS=new LinkedHashMap<>();
    private static final Map<String,Object> DETAILS=new LinkedHashMap<>();
    private static ServerPlayer player;private static ServerLevel level;
    private static RelayAirframeChecks airframe;
    private static RelayEntity relay,relaySecond;private static GeraniumEntity drone;
    private static Vec3 body,releasedPosition;
    private static int ticks;
    private static ListTag inventory;private static GameType mode;private static Vec3 playerStart;private static int selected;
    private static void check(String name,boolean ok){CHECKS.put(name,ok);System.out.println("RELAY_CHECK "+(ok?"PASS ":"FAIL ")+name);}
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent e){
        if(!Boolean.getBoolean("frontier.relaySmoke") || e.phase!=TickEvent.Phase.END || done)return;
        if(player==null){var players=net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayers();if(players.isEmpty())return;player=players.get(0);level=player.serverLevel();}
        try{
            ticks++;age++;
            if(ticks==1){
                check("dedicated isolated test save","Geranium Test".equals(level.getServer().getWorldData().getLevelName()));
                inventory=player.getInventory().save(new ListTag());selected=player.getInventory().selected;mode=player.gameMode.getGameModeForPlayer();playerStart=player.position();
                player.setGameMode(GameType.CREATIVE);player.getAbilities().flying=true;player.onUpdateAbilities();player.getInventory().clearContent();player.getInventory().selected=0;player.connection.send(new ClientboundSetCarriedItemPacket(0));
                level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,level.getServer());level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,level.getServer());level.setDayTime(6000);level.setWeatherParameters(6000,0,false,false);
                for(BlockPos p:BlockPos.betweenClosed(-8,80,-12,8,80,8))level.setBlockAndUpdate(p,Blocks.STONE.defaultBlockState());
                var offer=NativeAviationCatalog.offer("frontier_arcade_vehicles:relay_drone").orElse(null);
                check("PDA native catalog sells actual relay deployer",offer!=null && offer.price()==192 && NativeAviationCatalog.createStack(level,offer.id()).is(RelayContent.ITEM.get()));
                try{Class<?> c=Class.forName("dev.frontier.industry.IndustryCatalog");c.getMethod("invalidate").invoke(null);boolean found=false;
                    for(Object r:(List<?>)c.getMethod("recipes").invoke(null))if("frontier_arcade_vehicles:relay_drone".equals(r.getClass().getMethod("id").invoke(r)))found|="DRONE_FACTORY".equals(String.valueOf(r.getClass().getMethod("factory").invoke(r)));
                    check("powered drone factory recognizes relay recipe",found);
                }catch(Exception ex){check("powered drone factory recognizes relay recipe",false);DETAILS.put("factoryError",ex.toString());}
                if(Boolean.getBoolean("frontier.radioOnly")){RelayRadioChecks.run(player,RelaySmoke::check,DETAILS);finish();return;}
                airframe=new RelayAirframeChecks(player,new BlockPos(0,80,0),RelaySmoke::check,DETAILS);phase=1;age=0;
            }else if(phase==1){
                var plane=airframe.aircraft();photoPlane=plane==null?null:plane.getUUID();
                if(age==170 && plane!=null)player.teleportTo(level,plane.getX()+10,plane.getY()+6,plane.getZ()+11,135,15);
                if(airframe.tick()){photoPlane=null;RelayRadioChecks.run(player,RelaySmoke::check,DETAILS);startRemote();phase=5;age=0;}
            }else if(phase==5 && age==30){
                check("distant one-hop repeater is ticking before camera starts",relay.isRelayActive() && level.getEntity(relay.getUUID())==relay);
                startDistantMonitor(480);phase=2;age=0;
            }else if(phase==2){
                if(age==80){
                    var info=RelayNetwork.linkInfo(drone,drone.getOperatorPosition());
                    check("remote Geranium keeps native monitor through one airborne relay",drone.activeMonitor() && info.connected() && info.relayHops()==1 && drone.position().distanceTo(body)>400);
                    check("vanilla chunk watch center follows distant camera",player.getLastSectionPos().chunk().equals(drone.chunkPosition()) && RemoteDroneView.active(player));
                    check("physical player remains at operator position",player.position().distanceTo(body)<.1);
                    check("distant client receives real terrain chunks",remoteChunkSamples>5);
                    check("native tracker retains camera entity at distant position",remoteCameraSamples>5 && bestCameraError<.6);
                    check("operator body chunk remains ticking during remote view",level.hasChunkAt(player.blockPosition()));
                    DETAILS.put("remoteCamera",Map.of("distance",drone.position().distanceTo(body),"chunkSamples",remoteChunkSamples,"cameraSamples",remoteCameraSamples,"bestNoseError",bestCameraError,"operator",body.toString(),"camera",drone.position().toString(),"hops",info.relayHops()));
                    relay.discard();
                }
                if(age==84){check("destroyed relay disconnects native monitor immediately",!drone.activeMonitor() && drone.isSignalLost());}
                if(age==115){check("chunk subscription returns to physical body on signal loss",!RemoteDroneView.active(player) && player.getLastSectionPos().chunk().equals(player.chunkPosition()));
                        drone.discard();remoteDrone=null;relay=activeRelay(220.5,121,.5);relaySecond=activeRelay(440.5,121,.5);phase=3;age=0;}
            }else if(phase==3 && age==30){
                check("both distant repeaters are ticking before two-hop camera starts",relay.isRelayActive() && relaySecond.isRelayActive() && level.getEntity(relaySecond.getUUID())==relaySecond);
                startDistantMonitor(660);phase=4;age=0;
            }else if(phase==4){
                if(age==80){
                    var info=RelayNetwork.linkInfo(drone,drone.getOperatorPosition());
                    check("two airborne relays carry actual monitor beyond660 blocks",drone.activeMonitor() && info.connected() && info.relayHops()==2 && drone.position().distanceTo(body)>650);
                    check("two-hop camera receives native distant chunks and tracked drone",remoteChunkSamples>5 && remoteCameraSamples>5 && bestCameraError<.6 && player.getLastSectionPos().chunk().equals(drone.chunkPosition()));
                    check("physical operator stays in place through two-hop camera",player.position().distanceTo(body)<.1);
                    DETAILS.put("twoHopCamera",Map.of("distance",drone.position().distanceTo(body),"chunkSamples",remoteChunkSamples,"cameraSamples",remoteCameraSamples,"quality",info.quality(),"hops",info.relayHops()));
                    releasedPosition=drone.position();var held=player.getMainHandItem();held.getItem().use(level,player,InteractionHand.MAIN_HAND);player.inventoryMenu.broadcastChanges();
                }
                if(age==100){check("far Geranium keeps flying after monitor exit",!drone.activeMonitor() && drone.isAlive() && drone.position().distanceTo(releasedPosition)>5 && drone.getPower()>.05);
                    check("released camera restores operator without moving body",!RemoteDroneView.active(player) && player.position().distanceTo(body)<.1);relay.discard();}
                if(age==104)check("loss of first repeater stops released remote Geranium engine",relaySecond.isRelayActive() && drone.getPower()==0);
                if(age==115){check("two-hop exit restores physical body chunks",!RemoteDroneView.active(player) && player.getLastSectionPos().chunk().equals(player.chunkPosition()));finish();}
            }
            if(ticks>1700){check("completed before timeout",false);finish();}
        }catch(Throwable failure){failure.printStackTrace();DETAILS.put("exception",failure.toString());check("native relay checks complete without exception",false);finish();}
    }
    private static void startRemote(){
        player.teleportTo(level,.5,81,.5,0,0);player.setDeltaMovement(Vec3.ZERO);body=player.position();
        relay=activeRelay(250.5,121,.5);
    }
    private static void startDistantMonitor(int x){
        remoteChunkSamples=remoteCameraSamples=0;bestCameraError=Double.MAX_VALUE;landmarkX=x;
        drone=GeraniumContent.TYPE.get().create(level);drone.setOwner(player.getUUID());drone.setEnergy(GeraniumEntity.BATTERY);drone.setPos(.5,83,4.5);level.addFreshEntity(drone);
        ItemStack monitor=new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation("superbwarfare:monitor")));player.setItemInHand(InteractionHand.MAIN_HAND,monitor);drone.interact(player,InteractionHand.MAIN_HAND);monitor.getItem().use(level,player,InteractionHand.MAIN_HAND);
        check("remote test binds real native monitor before flight at"+x,drone.activeMonitor());
        level.getChunk(x/16,0);level.setBlockAndUpdate(new BlockPos(x,80,0),Blocks.GOLD_BLOCK.defaultBlockState());
        // An explicit boundary fixture is moved once. Subsequent movement uses the production fixed-wing engine.
        var saved=drone.saveWithoutId(new CompoundTag());var flight=saved.getCompound("GeraniumFlight");flight.putBoolean("Launched",true);flight.putInt("Ticks",100);flight.putFloat("Yaw",-90);flight.putFloat("Pitch",0);flight.putFloat("Speed",.5f);flight.putFloat("Throttle",GeraniumFlightModel.DEFAULT_THROTTLE);saved.putBoolean("GeraniumAirborne",true);drone.load(saved);drone.setPos(x+.5,121,.5);drone.setYRot(-90);drone.setXRot(0);
        remoteDrone=drone.getUUID();player.inventoryMenu.broadcastChanges();
    }
    private static RelayEntity activeRelay(double x,double y,double z){
        RelayEntity r=RelayContent.TYPE.get().create(level);r.setOwner(player.getUUID());r.setPos(x,y,z);r.setEnergy(RelayEntity.BATTERY);r.launch();
        var saved=r.saveWithoutId(new CompoundTag());saved.putBoolean("RelayAirborne",true);saved.putDouble("RelayLaunchY",y-40);var f=saved.getCompound("RelayFlight");f.putDouble("CenterX",x);f.putDouble("CenterY",y);f.putDouble("CenterZ",z-32);f.putFloat("Yaw",90);f.putFloat("Speed",.5f);f.putInt("Ticks",200);r.load(saved);level.addFreshEntity(r);return r;
    }
    public static void timeout(){if(level!=null){level.getServer().execute(()->{if(!done){check("client timeout",false);finish();}});}}
    private static void finish(){
        if(done)return;done=true;if(airframe!=null)airframe.close();if(relay!=null)relay.discard();if(relaySecond!=null)relaySecond.discard();if(drone!=null){drone.handleSignalLoss(player);drone.discard();}
        if(inventory!=null){player.getInventory().clearContent();player.getInventory().load(inventory);player.getInventory().selected=selected;player.setGameMode(mode);player.teleportTo(level,playerStart.x,playerStart.y,playerStart.z,0,0);player.inventoryMenu.broadcastChanges();}
        try{Files.writeString(FMLPaths.GAMEDIR.get().resolve("relay-results.json"),new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("allPassed",!CHECKS.containsValue(false),"checks",CHECKS,"details",DETAILS)));}catch(Exception ex){ex.printStackTrace();}
        System.out.println("RELAY_DONE "+CHECKS.size()+" checks "+(!CHECKS.containsValue(false)));
    }
}
