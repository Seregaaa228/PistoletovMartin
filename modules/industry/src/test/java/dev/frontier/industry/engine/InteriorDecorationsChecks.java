package dev.frontier.industry.engine;

import dev.frontier.industry.*;
import dev.frontier.industry.architecture.FinalIndustryArchitecture;
import dev.frontier.industry.buildings.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;

public final class InteriorDecorationsChecks {
    public static Map<String,Boolean> run(MinecraftServer server){
        Map<String,Boolean> checks=new LinkedHashMap<>();var level=server.overworld();
        checks.put("new placements select current decorated blueprint version13",Blueprint.CURRENT_VERSION==13&&BuildingOrientation.VERSION==13);
        for(var t:SiteType.values())for(int q=0;q<4;q++){
            int v=7+100*q;var origin=new BlockPos(2200+t.ordinal()*96,80,q*96);var map=Blueprint.blocks(t,origin,v);var previous=Blueprint.blocks(t,origin,v-1);String prefix="interior "+t+"/"+q+" ";
            var bounds=Blueprint.bounds(t,v);
            checks.put(prefix+"all decorations fit rotated bounds",map.keySet().stream().allMatch(p->bounds.contains(p.getX()-origin.getX(),p.getY()-origin.getY(),p.getZ()-origin.getZ())));
            checks.put(prefix+"existing walls machinery and ceilings unchanged",previous.entrySet().stream().filter(e->e.getKey().getY()>origin.getY()&&e.getKey().getY()!=origin.getY()+7).allMatch(e->e.getValue().equals(map.get(e.getKey()))));
            long props=map.values().stream().filter(s->s.getBlock() instanceof WorkshopProp p&&Set.of("tool_cabinet","materials_rack","parts_bin","wall_panel","sealed_drum").contains(p.kind)).count();
            checks.put(prefix+"industrial interior furniture actually generated",props>=4);
            checks.put(prefix+"older v6 has no new decorative blocks",previous.values().stream().noneMatch(s->s.getBlock() instanceof WorkshopProp p&&Set.of("tool_cabinet","materials_rack","parts_bin","wall_panel","sealed_drum").contains(p.kind)));
            map.forEach((p,s)->level.setBlock(p,s,2));
            var entry=Blueprint.entrance(t,origin,v);var npc=new Villager(EntityType.VILLAGER,level);npc.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(96);npc.moveTo(entry.getX()+.5,entry.getY(),entry.getZ()+.5,0,0);npc.setOnGround(true);
            boolean clear=true,paths=true;var failures=new ArrayList<String>();
            for(var job:FinalIndustryArchitecture.workplaces(t,v)){
                var p=origin.offset(job.stand());boolean free=level.noCollision(npc,new AABB(p.getX()+.2,p.getY(),p.getZ()+.2,p.getX()+.8,p.getY()+1.95,p.getZ()+.8));
                var path=npc.getNavigation().createPath(p,0);boolean reach=path!=null&&path.canReach();clear &=free;paths &=reach;if(!free||!reach)failures.add(job.id());
            }
            checks.put(prefix+"every workplace remains collision free",clear);checks.put(prefix+"native paths still reach every workplace",paths);
            if(!failures.isEmpty())System.out.println("INTERIOR_PATH_FAILURE "+prefix+failures);npc.discard();
            checks.put(prefix+"decorated rotation survives savedVersion",Blueprint.savedVersion(v)==v);
            if(t.isFactory())checks.put(prefix+"three original belts preserved exactly",FinalIndustryArchitecture.belts(t,v).stream().allMatch(p->map.get(origin.offset(p)).equals(previous.get(origin.offset(p)))));
            for(var p:map.entrySet())if(p.getValue().getBlock() instanceof WorkshopProp prop&&Set.of("tool_cabinet","materials_rack","parts_bin","wall_panel","sealed_drum").contains(prop.kind))checks.put(prefix+"decorative prop does not expose paid factory menu "+prop.kind,!FunctionalBuildings.terminal(p.getValue()));
        }
        return checks;
    }
}
