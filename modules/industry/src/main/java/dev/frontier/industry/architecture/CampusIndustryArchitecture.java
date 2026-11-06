package dev.frontier.industry.architecture;

import dev.frontier.industry.*;
import dev.frontier.industry.buildings.*;
import dev.frontier.industry.engine.BlueprintBounds;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.registries.ForgeRegistries;

/** Immutable v11 campus: distinct halls, physical product mockups and a shared-output warehouse. */
public final class CampusIndustryArchitecture {
    private static final Map<SiteType,Map<BlockPos,BlockState>> CACHE=new EnumMap<>(SiteType.class);
    private CampusIndustryArchitecture(){}
    public static BlueprintBounds baseBounds(SiteType t){var b=ReworkedIndustryArchitecture.baseBounds(t);return t.isFactory()?new BlueprintBounds(b.minX(),0,b.minZ()-(t==SiteType.VEHICLE_FACTORY?12:4),b.maxX(),b.maxY(),b.maxZ()+14):b;}
    public static Map<BlockPos,BlockState> relative(SiteType t){return CACHE.computeIfAbsent(t,CampusIndustryArchitecture::design);}
    public static List<BlockPos> warehousePoints(SiteType t,int v){if(!t.isFactory()||v%100<11)return List.of();int z=ReworkedIndustryArchitecture.baseBounds(t).maxZ()+5;return List.of(new BlockPos(-9,1,z),new BlockPos(9,1,z)).stream().map(p->BuildingOrientation.rotate(p,v)).toList();}
    private static Map<BlockPos,BlockState> design(SiteType t){var b=new Builder(t);if(t.isFactory()){factory(b,t);warehouse(b,t);}else if(t==SiteType.RESEARCH_CENTER)research(b);else service(b,t);return Collections.unmodifiableMap(b.map);}
    private static void factory(Builder b,SiteType t){
        var f=ReworkedIndustryArchitecture.baseBounds(t);int z=FinalIndustryArchitecture.start(t)+3;
        // Large silhouettes sit on the assembly lanes; the aisle and all saved worker stands stay clear.
        switch(t){
            case VEHICLE_FACTORY->{plane(b,0,z);vehicle(b,-16,z+2);vehicle(b,16,z+2);}
            case DRONE_FACTORY->{plane(b,-14,z);quadcopter(b,0,z+3);quadcopter(b,14,z+3);}
            case EQUIPMENT_FACTORY->{for(int c:FinalIndustryArchitecture.centres(t)){b.part(c,2,z+2,"mock_gun_breech");for(int zz=z-1;zz<=z+1;zz++)b.part(c,2,zz,"mock_gun_barrel");b.part(c,2,z+3,"mock_gun_mount");b.part(c+2,2,z+4,"campus_ammo_case");}}
            case MISSILE_FACTORY->{for(int c:FinalIndustryArchitecture.centres(t))rocket(b,c,z);}
            default->{}
        }
        BlockState walls=t==SiteType.DRONE_FACTORY?Blocks.WHITE_CONCRETE.defaultBlockState():t==SiteType.MISSILE_FACTORY?Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState():Builder.ed(t==SiteType.EQUIPMENT_FACTORY?"clinker_brick_stained_block":"clinker_brick_block");
        for(var e:new ArrayList<>(b.map.entrySet()))if(e.getValue().is(Blocks.BRICKS))b.map.put(e.getKey(),walls);
        // Supported inspection stations at the outer walls; nothing is scattered across the gate.
        for(int side:new int[]{-1,1}){
            int x=side*(f.maxX()-4);b.ed(x,1,2,"steel_table");b.part(x,2,2,"campus_blueprint");b.partFacing(side*(f.maxX()-3),2,4,"campus_toolboard",side>0?Direction.WEST:Direction.EAST);
            b.ed(x,1,7,"treated_wood_stool");b.facing(side*(f.maxX()-3),3,8,"sign_caution",side>0?Direction.WEST:Direction.EAST);
        }
        int front=f.minZ(),half=t==SiteType.VEHICLE_FACTORY?8:4,length=t==SiteType.VEHICLE_FACTORY?12:4;
        for(int x=-half-2;x<=half+2;x++)for(int zz=front-length;zz<=front;zz++)b.set(x,0,zz,Math.abs(x)==half?Blocks.YELLOW_CONCRETE:Math.abs(x)>half?Blocks.SMOOTH_STONE:x==0&&Math.floorMod(zz,4)<2?Blocks.WHITE_CONCRETE:Blocks.GRAY_CONCRETE);
        for(int side:new int[]{-1,1})for(int zz:new int[]{front-length+1,front-1}){int x=side*(half+1);b.part(x,1,zz,"campus_bollard");b.prop(x,0,zz,"floor_lamp");}
        for(int zz=front-length+2;zz<front;zz+=4)b.prop(0,0,zz,"floor_lamp");
        // A vehicle exit has road signs and a service apron, the other factories a compact dispatch entrance.
        if(t==SiteType.VEHICLE_FACTORY){b.part(-half-2,1,front-7,"campus_road_sign");b.part(half+2,1,front-7,"campus_road_sign");}
    }
    private static void plane(Builder b,int x,int z){
        b.part(x,2,z,"mock_air_nose");b.part(x,2,z+1,"mock_air_canopy");
        for(int zz=z+2;zz<=z+5;zz++)b.part(x,2,zz,"mock_air_body");
        b.part(x,2,z+6,"mock_air_tail");b.part(x,3,z+6,"mock_air_fin");
        for(int dx=-3;dx<=3;dx++)if(dx!=0)b.part(x+dx,2,z+3,Math.abs(dx)==3?"mock_wing_tip":"mock_air_wing");
        for(int dx:new int[]{-1,1})b.part(x+dx,2,z+6,"mock_air_wing");
    }
    private static void vehicle(Builder b,int x,int z){
        for(int dx=-1;dx<=1;dx++)for(int zz=z;zz<=z+4;zz++)b.part(x+dx,2,zz,dx==0?"mock_vehicle_hull":"mock_vehicle_tracks");
        b.part(x,3,z+2,"mock_vehicle_turret");b.part(x,3,z+1,"mock_gun_barrel");
    }
    private static void quadcopter(Builder b,int x,int z){b.part(x,2,z,"mock_drone_body");for(int dx:new int[]{-1,1})for(int dz:new int[]{-1,1}){b.part(x+dx,2,z,"mock_drone_arm");b.part(x+dx,2,z+dz,"mock_drone_arm");b.part(x+dx*2,2,z+dz,"mock_drone_arm");b.part(x+dx*2,2,z+dz*2,"mock_drone_rotor");}}
    private static void rocket(Builder b,int x,int z){b.part(x,2,z,"mock_rocket_nose");for(int zz=z+1;zz<=z+7;zz++)b.part(x,2,zz,"mock_rocket_body");b.part(x,2,z+8,"mock_rocket_engine");for(int dx:new int[]{-1,1})b.part(x+dx,2,z+7,"mock_rocket_fin");}
    private static void warehouse(Builder b,SiteType t){
        var f=ReworkedIndustryArchitecture.baseBounds(t);int from=f.maxZ()-2,to=f.maxZ()+11;
        b.fill(-16,0,from,16,0,to+3,Blocks.SMOOTH_STONE);
        var wall=t==SiteType.DRONE_FACTORY?Blocks.WHITE_CONCRETE.defaultBlockState():t==SiteType.MISSILE_FACTORY?Blocks.GRAY_CONCRETE.defaultBlockState():Builder.ed("rebar_concrete");
        for(int x=-15;x<=15;x++)for(int z=from;z<=to;z++){
            if(x==-15||x==15||z==to||z==from)for(int y=1;y<=5;y++)b.map.put(new BlockPos(x,y,z),y==3&&Math.floorMod(x+z,5)>1?Blocks.GLASS.defaultBlockState():wall);
            b.set(x,6,z,Blocks.SMOOTH_STONE);
        }
        b.open(-3,1,f.maxZ()-3,3,4,from+1);b.open(-3,1,to,3,4,to);
        b.fill(-3,5,to,3,5,to,Blocks.CYAN_CONCRETE);
        b.part(0,5,to+1,"campus_dispatch_sign");
        for(int x:new int[]{-9,9}){
            b.map.put(new BlockPos(x,1,from+7),FunctionalBuildings.WAREHOUSE.get().defaultBlockState());
            for(int z:new int[]{from+3,from+10})for(int xx=x-2;xx<=x+2;xx++)for(int y=1;y<=3;y++)b.part(xx,y,z,y==2?"campus_cargo_crate":"campus_storage_rack");
            b.ed(x,1,from+5,"steel_table");b.part(x,2,from+5,"campus_dispatch_screen");
        }
        b.part(-5,1,to+1,"campus_pallet_jack");b.part(5,1,to+1,"campus_pallet_jack");
        for(int x=-13;x<=13;x+=4)for(int z=from+1;z<=to+2;z+=4){b.prop(x,0,z,"floor_lamp");if(z<to){b.prop(x,5,z,"mounted_lamp");b.prop(x,6,z,"floor_lamp");}}
        // Railings sit on the exterior service apron, never replacing the wall blocks.
        for(int x:new int[]{-17,17})for(int z=from;z<=to+2;z+=3)b.ed(x,1,z,"steel_railing");
    }
    private static void research(Builder b){
        for(int z:new int[]{-6,0,6}){b.ed(-14,1,z,"steel_table");b.part(-14,2,z,"campus_specimen");b.partFacing(-16,4,z,"campus_poster",Direction.EAST);}
        for(int x:new int[]{4,9}){b.prop(x,8,10,"coffee_table");b.prop(x,8,9,"lounge_chair");b.part(x,9,10,"campus_blueprint");}
        b.ed(15,1,-3,"small_lab_furnace");b.ed(15,1,-1,"steel_table");b.part(15,2,-1,"campus_specimen");
        for(int z:new int[]{0,5,10})b.partFacing(-15,11,z,"campus_poster",Direction.EAST);
    }
    private static void service(Builder b,SiteType t){
        if(t==SiteType.POWER_STATION){for(int z:new int[]{-10,-6}){b.ed(24,1,z,"steel_table");b.part(24,2,z,"campus_service_tools");}b.facing(12,4,-10,"sign_hotwire",Direction.WEST);}
        else{b.ed(-8,1,3,"steel_table");b.part(-8,2,3,"campus_service_tools");b.ed(8,1,3,"thin_steel_pole");b.ed(8,2,3,"thin_steel_pole");b.ed(8,3,3,"sign_hotwire");}
    }
    private static final class Builder {
        final Map<BlockPos,BlockState> map;Builder(SiteType t){map=new LinkedHashMap<>(WorkshopIndustryArchitecture.relative(t));}
        static BlockState ed(String id){var key=new ResourceLocation("engineersdecor",id);var block=ForgeRegistries.BLOCKS.getValue(key);if(block==null||block==Blocks.AIR)throw new IllegalStateException("Required Engineer's Decor block missing: "+key);return block.defaultBlockState();}
        void ed(int x,int y,int z,String id){map.put(new BlockPos(x,y,z),ed(id));}
        void facing(int x,int y,int z,String id,Direction direction){var s=ed(id);if(s.hasProperty(BlockStateProperties.FACING))s=s.setValue(BlockStateProperties.FACING,direction);map.put(new BlockPos(x,y,z),s);}
        void set(int x,int y,int z,Block block){map.put(new BlockPos(x,y,z),block.defaultBlockState());}
        void fill(int x,int y,int z,int xx,int yy,int zz,Block block){for(int a=x;a<=xx;a++)for(int c=z;c<=zz;c++)for(int h=y;h<=yy;h++)set(a,h,c,block);}
        void open(int x,int y,int z,int xx,int yy,int zz){map.keySet().removeIf(p->p.getX()>=x&&p.getX()<=xx&&p.getY()>=y&&p.getY()<=yy&&p.getZ()>=z&&p.getZ()<=zz);}
        void part(int x,int y,int z,String kind){map.put(new BlockPos(x,y,z),FunctionalBuildings.EQUIPMENT.get(kind).get().defaultBlockState());}
        void partFacing(int x,int y,int z,String kind,Direction direction){map.put(new BlockPos(x,y,z),FunctionalBuildings.EQUIPMENT.get(kind).get().defaultBlockState().setValue(BlockStateProperties.FACING,direction));}
        void prop(int x,int y,int z,String kind){map.put(new BlockPos(x,y,z),FunctionalBuildings.PROPS.get(kind).get().defaultBlockState());}
    }
}
