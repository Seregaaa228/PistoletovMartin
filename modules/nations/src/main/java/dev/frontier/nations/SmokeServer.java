package dev.frontier.nations;

import com.diego.recruitsvillages.data.FactionData;
import com.diego.recruitsvillages.data.LivingVillagesSavedData;
import com.diego.recruitsvillages.materialize.MaterializationManager;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import com.tacz.guns.api.event.common.GunShootEvent;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.api.item.gun.FireMode;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.util.*;

/** Real-world checks in a dedicated development save, opt-in only. */
@Mod.EventBusSubscriber(modid=FrontierNations.ID)
public final class SmokeServer {
    private static final Logger LOG=LogUtils.getLogger();
    private static int ticks,stage;
    private static FactionData village;
    private static UUID protectedRecruit;
    private static UUID firingRecruit,firingTarget;
    private static int firingStart,firingEarliest,shots,hits,initialAmmo,minAmmo;
    private static float initialTargetHealth,minTargetHealth;
    private static ItemStack firingGun=ItemStack.EMPTY;
    private static final Map<BlockPos,BlockState> arenaBlocks=new LinkedHashMap<>();
    private static AABB arenaBounds;
    private static UUID observingPlayer;
    private static double observerX,observerY,observerZ;
    private static float observerYaw,observerPitch;
    private static final Map<String,Object> firingObservations=new LinkedHashMap<>();
    private static final Map<String,Boolean> results=new LinkedHashMap<>();
    @SubscribeEvent public static void start(ServerStartedEvent event){
        ticks=0;stage=0;village=null;protectedRecruit=null;results.clear();
        firingRecruit=null;firingTarget=null;firingGun=ItemStack.EMPTY;arenaBounds=null;observingPlayer=null;
        shots=0;hits=0;arenaBlocks.clear();firingObservations.clear();
    }
    @SubscribeEvent public static void stopping(ServerStoppingEvent event){
        if(Boolean.getBoolean("frontier.smokeTest"))cleanupFiring(event.getServer().overworld());
    }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if(!Boolean.getBoolean("frontier.smokeTest")||event.phase!=TickEvent.Phase.END)return;
        if(event.getServer().getPlayerCount()==0)return;
        ticks++;
        ServerLevel level=event.getServer().overworld();
        LivingVillagesSavedData data=LivingVillagesSavedData.get(level);
        if(stage==0&&ticks>=100) {
            check("initial AI nations",data.size()>=4);
            village=data.getFactions().stream().findFirst().orElse(null);
            if(village!=null) {
                var player=event.getServer().getPlayerList().getPlayers().get(0);
                level.getChunk(village.getCenterPos().getX()>>4,village.getCenterPos().getZ()>>4);
                BlockPos surface=level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,village.getCenterPos());
                double arrivalY=Math.max(surface.getY()+2,village.getCenterPos().getY()+8);
                player.teleportTo(level,surface.getX()+0.5,arrivalY,surface.getZ()+0.5,0,20);
                LOG.info("FRONTIER_TEST: loaded village destination center={} surface={} playerY={}",village.getCenterPos(),surface,arrivalY);
                var type=ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("recruits","bowman"));
                if(type!=null&&type.create(level) instanceof AbstractRecruitEntity recruit) {
                    // Stay above the native village's asynchronous building footprint.
                    recruit.setPos(player.getX()+5,player.getY()+20,player.getZ()+5);
                    recruit.setNoGravity(true);recruit.setInvulnerable(true);
                    recruit.setIsOwned(true);recruit.setOwnerUUID(Optional.of(player.getUUID()));
                    ItemStack bow=new ItemStack(Items.BOW);bow.setHoverName(Component.literal("Protected custom bow"));
                    recruit.setItemInHand(InteractionHand.MAIN_HAND,bow);recruit.setNoAi(true);
                    level.addFreshEntity(recruit);protectedRecruit=recruit.getUUID();
                }
            }
            stage=1;
        }
        if(stage==1&&ticks>=300) {
            var live=village==null?List.<AbstractRecruitEntity>of():MaterializationManager.liveRecruits(level,village);
            var ranged=live.stream().filter(e->e.getMainHandItem().getItem() instanceof IGun).toList();
            if(ranged.isEmpty()&&ticks%100==0)diagnoseNative(level,live);
            if(ranged.isEmpty()&&ticks<800)return;
            check("AI ranged troops received TaCZ",!ranged.isEmpty());
            if(!ranged.isEmpty()) {
                AbstractRecruitEntity recruit=ranged.get(0);
                firingGun=recruit.getMainHandItem().copy();
                ItemStack hand=recruit.getMainHandItem(),inventory=recruit.getInventory().getItem(recruit.getInventorySlotIndex(EquipmentSlot.MAINHAND));
                check("gun hand and native inventory agree",ItemStack.matches(hand,inventory));
                CompoundTag tag=new CompoundTag();recruit.saveWithoutId(tag);
                var clone=recruit.getType().create(level);
                if(clone instanceof AbstractRecruitEntity restored) {
                    restored.load(tag);
                    check("gun survives native entity serialization",ItemStack.matches(hand,restored.getMainHandItem())&&ItemStack.matches(hand,restored.getInventory().getItem(restored.getInventorySlotIndex(EquipmentSlot.MAINHAND))));
                } else check("native recruit clone exists",false);
            }
            var owned=protectedRecruit==null?null:level.getEntity(protectedRecruit);
            check("owned custom bow preserved",owned instanceof AbstractRecruitEntity recruit&&recruit.getMainHandItem().is(Items.BOW)&&recruit.getMainHandItem().hasCustomHoverName());
            if(owned!=null)owned.discard();
            CompoundTag snapshot=data.save(new CompoundTag());
            LivingVillagesSavedData restored=LivingVillagesSavedData.load(snapshot);
            check("native country save round trip",restored.size()==data.size()&&restored.getFactions().stream().mapToInt(FactionData::getRecruitCount).sum()==data.getFactions().stream().mapToInt(FactionData::getRecruitCount).sum());
            BootstrapState marker=BootstrapState.get(level);
            check("bootstrap marker survives save",BootstrapState.load(marker.save(new CompoundTag())).attempted);
            long drones=ForgeRegistries.ITEMS.getKeys().stream().filter(id->id.getNamespace().equals("uncomplicatedfpv")).count();
            check("drone addon items registered",drones>=3);
            firingEarliest=Math.max(650,ticks+20);stage=2;
        }
        if(stage==2&&ticks>=firingEarliest) {
            try {
                if(beginFiring(level)){firingStart=ticks;stage=3;}
                else {check("NPC firing arena created",false);cleanupFiring(level);report(data.size());stage=4;}
            } catch(Exception e) {
                LOG.error("FRONTIER_TEST: cannot prepare firing check",e);
                check("NPC firing arena created",false);cleanupFiring(level);report(data.size());stage=4;
            }
        }
        if(stage==3) {
            var shooter=level.getEntity(firingRecruit);
            var target=level.getEntity(firingTarget);
            if(shooter instanceof AbstractRecruitEntity recruit&&target instanceof Zombie zombie) {
                recruit.setTarget(zombie); // Exercise the ordinary registered gun goal, never invoke shooting directly.
                IGun gun=IGun.getIGunOrNull(recruit.getMainHandItem());
                if(gun!=null)minAmmo=Math.min(minAmmo,ammo(gun,recruit.getMainHandItem()));
                minTargetHealth=Math.min(minTargetHealth,zombie.getHealth());
                if((ticks-firingStart)%40==0)LOG.info("FRONTIER_TEST: firing elapsed={} shots={} hits={} ammoMin={} healthMin={} ranged={} state={} canAttack={} sight={}",ticks-firingStart,shots,hits,minAmmo,minTargetHealth,recruit.getShouldRanged(),recruit.getState(),recruit.canAttack(zombie),recruit.getSensing().hasLineOfSight(zombie));
            }
            if(ticks-firingStart>=200||(shots>0&&hits>0&&minTargetHealth<initialTargetHealth)) {
                check("NPC actually fires TaCZ",shots>0&&minAmmo<initialAmmo);
                check("NPC TaCZ damages hostile target",hits>0&&minTargetHealth<initialTargetHealth);
                firingObservations.put("elapsedTicks",ticks-firingStart);firingObservations.put("serverShotEvents",shots);firingObservations.put("serverGunHurtEvents",hits);
                firingObservations.put("initialAmmoIncludingBarrel",initialAmmo);firingObservations.put("minimumAmmoIncludingBarrel",minAmmo);
                firingObservations.put("initialTargetHealth",initialTargetHealth);firingObservations.put("minimumTargetHealth",minTargetHealth);
                cleanupFiring(level);check("firing test entities and arena cleaned",arenaBlocks.isEmpty()&&firingRecruit==null&&firingTarget==null);
                report(data.size());stage=4;
            }
        }
    }
    private static void diagnoseNative(ServerLevel level,List<AbstractRecruitEntity> live) {
        LOG.info("FRONTIER_TEST: native diagnostics tick={} village={} materialized={} live={} roster={}",ticks,village==null?"none":village.getName(),village!=null&&MaterializationManager.isMaterialized(village.getId()),live.size(),village==null?List.of():village.getRoster().stream().map(u->u.getType()+"/role="+u.getRole()+"/body="+u.getBodyId()).toList());
        for(AbstractRecruitEntity recruit:live)LOG.info("FRONTIER_TEST: native body={} type={} held={} inventory={} tracked={} owned={} namedItem={} enchanted={} armedMarker={} pos={}",recruit.getUUID(),ForgeRegistries.ENTITY_TYPES.getKey(recruit.getType()),ForgeRegistries.ITEMS.getKey(recruit.getMainHandItem().getItem()),ForgeRegistries.ITEMS.getKey(recruit.getInventory().getItem(recruit.getInventorySlotIndex(EquipmentSlot.MAINHAND)).getItem()),MaterializationManager.isTrackedRecruit(recruit.getUUID()),recruit.getIsOwned(),recruit.getMainHandItem().hasCustomHoverName(),recruit.getMainHandItem().isEnchanted(),recruit.getPersistentData().getBoolean("frontier_auto_armed"),recruit.blockPosition());
    }
    private static boolean beginFiring(ServerLevel level) {
        var player=level.getServer().getPlayerList().getPlayers().get(0);
        BlockPos origin=new BlockPos(player.getBlockX(),Math.min(level.getMaxBuildHeight()-6,Math.max(200,player.getBlockY()+32)),player.getBlockZ());
        // A small temporary enclosed lane over the village: no country body or structure is moved.
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=16;dz++) {
            level.getChunk((origin.getX()+dx)>>4,(origin.getZ()+dz)>>4);
            for(int dy=-1;dy<=3;dy++) {
                if(dy!=-1&&dy!=3&&dx!=-2&&dx!=2&&dz!=-2&&dz!=16)continue;
                BlockPos pos=origin.offset(dx,dy,dz);arenaBlocks.put(pos,level.getBlockState(pos));
                level.setBlock(pos,(dy==-1?Blocks.GLASS:Blocks.BARRIER).defaultBlockState(),3);
            }
        }
        arenaBounds=new AABB(origin.offset(-4,-2,-4),origin.offset(5,6,20));
        var type=ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("recruits","bowman"));
        if(type==null||!(type.create(level) instanceof AbstractRecruitEntity recruit))return false;
        recruit.initSpawn();recruit.setPos(origin.getX()+0.5,origin.getY(),origin.getZ()+0.5);
        recruit.setInvulnerable(true);recruit.setPersistenceRequired();recruit.setNoAi(false);
        recruit.setAggroState(1);recruit.setFollowState(0);recruit.setShouldRanged(true);
        var speed=recruit.getAttribute(Attributes.MOVEMENT_SPEED);if(speed!=null)speed.setBaseValue(0.0);
        boolean nativeGun=!firingGun.isEmpty();
        if(!nativeGun)firingGun=GunItemBuilder.create().setId(new ResourceLocation("tacz","ak47")).setAmmoCount(30).setAmmoInBarrel(true).setFireMode(FireMode.AUTO).build();
        IGun gun=IGun.getIGunOrNull(firingGun);if(gun==null){recruit.discard();return false;}
        recruit.setItemInHand(InteractionHand.MAIN_HAND,firingGun.copy());
        Zombie zombie=EntityType.ZOMBIE.create(level);if(zombie==null){recruit.discard();return false;}
        zombie.setPos(origin.getX()+0.5,origin.getY(),origin.getZ()+12.5);zombie.setNoAi(true);zombie.setSilent(true);zombie.setPersistenceRequired();
        zombie.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.IRON_HELMET));
        var health=zombie.getAttribute(Attributes.MAX_HEALTH);if(health!=null)health.setBaseValue(1000.0);zombie.setHealth(zombie.getMaxHealth());
        firingRecruit=recruit.getUUID();firingTarget=zombie.getUUID();
        if(!level.addFreshEntity(recruit)||!level.addFreshEntity(zombie))return false;
        recruit.setTarget(zombie);initialAmmo=ammo(gun,recruit.getMainHandItem());minAmmo=initialAmmo;
        initialTargetHealth=zombie.getHealth();minTargetHealth=initialTargetHealth;shots=0;hits=0;
        observingPlayer=player.getUUID();observerX=player.getX();observerY=player.getY();observerZ=player.getZ();observerYaw=player.getYRot();observerPitch=player.getXRot();
        player.teleportTo(level,origin.getX()+1.4,origin.getY(),origin.getZ()-0.5,0,0);
        firingObservations.put("gunSource",nativeGun?"copy of auto-armed native troop":"fallback builder; native arming failed separately");
        firingObservations.put("gunId",gun.getGunId(recruit.getMainHandItem()).toString());
        LOG.info("FRONTIER_TEST: firing check started shooter={} target={} initialAmmo={} targetHealth={} gunSource={} (ordinary goal scheduler; 200-tick limit)",firingRecruit,firingTarget,initialAmmo,initialTargetHealth,firingObservations.get("gunSource"));
        return true;
    }
    private static int ammo(IGun gun,ItemStack stack){return gun.getCurrentAmmoCount(stack)+(gun.hasBulletInBarrel(stack)?1:0);}
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void shot(GunShootEvent event) {
        if(Boolean.getBoolean("frontier.smokeTest")&&event.getLogicalSide()==LogicalSide.SERVER&&firingRecruit!=null&&firingRecruit.equals(event.getShooter().getUUID()))shots++;
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void hurt(EntityHurtByGunEvent.Post event) {
        if(Boolean.getBoolean("frontier.smokeTest")&&event.getLogicalSide()==LogicalSide.SERVER&&firingRecruit!=null&&firingTarget!=null&&event.getAttacker()!=null&&firingRecruit.equals(event.getAttacker().getUUID())&&firingTarget.equals(event.getHurtEntity().getUUID()))hits++;
    }
    private static void cleanupFiring(ServerLevel level) {
        if(arenaBounds!=null&&firingRecruit!=null)for(Projectile projectile:level.getEntitiesOfClass(Projectile.class,arenaBounds))if(projectile.getOwner()!=null&&firingRecruit.equals(projectile.getOwner().getUUID()))projectile.discard();
        for(UUID id:new UUID[]{firingRecruit,firingTarget,protectedRecruit})if(id!=null){var entity=level.getEntity(id);if(entity!=null)entity.discard();}
        firingRecruit=null;firingTarget=null;protectedRecruit=null;
        if(observingPlayer!=null){var player=level.getServer().getPlayerList().getPlayer(observingPlayer);if(player!=null)player.teleportTo(level,observerX,observerY,observerZ,observerYaw,observerPitch);observingPlayer=null;}
        for(var entry:arenaBlocks.entrySet())level.setBlock(entry.getKey(),entry.getValue(),3);
        arenaBlocks.clear();arenaBounds=null;
    }
    private static void check(String name,boolean ok){results.put(name,ok);LOG.info("FRONTIER_TEST {}: {}",ok?"PASS":"FAIL",name);}
    private static void report(int villages) {
        try {
            Map<String,Object> report=new LinkedHashMap<>();report.put("villages",villages);report.put("checks",results);report.put("firing",firingObservations);report.put("allPassed",results.values().stream().allMatch(Boolean::booleanValue));
            Files.writeString(FMLPaths.GAMEDIR.get().resolve("frontier-smoke-results.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
        } catch(Exception e){LOG.error("Cannot write smoke test report",e);}
    }
}
