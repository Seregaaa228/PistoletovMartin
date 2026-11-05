package dev.frontier.industry.architecture;
import dev.frontier.industry.*;
import dev.frontier.industry.buildings.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
/** V10: lightweight metalwork and specialist stations, without the stone portals. */
public final class WorkshopIndustryArchitecture {
    private static final Map<SiteType,Map<BlockPos,BlockState>> CACHE=new EnumMap<>(SiteType.class);
    public static Map<BlockPos,BlockState> relative(SiteType t){return CACHE.computeIfAbsent(t,WorkshopIndustryArchitecture::design);}
    private static Map<BlockPos,BlockState> design(SiteType t){var map=new LinkedHashMap<>(ReworkedIndustryArchitecture.relative(t));FactoryDetailUpgrade.change(map,t,10);
        if(t.isFactory())for(int c:FinalIndustryArchitecture.centres(t)){
            int z=FinalIndustryArchitecture.start(t)+5;String part=switch(t){case VEHICLE_FACTORY->"welding_cart";case DRONE_FACTORY->"avionics_rack";case EQUIPMENT_FACTORY->"hydraulic_press";default->"safety_cage";};
            put(map,c-7,1,z,part);if(t!=SiteType.VEHICLE_FACTORY)put(map,c-7,2,z,t==SiteType.EQUIPMENT_FACTORY?"press_head":t==SiteType.DRONE_FACTORY?"instrument_wall":part);
            if(t==SiteType.VEHICLE_FACTORY||t==SiteType.EQUIPMENT_FACTORY){put(map,c+6,1,FinalIndustryArchitecture.start(t)+2,"assembly_robot");put(map,c+6,2,FinalIndustryArchitecture.start(t)+2,"robot_arm");}
            put(map,c+8,1,FinalIndustryArchitecture.end(t)-1,"cable_spool");
        }
        if(t==SiteType.RESEARCH_CENTER){for(int x:new int[]{-10,0})put(map,x,4,13,"instrument_wall");put(map,14,4,13,"ventilation_fan");}
        if(t==SiteType.POWER_STATION){for(int z:new int[]{-7,1,9}){put(map,12,5,z,"instrument_wall");var p=new BlockPos(12,5,z);map.put(p,map.get(p).setValue(BlockStateProperties.FACING,Direction.WEST));}for(int x:new int[]{-20,-8,4})put(map,x,10,15,"ventilation_fan");put(map,17,1,14,"cable_spool");}
        return Collections.unmodifiableMap(map);
    }
    private static void put(Map<BlockPos,BlockState> map,int x,int y,int z,String kind){map.put(new BlockPos(x,y,z),FunctionalBuildings.EQUIPMENT.get(kind).get().defaultBlockState().setValue(BlockStateProperties.FACING,Direction.NORTH));}
}
