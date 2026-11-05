package dev.frontier.industry.architecture;

import dev.frontier.industry.SiteType;
import dev.frontier.industry.buildings.ProcessEquipment;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

/** V16 repairs misplaced decor while leaving the saved v15 blueprint intact. */
public final class V16RepairArchitecture {
    private static final Map<SiteType,Map<BlockPos,BlockState>> CACHE=new EnumMap<>(SiteType.class);
    public static Map<BlockPos,BlockState> relative(SiteType t){return CACHE.computeIfAbsent(t,V16RepairArchitecture::design);}
    private static Map<BlockPos,BlockState> design(SiteType t){
        var map=new LinkedHashMap<>(V15DecorArchitecture.relative(t));
        var bounds=CampusIndustryArchitecture.baseBounds(t);
        // V15's blanket exterior accents appeared in grass and on the entrance road.
        for(int x:new int[]{bounds.minX()+3,bounds.maxX()-3})for(int dz=0;dz<=2;dz++){
            var p=new BlockPos(x,1,bounds.maxZ()-2+dz);var s=map.get(p);
            if(s!=null&&id(s).startsWith("w2d:"))map.remove(p);
        }
        for(var e:new ArrayList<>(map.entrySet())){
            var p=e.getKey();var name=id(e.getValue());
            if(name.equals("w2d:candelabrum")||name.equals("w2d:dcamoslab")||name.equals("w2d:dcamodoor")){map.remove(p);continue;}
            if(name.equals("w2d:radio")&&p.getY()==2&&t.isFactory()&&map.getOrDefault(p.below(),Blocks.AIR.defaultBlockState()).isAir())map.remove(p);
            // Standalone bench instruments and papers need an actual table, not air.
            boolean tabletop=name.equals("frontier_industry:campus_microscopy")||name.equals("frontier_industry:campus_chemistry")||name.equals("frontier_industry:campus_electronics")||name.equals("w2d:book")||name.equals("w2d:papers");
            if(tabletop&&map.getOrDefault(p.below(),Blocks.AIR.defaultBlockState()).isAir())map.remove(p);
            if(e.getValue().getBlock() instanceof net.minecraft.world.level.block.FlowerPotBlock && map.getOrDefault(p.below(),Blocks.AIR.defaultBlockState()).isAir())map.remove(p);
        }
        if(t==SiteType.RESEARCH_CENTER){
            // Repair the underside of the roof; replace unsupported raised shrubs with planted pots on the roof.
            for(int z=-1;z<=3;z++)repairPlanter(map,16,z);
            for(int x=6;x<=9;x++)repairPlanter(map,x,13);
        }
        return Collections.unmodifiableMap(map);
    }
    private static void repairPlanter(Map<BlockPos,BlockState> map,int x,int z){
        var soil=new BlockPos(x,7,z);var plant=soil.above();
        if(map.getOrDefault(soil,Blocks.AIR.defaultBlockState()).is(Blocks.ROOTED_DIRT)){
            map.put(soil,Blocks.SMOOTH_QUARTZ.defaultBlockState());
            var s=map.get(plant);if(s!=null&&(s.is(Blocks.AZALEA)||s.is(Blocks.FLOWERING_AZALEA)))map.put(plant,Blocks.POTTED_FERN.defaultBlockState());
        }
    }
    private static String id(BlockState s){ResourceLocation id=ForgeRegistries.BLOCKS.getKey(s.getBlock());return id==null?"":id.toString();}
}
