package dev.frontier.arcade.smoke;

import com.google.gson.GsonBuilder;
import dev.frontier.arcade.ArcadeVehicles;
import dev.frontier.arcade.shop.NativeAviationCatalog;
import dev.frontier.arcade.uav.NativeUavCamera;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Opt-in, test-jar-only native item/control smoke. Never moves a deployed UAV artificially. */
@Mod.EventBusSubscriber(modid=ArcadeVehicles.ID)
public final class UavSmoke {
    public static volatile boolean done, flying;
    public static volatile String activeId="";
    public static volatile UUID activeUuid;
    public static volatile int flightAge;
    public static volatile short scriptedInput;
    private static final List<String> IDS=List.of(
        "uncomplicatedfpv:mavic_drone_no_drop", "uncomplicatedfpv:mavic_drone_with_drop",
        "uncomplicatedfpv:fpv_drone", "sbwdroneconfig:lucas_drone", "sbwdroneconfig:geranium_2",
        "sbwdroneconfig:ukrainian_bayraktar_tb2", "sbwdroneconfig:delivery_drone", "sbwdroneconfig:heavy_bomber_drone");
    private static final Map<String,Boolean> checks=new LinkedHashMap<>();
    private static final Map<String,Object> details=new LinkedHashMap<>();
    private static final Map<String,Integer> clientSamples=new ConcurrentHashMap<>();
    private static final Map<String,Integer> lateTerrainSamples=new ConcurrentHashMap<>();
    private static final Map<String,Integer> cameraSamples=new ConcurrentHashMap<>();
    private static final Map<String,Double> cameraBestDistance=new ConcurrentHashMap<>();
    private static int ticks, phase, age, index, linkedTicks, fuelReads;
    private static ServerLevel level;
    private static ServerPlayer player;
    private static Entity drone;
    private static ItemStack monitor=ItemStack.EMPTY;
    private static Vec3 start, operatorStart;
    private static float initialFuel, latestFuel, configuredMaxFuel;
    private static boolean originalInvisible, originalGravity, originalNoPhysics, originalInvulnerable;
    private static double maxMovement, maxOperatorMovement, maxAltitude;
    private static Map<String,Object> current;
    private static final BlockPos PAD=new BlockPos(0,80,10);
    private UavSmoke() { }

    private static Object call(Object target,String name,Class<?>[] types,Object... args)throws Exception {
        return target.getClass().getMethod(name,types).invoke(target,args);
    }
    private static Object call(Object target,String name)throws Exception{return call(target,name,new Class<?>[0]);}
    private static ItemStack item(String id){
        ResourceLocation key=new ResourceLocation(id);
        return ForgeRegistries.ITEMS.containsKey(key)?new ItemStack(Objects.requireNonNull(ForgeRegistries.ITEMS.getValue(key))):ItemStack.EMPTY;
    }
    private static void check(String name,boolean value){checks.put(name,value);System.out.println("UAV_CHECK "+(value?"PASS ":"FAIL ")+name);}
    private static String id(){return index<IDS.size()?IDS.get(index):"uncomplicatedfpv:shahed136";}
    private static String fuelMethod(){return activeId.endsWith("geranium_2")?"getGeraniumFuel":"getLucasFuel";}
    private static boolean fueled(){return activeId.endsWith("lucas_drone")||activeId.endsWith("geranium_2")||activeId.endsWith("ukrainian_bayraktar_tb2");}

    public static void observeClient(String id,double distanceToDrone,double distanceToBody,boolean linked){
        clientSamples.merge(id,1,Integer::sum);
        cameraBestDistance.merge(id,distanceToDrone,Math::min);
        if(linked&&distanceToDrone<8)cameraSamples.merge(id,1,Integer::sum);
    }
    public static void timeout(){
        if(done)return;check("completed before timeout",false);details.put("timeoutTicks",ticks);finish();
    }

    public static void observeLateTerrain(String id,boolean loaded) {if(loaded)lateTerrainSamples.merge(id,1,Integer::sum);}

    private static void initialize()throws Exception {
        check("dedicated isolated Uav-Test world",level.getServer().getWorldData().getLevelName().equals("Uav Test"));
        check("SBW hotfix and original FPV loaded",ModList.get().isLoaded("superbwarfare")&&ModList.get().isLoaded("uncomplicatedfpv"));
        check("Drone Warfare candidate loaded",ModList.get().isLoaded("sbwdroneconfig"));
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,level.getServer());
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,level.getServer());
        level.setDayTime(6000);level.setWeatherParameters(6000,0,false,false);
        for(BlockPos pos:BlockPos.betweenClosed(-48,80,-48,48,80,48))level.setBlockAndUpdate(pos,Blocks.STONE.defaultBlockState());
        player.teleportTo(level,0.5,81,-18.5,0,0);
        player.setItemSlot(EquipmentSlot.HEAD,item("sbwdroneconfig:fpv_goggles"));
        check("actual FPV goggles equipped",!player.getItemBySlot(EquipmentSlot.HEAD).isEmpty());
        var attachments=(Map<?,?>)Class.forName("com.atsuishio.superbwarfare.data.CustomData").getField("DRONE_ATTACHMENT").get(null);
        for(String payload:List.of("rpg_rocket_standard","rpg_rocket_tbg","mortar_shell")){
            Object data=attachments.get("superbwarfare:"+payload);
            check("preserved FPV blast "+payload,data!=null&&((Number)data.getClass().getField("explosionDamage").get(data)).floatValue()==120F&&((Number)data.getClass().getField("explosionRadius").get(data)).floatValue()==4F);
        }
        // Native anchor teleports the operator. This must be disabled in the tested production config.
        check("native remote world streaming anchor enabled",((Boolean)Class.forName("nl.smartstreamlabs.sbwdroneconfig.AddonConfig").getMethod("enableDronePlayerAnchor").invoke(null)));
    }

    private static void deploy()throws Exception {
        activeId=id();activeUuid=null;flightAge=0;flying=false;scriptedInput=0;
        current=new LinkedHashMap<>();details.put(activeId,current);
        ItemStack purchased=NativeAviationCatalog.createStack(level,activeId);
        check("purchased native stack "+activeId,!purchased.isEmpty());
        if(purchased.isEmpty()){current.put("failure","Shop stack unavailable");advance();return;}
        current.put("purchasedNBT",purchased.save(new CompoundTag()).toString());
        player.setItemInHand(InteractionHand.MAIN_HAND,purchased);
        var hit=new BlockHitResult(Vec3.atCenterOf(PAD).add(0,0.5,0),Direction.UP,PAD,false);
        var result=purchased.useOn(new UseOnContext(player,InteractionHand.MAIN_HAND,hit));
        current.put("nativeUseOn",result.toString());
        check("native deployer accepted "+activeId,result.consumesAction());
        age=0;phase=1;
    }

    private static void bind()throws Exception {
        drone=level.getEntitiesOfClass(Entity.class,new AABB(PAD).inflate(12),e->activeId.equals(String.valueOf(ForgeRegistries.ENTITY_TYPES.getKey(e.getType())))).stream().findFirst().orElse(null);
        check("native item spawned "+activeId,drone!=null);
        if(drone==null){current.put("failure","No native deployed entity");advance();return;}
        activeUuid=drone.getUUID();current.put("class",drone.getClass().getName());current.put("uuid",activeUuid.toString());
        monitor=item("superbwarfare:monitor");player.setItemInHand(InteractionHand.MAIN_HAND,monitor);
        var linked=drone.interact(player,InteractionHand.MAIN_HAND);
        current.put("nativeLinkResult",linked.toString());
        check("native monitor linked "+activeId,monitor.hasTag()&&activeUuid.toString().equals(monitor.getTag().getString("LinkedDrone")));
        var used=monitor.getItem().use(level,player,InteractionHand.MAIN_HAND);
        current.put("nativeMonitorUse",used.getResult().toString());
        check("native monitor enabled "+activeId,monitor.hasTag()&&monitor.getTag().getBoolean("Using"));
        check("native controller bound "+activeId,call(drone,"getController")==player);
        initialFuel=Float.NaN;latestFuel=Float.NaN;fuelReads=0;
        if(fueled()){
            initialFuel=((Number)call(drone,fuelMethod())).floatValue();latestFuel=initialFuel;fuelReads++;
            configuredMaxFuel=((Number)Class.forName("nl.smartstreamlabs.sbwdroneconfig.AddonConfig").getMethod("lucasMaxFuel").invoke(null)).floatValue();
            current.put("configuredMaxFuelUnits",configuredMaxFuel);
            check("purchased finite tank full "+activeId,configuredMaxFuel>0&&Math.abs(initialFuel-configuredMaxFuel)<0.01F);
        }
        start=drone.position();operatorStart=player.position();maxMovement=0;maxOperatorMovement=0;maxAltitude=start.y;linkedTicks=0;
        originalInvisible=player.isInvisible();originalGravity=player.isNoGravity();originalNoPhysics=player.noPhysics;originalInvulnerable=player.isInvulnerable();
        age=0;phase=2;flying=true;
    }

    private static short keys(){
        boolean fixed=fueled();
        // Native keys: W=4, Space=16, A=1. Early climb then fixed-wing turn keeps the circuit in view.
        return (short)(age<32?20:fixed?5:age<55?4:0);
    }
    private static void input()throws Exception {
        if(!flying||drone==null||!drone.isAlive())return;
        scriptedInput=keys();call(drone,"processInput",new Class<?>[]{short.class},scriptedInput);
        if(activeId.startsWith("sbwdroneconfig:")){
            Class<?> state=Class.forName("nl.smartstreamlabs.sbwdroneconfig.CubedFpvInputState");
            state.getMethod("receive",ServerPlayer.class,Entity.class,short.class,double.class,double.class,int.class).invoke(null,player,drone,scriptedInput,0D,0D,0);
        }
    }
    private static void sample()throws Exception {
        age++;flightAge=age;
        if(drone!=null){maxMovement=Math.max(maxMovement,drone.position().distanceTo(start));maxAltitude=Math.max(maxAltitude,drone.getY());}
        maxOperatorMovement=Math.max(maxOperatorMovement,player.position().distanceTo(operatorStart));
        if(drone!=null&&drone.isAlive()&&monitor.hasTag()&&monitor.getTag().getBoolean("Using")&&call(drone,"getController")==player)linkedTicks++;
        if(fueled()&&drone!=null&&drone.isAlive()){latestFuel=((Number)call(drone,fuelMethod())).floatValue();fuelReads++;}
        if(age>=110)finishCase();
    }

    private static void finishCase()throws Exception {
        flying=false;scriptedInput=0;
        current.put("nativeDisplacementMax",maxMovement);current.put("maxAltitude",maxAltitude);current.put("operatorBodyDisplacementMax",maxOperatorMovement);
        current.put("linkedServerTicks",linkedTicks);current.put("flightTicks",age);current.put("clientObservedSamples",clientSamples.getOrDefault(activeId,0));
        current.put("cameraNearDroneAwayFromBodySamples",cameraSamples.getOrDefault(activeId,0));current.put("cameraClosestDistance",cameraBestDistance.getOrDefault(activeId,-1D));
        check("actual native powered movement "+activeId,drone!=null&&drone.isAlive()&&maxMovement>5&&maxAltitude>start.y+1);
        check("monitor remains active over 80 ticks "+activeId,linkedTicks>80&&monitor.hasTag()&&monitor.getTag().getBoolean("Using"));
        check("rendered remote camera follows UAV "+activeId,cameraSamples.getOrDefault(activeId,0)>12);
        if(activeId.startsWith("uncomplicatedfpv:"))check("original operator body remains at controls "+activeId,maxOperatorMovement<2);
        else {
            check("native remote anchor tracks UAV "+activeId,player.position().distanceTo(drone.position())<6);
            current.put("lateTerrainSamples",lateTerrainSamples.getOrDefault(activeId,0));
            check("late flight terrain is loaded at UAV "+activeId,lateTerrainSamples.getOrDefault(activeId,0)>12);
        }
        if(fueled()){
            current.put("initialFuel",initialFuel);current.put("remainingFuel",latestFuel);current.put("fuelReads",fuelReads);
            check("native fuel consumed "+activeId,fuelReads>80&&latestFuel>0&&latestFuel<initialFuel);
        }
        if(drone!=null&&drone.isAlive()&&activeId.startsWith("uncomplicatedfpv:")){
            float health=player.getHealth();
            // Execute the native unarmed collision hook; no fabricated damage or entity movement.
            call(drone,"hitEntityCrash",new Class<?>[]{Player.class,Entity.class},player,player);
            check("native unarmed pilot collision safe "+activeId,player.isAlive()&&player.getHealth()==health);
            current.put("unarmedCollisionHookExecuted",true);
        }
        if(activeId.startsWith("sbwdroneconfig:")) {monitor.getOrCreateTag().putBoolean("Using",false);phase=7;age=0;}else advance();
    }
    private static void advance()throws Exception {
        flying=false;scriptedInput=0;
        if(monitor.hasTag())monitor.getTag().putBoolean("Using",false);
        if(drone!=null){drone.discard();drone=null;}
        player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);activeUuid=null;
        // Only operator setup between cases; never reposition a deployed drone.
        player.teleportTo(level,0.5,81,-18.5,0,0);
        if(++index<IDS.size()){phase=4;age=0;}else if(index==IDS.size()){phase=5;age=0;}else{phase=3;age=0;}
    }

    private static void deployShahed()throws Exception {
        player.teleportTo(level,0.5,81,0.5,0,0);
        activeId="uncomplicatedfpv:shahed136";activeUuid=null;flightAge=0;scriptedInput=0;flying=false;
        current=new LinkedHashMap<>();details.put(activeId,current);
        ItemStack purchased=NativeAviationCatalog.createStack(level,activeId);
        check("purchased original Shahed stack",!purchased.isEmpty());
        player.setItemInHand(InteractionHand.MAIN_HAND,purchased);
        var hit=new BlockHitResult(Vec3.atCenterOf(PAD).add(0,0.5,0),Direction.UP,PAD,false);
        var placed=purchased.useOn(new UseOnContext(player,InteractionHand.MAIN_HAND,hit));
        current.put("nativeUseOn",placed.toString());
        drone=level.getEntitiesOfClass(Entity.class,new AABB(PAD).inflate(12),e->NativeUavCamera.isNativeShahed(e)).stream().findFirst().orElseThrow();
        activeUuid=drone.getUUID();current.put("class",drone.getClass().getName());
        check("Shahed native owner retained",NativeUavCamera.owns(player,drone));
        monitor=NativeAviationCatalog.createStack(level,"frontier_arcade_vehicles:uav_camera");
        check("purchased original Shahed camera",!monitor.isEmpty());
        player.setItemInHand(InteractionHand.MAIN_HAND,monitor);
        check("owner validated Shahed camera bind",NativeUavCamera.bind(player,monitor,drone));
        monitor.getItem().use(level,player,InteractionHand.MAIN_HAND);
        check("native Shahed camera use enabled",NativeUavCamera.isUsing(monitor));
        Class<?> config=Class.forName("ru.lavafrai.uncomplicatedfpv.config.ServerConfig");
        Object damage=config.getField("SHAHED136_EXPLOSION_DAMAGE").get(null), radius=config.getField("SHAHED136_EXPLOSION_RADIUS").get(null);
        check("original Shahed blast parameters preserved",((Number)call(damage,"get")).doubleValue()==300D&&((Number)call(radius,"get")).doubleValue()==16D);
        call(drone,"setTargetPos",new Class<?>[]{double.class,double.class,double.class},0.5D,81D,240D);
        call(drone,"setSetAltitude",new Class<?>[]{float.class},96F);
        call(drone,"setSetSpeed",new Class<?>[]{float.class},120F);
        call(drone,"launch");
        check("original Shahed native launch",(Boolean)call(drone,"isLaunched"));
        start=drone.position();operatorStart=player.position();maxMovement=0;maxOperatorMovement=0;linkedTicks=0;
        age=0;phase=6;flying=true;
    }
    private static void sampleShahed()throws Exception {
        age++;flightAge=age;
        maxMovement=Math.max(maxMovement,drone.position().distanceTo(start));
        maxOperatorMovement=Math.max(maxOperatorMovement,player.position().distanceTo(operatorStart));
        if(NativeUavCamera.isUsing(monitor)&&NativeUavCamera.linkedTarget(player,monitor)==drone)linkedTicks++;
        if(age<30)return;
        current.put("nativeDisplacementMax",maxMovement);current.put("linkedCameraTicks",linkedTicks);current.put("operatorBodyDisplacementMax",maxOperatorMovement);
        current.put("cameraNearDroneAwayFromBodySamples",cameraSamples.getOrDefault(activeId,0));
        check("original Shahed native launched movement",drone.isAlive()&&maxMovement>5);
        check("original Shahed camera stays active",linkedTicks>=20&&NativeUavCamera.isUsing(monitor));
        check("rendered original Shahed camera follows UAV",cameraSamples.getOrDefault(activeId,0)>8);
        check("original Shahed operator body stationary",maxOperatorMovement<2);
        advance();
    }

    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event){
        if(!Boolean.getBoolean("frontier.uavSmoke")||done||event.getServer().getPlayerCount()==0)return;
        try{
            if(event.phase==TickEvent.Phase.START){if(phase==2)input();return;}
            if(++ticks>2200){timeout();return;}
            if(ticks<80)return;
            if(phase==0){player=event.getServer().getPlayerList().getPlayers().get(0);level=player.serverLevel();initialize();deploy();}
            else if(phase==1&&++age>=8)bind();
            else if(phase==2)sample();
            else if(phase==4&&++age>20)deploy();
            else if(phase==5&&++age>20)deployShahed();
            else if(phase==6)sampleShahed();
            else if(phase==7&&++age>=10) {
                check("native remote exit restores origin "+activeId,player.position().distanceTo(operatorStart)<2);
                check("native remote exit restores flags "+activeId,player.isInvisible()==originalInvisible&&player.isNoGravity()==originalGravity&&player.noPhysics==originalNoPhysics&&player.isInvulnerable()==originalInvulnerable);
                advance();
            }
            else if(phase==3&&++age>35){check("all eight native drone cases exercised",details.keySet().containsAll(IDS));finish();}
        }catch(Throwable failure){failure.printStackTrace();check("runtime exception free "+activeId,false);if(current!=null)current.put("exception",failure.toString());try{advance();}catch(Throwable fatal){details.put("fatal",fatal.toString());finish();}}
    }
    private static void finish(){
        done=true;flying=false;scriptedInput=0;activeUuid=null;
        try{
            Map<String,Object> out=new LinkedHashMap<>();out.put("allPassed",checks.values().stream().allMatch(Boolean::booleanValue));out.put("checks",checks);out.put("details",details);out.put("serverTicks",ticks);
            out.put("testMethod","Native purchased UseOnContext, native monitor interact/use, ordinary server ticks and native key processing; no UAV position/velocity writes after deployment");
            Files.writeString(FMLPaths.GAMEDIR.get().resolve("uav-results.json"),new GsonBuilder().setPrettyPrinting().create().toJson(out));
        }catch(Exception failure){failure.printStackTrace();}
    }
}
