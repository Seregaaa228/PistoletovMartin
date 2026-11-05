package dev.frontier.industry.architecture;

import dev.frontier.industry.*;
import dev.frontier.industry.buildings.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** V13: planted entrance courts, sheltered seating and decorated wall bays. Frozen v12 is retained. */
public final class GardenIndustryArchitecture {
    private static final Map<SiteType,Map<BlockPos,BlockState>> CACHE=new EnumMap<>(SiteType.class);
    public static Map<BlockPos,BlockState> relative(SiteType t){return CACHE.computeIfAbsent(t,GardenIndustryArchitecture::design);}
    private static Map<BlockPos,BlockState> design(SiteType t){
        var m=new LinkedHashMap<>(LaboratoryIndustryArchitecture.relative(t));
        var f=CampusIndustryArchitecture.baseBounds(t);var old=ReworkedIndustryArchitecture.baseBounds(t);
        var protectedCells=new HashSet<BlockPos>();
        for(var j:FinalIndustryArchitecture.workplaces(t,12))for(int y=0;y<=2;y++)for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)protectedCells.add(j.stand().offset(dx,y,dz));
        // Continuous greenery on the exterior side walks. Road, gate and loading dock stay open.
        int edgeX=old.maxX()-1;
        for(int side:new int[]{-1,1})for(int z=old.minZ()+2;z<old.maxZ()-1;z++){
            int x=side*edgeX;var p=new BlockPos(x,1,z);
            if(!empty(m,p)||!empty(m,p.above())||protectedCells.contains(p))continue;
            if(Math.floorMod(z,9)==0){prop(m,p,"post_lantern");continue;}
            if(Math.floorMod(z,9)==1)continue;
            m.put(p.below(),Blocks.ROOTED_DIRT.defaultBlockState());
            m.put(p,Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT,true));
            if(Math.floorMod(z,3)==0)m.put(p.above(),Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT,true));
        }
        // Flower beds frame the entrance without forming a barrier across it.
        for(int side:new int[]{-1,1})for(int dx=7;dx<=Math.min(17,old.maxX()-3);dx++){
            var p=new BlockPos(side*dx,1,old.minZ()+1);if(!empty(m,p)||protectedCells.contains(p))continue;
            m.put(p.below(),Blocks.GRASS_BLOCK.defaultBlockState());m.put(p,(dx%3==0?Blocks.ALLIUM:dx%3==1?Blocks.AZURE_BLUET:Blocks.RED_TULIP).defaultBlockState());
        }
        if(t.isFactory()){
            // Interior perimeter bays: plants, stocked tool cabinets and small maintenance desks.
            for(int side:new int[]{-1,1})for(int z:new int[]{-11,-4,4,11}){
                var p=new BlockPos(side*(old.maxX()-6),1,z);
                if(empty(m,p)&&empty(m,p.above())&&!protectedCells.contains(p))part(m,p,"campus_green_planter");
            }
            for(int side:new int[]{-1,1}){
                var bench=new BlockPos(side*(old.maxX()-8),1,old.minZ()+2);if(empty(m,bench)&&!protectedCells.contains(bench))prop(m,bench,"courtyard_bench");
                // An actual output container at the dispatch entrance, not a decorative cargo prop.
                var box=new BlockPos(side*5,1,old.maxZ()+10);if(empty(m,box))m.put(box,FunctionalBuildings.CONTAINER.get().defaultBlockState());
            }
        }
        if(t==SiteType.RESEARCH_CENTER){
            for(int z=-8;z<=11;z++){
                var p=new BlockPos(14,8,z);if(empty(m,p)&&empty(m,p.above())){m.put(p,Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT,true));}
            }
            // Reception and upstairs lounge have distinct planted corners.
            for(var p:List.of(new BlockPos(-14,1,-10),new BlockPos(14,1,-10),new BlockPos(-13,8,11)))if(empty(m,p))part(m,p,"campus_green_planter");
        }
        // Guard against unaccounted footprint expansion and accidental door decoration.
        if(m.keySet().stream().anyMatch(p->!f.contains(p.getX(),p.getY(),p.getZ())))throw new IllegalStateException("Garden outside blueprint "+t);
        return Collections.unmodifiableMap(m);
    }
    private static boolean empty(Map<BlockPos,BlockState> m,BlockPos p){return !m.containsKey(p)||m.get(p).isAir();}
    private static void part(Map<BlockPos,BlockState> m,BlockPos p,String kind){m.put(p,FunctionalBuildings.EQUIPMENT.get(kind).get().defaultBlockState());}
    private static void prop(Map<BlockPos,BlockState> m,BlockPos p,String kind){m.put(p,FunctionalBuildings.PROPS.get(kind).get().defaultBlockState());}
}
