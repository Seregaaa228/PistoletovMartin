package dev.frontier.arcade.smoke;

import com.google.gson.GsonBuilder;
import dev.frontier.arcade.ArcadeJei;
import dev.frontier.arcade.ArcadeVehicles;
import dev.frontier.arcade.shop.NativeAviationCatalog;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import java.nio.file.Files;
import java.util.*;

/** Test-only state machine; excluded from production. Runs only in our isolated Air-Test world. */
@Mod.EventBusSubscriber(modid=ArcadeVehicles.ID)
public final class AirSmoke {
    public static volatile boolean done, creativeChecked, creativeOkay, shahedCameraObserved;
    public static volatile int aircraftRendered;
    private static int ticks, phase, age, index;
    private static ServerLevel level;
    private static ServerPlayer player;
    private static Entity aircraft;
    private static Vec3 start;
    private static int initialEnergy;
    private static double minAltitude;
    private static BlockPos containerPos;
    private static String expectedType;
    private static Entity cameraShahed;
    private static Vec3 cameraBody;
    private static net.minecraft.world.level.GameType cameraMode;
    private static final List<String> AIR=List.of("a_10a","j_16","ac_130h","ju_87","ah_6","mi_28");
    private static final Map<String,Boolean> checks=new LinkedHashMap<>();
    private static final Map<String,Object> details=new LinkedHashMap<>();
    private static final Map<String,String> stacks=new TreeMap<>();
    public static void check(String name, boolean ok) {checks.put(name,ok);System.out.println("AIR_CHECK "+(ok?"PASS ":"FAIL ")+name);}
    private static Object call(Object o,String name,Class<?>[] types,Object... args)throws Exception {return o.getClass().getMethod(name,types).invoke(o,args);}
    private static Object call(Object o,String name)throws Exception{return call(o,name,new Class<?>[0]);}
    private static int energy(Entity e)throws Exception{return ((Number)call(e,"getEnergy")).intValue();}
    private static Entity entity(String id) {return Objects.requireNonNull(ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(id))).create(level);}
    private static ItemStack nativeContainer(String id)throws Exception {
        return (ItemStack)Class.forName("com.atsuishio.superbwarfare.item.container.ContainerBlockItem").getMethod("createInstance",EntityType.class)
            .invoke(null,ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("superbwarfare",id)));
    }
    private static void inspect()throws Exception {
        ShopSmoke.inspect(player);
        check("SimplePlanes not loaded",!ModList.get().isLoaded("simpleplanes"));
        check("Trajan Tanks not loaded",!ModList.get().isLoaded("trajanstanks"));
        check("Trajan Core not loaded",!ModList.get().isLoaded("trajanscore"));
        check("SBW and existing FPV loaded",ModList.get().isLoaded("superbwarfare")&&ModList.get().isLoaded("uncomplicatedfpv"));
        check("unknown shop ID fails closed",NativeAviationCatalog.createStack(level,"superbwarfare:t_90a").isEmpty());
        for(var offer:NativeAviationCatalog.offers()) {
            ItemStack stack=NativeAviationCatalog.createStack(level,offer.id());
            check("usable shop offer "+offer.id(),!stack.isEmpty()&&offer.price()>0);
            check("offer not blocked "+offer.id(),!ArcadeVehicles.isReplaced(stack));
            stacks.put(offer.id(),stack.save(new CompoundTag()).toString());
        }
        for(String id:AIR) {
            check("aircraft available "+id,!ArcadeVehicles.isReplaced(nativeContainer(id)));
            if(!id.equals("j_16"))check("aircraft assembly restored "+id,level.getRecipeManager().byKey(new ResourceLocation("superbwarfare",id)).isPresent());
        }
        for(String id:ArcadeVehicles.REPLACED) {
            check("blocked creative stack "+id,ArcadeVehicles.isReplaced(nativeContainer(id)));
            check("blocked assembly absent "+id,level.getRecipeManager().byKey(new ResourceLocation("superbwarfare",id)).isEmpty());
            Entity old=entity("superbwarfare:"+id);
            CompoundTag nbt=old.saveWithoutId(new CompoundTag());nbt.putString("id","superbwarfare:"+id);
            check("legacy entity reloads "+id,EntityType.loadEntityRecursive(nbt,level,e->e)!=null);
        }
        for(String id:List.of("bmp_2","bradley","lav_25","lav_ad","speedboat")) {
            var type=ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("superbwarfare",id));
            if(type!=null)check("preserved IFV/PVO/navy "+id,!ArcadeVehicles.isReplaced(nativeContainer(id)));
        }
        var attachments=(Map<?,?>)Class.forName("com.atsuishio.superbwarfare.data.CustomData").getField("DRONE_ATTACHMENT").get(null);
        for(String id:List.of("rpg_rocket_standard","rpg_rocket_tbg","mortar_shell")) {
            Object d=attachments.get("superbwarfare:"+id);
            check("FPV damage preserved "+id,((Number)d.getClass().getField("explosionDamage").get(d)).floatValue()==120&&((Number)d.getClass().getField("explosionRadius").get(d)).floatValue()==4);
        }
    }
    private static void deploy()throws Exception {
        expectedType="superbwarfare:"+AIR.get(index);
        String offerId=NativeAviationCatalog.offers().stream().filter(o->o.id().endsWith(AIR.get(index))).map(NativeAviationCatalog.Offer::id).findFirst().orElseThrow();
        ItemStack stack=NativeAviationCatalog.createStack(level,offerId);
        var bet=stack.getOrCreateTag().getCompound("BlockEntityTag");
        check("native container carries aircraft "+AIR.get(index),bet.getString("EntityType").equals(expectedType)&&bet.contains("Entity",10));
        containerPos=new BlockPos(index*30,200,0);
        var block=((BlockItem)stack.getItem()).getBlock();var state=block.defaultBlockState();
        var opened=state.getProperties().stream().filter(p->p.getName().equals("opened")).findFirst().orElseThrow();
        state=state.setValue((BooleanProperty)opened,true);
        level.setBlockAndUpdate(containerPos,state);var be=level.getBlockEntity(containerPos);be.load(bet);be.setChanged();
        player.teleportTo(level,containerPos.getX()-12,205,-10,0,0);
        age=0;phase=1;
    }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if(!Boolean.getBoolean("frontier.airSmoke")||done||event.getServer().getPlayerCount()==0)return;
        try {
            if(event.phase==TickEvent.Phase.START) {
                if(phase==2&&aircraft!=null) {
                    call(aircraft,"processInput",new Class<?>[]{short.class},(short)4);
                    // Native input/engine drives movement; no position/velocity writes during flight.
                }
                return;
            }
            if(++ticks<80)return;
            if(phase==0) {
                player=event.getServer().getPlayerList().getPlayers().get(0);level=player.serverLevel();
                level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false,event.getServer());
                inspect();deploy();
            } else if(phase==1&&++age>=23) {
                aircraft=level.getEntitiesOfClass(Entity.class,new net.minecraft.world.phys.AABB(containerPos).inflate(16),e->EntityType.getKey(e.getType()).toString().equals(expectedType)).stream().findFirst().orElseThrow();
                check("native unpack spawned "+AIR.get(index),level.getBlockState(containerPos).isAir());
                initialEnergy=energy(aircraft);
                check("full finite initial fuel "+AIR.get(index),initialEnergy==((Number)call(aircraft,"getMaxEnergy")).intValue()&&initialEnergy>0&&initialEnergy<Integer.MAX_VALUE);
                check("empty purchased inventory "+AIR.get(index),aircraft instanceof Container c&&c.isEmpty());
                boolean emptyGuns=true;
                for(Object gun:((Map<?,?>)call(aircraft,"getGunDataMap")).values())emptyGuns &= ((Number)call(gun.getClass().getField("ammo").get(gun),"get")).intValue()==0 && ((Number)call(gun.getClass().getField("virtualAmmo").get(gun),"get")).intValue()==0;
                check("purchased aircraft has no hidden weapon rounds "+AIR.get(index),emptyGuns);
                check("player takes pilot seat "+AIR.get(index),player.startRiding(aircraft,true)&&aircraft.getFirstPassenger()==player);
                call(aircraft,"setPower",new Class<?>[]{float.class},0.7f);
                call(aircraft,"setEngineStart",new Class<?>[]{boolean.class},true);
                call(aircraft,"setEngineStartOver",new Class<?>[]{boolean.class},true);
                aircraft.setDeltaMovement(0,0,2.0);start=aircraft.position();minAltitude=start.y;age=0;phase=2;
            } else if(phase==2) {
                age++;minAltitude=Math.min(minAltitude,aircraft.getY());
                if(age>=100) {
                    double distance=aircraft.position().distanceTo(start);
                    details.put(AIR.get(index),Map.of("distance",distance,"minimumAltitude",minAltitude,"fuelUsed",initialEnergy-energy(aircraft),"alive",aircraft.isAlive()));
                    check("native powered flight "+AIR.get(index),distance>5&&minAltitude>15&&aircraft.isAlive()&&player.getVehicle()==aircraft);
                    check("finite fuel consumed "+AIR.get(index),energy(aircraft)<initialEnergy&&energy(aircraft)>0);
                    var saved=aircraft.saveWithoutId(new CompoundTag());saved.putString("id",expectedType);
                    var restored=EntityType.loadEntityRecursive(saved,level,e->e);
                    check("aircraft reload keeps fuel "+AIR.get(index),restored!=null&&energy(restored)==energy(aircraft));
                    player.stopRiding();aircraft.discard();aircraft=null;
                    if(++index<AIR.size())deploy();else {phase=3;age=0;}
                }
            } else if(phase==3&&++age>60) {
                check("creative tabs filtered and aircraft visible",creativeChecked&&creativeOkay);
                check("JEI excludes blocked variants",ArcadeJei.filtered);
                check("aircraft native renderers observed",aircraftRendered>=6);
                level.setBlockAndUpdate(new BlockPos(0,199,0),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(20,199,0),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                player.teleportTo(level,0.5,200,0.5,0,0);player.setDeltaMovement(Vec3.ZERO);
                cameraShahed=entity("uncomplicatedfpv:shahed136");cameraShahed.setPos(20.5,200,0.5);
                call(cameraShahed,"setOwnerUUID",new Class<?>[]{java.util.UUID.class},player.getUUID());level.addFreshEntity(cameraShahed);
                var camera=dev.frontier.arcade.uav.NativeUavCamera.createCameraFor(player,cameraShahed);
                check("native Shahed camera binds actual owner",!camera.isEmpty());
                call(cameraShahed,"setOwnerUUID",new Class<?>[]{java.util.UUID.class},UUID.randomUUID());
                check("native Shahed camera rejects foreign owner",dev.frontier.arcade.uav.NativeUavCamera.createCameraFor(player,cameraShahed).isEmpty());
                call(cameraShahed,"setOwnerUUID",new Class<?>[]{java.util.UUID.class},player.getUUID());
                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,camera);
                camera.getItem().use(level,player,net.minecraft.world.InteractionHand.MAIN_HAND);
                cameraBody=player.position();cameraMode=player.gameMode.getGameModeForPlayer();age=0;phase=4;
            } else if(phase==4&&++age>=50) {
                check("native Shahed camera shows real client viewpoint",shahedCameraObserved);
                check("Shahed camera preserves physical player body and mode",player.position().distanceTo(cameraBody)<0.5&&player.gameMode.getGameModeForPlayer()==cameraMode);
                cameraShahed.setPos(250,200,0);
                check("Shahed camera enforces finite signal range",dev.frontier.arcade.uav.NativeUavCamera.linkedTarget(player,player.getMainHandItem())==null);
                cameraShahed.discard();
                finish();
            }
        } catch(Throwable error) {error.printStackTrace();check("runtime exception free",false);details.put("error",error.toString());finish();}
    }
    private static void finish() {
        done=true;
        try {
            var out=new LinkedHashMap<String,Object>();out.put("allPassed",checks.values().stream().allMatch(Boolean::booleanValue));out.put("checks",checks);out.put("details",details);out.put("nativeItemStackNBT",stacks);
            Files.writeString(FMLPaths.GAMEDIR.get().resolve("air-results.json"),new GsonBuilder().setPrettyPrinting().create().toJson(out));
        }catch(Exception e){e.printStackTrace();}
    }
}
