package dev.frontier.industry.architecture;

import dev.frontier.industry.*;
import dev.frontier.industry.buildings.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Version 7 is additive interior dressing. Saved v6 geometry remains byte-for-byte stable. */
public final class InteriorDecorations {
    private static final Map<SiteType,Map<BlockPos,BlockState>> CACHE=new EnumMap<>(SiteType.class);
    private InteriorDecorations(){}
    public static Map<BlockPos,BlockState> relative(SiteType type){return CACHE.computeIfAbsent(type,InteriorDecorations::design);}
    private static Map<BlockPos,BlockState> design(SiteType t){
        var b=new Dressing(t);
        if(t.isFactory())factory(b,t);
        else switch(t){case RESEARCH_CENTER->research(b);case POWER_STATION->power(b);case SUBSTATION->substation(b);default->throw new IllegalStateException();}
        return Collections.unmodifiableMap(b.map);
    }
    private static void factory(Dressing b,SiteType t){
        var f=FinalIndustryArchitecture.baseBounds(t);int left=f.minX()+3,right=f.maxX()-3,front=f.minZ()+3,rear=f.maxZ()-3;
        // Each station has its own supplies or tools behind the operator, not on the belt.
        for(var job:FinalIndustryArchitecture.workplaces(t,6))if(job.line()>=0){
            var p=job.equipment().relative(job.facing().getOpposite());
            b.prop(p,job.role().equals("assembly")?"tool_cabinet":job.role().equals("quality")?"parts_bin":"materials_rack",job.facing());
            b.floor(job.stand(),Blocks.LIGHT_GRAY_CONCRETE);
        }
        // Wall storage and repair bays leave the central and longitudinal aisles open.
        for(int z=front+5;z<=rear-3;z+=6){
            b.prop(left,1,z,"materials_rack",Direction.EAST);b.prop(right,1,z,"tool_cabinet",Direction.WEST);
            b.prop(left,3,z,"wall_panel",Direction.EAST);b.prop(right,3,z,"wall_panel",Direction.WEST);
        }
        for(int cx:FinalIndustryArchitecture.centres(t)){
            for(int x=cx-1;x<=cx+1;x++){
                b.floor(new BlockPos(x,1,FinalIndustryArchitecture.start(t)-4),Blocks.CYAN_CONCRETE);
                b.floor(new BlockPos(x,1,FinalIndustryArchitecture.end(t)+3),Blocks.ORANGE_CONCRETE);
            }
            b.prop(cx-5,1,FinalIndustryArchitecture.end(t)+2,"parts_bin",Direction.NORTH);
            b.prop(cx+5,1,FinalIndustryArchitecture.start(t)-3,"sealed_drum",Direction.NORTH);
        }
        for(int x:new int[]{left+2,right-2})for(int z:new int[]{front+2,rear-1}){
            b.prop(x,1,z,"pallet",Direction.NORTH);b.prop(x+(x<0?1:-1),1,z,"parts_bin",Direction.NORTH);
        }
        // Cable trays run at ceiling height and terminate in wall-mounted posts.
        int y=t==SiteType.MISSILE_FACTORY?8:t==SiteType.VEHICLE_FACTORY?7:5;
        for(int x:new int[]{left,right}){
            for(int z=front+2;z<=rear-2;z++)b.solid(x,y,z,Blocks.CUT_COPPER);
            for(int z:new int[]{front+2,rear-2})for(int k=1;k<y;k++)b.solid(x,k,z,Blocks.POLISHED_DEEPSLATE);
        }
        // A small staffed dispatch / safety corner, with equipment mounted on a real backing.
        b.prop(left+3,1,front+2,"tool_cabinet",Direction.SOUTH);
        b.prop(right-3,1,rear-1,"materials_rack",Direction.NORTH);
    }
    private static void research(Dressing b){
        // Reception counters, visitor seating and a separated records bay.
        for(int x:new int[]{-11,-10,10,11})b.solid(x,1,-10,Blocks.SMOOTH_QUARTZ);
        for(int x:new int[]{-14,14}){
            b.prop(x,1,-12,"tool_cabinet",Direction.SOUTH);
            b.solid(x,1,-8,Blocks.SMOOTH_QUARTZ);b.solid(x,2,-8,Blocks.FLOWER_POT);
        }
        for(var job:FinalIndustryArchitecture.workplaces(SiteType.RESEARCH_CENTER,6))if(job.role().equals("scientist")){
            var p=job.equipment().west();b.prop(p,"parts_bin",Direction.EAST);
            b.floor(job.stand(),Blocks.LIGHT_BLUE_CONCRETE);
        }
        // Server racks have a full maintenance aisle on the inner side.
        for(int z:new int[]{-3,1,5,9})b.prop(15,1,z,"research_server",Direction.WEST);
        b.prop(6,1,8,"tool_cabinet",Direction.EAST);b.prop(6,1,4,"materials_rack",Direction.EAST);
        for(int z:new int[]{-4,4,10})b.prop(16,4,z,"wall_panel",Direction.WEST);
        // Analytics reference library, tables with supplies, plants and light on the terrace.
        for(int z:new int[]{-3,3,9})b.prop(-15,8,z,"materials_rack",Direction.EAST);
        for(int x:new int[]{-8,-2})for(int z:new int[]{2,8})b.prop(x-1,8,z,"parts_bin",Direction.EAST);
        for(int x:new int[]{-9,0}){b.solid(x,8,11,Blocks.SMOOTH_QUARTZ);b.solid(x,9,11,Blocks.FLOWER_POT);}
        for(int z:new int[]{-3,12})b.floor(new BlockPos(11,8,z),Blocks.SEA_LANTERN);
        for(int z:new int[]{2,9})b.prop(6,8,z,"parts_bin",Direction.EAST);
    }
    private static void power(Dressing b){
        // Switchboard wall, spare parts and boiler service tools.
        for(int z:new int[]{-9,-5,-1}){
            b.prop(-13,1,z,"tool_cabinet",Direction.EAST);b.prop(-13,3,z,"wall_panel",Direction.EAST);
        }
        for(int x:new int[]{-11,-8,-4,3})b.prop(x,1,11,"parts_bin",Direction.NORTH);
        for(int z:new int[]{-9,-7})b.prop(14,1,z,"sealed_drum",Direction.WEST);
        b.prop(14,1,10,"materials_rack",Direction.WEST);
        for(int x=-11;x<=1;x++)b.solid(x,7,-11,Blocks.CUT_COPPER);
        for(int x:new int[]{-11,1})for(int y=1;y<7;y++)b.solid(x,y,-11,Blocks.POLISHED_DEEPSLATE);
    }
    private static void substation(Dressing b){
        b.prop(-5,1,6,"tool_cabinet",Direction.EAST);b.prop(5,1,6,"materials_rack",Direction.WEST);
        b.prop(0,3,8,"wall_panel",Direction.NORTH);
        b.prop(-8,1,-5,"sealed_drum",Direction.EAST);
        b.prop(8,1,-5,"parts_bin",Direction.WEST);
        for(int y=1;y<=3;y++)b.solid(-8,y,-3,Blocks.POLISHED_DEEPSLATE);
        b.prop(-8,2,-4,"wall_panel",Direction.NORTH);
    }
    private static final class Dressing {
        final Map<BlockPos,BlockState> map;final Set<BlockPos> protectedAir=new HashSet<>();
        Dressing(SiteType t){
            map=new LinkedHashMap<>(FinalIndustryArchitecture.blocks(t,BlockPos.ZERO,6));
            for(var job:FinalIndustryArchitecture.workplaces(t,6))for(int y=0;y<2;y++){
                var p=job.stand().above(y);protectedAir.add(p);for(var d:Direction.Plane.HORIZONTAL)protectedAir.add(p.relative(d));
            }
            var entry=FinalIndustryArchitecture.entrance(t,BlockPos.ZERO,6);
            for(int z=entry.getZ();z<=entry.getZ()+5;z++)for(int x=-1;x<=1;x++)for(int y=1;y<=3;y++)protectedAir.add(new BlockPos(x,y,z));
        }
        void floor(BlockPos standing,Block block){var p=standing.below();if(map.containsKey(p))map.put(p,block.defaultBlockState());}
        boolean free(BlockPos p){return !map.containsKey(p)&&!protectedAir.contains(p);}
        void solid(int x,int y,int z,Block block){var p=new BlockPos(x,y,z);if(free(p))map.put(p,block.defaultBlockState());}
        void prop(int x,int y,int z,String id,Direction facing){prop(new BlockPos(x,y,z),id,facing);}
        void prop(BlockPos p,String id,Direction facing){
            boolean tall=id.equals("tool_cabinet")||id.equals("materials_rack")||id.equals("research_server");
            if(!free(p)||tall&&!free(p.above()))return;
            map.put(p,FunctionalBuildings.PROPS.get(id).get().defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,facing));
        }
    }
}
