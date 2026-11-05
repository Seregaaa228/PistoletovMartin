package dev.frontier.industry.engine;

import com.google.gson.*;
import dev.frontier.industry.*;
import dev.frontier.industry.architecture.*;
import dev.frontier.industry.buildings.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

public final class DistinctBuildingsChecks {
    record Fixture(SiteType type,int version,BlockPos origin,Map<BlockPos,BlockState> blocks){}
    private static final List<Fixture> FIXTURES=new ArrayList<>();
    public static Map<String,Boolean> run(MinecraftServer server)throws Exception {
        Map<String,Boolean> checks=new LinkedHashMap<>();var level=server.overworld();level.setDayTime(18000);
        checks.put("distinct blueprint8 remains readable under current generation",Blueprint.savedVersion(308)==308&&Blueprint.CURRENT_VERSION==13&&BuildingOrientation.VERSION==13);
        for(var t:SiteType.values())for(int q=0;q<4;q++){
            int v=8+100*q;var o=new BlockPos(3400+t.ordinal()*96,80,q*96);var map=Blueprint.blocks(t,o,v);var bounds=Blueprint.bounds(t,v);String prefix="distinct "+t+"/"+q+" ";
            FIXTURES.add(new Fixture(t,v,o,map));
            for(int x=(o.getX()+bounds.minX())>>4;x<=((o.getX()+bounds.maxX())>>4);x++)for(int z=(o.getZ()+bounds.minZ())>>4;z<=((o.getZ()+bounds.maxZ())>>4);z++)level.setChunkForced(x,z,true);
            checks.put(prefix+"all blocks in rotated bounds",map.keySet().stream().allMatch(p->bounds.contains(p.getX()-o.getX(),p.getY()-o.getY(),p.getZ()-o.getZ())));
            map.forEach((p,s)->level.setBlock(p,s,2));
            var entry=Blueprint.entrance(t,o,v);var npc=new Villager(EntityType.VILLAGER,level);npc.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(96);npc.moveTo(entry.getX()+.5,entry.getY(),entry.getZ()+.5,0,0);npc.setOnGround(true);
            boolean clear=true,reach=true;var failed=new ArrayList<String>();
            for(var job:FinalIndustryArchitecture.workplaces(t,v)){
                var p=o.offset(job.stand());boolean free=level.noCollision(npc,new AABB(p.getX()+.2,p.getY(),p.getZ()+.2,p.getX()+.8,p.getY()+1.95,p.getZ()+.8));var path=npc.getNavigation().createPath(p,0);boolean found=path!=null&&path.canReach();clear &=free;reach &=found;if(!free||!found)failed.add(job.id());
            }
            checks.put(prefix+"all workplaces have physical clearance",clear);checks.put(prefix+"every workplace reached by native navigation",reach);if(!failed.isEmpty())System.out.println("DISTINCT_PATH_FAILURE "+prefix+failed);npc.discard();
            checks.put(prefix+"critical power cores preserved",Blueprint.criticalBlocks(t,o,v).entrySet().stream().allMatch(e->e.getValue()!=null&&e.getValue().equals(map.get(e.getKey()))));
            checks.put(prefix+"saved rotation8 survives reload",Blueprint.savedVersion(v)==v);
            checks.put(prefix+"controller is at the authoritative saved position",map.get(Blueprint.controller(t,o,v)).is(IndustryContent.controller(t)));
            checks.put(prefix+"exactly one main nameplate",map.values().stream().filter(s->s.is(FunctionalBuildings.SIGNS.get(t).get())).count()==1);
            checks.put(prefix+"new floor lighting physically generated",map.values().stream().filter(s->s.is(FunctionalBuildings.PROPS.get("floor_lamp").get())).count()>12);
            if(t==SiteType.RESEARCH_CENTER){boolean guarded=true;for(int x:new int[]{-15,-11})for(int z=-4;z<=0;z++)guarded &=map.get(o.offset(BuildingOrientation.rotate(new BlockPos(x,8,z),v))).is(net.minecraft.world.level.block.Blocks.LIGHT_BLUE_STAINED_GLASS_PANE);checks.put(prefix+"upper stair opening has continuous safety guards",guarded);}
            if(t.isFactory()){
                var previous=Blueprint.blocks(t,o,v-1);checks.put(prefix+"three production belts unchanged",FinalIndustryArchitecture.belts(t,v).stream().allMatch(p->map.get(o.offset(p)).equals(previous.get(o.offset(p)))));
                boolean open=true;int front=FinalIndustryArchitecture.baseBounds(t).minZ()+3;
                for(int x=-DistinctIndustryArchitecture.gateHalfWidth(t);x<=DistinctIndustryArchitecture.gateHalfWidth(t);x++)for(int y=1;y<=DistinctIndustryArchitecture.gateHeight(t);y++)if(map.containsKey(o.offset(BuildingOrientation.rotate(new BlockPos(x,y,front),v))))open=false;
                checks.put(prefix+"full specified gate width and height are clear",open);
                if(t==SiteType.VEHICLE_FACTORY){boolean apron=true;for(int x=-12;x<=12;x++)for(int z=front;z<FinalIndustryArchitecture.start(t)-2;z++)for(int y=1;y<=8;y++)apron &=!map.containsKey(o.offset(BuildingOrientation.rotate(new BlockPos(x,y,z),v)));checks.put(prefix+"aircraft rollout apron is free of controllers and furniture",apron);}
            }
            if(t==SiteType.POWER_STATION){
                boolean joints=true;int pipes=0;
                for(var e:map.entrySet())if(e.getValue().getBlock() instanceof ProcessPipe){pipes++;joints &=level.getBlockState(e.getKey()).equals(e.getValue());for(var d:Direction.values())joints &=e.getValue().getValue(ProcessPipe.CONNECTIONS.get(d))==ProcessPipe.socket(map.getOrDefault(e.getKey().relative(d),net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()));}
                checks.put(prefix+"all six pipe joins match physical neighbours",joints&&pipes>30);
                var site=new IndustryData.Site(IndustryData.get(server),UUID.randomUUID(),UUID.randomUUID(),t,level.dimension().location(),o);site.blueprintVersion=v;site.loaded=site.controller=site.machineryIntact=true;site.matching=site.total=map.size();
                checks.put(prefix+"ready idle plant has visible coolant steam",IndustrialEffects.intensity(site)==1);site.burn=1200;checks.put(prefix+"real fuel burn gives stronger smoke",IndustrialEffects.intensity(site)>1);site.constructing=true;checks.put(prefix+"construction does not emit smoke",IndustrialEffects.intensity(site)==0);
            }
        }
        return checks;
    }
    public static Map<String,Boolean> lighting(MinecraftServer server)throws Exception {
        Map<String,Boolean> checks=new LinkedHashMap<>();JsonArray report=new JsonArray();var level=server.overworld();
        var body=EntityType.VILLAGER.create(level);
        for(var fixture:FIXTURES){
            int count=0,dark=0,min=15;JsonArray examples=new JsonArray();
            for(var e:fixture.blocks.entrySet()){
                var floor=e.getKey();if(!Block.isShapeFullBlock(level.getBlockState(floor).getCollisionShape(level,floor)))continue;var p=floor.above();
                if(!level.noCollision(body,new AABB(p.getX()+.2,p.getY(),p.getZ()+.2,p.getX()+.8,p.getY()+1.95,p.getZ()+.8)))continue;
                int light=level.getBrightness(LightLayer.BLOCK,p);count++;min=Math.min(min,light);if(light==0){dark++;if(examples.size()<16)examples.add(p.subtract(fixture.origin).toShortString());}
            }
            checks.put("night lighting "+fixture.type+"/"+BuildingOrientation.quarter(fixture.version)+" no dark spawnable surface",count>30&&dark==0);
            var row=new JsonObject();row.addProperty("type",fixture.type.name());row.addProperty("version",fixture.version);row.addProperty("testedStandingCells",count);row.addProperty("minimumBlockLight",min);row.addProperty("darkCells",dark);row.add("examples",examples);report.add(row);
            if(dark>0)System.out.println("DARK_CELLS "+fixture.type+"/"+fixture.version+" count="+dark+" "+examples);
        }
        body.discard();Files.writeString(Path.of("architecture-check/night-lighting.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));return checks;
    }
}
