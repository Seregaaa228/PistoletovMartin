package dev.frontier.industry.engine;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import dev.frontier.industry.*;
import dev.frontier.industry.construction.Construction;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.util.FakePlayerFactory;

/** Exercises the real deployer entry point and placement loop, not a copy of the blueprint. */
public final class ConstructionPipelineChecks {
    public static JsonObject run(MinecraftServer server,boolean baseline)throws Exception {
        var report=new JsonObject();var level=server.overworld();var data=IndustryData.get(server);
        var point=Construction.class.getDeclaredMethod("workPoint",net.minecraft.server.level.ServerLevel.class,IndustryData.Site.class);point.setAccessible(true);
        var rows=new JsonArray();int serial=0;
        for(var type:baseline?new SiteType[]{SiteType.RESEARCH_CENTER,SiteType.VEHICLE_FACTORY}:SiteType.values())for(int q=0;q<(baseline?1:4);q++){
            serial++;var origin=new BlockPos(-10000,80,-8000);var bounds=Blueprint.bounds(type,baseline?8:9);
            int radius=40;
            for(int cx=(origin.getX()-radius)>>4;cx<=((origin.getX()+radius)>>4);cx++)for(int cz=(origin.getZ()-radius)>>4;cz<=((origin.getZ()+radius)>>4);cz++)level.setChunkForced(cx,cz,true);
            if(serial==1)for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++)level.setBlock(origin.offset(x,-1,z),Blocks.STONE.defaultBlockState(),2);
            var owner=FakePlayerFactory.get(level,new GameProfile(UUID.nameUUIDFromBytes(("construction/"+serial).getBytes()),"BuildCheck"+serial));
            owner.gameMode.changeGameModeForPlayer(GameType.CREATIVE);owner.moveTo(origin.getX()+.5,80,origin.getZ()-bounds.depth()/2.0-3,90*q,0);
            var npc=IndustryContent.WORKER.get().create(level);
            var worker=new IndustryData.Worker(npc.getUUID(),owner.getUUID(),"builder",level.dimension().location());
            var spawnProbe=new IndustryData.Site(data,UUID.randomUUID(),owner.getUUID(),type,level.dimension().location(),origin);spawnProbe.blueprintVersion=dev.frontier.industry.buildings.BuildingOrientation.forPlayer(owner);var spawnPoint=dev.frontier.industry.buildings.ConstructionCrew.workPoint(level,spawnProbe);if(spawnPoint==null)throw new IllegalStateException("No construction fixture footing");
            IndustryEngine.initialize(npc,worker);npc.setNoAi(true);npc.moveTo(spawnPoint.getX()+.5,spawnPoint.getY(),spawnPoint.getZ()+.5,0,0);if(!level.addFreshEntity(npc))throw new IllegalStateException("Construction fixture spawn rejected");data.workers.put(worker.id,worker);
            // This batch runs synchronously inside ServerStartedEvent. Drain pending forced-chunk
            // promotions before measuring construction; normal gameplay processes them between ticks.
            for(int warmup=0;warmup<200&&IndustryEngine.find(server,npc.getUUID())==null;warmup++){
                level.getChunkSource().tick(()->true,true);while(level.getChunkSource().pollTask()){}
            }
            if(IndustryEngine.find(server,npc.getUUID())==null)throw new IllegalStateException("Construction fixture chunk is not entity-ticking: "+level.getChunkSource().getChunkDebugData(npc.chunkPosition()));
            var id=IndustryEngine.place(owner,type,origin);if(id==null)throw new IllegalStateException("Deployer rejected "+type);
            var site=data.sites.get(id);var row=new JsonObject();row.addProperty("type",type.name());row.addProperty("version",site.blueprintVersion);row.addProperty("operations",Construction.plan(site).size());
            level.getChunkSource().tick(()->true,true);
            int calls=0,stuck=0,previous=-1,pausedCalls=0,reloads=0;boolean recovery=true;
            if(!baseline&&q==0){site.energy=123;site.inventory.setStackInSlot(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT,12));var order=new IndustryData.Order("minecraft:diamond_sword",2);order.reserved=true;order.progress=12;order.paid.add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT,3));site.orders.add(order);}
            long started=System.nanoTime();
            while(site.constructing&&calls++<(baseline?2200:700)){
                var p=(BlockPos)point.invoke(null,level,site);if(p!=null){var oldChunk=npc.chunkPosition();npc.moveTo(p.getX()+.5,p.getY(),p.getZ()+.5,0,0);if(!oldChunk.equals(npc.chunkPosition())||IndustryEngine.find(server,npc.getUUID())==null)level.getChunkSource().tick(()->true,true);}
                if(!baseline&&q==0&&Set.of(75,150,270).contains(calls)){
                    int cursor=site.constructionCursor,ticks=site.constructionTicks;var loaded=IndustryData.load(data.save(new net.minecraft.nbt.CompoundTag()));site=loaded.sites.get(id);data.sites.put(id,site);reloads++;
                    recovery&=site.constructionCursor==cursor&&site.constructionTicks==ticks&&site.energy==123&&site.inventory.getStackInSlot(0).getCount()==12&&site.orders.peek()!=null&&site.orders.peek().reserved&&site.orders.peek().paid.get(0).getCount()==3;
                }
                if(!baseline&&q==0&&calls==100){
                    int cursor=site.constructionCursor,ticks=site.constructionTicks;data.workers.remove(worker.id);for(int i=0;i<3;i++){Construction.tick(server);pausedCalls++;}recovery&=site.constructionCursor==cursor&&site.constructionTicks==ticks;data.workers.put(worker.id,worker);
                    var nextCell=site.origin.offset(Construction.plan(site).get(site.constructionCursor).getKey());level.setBlock(nextCell,Blocks.DIAMOND_BLOCK.defaultBlockState(),2);for(int i=0;i<3;i++){Construction.tick(server);pausedCalls++;}recovery&=site.constructionCursor==cursor&&site.constructionTicks==ticks;level.setBlock(nextCell,Blocks.AIR.defaultBlockState(),2);
                }
                Construction.tick(server);
                if(site.constructionCursor==previous)stuck++;else stuck=0;previous=site.constructionCursor;if(stuck>30)break;
            }
            row.addProperty("calls",calls);row.addProperty("activeTicks",site.constructionTicks);row.addProperty("duration",site.constructionDuration);row.addProperty("cursor",site.constructionCursor);row.addProperty("complete",!site.constructing);row.addProperty("status",site.status);row.addProperty("elapsedMs",(System.nanoTime()-started)/1000000);
            row.addProperty("pausedCalls",pausedCalls);row.addProperty("saveReloads",reloads);row.addProperty("recoveryAndPaidStockPreserved",recovery);
            if(site.constructing){row.addProperty("builderAssigned",site.id.equals(worker.site));row.addProperty("builderIndexed",IndustryEngine.find(server,npc.getUUID())!=null);row.addProperty("builderAlive",npc.isAlive());row.addProperty("builderOwned",IndustryEngine.owned(npc,site.owner));}
            int missing=0;var next=new JsonObject();for(var e:Blueprint.blocks(type,origin,site.blueprintVersion).entrySet())if(!level.getBlockState(e.getKey()).is(e.getValue().getBlock()))missing++;
            row.addProperty("missing",missing);
            if(site.constructionCursor<Construction.plan(site).size()){
                var e=Construction.plan(site).get(site.constructionCursor);var pos=origin.offset(e.getKey());next.addProperty("position",pos.toShortString());next.addProperty("expected",e.getValue().toString());next.addProperty("actual",level.getBlockState(pos).toString());next.addProperty("noCollision",level.noCollision(npc,new net.minecraft.world.phys.AABB(pos)));row.add("next",next);
            }
            rows.add(row);System.out.println("CONSTRUCTION_PIPELINE "+row);data.sites.remove(id);data.workers.remove(worker.id);npc.discard();
            for(var p:Blueprint.blocks(type,origin,site.blueprintVersion).keySet())level.setBlock(p,Blocks.AIR.defaultBlockState(),2);
        }
        report.add("sites",rows);report.addProperty("baseline",baseline);return report;
    }
}
