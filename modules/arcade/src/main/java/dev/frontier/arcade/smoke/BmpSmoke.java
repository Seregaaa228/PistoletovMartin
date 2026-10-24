package dev.frontier.arcade.smoke;

import com.google.gson.GsonBuilder;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import dev.frontier.arcade.ArcadeVehicles;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.Files;
import java.util.*;

/** Excluded from production. All mutations are confined to the isolated Bmp-Test save. */
@Mod.EventBusSubscriber(modid=ArcadeVehicles.ID)
public final class BmpSmoke {
    public static volatile boolean done;
    private static int ticks,index,age,phase,ground;
    private static ServerLevel level;
    private static ServerPlayer player;
    private static Entity vehicle,opponent;
    private static AbstractRecruitEntity crew,enemyCrew;
    private static Zombie target;
    private static Vec3 start;
    private static int ammoBefore;
    private static float targetBefore;
    private static double minHpA,minHpB;
    private static final String[] IDS={"bmp_2","bradley","lav_25"};
    private static final Map<String,Boolean> checks=new LinkedHashMap<>();
    private static final Map<String,Object> details=new LinkedHashMap<>();
    private static final Map<UUID,Integer> shots=new HashMap<>();
    private static final Map<UUID,List<Long>> shotTicks=new HashMap<>();
    private static final Set<String> projectileTypes=new TreeSet<>();

    private static void check(String name,boolean ok) {checks.put(name,ok);System.out.println("BMP_CHECK "+(ok?"PASS ":"FAIL ")+name);}
    private static Object call(Object o,String name,Class<?>[] types,Object... args)throws Exception {return o.getClass().getMethod(name,types).invoke(o,args);}
    private static Object call(Object o,String name)throws Exception{return call(o,name,new Class<?>[0]);}
    private static Entity create(String id) {
        var type=ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(id));
        if(type==null)throw new IllegalStateException("No entity "+id);
        return Objects.requireNonNull(type.create(level));
    }
    private static ItemStack item(String id,int count) {
        var item=ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
        if(item==null||item==Items.AIR)throw new IllegalStateException("No item "+id);
        return new ItemStack(item,count);
    }
    private static Entity makeVehicle(String id,double x,double z,float yaw)throws Exception {
        var v=create("superbwarfare:"+id);v.setPos(x,ground+0.2,z);v.setYRot(yaw);level.addFreshEntity(v);
        call(v,"setEnergy",new Class<?>[]{int.class},5000000);
        var inv=(Container)v;
        for(int i=0;i<8;i++)inv.setItem(i,item("superbwarfare:small_shell_ap",64));
        return v;
    }
    private static AbstractRecruitEntity makeCrew(Entity v,UUID owner)throws Exception {
        var r=(AbstractRecruitEntity)create("recruits:bowman");r.initSpawn();r.setPersistenceRequired();r.setInvulnerable(true);
        r.setIsOwned(true);r.setOwnerUUID(Optional.of(owner));r.setAggroState(1);r.setFollowState(0);r.setShouldRanged(true);
        r.setShouldFollow(false);r.setShouldRest(false);r.setShouldProtect(false);r.setShouldHoldPos(false);r.setShouldMount(false);
        r.setPos(v.getX(),ground+0.2,v.getZ());level.addFreshEntity(r);r.startRiding(v,true);
        return r;
    }
    private static int ammunition(Entity v) {
        int result=0;var inv=(Container)v;
        for(int i=0;i<inv.getContainerSize();i++)if(inv.getItem(i).is(item("superbwarfare:small_shell_ap",1).getItem()))result+=inv.getItem(i).getCount();
        return result;
    }
    private static double hp(Entity v)throws Exception{return ((Number)call(v,"getHealth")).doubleValue();}
    private static void clearMobs() {
        var snapshot=new ArrayList<Entity>();level.getAllEntities().forEach(snapshot::add);
        for(Entity e:snapshot)if(e instanceof Mob)e.discard();
    }
    private static void beginVehicle()throws Exception {
        vehicle=makeVehicle(IDS[index],0,0,0);crew=makeCrew(vehicle,player.getUUID());
        crew.setMovePos(new BlockPos(30,ground,35));crew.setShouldMovePos(true);
        start=vehicle.position();age=0;phase=1;
        check(IDS[index]+" real recruit occupies driver seat",vehicle.getFirstPassenger()==crew);
        check(IDS[index]+" native assembly recipe restored",level.getRecipeManager().byKey(new ResourceLocation("superbwarfare",IDS[index])).isPresent());
        check(IDS[index]+" health balance loaded",hp(vehicle)==new int[]{160,180,150}[index]);
        // Resolve the exact restored binary signature without calling fire manually.
        check(IDS[index]+" legacy NPC shooting signature available",vehicle.getClass().getMethod("vehicleShoot",LivingEntity.class,String.class)!=null);
    }
    private static void beginTarget() {
        crew.setShouldMovePos(false);crew.setShouldHoldPos(true);crew.setHoldPos(vehicle.position());
        var forward=vehicle.getForward();
        target=EntityType.ZOMBIE.create(level);
        target.setPos(vehicle.getX()+forward.x*25,ground,vehicle.getZ()+forward.z*25);
        target.setNoAi(true);target.setPersistenceRequired();target.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.IRON_HELMET));
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);target.setHealth(1000);level.addFreshEntity(target);
        crew.setTarget(target);ammoBefore=ammunition(vehicle);targetBefore=target.getHealth();age=0;phase=2;
    }
    @SubscribeEvent public static void projectile(EntityJoinLevelEvent event) {
        if(!Boolean.getBoolean("frontier.bmpSmoke")||event.getLevel().isClientSide())return;
        if(event.getEntity() instanceof Projectile p&&p.getOwner()!=null&&p.getClass().getName().startsWith("com.atsuishio.superbwarfare")) {
            shots.merge(p.getOwner().getUUID(),1,Integer::sum);
            if(EntityType.getKey(p.getType()).toString().equals("superbwarfare:small_cannon_shell"))shotTicks.computeIfAbsent(p.getOwner().getUUID(),k->new ArrayList<>()).add(event.getLevel().getGameTime());
            projectileTypes.add(Objects.toString(ForgeRegistries.ENTITY_TYPES.getKey(p.getType())));
        }
    }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if(!Boolean.getBoolean("frontier.bmpSmoke")||done||event.phase!=TickEvent.Phase.END||event.getServer().getPlayerCount()==0)return;
        if(++ticks<60)return;
        try {
            if(phase==0) {
                player=event.getServer().getPlayerList().getPlayers().get(0);level=player.serverLevel();level.setDayTime(18000);
                level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false,event.getServer());
                clearMobs();
                ground=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,0,0);
                details.put("ground",ground);
                check("test lane has natural solid ground",!level.getBlockState(new BlockPos(0,ground-1,0)).isAir());
                player.teleportTo(level,-12,ground+6,-10,0,10);
                for(String id:IDS)check(id+" enabled in item filters",!ArcadeVehicles.REPLACED.contains(id));
                var data=(Map<?,?>)Class.forName("com.atsuishio.superbwarfare.data.CustomData").getField("DRONE_ATTACHMENT").get(null);
                for(String id:List.of("rpg_rocket_standard","rpg_rocket_tbg","mortar_shell")) {
                    var d=data.get("superbwarfare:"+id);
                    check("FPV blast preserved "+id,((Number)d.getClass().getField("explosionDamage").get(d)).floatValue()==120&&((Number)d.getClass().getField("explosionRadius").get(d)).floatValue()==4);
                }
                beginVehicle();
            } else if(phase==1&&++age>=160) {
                double distance=vehicle.position().subtract(start).horizontalDistance();details.put(IDS[index]+"Distance",distance);details.put(IDS[index]+"Yaw",vehicle.getYRot());
                check(IDS[index]+" moves through native NPC goal",distance>3);
                check(IDS[index]+" turns through native NPC goal",Math.abs(vehicle.getYRot())>5);
                check(IDS[index]+" driver stays mounted",crew.getVehicle()==vehicle);
                check(IDS[index]+" remains on ground",Math.abs(vehicle.getY()-ground)<2);
                beginTarget();
            } else if(phase==2) {
                age++;
                if(age%40==0)System.out.println("BMP_DIAG "+IDS[index]+" age="+age+" pos="+vehicle.position()+" yaw="+vehicle.getYRot()+" target="+crew.getTarget()+" ammo="+ammunition(vehicle)+" targetHP="+target.getHealth()+" shots="+shots.getOrDefault(crew.getUUID(),0));
                if(age>=300) {
                    details.put(IDS[index]+"Shots",shots.getOrDefault(crew.getUUID(),0));details.put(IDS[index]+"AmmoUsed",ammoBefore-ammunition(vehicle));details.put(IDS[index]+"TargetDamage",targetBefore-target.getHealth());
                    check(IDS[index]+" NPC fires actual SBW projectiles",shots.getOrDefault(crew.getUUID(),0)>0);
                    check(IDS[index]+" NPC consumes finite shell supply",ammunition(vehicle)<ammoBefore);
                    var times=shotTicks.getOrDefault(crew.getUUID(),List.of());
                    long minimum=Long.MAX_VALUE;for(int i=1;i<times.size();i++)minimum=Math.min(minimum,times.get(i)-times.get(i-1));
                    details.put(IDS[index]+"MinimumShotInterval",minimum);
                    check(IDS[index]+" NPC obeys configured cannon rate",times.size()>1&&minimum>=new int[]{5,7,7}[index]);
                    check(IDS[index]+" NPC damages hostile target",target.getHealth()<targetBefore);
                    var saved=vehicle.saveWithoutId(new CompoundTag());saved.putString("id",EntityType.getKey(vehicle.getType()).toString());
                    var restored=EntityType.loadEntityRecursive(saved,level,e->e);
                    check(IDS[index]+" health and ammunition survive NBT reload",restored!=null&&hp(restored)==hp(vehicle)&&ammunition(restored)==ammunition(vehicle));
                    crew.discard();vehicle.discard();target.discard();
                    if(++index<IDS.length)beginVehicle();else {
                        clearMobs();
                        vehicle=makeVehicle("bmp_2",-2,0,0);opponent=makeVehicle("bradley",2,40,180);
                        crew=makeCrew(vehicle,player.getUUID());enemyCrew=makeCrew(opponent,UUID.randomUUID());
                        crew.setShouldHoldPos(true);crew.setHoldPos(vehicle.position());enemyCrew.setShouldHoldPos(true);enemyCrew.setHoldPos(opponent.position());
                        crew.setAggroState(2);enemyCrew.setAggroState(2);crew.setTarget(enemyCrew);enemyCrew.setTarget(crew);
                        minHpA=hp(vehicle);minHpB=hp(opponent);age=0;phase=3;
                    }
                }
            } else if(phase==3) {
                age++;minHpA=Math.min(minHpA,hp(vehicle));minHpB=Math.min(minHpB,hp(opponent));
                if(age%60==0)System.out.println("BMP_BATTLE age="+age+" hp="+minHpA+"/"+minHpB+" shots="+shots.getOrDefault(crew.getUUID(),0)+"/"+shots.getOrDefault(enemyCrew.getUUID(),0));
                if(age>=400) {
                    details.put("battleBmpHealth",minHpA);details.put("battleBradleyHealth",minHpB);
                    details.put("battleBmpShots",shots.getOrDefault(crew.getUUID(),0));details.put("battleBradleyShots",shots.getOrDefault(enemyCrew.getUUID(),0));
                    check("opposing NPC crews both fire",shots.getOrDefault(crew.getUUID(),0)>0&&shots.getOrDefault(enemyCrew.getUUID(),0)>0);
                    check("opposing NPC crews damage armor",minHpA<160||minHpB<180);
                    check("JEI filtering completed",dev.frontier.arcade.ArcadeJei.filtered);
                    finish();
                }
            }
        } catch(Throwable failure) {failure.printStackTrace();check("runtime without exception",false);details.put("failure",failure.toString());finish();}
    }
    private static void finish() {
        done=true;details.put("projectileTypes",projectileTypes);
        try {var report=new LinkedHashMap<String,Object>();report.put("allPassed",checks.values().stream().allMatch(Boolean::booleanValue));report.put("checks",checks);report.put("details",details);
            Files.writeString(FMLPaths.GAMEDIR.get().resolve("bmp-results.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");
        }catch(Exception e){e.printStackTrace();}
    }
}
