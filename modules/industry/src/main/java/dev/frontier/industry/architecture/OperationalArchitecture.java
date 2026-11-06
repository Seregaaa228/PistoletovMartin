package dev.frontier.industry.architecture;

import dev.frontier.industry.*;
import dev.frontier.industry.buildings.FunctionalBuildings;
import dev.frontier.industry.engine.BlueprintBounds;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** V5 keeps v4 immutable and records explicit, accessible future NPC workplaces. */
public final class OperationalArchitecture {
    private OperationalArchitecture() {}
    public record Workplace(BlockPos equipment,BlockPos stand,Direction facing,String part,String role) {}
    private static final Map<SiteType,List<Workplace>> WORK=new EnumMap<>(SiteType.class);
    private static final Map<SiteType,List<BlockPos>> BELTS=new EnumMap<>(SiteType.class);
    static {
        for(var type:SiteType.values()) {
            List<Workplace> jobs=new ArrayList<>();
            if(type==SiteType.RESEARCH_CENTER) {
                for(int x:new int[]{-4,4})for(int z:new int[]{-4,-1,2,5})jobs.add(job(x,1,z,"laboratory","scientist"));
                // Keep all future NPC workstations on the accessible production floor.
                // The upper floor remains available for servers and human/player access.
                for(int x:new int[]{-2,2})jobs.add(job(x,1,0,"laboratory","scientist"));
            } else if(type.isFactory()) {
                int x=type==SiteType.VEHICLE_FACTORY?8:type==SiteType.DRONE_FACTORY?5:6;
                int[] rows=type==SiteType.MISSILE_FACTORY?new int[]{-6,-2,2,6,10}:type==SiteType.VEHICLE_FACTORY?new int[]{-3,1,5,8}:new int[]{-3,0,3,6};
                for(int side:new int[]{-x,x})for(int z:rows) {
                    int standX=side<0?side+1:side-1;
                    if(type==SiteType.MISSILE_FACTORY&&side<0&&z==2)standX=-4;
                    // The drone factory's southern row meets the solid glazed facade at z=6.
                    int standZ=type==SiteType.DRONE_FACTORY&&z==6?z-1:z;
                    if(type==SiteType.MISSILE_FACTORY&&side<0&&z==2)standZ=1;
                    jobs.add(new Workplace(new BlockPos(side,1,z),new BlockPos(standX,1,standZ),side<0?Direction.EAST:Direction.WEST,
                    type==SiteType.DRONE_FACTORY?"drone_bench":type==SiteType.MISSILE_FACTORY&&side<0?"missile_fixture":z%2==0?"press":"machine","operator"));
                }
            }
            WORK.put(type,List.copyOf(jobs));
            List<BlockPos> belts=new ArrayList<>();
            if(type.isFactory()) {
                int[] xs=type==SiteType.VEHICLE_FACTORY?new int[]{-1,0,1}:type==SiteType.DRONE_FACTORY?new int[]{-2,-1,1,2}:new int[]{-3,-2,2,3};
                int from=type==SiteType.VEHICLE_FACTORY?1:type==SiteType.MISSILE_FACTORY?-5:-2;
                int to=type==SiteType.MISSILE_FACTORY?10:type==SiteType.VEHICLE_FACTORY?8:6;
                for(int bx:xs)for(int z=from;z<=to;z++)belts.add(new BlockPos(bx,1,z));
            }
            BELTS.put(type,List.copyOf(belts));
        }
    }
    private static Workplace job(int x,int y,int z,String part,String role) {
        // Leave the neighboring tile open for the worker's body and pathfinding.
        return new Workplace(new BlockPos(x,y,z),new BlockPos(x<0?x+1:x-1,y,z),x<0?Direction.EAST:Direction.WEST,part,role);
    }
    public static List<Workplace> workplaces(SiteType type){return WORK.get(type);}
    public static List<BlockPos> belts(SiteType type){return BELTS.get(type);}
    public static BlueprintBounds bounds(SiteType type) {
        var b=SolidIndustryArchitecture.bounds(type);
        return type==SiteType.POWER_STATION?new BlueprintBounds(b.minX(),b.minY(),b.minZ(),b.maxX(),26,b.maxZ()):b;
    }
    public static Map<BlockPos,BlockState> blocks(SiteType type,BlockPos origin) {
        var map=new LinkedHashMap<>(SolidIndustryArchitecture.blocks(type,BlockPos.ZERO));
        map.entrySet().removeIf(e->e.getValue().is(IndustryContent.detail("sign_factory")) || e.getValue().is(IndustryContent.detail("sign_power")) || e.getValue().is(IndustryContent.detail("sign_research"))
            || e.getValue().is(IndustryContent.detail("conveyor")) || (type.isFactory()||type==SiteType.RESEARCH_CENTER)&&workstation(e.getValue()));
        for(var job:workplaces(type)) map.put(job.equipment(),IndustryContent.detail(job.part()).defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,job.facing()));
        for(var p:belts(type))map.put(p,FunctionalBuildings.belt(Direction.SOUTH));
        int signZ=switch(type){case POWER_STATION->-11;case SUBSTATION->3;case VEHICLE_FACTORY->-10;case MISSILE_FACTORY->-12;default->-8;};
        int signY=switch(type){case SUBSTATION->4;case RESEARCH_CENTER,DRONE_FACTORY->5;default->6;};
        map.put(new BlockPos(0,signY,signZ),FunctionalBuildings.SIGNS.get(type).get().defaultBlockState());
        if(type==SiteType.RESEARCH_CENTER) {
            // The last stair used to end over a hole; add the complete landing.
            for(int x=-6;x<=-5;x++)map.put(new BlockPos(x,6,6),Blocks.SMOOTH_QUARTZ.defaultBlockState());
            for(int x:new int[]{-3,0,3})map.put(new BlockPos(x,7,5),IndustryContent.detail("server").defaultBlockState());
            // Access lighting on the stairs and both floors.
            map.put(new BlockPos(-6,10,4),Blocks.SEA_LANTERN.defaultBlockState());
        }
        if(type==SiteType.POWER_STATION) {
            // Real 3x3 hollow flues, joined to the boiler roof, with open smoke outlets.
            map.entrySet().removeIf(e->e.getKey().getY()>=11 && (e.getValue().is(IndustryContent.detail("chimney_white"))||e.getValue().is(IndustryContent.detail("chimney_red"))||e.getValue().is(IndustryContent.detail("chimney_cap"))));
            for(int cx:new int[]{-6,0})for(int y=11;y<=26;y++)for(int x=cx-1;x<=cx+1;x++)for(int z=6;z<=8;z++) {
                if(x==cx && z==7)continue;
                map.put(new BlockPos(x,y,z),(y==26?Blocks.POLISHED_DEEPSLATE:(y>=15&&y<=17||y>=23&&y<=25)?Blocks.RED_CONCRETE:Blocks.WHITE_CONCRETE).defaultBlockState());
            }
        }
        Map<BlockPos,BlockState> result=new LinkedHashMap<>();map.forEach((p,s)->result.put(origin.offset(p),s));return result;
    }
    private static boolean workstation(BlockState s) {
        for(String part:new String[]{"machine","press","laboratory","server","drone_bench","rack","missile_fixture"})if(s.is(IndustryContent.detail(part)))return true;
        return false;
    }
    public static Map<BlockPos,BlockState> criticalBlocks(SiteType type,BlockPos origin){return SolidIndustryArchitecture.criticalBlocks(type,origin);}
}
