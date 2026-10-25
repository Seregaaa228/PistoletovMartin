package dev.frontier.nations;

import com.diego.recruitsvillages.data.FactionData;
import com.diego.recruitsvillages.data.LivingVillagesSavedData;
import com.diego.recruitsvillages.data.RecruitUnit;
import com.diego.recruitsvillages.faction.VillageFounder;
import com.diego.recruitsvillages.materialize.MaterializationManager;
import com.mojang.logging.LogUtils;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.api.item.builder.AmmoItemBuilder;
import com.tacz.guns.api.item.gun.FireMode;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.entities.BowmanEntity;
import dev.frontier.nations.flag.FlagNet;
import dev.frontier.nations.map.PoliticalMapBridge;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.util.*;

/** Compatibility layer only. Recruits/Living Villages retain all state and authority. */
@Mod(FrontierNations.ID)
public final class FrontierNations {
    public static final String ID="frontier_nations";
    private static final Logger LOG=LogUtils.getLogger();
    private static final Map<UUID,Pending> PENDING=new LinkedHashMap<>();
    private record Pending(ResourceKey<Level> dimension,long due) {}
    private static int armed;

    public FrontierNations() {
        MinecraftForge.EVENT_BUS.addListener(this::started);
        MinecraftForge.EVENT_BUS.addListener(this::stopped);
        MinecraftForge.EVENT_BUS.addListener(this::joined);
        MinecraftForge.EVENT_BUS.addListener(this::tick);
        MinecraftForge.EVENT_BUS.addListener(this::commands);
        MinecraftForge.EVENT_BUS.addListener(this::login);
        PoliticalMapBridge.init();
        FlagNet.init();
        LOG.info("Frontier Nations compatibility loaded; original factions/claims remain authoritative");
    }
    private void started(ServerStartedEvent event) {
        ServerLevel level=event.getServer().overworld();
        LivingVillagesSavedData data=LivingVillagesSavedData.get(level);
        BootstrapState marker=BootstrapState.get(level);
        if(!marker.attempted) {
            marker.attempted=true;marker.setDirty();
            if(level.getGameTime()<100 && data.size()==0) bootstrap(level);
        }
        LOG.info("FRONTIER_CHECK: started villages={} armed={} gameTime={}",data.size(),armed,level.getGameTime());
    }
    private static int bootstrap(ServerLevel level) {
        LivingVillagesSavedData data=LivingVillagesSavedData.get(level);
        if(data.size()!=0)return 0;
        BlockPos spawn=level.getSharedSpawnPos();
        int[][] offsets={{-320,-192},{0,-384},{320,-192},{-320,192},{0,384},{320,192}};
        String[] names={"Северная Республика","Вольные Города","Восточный Союз","Западная Федерация","Южная Лига","Приграничье"};
        int count=0;
        for(int i=0;i<offsets.length;i++) {
            int x=spawn.getX()+offsets[i][0],z=spawn.getZ()+offsets[i][1];
            BlockPos site=findSite(level,x,z);
            if(site==null){LOG.warn("No safe founding plot near {},{}; skipping {}",x,z,names[i]);continue;}
            FactionData faction=VillageFounder.found(level,names[i],site);
            if(faction!=null){
                int index=0;
                for(RecruitUnit unit:faction.getRoster())if(unit.getRole()!=RecruitUnit.ROLE_LEADER&&index++%2==0)unit.setType("recruits:bowman");
                count++;LOG.info("FRONTIER_CHECK: founded {} at {} roster={}",faction.getName(),faction.getCenterPos(),faction.getRecruitCount());
            }
        }
        data.setDirty();return count;
    }
    private static BlockPos findSite(ServerLevel level,int x,int z) {
        // Deliberately bounded world generation during first creation, never from network coordinates.
        List<BlockPos> trials=new ArrayList<>();
        trials.add(new BlockPos(x,0,z));
        for(int radius:new int[]{32,64,96})for(int dx:new int[]{-1,0,1})for(int dz:new int[]{-1,0,1})
            if(dx!=0||dz!=0)trials.add(new BlockPos(x+radius*dx,0,z+radius*dz));
        for(BlockPos trial:trials) {
            if(!level.getWorldBorder().isWithinBounds(trial))continue;
            BlockPos surface=surfaceAt(level,trial.getX(),trial.getZ());
            if(surface.getY()<level.getSeaLevel()||surface.getY()>150)continue;
            if(!level.getFluidState(surface.below()).isEmpty())continue;
            if(!level.getWorldBorder().isWithinBounds(surface))continue;
            boolean gentle=true;
            for(int dx:new int[]{-8,8})for(int dz:new int[]{-8,8}) {
                int height=surfaceAt(level,surface.getX()+dx,surface.getZ()+dz).getY();
                if(Math.abs(height-surface.getY())>5)gentle=false;
            }
            if(gentle)return surface;
        }
        return null;
    }
    private static BlockPos surfaceAt(ServerLevel level,int x,int z) {
        // Level.getHeight returns minBuildHeight for an unloaded chunk. Generate only
        // this bounded founding sample before querying its height, never keep it forced.
        level.getChunk(x>>4,z>>4);
        BlockPos surface=level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,new BlockPos(x,0,z));
        // The no-leaves heightmap still includes tree trunks. Found on the soil,
        // rather than rejecting an otherwise usable forest as a steep hill.
        for(int i=0;i<32;i++) {
            var below=level.getBlockState(surface.below());
            if(!below.is(BlockTags.LOGS)&&!below.is(BlockTags.LEAVES)&&!below.canBeReplaced())break;
            surface=surface.below();
        }
        return surface;
    }
    private void stopped(ServerStoppedEvent event){PENDING.clear();armed=0;}
    private void joined(EntityJoinLevelEvent event) {
        if(event.getLevel() instanceof ServerLevel level && isRanged(event.getEntity())) {
            if(PENDING.size()<4096)PENDING.put(event.getEntity().getUUID(),new Pending(level.dimension(),level.getGameTime()+20));
        }
    }
    // The pinned TACZ bridge registers its firearm goal only on BowmanEntity.
    private static boolean isRanged(Object entity){return entity instanceof BowmanEntity;}
    private void tick(TickEvent.ServerTickEvent event) {
        if(event.phase!=TickEvent.Phase.END)return;
        MinecraftServer server=event.getServer();
        for(var it=PENDING.entrySet().iterator();it.hasNext();) {
            var entry=it.next();ServerLevel level=server.getLevel(entry.getValue().dimension());
            if(level==null){it.remove();continue;}
            if(level.getGameTime()<entry.getValue().due())continue;
            var entity=level.getEntity(entry.getKey());
            if(entity instanceof AbstractRecruitEntity recruit)arm(recruit);
            it.remove(); // Unloaded troops will queue again through their ordinary entity join.
        }
    }
    private static ItemStack rifle() {
        return GunItemBuilder.create().setId(new ResourceLocation("tacz","ak47")).setAmmoCount(30)
                .setAmmoInBarrel(true).setFireMode(FireMode.AUTO).build();
    }
    private static boolean arm(AbstractRecruitEntity recruit) {
        if(recruit.getIsOwned()||!MaterializationManager.isTrackedRecruit(recruit.getUUID())||recruit.getPersistentData().getBoolean("frontier_auto_armed"))return false;
        ItemStack held=recruit.getMainHandItem();
        if(held.hasCustomHoverName()||held.isEnchanted())return false;
        // Never replace an existing firearm or a player's custom weapon.
        if(!(held.getItem() instanceof BowItem)&&!(held.getItem() instanceof CrossbowItem))return false;
        ItemStack gun=rifle();
        if(gun.isEmpty()){LOG.warn("AK47 index is unavailable; leaving original recruit weapon intact");return false;}
        recruit.setItemInHand(InteractionHand.MAIN_HAND,gun);
        recruit.setShouldRanged(true);
        recruit.getPersistentData().putBoolean("frontier_auto_armed",true);
        armed++;LOG.info("FRONTIER_CHECK: armed ranged recruit {} team={}",recruit.getUUID(),recruit.getTeam()==null?"none":recruit.getTeam().getName());
        return true;
    }
    private void login(PlayerEvent.PlayerLoggedInEvent event) {
        if(event.getEntity() instanceof ServerPlayer player) {
            player.sendSystemMessage(Component.literal("Frontier Nations: M — карта мира со странами, T — слой территорий. /frontier help — подсказка."));
        }
    }
    private void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("frontier")
            .executes(c->help(c.getSource()))
            .then(Commands.literal("help").executes(c->help(c.getSource())))
            .then(Commands.literal("doctor").executes(c->{
                MinecraftServer server=c.getSource().getServer();
                int factions=LivingVillagesSavedData.get(server).size();
                String msg="Государств: "+factions+"; вооружено за этот запуск: "+armed+"; модов: "+ModList.get().getMods().size()+". Система территорий: Recruits.";
                c.getSource().sendSuccess(()->Component.literal(msg),false);return factions;
            }))
            .then(Commands.literal("kit").requires(s->s.hasPermission(2)).executes(c->{
                ServerPlayer p=c.getSource().getPlayerOrException();
                give(p,rifle());
                give(p,AmmoItemBuilder.create().setId(new ResourceLocation("tacz","762x39")).setCount(64).build());
                give(p,new ItemStack(Items.EMERALD,64));give(p,new ItemStack(Items.COOKED_BEEF,64));
                for(ResourceLocation id:ForgeRegistries.ITEMS.getKeys()) {
                    String path=id.getPath();
                    boolean drone=id.getNamespace().equals("uncomplicatedfpv")&&(path.contains("fpv")||path.contains("mavic")||path.contains("shahed")||path.contains("radio"));
                    boolean monitor=id.getNamespace().equals("superbwarfare")&&(path.equals("monitor")||path.equals("drone_monitor")||path.equals("tactical_radio"));
                    if(drone||monitor)give(p,new ItemStack(Objects.requireNonNull(ForgeRegistries.ITEMS.getValue(id))));
                }
                p.sendSystemMessage(Component.literal("Тестовый набор выдан. Боеприпасы и остальные предметы доступны в творческом инвентаре и JEI."));return 1;
            })));
    }
    private static int help(net.minecraft.commands.CommandSourceStack source) {
        source.sendSuccess(()->Component.literal("M — карта Xaero со странами; T — слой территорий; «Мой флаг» — на карте (M), меняет лидер страны; /rvguide — руководство государств; /rvfind — найти поселение; /rvjoin — вступить в поселение; /rvvillage — управление; /rvrelations — дипломатия (творческий режим); /frontier doctor — проверка; /frontier kit — тестовое снаряжение (нужны читы/OP)."),false);return 1;
    }
    private static void give(ServerPlayer player,ItemStack stack){if(!stack.isEmpty()&&!player.getInventory().add(stack))player.drop(stack,false);}
}
