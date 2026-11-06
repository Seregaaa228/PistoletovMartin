package dev.frontier.industry.engine;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import dev.frontier.industry.*;
import dev.frontier.industry.construction.Construction;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.*;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Two actual server processes: save four simultaneous sites halfway, then resume their world. */
@Mod.EventBusSubscriber(modid=IndustryMod.ID)
public final class ConstructionRestartChecks {
    static final Path MARKER=Path.of("construction-restart-progress.json"),RESULT=Path.of("construction-restart-checks.json");
    static List<UUID> sites;static boolean resumed,loadedPaid=true,unloadedGate;static int ticks;
    static final Gson GSON=new GsonBuilder().setPrettyPrinting().create();
    public static void begin(net.minecraft.server.MinecraftServer server)throws Exception{
        var data=IndustryData.get(server);var level=server.overworld();sites=new ArrayList<>();ticks=0;
        var savedSites=new LinkedHashMap<>(data.sites);data.sites.clear();
        var unloadedOrigin=new BlockPos(15000000,80,15000000);var unloaded=new IndustryData.Site(data,UUID.randomUUID(),UUID.randomUUID(),SiteType.POWER_STATION,level.dimension().location(),unloadedOrigin);unloaded.blueprintVersion=9;unloaded.constructing=true;unloaded.constructionDuration=300;data.sites.put(unloaded.id,unloaded);
        boolean absentBefore=!level.hasChunkAt(unloadedOrigin);Construction.tick(server);unloadedGate=absentBefore&&!level.hasChunkAt(unloadedOrigin)&&unloaded.constructionTicks==0&&unloaded.constructionCursor==0;data.sites.clear();data.sites.putAll(savedSites);
        if(Files.exists(MARKER)){
            var marker=JsonParser.parseString(Files.readString(MARKER)).getAsJsonObject();resumed=true;
            for(var row:marker.getAsJsonArray("sites")){
                var before=row.getAsJsonObject();var id=UUID.fromString(before.get("id").getAsString());var site=data.sites.get(id);sites.add(id);
                loadedPaid&=site!=null&&site.constructing&&site.constructionCursor==before.get("cursor").getAsInt()&&site.constructionTicks==before.get("ticks").getAsInt()&&site.inventory.getStackInSlot(0).getCount()==12&&site.orders.peek()!=null&&site.orders.peek().reserved&&site.orders.peek().paid.get(0).getCount()==3;
                if(site==null)throw new IllegalStateException("Lost persisted construction "+id);force(level,site.origin);
            }
            System.out.println("CONSTRUCTION_RESTART resumed actual SavedData: "+loadedPaid);
        }else{
            resumed=false;int index=0;
            for(var type:new SiteType[]{SiteType.POWER_STATION,SiteType.VEHICLE_FACTORY,SiteType.RESEARCH_CENTER,SiteType.MISSILE_FACTORY}){
                var o=new BlockPos(-16000+index*128,80,-14000);force(level,o);
                for(int x=-38;x<=38;x++)for(int z=-38;z<=38;z++)level.setBlock(o.offset(x,-1,z),Blocks.STONE.defaultBlockState(),2);
                double front=o.getZ()+Blueprint.bounds(type,9).minZ()-3;
                var owner=FakePlayerFactory.get(level,new GameProfile(UUID.nameUUIDFromBytes(("restart/"+index).getBytes()),"Restart"+index));owner.gameMode.changeGameModeForPlayer(GameType.CREATIVE);owner.moveTo(o.getX(),80,front,0,0);
                var npc=IndustryContent.WORKER.get().create(level);var worker=new IndustryData.Worker(npc.getUUID(),owner.getUUID(),"builder",level.dimension().location());IndustryEngine.initialize(npc,worker);npc.setNoAi(true);npc.moveTo(o.getX(),80,front,0,0);level.addFreshEntity(npc);data.workers.put(worker.id,worker);
                var id=IndustryEngine.place(owner,type,o);if(id==null)throw new IllegalStateException("Restart deployer rejected "+type);sites.add(id);var site=data.sites.get(id);site.energy=123;site.inventory.setStackInSlot(0,new ItemStack(Items.IRON_INGOT,12));var order=new IndustryData.Order("minecraft:diamond_sword",2);order.reserved=true;order.progress=12;order.paid.add(new ItemStack(Items.IRON_INGOT,3));site.orders.add(order);index++;
            }
            data.setDirty();
        }
    }
    static void force(net.minecraft.server.level.ServerLevel level,BlockPos o){for(int x=(o.getX()-40)>>4;x<=((o.getX()+40)>>4);x++)for(int z=(o.getZ()-40)>>4;z<=((o.getZ()+40)>>4);z++)level.setChunkForced(x,z,true);}
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event)throws Exception{
        if(sites==null)return;var server=event.getServer();var data=IndustryData.get(server);var level=server.overworld();
        if(event.phase==TickEvent.Phase.START){
            var method=Construction.class.getDeclaredMethod("workPoint",net.minecraft.server.level.ServerLevel.class,IndustryData.Site.class);method.setAccessible(true);
            for(var id:sites){var site=data.sites.get(id);if(site==null||!site.constructing)continue;var p=(BlockPos)method.invoke(null,level,site);if(p==null)continue;for(var worker:data.workers.values())if(id.equals(worker.site)){var e=IndustryEngine.find(server,worker.id);if(e!=null)e.moveTo(p.getX()+.5,p.getY(),p.getZ()+.5,0,0);}}
            return;
        }
        ticks++;
        boolean half=!resumed&&sites.stream().allMatch(id->data.sites.get(id).constructionTicks>=150);
        boolean done=resumed&&sites.stream().allMatch(id->!data.sites.get(id).constructing);
        if(!half&&!done&&ticks<900)return;
        var report=new JsonObject();report.addProperty("resumedActualServerProcess",resumed);report.addProperty("paidDataSurvivedDiskReload",loadedPaid);report.addProperty("unloadedChunksPauseWithoutGeneration",unloadedGate);report.addProperty("serverTicksThisProcess",ticks);var rows=new JsonArray();boolean pass=(half||done)&&unloadedGate;
        for(var id:sites){var site=data.sites.get(id);var row=new JsonObject();row.addProperty("id",id.toString());row.addProperty("type",site.type.name());row.addProperty("cursor",site.constructionCursor);row.addProperty("ticks",site.constructionTicks);row.addProperty("constructing",site.constructing);row.addProperty("status",site.status);row.addProperty("missing",Construction.firstMissing(level,site)==null?0:1);row.addProperty("paid",site.orders.peek()!=null&&site.orders.peek().reserved&&site.orders.peek().paid.get(0).getCount()==3&&site.inventory.getStackInSlot(0).getCount()==12);rows.add(row);if(done)pass&=!site.constructing&&site.constructionTicks==300&&row.get("missing").getAsInt()==0&&row.get("paid").getAsBoolean();}
        report.add("sites",rows);report.addProperty("passed",pass&&loadedPaid);
        if(half)Files.writeString(MARKER,GSON.toJson(report));else Files.writeString(RESULT,GSON.toJson(report));
        System.out.println("CONSTRUCTION_RESTART "+report);sites=null;server.halt(false);
    }
}
