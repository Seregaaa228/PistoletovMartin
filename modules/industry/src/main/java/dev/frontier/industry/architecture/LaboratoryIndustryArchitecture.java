package dev.frontier.industry.architecture;

import dev.frontier.industry.*;
import dev.frontier.industry.buildings.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** V12: usable stock containers, small arms assembly and a planted research campus. V11 stays frozen. */
public final class LaboratoryIndustryArchitecture {
    private static final Map<SiteType,Map<BlockPos,BlockState>> CACHE=new EnumMap<>(SiteType.class);
    public static Map<BlockPos,BlockState> relative(SiteType t){return CACHE.computeIfAbsent(t,LaboratoryIndustryArchitecture::design);}
    private static Map<BlockPos,BlockState> design(SiteType t){
        var m=new LinkedHashMap<>(CampusIndustryArchitecture.relative(t));
        if(t.isFactory()){
            // Every crate is an access point to the same authoritative finished-goods stock.
            for(var e:new ArrayList<>(m.entrySet()))if(e.getValue().getBlock() instanceof ProcessEquipment p&&Set.of("campus_cargo_crate","campus_storage_rack").contains(p.kind))m.put(e.getKey(),FunctionalBuildings.CONTAINER.get().defaultBlockState());
            if(t==SiteType.EQUIPMENT_FACTORY){
                m.entrySet().removeIf(e->e.getValue().getBlock() instanceof ProcessEquipment p&&Set.of("mock_gun_breech","mock_gun_barrel","mock_gun_mount").contains(p.kind));
                String[] weapons={"campus_rifle_display","campus_rpg_display","campus_pistol_display"};int i=0;
                for(int c:FinalIndustryArchitecture.centres(t)){
                    var display=i==0?FunctionalBuildings.GUN_RIFLE_DISPLAY.get():i==1?FunctionalBuildings.GUN_RPG_DISPLAY.get():FunctionalBuildings.GUN_PISTOL_DISPLAY.get();
                    for(int z:new int[]{-3,3,8})m.put(new BlockPos(c,2,z),display.defaultBlockState());
                    // Matching finished weapons at inspection benches, supported by real steel tables.
                    put(m,c+2,1,12,"lab_bench");m.put(new BlockPos(c+2,2,12),display.defaultBlockState());i++;
                }
            }
            // Keep planted borders outside the assembly and walking lanes.
            var f=ReworkedIndustryArchitecture.baseBounds(t);
            for(int x:new int[]{f.minX()+2,f.maxX()-2})for(int z:new int[]{f.minZ()+2,f.maxZ()-2})plant(m,x,1,z);
        }
        if(t==SiteType.RESEARCH_CENTER)research(m);
        return Collections.unmodifiableMap(m);
    }
    private static void research(Map<BlockPos,BlockState> m){
        // Remove the inherited empty pots, their quartz pedestals, and abstract display samples.
        for(var e:new ArrayList<>(m.entrySet())){
            var p=e.getKey();var s=e.getValue();
            if(s.is(Blocks.FLOWER_POT)){m.remove(p);if(m.getOrDefault(p.below(),Blocks.AIR.defaultBlockState()).is(Blocks.SMOOTH_QUARTZ))m.remove(p.below());plant(m,p.getX(),p.getY()-1,p.getZ());}
            if(s.getBlock() instanceof ProcessEquipment part&&part.kind.equals("campus_specimen"))m.remove(p);
        }
        for(int z:new int[]{-6,0,6}){put(m,-14,2,z,z==0?"campus_chemistry":"campus_microscopy");}
        put(m,15,2,-1,"campus_chemistry");
        // Instruments are grouped by task, while all 16 saved worker stands remain unchanged.
        for(int x:new int[]{-8,-2})for(int z:new int[]{-2,2,6,10}){
            String kind=x==-8?"campus_microscopy":"campus_electronics";
            // Preserve the interactive lab console at the saved job that uses it.
            if(!(x==-2&&z==6))put(m,x,2,z,kind);
            put(m,x+1,2,z,x==-8?"campus_chemistry":"campus_electronics");
        }
        // The upstairs outdoor area is a planted terrace with benches, rather than computer pods.
        m.entrySet().removeIf(e->e.getKey().getY()>=8&&e.getKey().getY()<=10&&e.getKey().getX()>=3&&
            (e.getValue().getBlock() instanceof WorkshopProp p&&Set.of("coffee_table","lounge_chair","operator_console").contains(p.kind)
            ||e.getValue().getBlock() instanceof ProcessEquipment part&&Set.of("campus_blueprint","campus_specimen").contains(part.kind)));
        for(int z:new int[]{-7,-1,5,11}){plant(m,14,8,z);plant(m,4,8,z);}
        for(int z:new int[]{-4,3,10}){prop(m,7,8,z,"courtyard_bench");prop(m,10,8,z,"coffee_table");}
        for(int x:new int[]{-15,15})for(int z:new int[]{-17,-12})plant(m,x,1,z);
        for(int x:new int[]{-11,11})for(int z:new int[]{-16,-17})plant(m,x,1,z);
        for(int x:new int[]{-14,14})plant(m,x,1,12);
    }
    private static void plant(Map<BlockPos,BlockState> m,int x,int y,int z){
        put(m,x,y,z,"campus_green_planter");
    }
    private static void put(Map<BlockPos,BlockState> m,int x,int y,int z,String kind){m.put(new BlockPos(x,y,z),FunctionalBuildings.EQUIPMENT.get(kind).get().defaultBlockState());}
    private static void prop(Map<BlockPos,BlockState> m,int x,int y,int z,String kind){m.put(new BlockPos(x,y,z),FunctionalBuildings.PROPS.get(kind).get().defaultBlockState());}
}
