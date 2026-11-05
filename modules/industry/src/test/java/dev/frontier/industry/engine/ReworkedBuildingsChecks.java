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
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;

public final class ReworkedBuildingsChecks {
    record Fixture(SiteType type,int version,BlockPos origin,Map<BlockPos,BlockState> blocks){}
    private static final List<Fixture> fixtures=new ArrayList<>();
    public static Map<String,Boolean> run(MinecraftServer server)throws Exception{
        var checks=new LinkedHashMap<String,Boolean>();var level=server.overworld();var jobsReport=new JsonArray();var pipeReport=new JsonArray();
        checks.put("v9 orientation is current and v8 remains readable",Blueprint.CURRENT_VERSION==13&&Blueprint.savedVersion(309)==309&&Blueprint.savedVersion(308)==308);
        for(int generation:new int[]{9,10,11,12,13})for(var t:SiteType.values())for(int q=0;q<4;q++){
            int v=generation+q*100;var o=new BlockPos(7000+t.ordinal()*96,80,(2000+(generation-9)*3000)+q*96);var blocks=Blueprint.blocks(t,o,v);var bounds=Blueprint.bounds(t,v);String prefix="rework "+t+"/"+v+" ";
            for(int x=(o.getX()+bounds.minX())>>4;x<=((o.getX()+bounds.maxX())>>4);x++)for(int z=(o.getZ()+bounds.minZ())>>4;z<=((o.getZ()+bounds.maxZ())>>4);z++)level.setChunkForced(x,z,true);
            // The isolated verification world may be reused after a layout edit. Remove the
            // previous fixture including cells omitted by the new blueprint (never a user world).
            for(var p:BlockPos.betweenClosed(o.offset(bounds.minX(),bounds.minY(),bounds.minZ()),o.offset(bounds.maxX(),bounds.maxY(),bounds.maxZ())))if(!level.getBlockState(p).isAir())level.setBlock(p,Blocks.AIR.defaultBlockState(),2);
            blocks.forEach((p,s)->level.setBlock(p,s,2));fixtures.add(new Fixture(t,v,o,blocks));
            checks.put(prefix+"every cell lies inside preview bounds",blocks.keySet().stream().allMatch(p->bounds.contains(p.getX()-o.getX(),p.getY()-o.getY(),p.getZ()-o.getZ())));
            var npc=new Villager(EntityType.VILLAGER,level);npc.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(128);var entry=Blueprint.entrance(t,o,v);npc.moveTo(entry.getX()+.5,entry.getY(),entry.getZ()+.5,0,0);npc.setOnGround(true);
            boolean clear=true,reach=true;var jobs=FinalIndustryArchitecture.workplaces(t,v);
            for(var job:jobs){var p=o.offset(job.stand());boolean free=level.noCollision(npc,new AABB(p.getX()+.2,p.getY(),p.getZ()+.2,p.getX()+.8,p.getY()+1.95,p.getZ()+.8));var path=npc.getNavigation().createPath(p,0);boolean found=path!=null&&path.canReach();clear&=free;reach&=found;
                var row=new JsonObject();row.addProperty("site",t.name());row.addProperty("version",v);row.addProperty("id",job.id());row.addProperty("clear",free);row.addProperty("reachable",found);row.addProperty("equipment",job.part());jobsReport.add(row);if(!free||!found)System.out.println("REWORK_JOB_FAILURE "+prefix+job.id()+" clear="+free+" path="+found+" "+p);
            }
            checks.put(prefix+"all workstations clear",clear);checks.put(prefix+"native NPC path to every workstation",reach);npc.discard();
            checks.put(prefix+"authoritative controller exists",blocks.get(Blueprint.controller(t,o,v)).is(IndustryContent.controller(t)));
            checks.put(prefix+"one readable main nameplate",blocks.values().stream().filter(s->s.is(FunctionalBuildings.SIGNS.get(t).get())).count()==1);
            if(t.isFactory()){
                checks.put(prefix+"15 purpose assigned jobs",jobs.size()==15);
                checks.put(prefix+"three complete belts",FinalIndustryArchitecture.belts(t,v).stream().allMatch(p->blocks.get(o.offset(p))!=null&&blocks.get(o.offset(p)).is(FunctionalBuildings.BELT.get())));
                checks.put(prefix+"no planters on production floor",blocks.entrySet().stream().noneMatch(e->e.getKey().getY()==o.getY()+1&&e.getValue().is(FunctionalBuildings.PROPS.get("planter").get())));
            }
            if(t==SiteType.RESEARCH_CENTER){
                checks.put(prefix+"16 research positions",jobs.size()==16);checks.put(prefix+"server room replaced by instruments",blocks.values().stream().filter(s->s.is(FunctionalBuildings.PROPS.get("research_server").get())).count()<=2&&blocks.values().stream().filter(s->s.getBlock() instanceof ProcessEquipment p&&p.kind.equals(generation>=12?"campus_microscopy":"lab_microscope")).count()==(generation>=12?6:4));
                checks.put(prefix+"native research console interactive",FunctionalBuildings.terminal(FunctionalBuildings.EQUIPMENT.get("lab_console").get().defaultBlockState()));
            }
            if(t==SiteType.POWER_STATION){
                checks.put(prefix+"expanded station 53 by 43",bounds.width()*bounds.depth()==53*43);
                boolean connected=true,attached=true;int ports=0,pipes=0;
                for(var e:blocks.entrySet()){
                    if(e.getValue().getBlock() instanceof EngineeredPipe pipe){pipes++;int degree=0;for(var d:Direction.values()){
                        var other=blocks.getOrDefault(e.getKey().relative(d),Blocks.AIR.defaultBlockState());boolean expected=pipe.connects(other,d);if(expected)degree++;connected&=e.getValue().getValue(ProcessPipe.CONNECTIONS.get(d))==expected;
                    }connected&=degree>=2;if(degree<2)System.out.println("REWORK_PIPE_DANGLING "+prefix+e.getKey()+" "+pipe.circuit+" degree="+degree);
                    }else if(e.getValue().getBlock() instanceof ProcessEquipment equipment&&equipment.kind.startsWith("port_")){
                        ports++;var normal=e.getValue().getValue(BlockStateProperties.FACING);var outward=blocks.get(e.getKey().relative(normal));var back=blocks.get(e.getKey().relative(normal.getOpposite()));boolean pipeOK=outward!=null&&outward.getBlock() instanceof EngineeredPipe p&&equipment.kind.equals("port_"+p.circuit);boolean machineOK=back!=null&&!(back.getBlock() instanceof EngineeredPipe)&&!FunctionalBuildings.terminal(back)&&!back.isAir();
                        if(equipment.kind.equals("port_flue")&&normal==Direction.DOWN){machineOK=level.getBlockState(e.getKey().above()).isAir();for(var side:Direction.Plane.HORIZONTAL){var lining=blocks.get(e.getKey().relative(side));machineOK&=lining!=null&&(lining.is(Blocks.WHITE_CONCRETE)||lining.is(Blocks.RED_CONCRETE));}}
                        attached&=pipeOK&&machineOK;if(!pipeOK||!machineOK)System.out.println("REWORK_PORT_FAILURE "+prefix+e.getKey()+" pipe="+pipeOK+" machinery="+machineOK);
                    }
                }
                checks.put(prefix+"continuous typed pipe circuits",connected);checks.put(prefix+"ports attach to actual machinery",attached);
                var row=new JsonObject();row.addProperty("version",v);row.addProperty("pipeCells",pipes);row.addProperty("ports",ports);row.addProperty("connected",connected);row.addProperty("attached",attached);pipeReport.add(row);
                checks.put(prefix+"three unobstructed chimney outlets",FinalIndustryArchitecture.smokePoints(v).size()==3&&FinalIndustryArchitecture.smokePoints(v).stream().allMatch(p->level.getBlockState(o.offset(p)).isAir()&&level.getBlockState(o.offset(p).below()).isAir()));
                var data=IndustryData.get(server);var site=new IndustryData.Site(data,UUID.randomUUID(),UUID.randomUUID(),t,level.dimension().location(),o);site.blueprintVersion=v;site.loaded=true;site.matching=site.total=blocks.size();site.controller=true;site.machineryIntact=true;site.burn=600;
                checks.put(prefix+"working station emits smoke",IndustrialEffects.intensity(site)==6);site.burn=0;checks.put(prefix+"idle station does not fake fuel burn",IndustrialEffects.intensity(site)==2);
                int inverse=9+100*((4-q)%4);
                checks.put(prefix+"no outdoor racks or operator desks",blocks.entrySet().stream().filter(e->{var p=BuildingOrientation.rotate(e.getKey().subtract(o),inverse);return p.getX()>14||p.getZ()>17;}).noneMatch(e->e.getValue().getBlock() instanceof WorkshopProp p&&Set.of("operator_console","materials_rack","research_server").contains(p.kind)));
            }
        }
        var gson=new GsonBuilder().setPrettyPrinting().create();Files.writeString(Path.of("architecture-check/rework-workstations.json"),gson.toJson(jobsReport));Files.writeString(Path.of("architecture-check/pipe-connections.json"),gson.toJson(pipeReport));return checks;
    }
    public static Map<String,Boolean> lighting(MinecraftServer server)throws Exception{
        var checks=new LinkedHashMap<String,Boolean>();var report=new JsonArray();var l=server.overworld();l.setDayTime(18000);
        for(var f:fixtures){int cells=0,dark=0,min=15;var failures=new JsonArray();
            for(var e:f.blocks.entrySet()){
                var foot=e.getKey().above();if(!e.getValue().isFaceSturdy(l,e.getKey(),Direction.UP)||!l.getBlockState(foot).getCollisionShape(l,foot).isEmpty()||!l.getBlockState(foot.above()).getCollisionShape(l,foot.above()).isEmpty())continue;
                int light=l.getBrightness(LightLayer.BLOCK,foot);cells++;min=Math.min(min,light);if(light==0){dark++;if(failures.size()<20)failures.add(foot.subtract(f.origin).toShortString());}
            }
            checks.put("rework night light "+f.type+"/"+f.version+" no dark standing cells",dark==0);var row=new JsonObject();row.addProperty("type",f.type.name());row.addProperty("version",f.version);row.addProperty("cells",cells);row.addProperty("dark",dark);row.addProperty("minimum",min);row.add("examples",failures);report.add(row);if(dark>0)System.out.println("REWORK_DARK "+row);
        }
        Files.writeString(Path.of("architecture-check/rework-night-lighting.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));return checks;
    }
}
