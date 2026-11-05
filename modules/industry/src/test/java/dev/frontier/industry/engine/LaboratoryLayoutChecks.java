package dev.frontier.industry.engine;
import dev.frontier.industry.*;
import dev.frontier.industry.architecture.*;
import dev.frontier.industry.buildings.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;
public final class LaboratoryLayoutChecks {
    public static Map<String,Boolean> run(net.minecraft.server.MinecraftServer server){
        var checks=new LinkedHashMap<String,Boolean>();var level=server.overworld();
        for(int generation:new int[]{12,13})for(var t:SiteType.values())for(int q=0;q<4;q++){
            int v=generation+q*100;var o=new BlockPos(12000+t.ordinal()*110,80,12000+(generation-12)*600+q*110);var b=Blueprint.bounds(t,v);
            for(int x=(o.getX()+b.minX())>>4;x<=((o.getX()+b.maxX())>>4);x++)for(int z=(o.getZ()+b.minZ())>>4;z<=((o.getZ()+b.maxZ())>>4);z++)level.setChunkForced(x,z,true);
            var map=Blueprint.blocks(t,o,v);map.forEach((p,s)->level.setBlock(p,s,2));String key="v"+generation+" "+t+"/"+q+" ";
            var walker=new Villager(EntityType.VILLAGER,level);walker.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE).setBaseValue(128);var e=Blueprint.entrance(t,o,v);walker.moveTo(e.getX()+.5,e.getY(),e.getZ()+.5,0,0);walker.setOnGround(true);
            boolean clear=true,reach=true;
            for(var job:FinalIndustryArchitecture.workplaces(t,v)){
                var p=o.offset(job.stand());clear&=level.noCollision(walker,new AABB(p.getX()+.2,p.getY(),p.getZ()+.2,p.getX()+.8,p.getY()+1.95,p.getZ()+.8));var path=walker.getNavigation().createPath(p,0);reach&=path!=null&&path.canReach();
            }
            checks.put(key+"all saved workplaces clear",clear);checks.put(key+"native paths reach every workplace",reach);walker.discard();
            if(t==SiteType.EQUIPMENT_FACTORY){checks.put(key+"rifles RPGs and pistols physically present",java.util.stream.Stream.of("campus_rifle_display","campus_rpg_display","campus_pistol_display").allMatch(k->map.values().stream().filter(s->s.is(FunctionalBuildings.EQUIPMENT.get(k).get())).count()>=4));checks.put(key+"no giant artillery mockup",map.values().stream().noneMatch(s->s.getBlock() instanceof ProcessEquipment p&&Set.of("mock_gun_barrel","mock_gun_breech","mock_gun_mount").contains(p.kind)));}
            if(t.isFactory())checks.put(key+"all warehouse racks replaced with usable containers",map.values().stream().filter(s->s.is(FunctionalBuildings.CONTAINER.get())).count()==(generation==13?62:60)&&map.values().stream().noneMatch(s->s.getBlock() instanceof ProcessEquipment p&&Set.of("campus_cargo_crate","campus_storage_rack").contains(p.kind)));
            if(t==SiteType.RESEARCH_CENTER){checks.put(key+"no empty pots or abstract samples",map.values().stream().noneMatch(s->s.is(Blocks.FLOWER_POT)||s.getBlock() instanceof ProcessEquipment p&&p.kind.equals("campus_specimen")));checks.put(key+"planted research campus",map.values().stream().filter(s->s.is(FunctionalBuildings.EQUIPMENT.get("campus_green_planter").get())).count()>=20);checks.put(key+"three recognisable scientific instruments",java.util.stream.Stream.of("campus_microscopy","campus_chemistry","campus_electronics").allMatch(k->map.values().stream().filter(s->s.is(FunctionalBuildings.EQUIPMENT.get(k).get())).count()>=4));}
        }
        return checks;
    }
}
