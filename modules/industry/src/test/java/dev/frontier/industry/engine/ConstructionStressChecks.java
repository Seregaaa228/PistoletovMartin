package dev.frontier.industry.engine;
import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import dev.frontier.industry.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** One owner, 64 normal AI workers, eight simultaneous sites plus four later orders. No test teleports. */
@Mod.EventBusSubscriber(modid=IndustryMod.ID)
public final class ConstructionStressChecks {
    static List<UUID> sites,workers;static UUID owner;static int ticks,wave;static double maxStep;static final Map<UUID,net.minecraft.world.phys.Vec3> previous=new HashMap<>();
    static final BlockPos BASE=new BlockPos(-20000,80,-18000);
    public static void begin(net.minecraft.server.MinecraftServer server)throws Exception{
        var level=server.overworld();var data=IndustryData.get(server);sites=new ArrayList<>();workers=new ArrayList<>();ticks=0;wave=0;maxStep=0;
        for(int x=(BASE.getX()-45)>>4;x<=((BASE.getX()+330)>>4);x++)for(int z=(BASE.getZ()-90)>>4;z<=((BASE.getZ()+230)>>4);z++)level.setChunkForced(x,z,true);
        for(int x=-45;x<=330;x++)for(int z=-90;z<=230;z++)level.setBlock(BASE.offset(x,-1,z),Blocks.STONE.defaultBlockState(),2);
        var player=FakePlayerFactory.get(level,new GameProfile(UUID.nameUUIDFromBytes("stress-owner-64".getBytes()),"CrewStress"));owner=player.getUUID();player.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
        for(int i=0;i<64;i++){var npc=IndustryContent.WORKER.get().create(level);var w=new IndustryData.Worker(npc.getUUID(),owner,"builder",level.dimension().location());IndustryEngine.initialize(npc,w);npc.moveTo(BASE.getX()+88+(i%8)*2,80,BASE.getZ()-56+(i/8)*2,0,0);if(!level.addFreshEntity(npc))throw new IllegalStateException("Worker spawn failed");data.workers.put(w.id,w);workers.add(w.id);previous.put(w.id,npc.position());}
        level.getChunkSource().tick(()->true,true);
        for(int i=0;i<8;i++){var type=new SiteType[]{SiteType.DRONE_FACTORY,SiteType.VEHICLE_FACTORY,SiteType.EQUIPMENT_FACTORY,SiteType.MISSILE_FACTORY}[i%4];place(server,player,type,BASE.offset((i%4)*96,0,(i/4)*96));}
        // Simulate duplicate bindings from an older crowded queue; the dispatcher must release them.
        int extra=0;for(var id:workers){var w=data.workers.get(id);if(w.site==null&&extra++<12){w.site=sites.get(extra%3);IndustryEngine.initialize((WorkerEntity)IndustryEngine.find(server,id),w);}}
        data.setDirty();
    }
    static void place(net.minecraft.server.MinecraftServer server,net.minecraft.server.level.ServerPlayer player,SiteType type,BlockPos origin){player.moveTo(origin.getX(),origin.getY(),origin.getZ()+Blueprint.bounds(type).minZ()-3,0,0);var id=IndustryEngine.place(player,type,origin);if(id==null)throw new IllegalStateException("Stress deployer rejected "+type+" at "+origin);sites.add(id);}
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event)throws Exception{
        if(sites==null||event.phase!=TickEvent.Phase.END)return;var server=event.getServer();var data=IndustryData.get(server);ticks++;
        for(var id:workers){var e=IndustryEngine.find(server,id);if(e!=null){var old=previous.put(id,e.position());if(old!=null)maxStep=Math.max(maxStep,old.distanceToSqr(e.position()));}}
        boolean done=sites.stream().allMatch(id->!data.sites.get(id).constructing);
        if(done&&wave==0){
            wave=1;var player=FakePlayerFactory.get(server.overworld(),new GameProfile(owner,"CrewStress"));player.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
            int extras=0;for(var id:workers){var w=data.workers.get(id);if(w.site==null&&extras++<12){w.site=sites.get(0);var e=IndustryEngine.find(server,id);if(e instanceof WorkerEntity npc)IndustryEngine.initialize(npc,w);}}
            for(int i=0;i<4;i++)place(server,player,SiteType.SUBSTATION,BASE.offset(i*96,0,192));done=false;
        }
        if(ticks%200==0)System.out.println("CREW_STRESS_PROGRESS ticks="+ticks+" complete="+sites.stream().filter(id->!data.sites.get(id).constructing).count()+"/"+sites.size());
        if(!done&&ticks<5000)return;
        var report=new JsonObject();report.addProperty("workers",workers.size());report.addProperty("sameOwner",true);report.addProperty("normalAI",true);report.addProperty("testTeleports",false);report.addProperty("maxStepSquared",maxStep);report.addProperty("serverTicks",ticks);report.addProperty("wave",wave);var rows=new JsonArray();boolean pass=done&&wave==1&&sites.size()==12&&maxStep<9;
        for(var id:sites){var site=data.sites.get(id);var row=new JsonObject();row.addProperty("type",site.type.name());row.addProperty("complete",!site.constructing);row.addProperty("activeTicks",site.constructionTicks);row.addProperty("cursor",site.constructionCursor);row.addProperty("status",site.status);var npc=dev.frontier.industry.buildings.ConstructionCrew.current(server,data,site);if(npc!=null){var p=dev.frontier.industry.buildings.ConstructionCrew.workPoint(server.overworld(),site);row.addProperty("builderDistance",p==null?-1:Math.sqrt(npc.blockPosition().distSqr(p)));row.addProperty("pathDone",npc.getNavigation().isDone());}rows.add(row);pass&=!site.constructing&&site.constructionTicks==300;}
        report.add("sites",rows);report.addProperty("passed",pass);Files.writeString(Path.of("crew-stress-checks.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));System.out.println("CREW_STRESS_RESULT "+report);sites=null;server.halt(false);
    }
}
