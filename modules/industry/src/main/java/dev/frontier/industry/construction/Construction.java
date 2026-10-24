package dev.frontier.industry.construction;

import com.google.gson.JsonObject;
import dev.frontier.industry.*;
import dev.frontier.industry.engine.*;
import dev.frontier.industry.engine.IndustryData.Site;
import dev.frontier.industry.engine.IndustryData.Worker;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** A saved placement cursor is a compact journal over the immutable versioned blueprint. */
public final class Construction {
    private Construction() {}
    private static final Map<String,List<Map.Entry<BlockPos,BlockState>>> PLANS=new HashMap<>();
    public static List<Map.Entry<BlockPos,BlockState>> plan(Site site) {
        String key=site.type.name()+":"+site.blueprintVersion;
        var relative=PLANS.computeIfAbsent(key,k->{
            var list=new ArrayList<>(Blueprint.blocks(site.type,BlockPos.ZERO,site.blueprintVersion).entrySet());
            list.sort(Comparator.<Map.Entry<BlockPos,BlockState>>comparingInt(e->stage(site,e))
                .thenComparingInt(e->e.getKey().getY()).thenComparingInt(e->e.getKey().getZ()).thenComparingInt(e->e.getKey().getX()));
            return List.copyOf(list);
        });
        return relative;
    }
    private static int stage(Site site,Map.Entry<BlockPos,BlockState> e) {
        if(e.getKey().getY()==0)return 0;
        var id=net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(e.getValue().getBlock());
        if(e.getValue().is(IndustryContent.controller(site.type)))return 3;
        if(id!=null&&id.getPath().startsWith("detail_")) {
            String part=id.getPath().substring(7);
            if(!Set.of("wall_brick","wall_white","wall_metal","window","window_lab","column","beam","roof","roof_saw","rail").contains(part))return 3;
        }
        return e.getKey().getY()>=5?2:1;
    }
    public static Map<BlockPos,BlockState> repairBlocks(Site site) {
        if(!site.constructing)return Blueprint.blocks(site.type,site.origin,site.blueprintVersion);
        Map<BlockPos,BlockState> result=new LinkedHashMap<>();var plan=plan(site);
        for(int i=0;i<Math.min(site.constructionCursor,plan.size());i++) {
            var entry=plan.get(i);result.put(site.origin.offset(entry.getKey()),entry.getValue());
        }
        return result;
    }
    public static UUID start(ServerPlayer player,Site site) {
        site.constructing=true;site.constructionDuration=IndustryConfig.buildingTicks(site.type);
        site.constructionCursor=0;site.constructionTicks=0;site.kitPaid=!player.isCreative();site.loaded=true;
        site.status="Стройка: назначь строителя в КПК";
        var data=IndustryData.get(player.server);data.sites.put(site.id,site);data.setDirty();
        autoAssign(player.server,data,site,false);
        IndustryEngine.tell(player,site.type.title()+": площадка зарезервирована. Строитель возведёт объект за "+site.constructionDuration/20+" с работы.");
        return site.id;
    }
    public static void hireBuilder(ServerPlayer player) {
        if(player==null || !player.isAlive() || player.isSpectator())return;
        var data=IndustryData.get(player.server);
        if(!IndustryRules.canHire(data.workers.values().stream().filter(w->w.owner.equals(player.getUUID())&&w.active()).count())) {IndustryEngine.tell(player,"Предел: 64 гражданских работника");return;}
        if(IndustryEngine.countItem(player,Items.EMERALD)<4){IndustryEngine.tell(player,"Найм строителя: 4 изумруда");return;}
        BlockPos spawn=null;
        for(int dx=-2;dx<=2&&spawn==null;dx++)for(int dz=-2;dz<=2;dz++) {
            BlockPos p=player.blockPosition().offset(dx,0,dz);
            if(safe(player.serverLevel(),p)){spawn=p;break;}
        }
        if(spawn==null){IndustryEngine.tell(player,"Рядом нужно свободное место для строителя");return;}
        WorkerEntity entity=IndustryContent.WORKER.get().create(player.serverLevel());if(entity==null)return;
        var worker=new Worker(entity.getUUID(),player.getUUID(),"builder",player.level().dimension().location());
        IndustryEngine.initialize(entity,worker);entity.moveTo(spawn.getX()+.5,spawn.getY(),spawn.getZ()+.5,player.getYRot(),0);
        entity.setCustomName(Component.literal("Строитель"));entity.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_PICKAXE));entity.setDropChance(EquipmentSlot.MAINHAND,0);
        data.workers.put(worker.id,worker);
        if(!player.serverLevel().addFreshEntity(entity)){data.workers.remove(worker.id);return;}
        IndustryEngine.takePlayerItem(player,Items.EMERALD,4);data.setDirty();
        IndustryEngine.tell(player,"Нанят строитель за 4 изумруда");
        for(var site:data.sites.values())if(site.constructing&&site.owner.equals(player.getUUID()))autoAssign(player.server,data,site,false);
    }
    public static void staff(ServerPlayer player,Site site) {
        var data=IndustryData.get(player.server);
        if(!site.constructing || !site.owner.equals(player.getUUID()))return;
        if(builder(player.server,data,site)!=null){IndustryEngine.tell(player,"Строитель уже назначен");return;}
        if(!autoAssign(player.server,data,site,true)){hireBuilder(player);autoAssign(player.server,data,site,true);}
    }
    private static boolean autoAssign(MinecraftServer server,IndustryData data,Site site,boolean anyFree) {
        if(builder(server,data,site)!=null)return true;
        for(Worker worker:data.workers.values()) {
            if(!worker.active()||worker.site!=null||!worker.owner.equals(site.owner)||!worker.dimension.equals(site.dimension)||(!anyFree&&!worker.role.equals("builder")))continue;
            var entity=IndustryEngine.find(server,worker.id);
            if(!(entity instanceof WorkerEntity npc)||!npc.isAlive()||npc.isPassenger()||IndustryEngine.busy(npc)||npc.blockPosition().distSqr(site.origin)>128*128)continue;
            worker.site=site.id;worker.role="builder";IndustryEngine.initialize(npc,worker);data.setDirty();return true;
        }
        return false;
    }
    private static WorkerEntity builder(MinecraftServer server,IndustryData data,Site site) {
        for(Worker w:data.workers.values())if(w.active()&&site.id.equals(w.site)&&w.owner.equals(site.owner)&&w.role.equals("builder")) {
            var e=IndustryEngine.find(server,w.id);
            if(e instanceof WorkerEntity npc && npc.isAlive()&&!npc.isPassenger()&&!IndustryEngine.busy(npc)
                && npc.level().dimension().location().equals(site.dimension)&&IndustryEngine.owned(npc,site.owner)
                && npc.getPersistentData().getString("frontierIndustryRole").equals("builder"))return npc;
        }
        return null;
    }
    public static void tick(MinecraftServer server) {
        var data=IndustryData.get(server);int budget=IndustryConfig.PLACEMENT_BUDGET.get();
        // Rotating start avoids starving later sites when many bases build concurrently.
        var sites=new ArrayList<>(data.sites.values());if(!sites.isEmpty())Collections.rotate(sites,-Math.floorMod(server.getTickCount(),sites.size()));
        for(Site site:sites)if(site.constructing) {
            var level=IndustryEngine.level(server,site);
            if(level==null || !IndustryEngine.loaded(level,site)){site.status="Стройка: чанки выгружены — пауза";continue;}
            if(server.getTickCount()%20==0)autoAssign(server,data,site,false);
            if(!site.loaded||server.getTickCount()%20==0)IndustryEngine.poll(level,site);
            site.loaded=true;
            if(site.matching<site.total){site.status="Стройка повреждена: нужен ремонт построенных этапов";continue;}
            WorkerEntity npc=builder(server,data,site);
            if(npc==null){site.status="Стройка: нет живого назначенного строителя";continue;}
            BlockPos point=workPoint(level,site);
            if(point==null){site.status="Стройка: проход к рабочей точке перекрыт";continue;}
            if(npc.distanceToSqr(point.getX()+.5,point.getY(),point.getZ()+.5)>9){site.status="Строитель идёт к площадке";continue;}
            var plan=plan(site);int next=ConstructionRules.advance(site.constructionTicks,site.constructionDuration,true);
            int target=(int)((long)plan.size()*next/Math.max(1,site.constructionDuration));
            int quota=ConstructionRules.quota(site.constructionCursor,plan.size(),next,site.constructionDuration,Math.min(24,budget));
            boolean blocked=false;
            for(int n=0;n<quota;n++) {
                var e=plan.get(site.constructionCursor);BlockPos pos=site.origin.offset(e.getKey());
                if(!level.getBlockState(pos).isAir()||!ClaimPermissions.allows(level,site.owner,site.team,pos)||!level.noCollision(npc,new net.minecraft.world.phys.AABB(pos))){
                    site.status="Стройка: освободи место или права на "+pos.toShortString();blocked=true;break;
                }
                if(!level.setBlock(pos,e.getValue(),Block.UPDATE_ALL)){site.status="Не удалось установить строительный блок";blocked=true;break;}
                site.constructionCursor++;site.matching++;site.total++;budget--;data.setDirty();
            }
            if(blocked)continue;
            if(site.constructionCursor>=target){site.constructionTicks=next;data.setDirty();}
            if(server.getTickCount()%20==0)npc.swing(InteractionHand.MAIN_HAND);
            site.status="Стройка: "+Math.min(100,100*site.constructionTicks/Math.max(1,site.constructionDuration))+"% · "+Math.max(0,(site.constructionDuration-site.constructionTicks+19)/20)+" с";
            if(site.constructionTicks>=site.constructionDuration && site.constructionCursor>=plan.size()) {
                site.constructing=false;release(server,data,site);IndustryEngine.poll(level,site);IndustryEngine.autoConnect(level,data,site);site.status="Строительство завершено";data.setDirty();
            }
        }
    }
    public static boolean workerTick(WorkerEntity entity) {
        if(!(entity.level() instanceof ServerLevel level))return false;
        var data=IndustryData.get(level.getServer());var worker=data.workers.get(entity.getUUID());
        if(worker==null||!worker.role.equals("builder"))return false;
        Site site=worker.site==null?null:data.sites.get(worker.site);
        if(site==null||!site.constructing)return false;
        BlockPos point=workPoint(level,site);
        if(point!=null && entity.tickCount%20==0) {
            if(entity.distanceToSqr(point.getX()+.5,point.getY(),point.getZ()+.5)>4)entity.getNavigation().moveTo(point.getX()+.5,point.getY(),point.getZ()+.5,1.0);
            else entity.getNavigation().stop();
        }
        return true;
    }
    private static BlockPos workPoint(ServerLevel level,Site site) {
        var bounds=Blueprint.bounds(site.type,site.blueprintVersion);
        int x=site.constructionTicks<site.constructionDuration/3?-3:site.constructionTicks<site.constructionDuration*2/3?3:0;
        for(int y:new int[]{0,1,-1,2,-2}) {
            BlockPos p=site.origin.offset(x,y,bounds.minZ()-1);if(safe(level,p))return p;
        }
        return null;
    }
    private static boolean safe(ServerLevel level,BlockPos p) {
        return level.hasChunkAt(p)&&level.getWorldBorder().isWithinBounds(p)&&level.getBlockState(p).getCollisionShape(level,p).isEmpty()
            &&level.getBlockState(p.above()).getCollisionShape(level,p.above()).isEmpty()&&level.getBlockState(p.below()).isFaceSturdy(level,p.below(),net.minecraft.core.Direction.UP);
    }
    private static void release(MinecraftServer server,IndustryData data,Site site) {
        for(var w:data.workers.values())if(site.id.equals(w.site)&&w.role.equals("builder")) {
            w.site=null;var e=IndustryEngine.find(server,w.id);if(e instanceof WorkerEntity npc){IndustryEngine.initialize(npc,w);npc.getNavigation().stop();}
        }
    }
    public static void cancel(ServerPlayer player,Site site) {
        if(!site.constructing||!site.owner.equals(player.getUUID())||!IndustryEngine.loaded(player.serverLevel(),site))return;
        var level=player.serverLevel();var blocks=repairBlocks(site);
        if(blocks.keySet().stream().anyMatch(p->!ClaimPermissions.allowsBreaking(level,site.owner,site.team,p))){IndustryEngine.tell(player,"Нет прав для отмены на части площадки");return;}
        // All site stock must fit in the player's inventory before touching any world block.
        var stock=new ArrayList<ItemStack>();
        for(int i=0;i<site.inventory.getSlots();i++)if(!site.inventory.getStackInSlot(i).isEmpty())stock.add(site.inventory.getStackInSlot(i).copy());
        if(!stock.isEmpty()){IndustryEngine.tell(player,"Сначала забери материалы из объекта");return;}
        boolean refund=ConstructionRules.refundable(site.kitPaid,site.constructionCursor);
        var returns=new ArrayList<ItemStack>();
        if(refund)returns.add(new ItemStack(IndustryContent.deployer(site.type)));
        if(site.repairKit)returns.add(new ItemStack(IndustryContent.REPAIR_KIT.get()));
        // Simulate all returns together before changing blocks, the record, or inventory.
        var simulated=new net.minecraft.world.entity.player.Inventory(player);
        simulated.load(player.getInventory().save(new net.minecraft.nbt.ListTag()));
        for(ItemStack stack:returns)if(!simulated.add(stack.copy())){IndustryEngine.tell(player,"Освободи место для возврата комплекта/ремнабора");return;}
        for(var e:blocks.entrySet())if(level.getBlockEntity(e.getKey())==null&&level.getBlockState(e.getKey()).equals(e.getValue()))level.setBlock(e.getKey(),Blocks.AIR.defaultBlockState(),Block.UPDATE_ALL);
        var data=IndustryData.get(player.server);release(player.server,data,site);site.constructing=false;site.repairKit=false;site.repairRequested=false;site.repairProgress=0;data.sites.remove(site.id);data.setDirty();
        for(ItemStack stack:returns)player.getInventory().add(stack);
        player.closeContainer();IndustryEngine.tell(player,refund?"Стройка отменена, комплект возвращён":"Стройка отменена; начатый комплект израсходован");
    }
    public static JsonObject snapshot(Site site) {
        var j=new JsonObject();j.addProperty("active",site.constructing);j.addProperty("progress",site.constructionTicks);j.addProperty("total",site.constructionDuration);
        j.addProperty("placed",site.constructionCursor);j.addProperty("blocks",plan(site).size());return j;
    }
    public static boolean generated(ServerLevel level,BlockPos pos,BlockState state) {
        UUID id=IndustryEngine.siteAt(level,pos);var data=IndustryData.get(level.getServer());Site site=id==null?null:data.sites.get(id);
        if(site==null||site.blueprintVersion<3)return false;
        BlockState expected=repairBlocks(site).get(pos);
        return expected!=null&&state.is(expected.getBlock());
    }
}
