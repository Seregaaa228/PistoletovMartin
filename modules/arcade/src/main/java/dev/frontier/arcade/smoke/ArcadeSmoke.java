package dev.frontier.arcade.smoke;

import com.google.gson.GsonBuilder;
import dev.frontier.arcade.ArcadeVehicles;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Test artifact only: runs in a fresh, hidden local world, never in the user's save. */
@Mod.EventBusSubscriber(modid = ArcadeVehicles.ID)
public final class ArcadeSmoke {
    public static volatile int stage, age;
    public static volatile boolean done;
    public static volatile int renderedTypes;
    public static volatile boolean creativeVerified;
    public static final Map<String,Boolean> CHECKS = new LinkedHashMap<>();
    public static final Map<String,Object> DETAILS = new LinkedHashMap<>();
    private static int ticks;
    private static Entity active;
    private static ServerLevel level;
    private static ServerPlayer pilot;
    private static Vec3 start;
    private static int ammoBefore;
    private static double highest;

    private static void check(String name, boolean ok) {
        CHECKS.put(name,ok);
        System.out.println("ARCADE_CHECK " + (ok?"PASS ":"FAIL ") + name);
    }
    public static Object invoke(Object target,String name,Class<?>[] args,Object... values) throws Exception {
        return target.getClass().getMethod(name,args).invoke(target,values);
    }
    private static Object read(Object target,String name) throws Exception {
        return target.getClass().getField(name).get(target);
    }
    private static Entity entity(String name) {
        var type=ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(name));
        if(type==null)throw new IllegalStateException("Missing entity " + name);
        Entity result=type.create(level);
        if(result==null)throw new IllegalStateException("Could not create " + name);
        return result;
    }
    private static ItemStack item(String name) {
        var value=ForgeRegistries.ITEMS.getValue(new ResourceLocation(name));
        if(value==null||value==Items.AIR)throw new IllegalStateException("Missing item " + name);
        return new ItemStack(value);
    }
    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if(!Boolean.getBoolean("frontier.arcadeSmoke")||done||event.phase!=TickEvent.Phase.END)return;
        if(event.getServer().getPlayerCount()==0)return;
        if(++ticks<60)return;
        try {
            if(stage==0) {
                pilot=event.getServer().getPlayerList().getPlayers().get(0);
                level=pilot.serverLevel();pilot.setGameMode(GameType.CREATIVE);
                // Arena exists only in this newly generated test save.
                for(int x=-12;x<=12;x++)for(int z=-20;z<=110;z++)
                    level.setBlock(new BlockPos(x,200,z),Blocks.BEDROCK.defaultBlockState(),2);
                inspect();
                pilot.teleportTo(level,0,201,0,0,0);
                active=entity("trajanstanks:panzer_two_entity_type");
                active.setPos(0,201,0);level.addFreshEntity(active);
                pilot.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.COAL));
                active.interactAt(pilot,Vec3.ZERO,InteractionHand.MAIN_HAND);
                check("native tank accepts coal",((Number)invoke(active,"getFuelAmount",new Class<?>[0])).intValue()>0);
                pilot.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
                active.interact(pilot,InteractionHand.MAIN_HAND);
                check("native tank can be mounted",pilot.getVehicle()==active);
                ItemStack shells=item("trajanscore:standard_shell");shells.setCount(8);
                pilot.setItemInHand(InteractionHand.OFF_HAND,shells);ammoBefore=shells.getCount();
                start=active.position();stage=1;age=0;
            } else if(stage==1) {
                age++;
                if(age==190) {
                    DETAILS.put("tankDistance",active.position().distanceTo(start));
                    DETAILS.put("tankYaw",active.getYRot());
                    check("tank moves from native W input",active.position().distanceTo(start)>1.0);
                    check("tank turns from native D input",Math.abs(active.getYRot())>10);
                    check("native G shot consumes shell",pilot.getOffhandItem().getCount()<ammoBefore);
                    // Exercise the ordinary empty-offhand edge case without crashing the test runner.
                    pilot.setItemInHand(InteractionHand.OFF_HAND,ItemStack.EMPTY);
                    try {
                        Class<?> base=Class.forName("com.crescentine.trajanscore.basetank.BaseTankEntity");
                        invoke(active,"shoot",new Class<?>[]{net.minecraft.world.entity.player.Player.class,base,net.minecraft.world.level.Level.class},pilot,active,level);
                        check("shooting with empty offhand is safe",true);
                    } catch(Exception failure) {
                        check("shooting with empty offhand is safe",false);
                        DETAILS.put("emptyOffhandFailure",failure.getCause()==null?failure.toString():failure.getCause().toString());
                    }
                    pilot.setItemInHand(InteractionHand.OFF_HAND,new ItemStack(Items.DIRT));
                    Class<?> nativeTank=Class.forName("com.crescentine.trajanscore.basetank.BaseTankEntity");
                    invoke(active,"shoot",new Class<?>[]{net.minecraft.world.entity.player.Player.class,nativeTank,net.minecraft.world.level.Level.class},pilot,active,level);
                    check("shooting with wrong offhand item is safe",true);
                    pilot.stopRiding();active.discard();
                    active=entity("simpleplanes:plane");active.setPos(0,201,0);level.addFreshEntity(active);
                    for(String upgrade:List.of("furnace_engine","propeller")) {
                        pilot.setItemInHand(InteractionHand.MAIN_HAND,item("simpleplanes:"+upgrade));
                        active.interact(pilot,InteractionHand.MAIN_HAND);
                    }
                    Object engine=read(active,"engineUpgrade");
                    ((ItemStackHandler)read(engine,"itemStackHandler")).setStackInSlot(0,new ItemStack(Items.COAL,16));
                    pilot.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
                    active.interact(pilot,InteractionHand.MAIN_HAND);
                    check("native plane can be mounted",pilot.getVehicle()==active);
                    check("native plane accepts furnace engine",engine!=null&&((Map<?,?>)read(active,"upgrades")).size()>=1);
                    start=active.position();highest=start.y;stage=2;age=0;
                }
            } else if(stage==2) {
                age++;highest=Math.max(highest,active.getY());
                if(age==240) {
                    DETAILS.put("planeDistance",active.position().distanceTo(start));
                    DETAILS.put("planeAltitudeGain",highest-start.y);
                    check("native plane accelerates",active.position().distanceTo(start)>10);
                    check("native plane takes off",highest-start.y>2);
                    CompoundTag saved=active.saveWithoutId(new CompoundTag());
                    saved.putString("id",EntityType.getKey(active.getType()).toString());
                    Entity restored=EntityType.loadEntityRecursive(saved,level,e->e);
                    check("plane upgrades survive NBT reload",restored!=null&&((Map<?,?>)read(restored,"upgrades")).size()>=1);
                    pilot.stopRiding();active.discard();
                    pilot.teleportTo(level,0,201,-7,0,0);
                    Entity showcaseTank=entity("trajanstanks:panzer_two_entity_type");showcaseTank.setPos(-4,201,5);level.addFreshEntity(showcaseTank);
                    Entity showcasePlane=entity("simpleplanes:plane");showcasePlane.setPos(3,201,5);level.addFreshEntity(showcasePlane);
                    stage=3;age=0;
                }
            } else if(stage==3&&++age>=80) {
                check("vehicle client renderers ran",renderedTypes>=2);
                check("legacy vehicles hidden while replacement items and monitor remain",creativeVerified);
                check("legacy vehicles absent in JEI",dev.frontier.arcade.ArcadeJei.filtered);
                done=true;stage=4;report();
            }
        } catch(Throwable failure) {
            failure.printStackTrace();check("runtime completed without exception",false);
            DETAILS.put("failure",failure.toString());done=true;stage=4;report();
        }
    }
    private static void inspect() throws Exception {
        int tanks=0,planes=0,drones=0;
        for(var id:ForgeRegistries.ENTITY_TYPES.getKeys()) {
            boolean tank=id.getNamespace().equals("trajanstanks")&&!id.getPath().contains("artillery")&&!id.getPath().contains("qf6");
            boolean plane=id.getNamespace().equals("simpleplanes")&&!id.getPath().equals("parachute")&&!id.getPath().equals("supply_crate");
            boolean drone=id.getNamespace().equals("uncomplicatedfpv");
            if(!tank&&!plane&&!drone)continue;
            Entity e=entity(id.toString());
            CompoundTag nbt=e.saveWithoutId(new CompoundTag());nbt.putString("id",id.toString());
            Entity reloaded=EntityType.loadEntityRecursive(nbt,level,v->v);
            check(id+" creates and reloads",reloaded!=null&&reloaded.getClass()==e.getClass());
            if(tank) {
                int hp=((Number)invoke(e,"getHealth",new Class<?>[0])).intValue();
                check(id+" bounded health",hp>=80&&hp<=140);tanks++;
            }
            if(plane)planes++;
            if(drone)drones++;
        }
        DETAILS.put("newTanks",tanks);DETAILS.put("newAircraft",planes);DETAILS.put("preservedFpvEntities",drones);
        for(String id:ArcadeVehicles.REPLACED)
            check("legacy assembly removed: "+id,level.getRecipeManager().byKey(new ResourceLocation("superbwarfare",id)).isEmpty());
        Class<?> custom=Class.forName("com.atsuishio.superbwarfare.data.CustomData");
        Map<?,?> attachments=(Map<?,?>)custom.getField("DRONE_ATTACHMENT").get(null);
        for(String id:List.of("rpg_rocket_standard","rpg_rocket_tbg","mortar_shell")) {
            Object data=attachments.get("superbwarfare:"+id);
            check("preserved FPV blast "+id,((Number)read(data,"explosionDamage")).floatValue()==120f&&((Number)read(data,"explosionRadius")).floatValue()==4f);
        }
        Entity shell=entity("trajanscore:standard_shell");
        check("tank standard direct damage is 24",((Number)read(shell,"damage")).doubleValue()==24);
        check("tank standard blast radius is 2",((Number)read(shell,"explosionRadius")).intValue()==2);
        Entity tank=entity("trajanstanks:panzer_two_entity_type");
        Entity npc=EntityType.VILLAGER.create(level);
        Entity other=EntityType.VILLAGER.create(level);
        if(npc==null||other==null)throw new IllegalStateException("Missing test villager");
        npc.startRiding(tank,true);
        try {
            tank.push(other);check("NPC tank collision is safe",true);
        } catch(ClassCastException failure) {
            check("NPC tank collision is safe",false);DETAILS.put("npcCollisionFailure",failure.toString());
        }
        npc.stopRiding();
        try {
            tank.push(new net.minecraft.world.entity.item.ItemEntity(level,0,201,0,new ItemStack(Items.DIRT)));
            check("tank collision with dropped items is safe",true);
        } catch(ClassCastException failure) {
            check("tank collision with dropped items is safe",false);DETAILS.put("itemCollisionFailure",failure.toString());
        }
        // Call the real native tick: no external adapter or replacement movement.
        tank.setPos(0,201,0);level.addFreshEntity(tank);
        npc.startRiding(tank,true);
        invoke(tank,"setFuelAmount",new Class<?>[]{int.class},600);
        invoke(tank,"setInput",new Class<?>[]{boolean.class,boolean.class,boolean.class,boolean.class},false,false,true,false);
        Vec3 npcStart=tank.position();
        for(int i=0;i<100;i++)tank.tick();
        double npcTravel=Math.hypot(tank.getX()-npcStart.x,tank.getZ()-npcStart.z);
        DETAILS.put("nativeNpcTravel",npcTravel);
        check("native server tick moves NPC tank",npcTravel>1.0);
        check("NPC capability marker is present",Boolean.TRUE.equals(invoke(tank,"frontierSupportsNpcDriver",new Class<?>[0])));
        npc.stopRiding();tank.discard();
    }
    private static void report() {
        try {
            var report=new LinkedHashMap<String,Object>();
            report.put("checks",CHECKS);report.put("details",DETAILS);
            report.put("allPassed",CHECKS.size()>40&&CHECKS.values().stream().allMatch(Boolean::booleanValue));
            Files.writeString(FMLPaths.GAMEDIR.get().resolve("arcade-results.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
        } catch(Exception failure){failure.printStackTrace();}
    }
}
