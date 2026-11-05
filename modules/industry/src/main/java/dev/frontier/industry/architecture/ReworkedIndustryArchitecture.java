package dev.frontier.industry.architecture;

import dev.frontier.industry.*;
import dev.frontier.industry.buildings.*;
import dev.frontier.industry.engine.BlueprintBounds;
import dev.frontier.industry.architecture.FinalIndustryArchitecture.Workstation;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** V9 is immutable; older saved cursors keep their original V6/V7/V8 maps. */
public final class ReworkedIndustryArchitecture {
    private static final Map<SiteType,Map<BlockPos,BlockState>> CACHE=new EnumMap<>(SiteType.class);
    private ReworkedIndustryArchitecture(){}
    public static BlueprintBounds baseBounds(SiteType type){return type==SiteType.POWER_STATION?new BlueprintBounds(-26,0,-21,26,34,21):FinalIndustryArchitecture.baseBounds(type);}
    public static BlockPos controller(SiteType t){return t==SiteType.POWER_STATION?new BlockPos(6,1,-16):FinalIndustryArchitecture.controller(t,BlockPos.ZERO,8);}
    public static List<BlockPos> smokePoints(){return java.util.stream.IntStream.of(-15,-3,9).mapToObj(x->new BlockPos(x,35,14)).toList();}
    public static List<Workstation> workplaces(SiteType t,int version){
        List<Workstation> jobs=new ArrayList<>();
        if(t.isFactory()){
            for(var j:FinalIndustryArchitecture.workplaces(t,8)){
                String part=switch(j.role()){case "supply","packing","logistics"->"parts_cart";case "maintenance"->"tool_trolley";case "quality"->"quality_console";default->switch(t){case VEHICLE_FACTORY->"airframe_jig";case DRONE_FACTORY->"drone_jig";case EQUIPMENT_FACTORY->"cnc_machine";default->"rocket_cradle";};};
                jobs.add(new Workstation(j.id(),j.role(),j.line(),j.equipment(),j.stand(),j.facing(),part,j.capacity()));
            }
        }else if(t==SiteType.RESEARCH_CENTER){
            for(int x:new int[]{-8,-2})for(int z:new int[]{-2,2,6,10})jobs.add(job("lab/"+x+"/"+z,"scientist",x,2,z,x,1,z-1,x==-8?"lab_microscope":z==6?"lab_console":"lab_analyser",Direction.NORTH));
            for(int z:new int[]{0,5})jobs.add(job("experiment/"+z,"scientist",8,2,z,8,1,z-1,"lab_console",Direction.NORTH));
            for(int x:new int[]{-8,-2})jobs.add(job("analysis/"+x,"analyst",x,9,8,x,8,7,"lab_console",Direction.NORTH));
            for(int z:new int[]{8,11})jobs.add(job("technical/"+z,"maintenance",14,1,z,13,1,z,z==11?"research_server":"heat_exchanger",Direction.WEST));
            for(int x:new int[]{-8,8})jobs.add(job("reception/"+x,"reception",x,2,-10,x,1,-11,"lab_console",Direction.NORTH));
        }else if(t==SiteType.POWER_STATION){
            for(int x:new int[]{-20,-17,-14})jobs.add(job("power/control/"+x,"engineer",x,1,-14,x,1,-13,"operator_console",Direction.SOUTH));
            for(int x:new int[]{-11,1,11})jobs.add(job("power/service/"+x,"maintenance",x,1,7,x+1,1,7,"tool_trolley",Direction.EAST));
        }else return FinalIndustryArchitecture.workplaces(t,8+100*BuildingOrientation.quarter(version));
        return jobs.stream().map(j->new Workstation(j.id(),j.role(),j.line(),BuildingOrientation.rotate(j.equipment(),version),BuildingOrientation.rotate(j.stand(),version),BuildingOrientation.rotate(j.facing(),version),j.part(),j.capacity())).toList();
    }
    private static Workstation job(String id,String role,int x,int y,int z,int sx,int sy,int sz,String part,Direction f){return new Workstation(id,role,-1,new BlockPos(x,y,z),new BlockPos(sx,sy,sz),f,part,1);}
    public static Map<BlockPos,BlockState> relative(SiteType t){return CACHE.computeIfAbsent(t,ReworkedIndustryArchitecture::design);}
    private static Map<BlockPos,BlockState> design(SiteType t){
        var b=new Builder(t);
        if(t==SiteType.POWER_STATION)power(b);else if(t==SiteType.RESEARCH_CENTER)research(b);else if(t.isFactory())factory(b,t);
        for(var job:workplaces(t,9))b.equipment(job.equipment(),job.part(),job.facing());
        b.map.put(controller(t),IndustryContent.controller(t).defaultBlockState());
        b.finishPipes();return Collections.unmodifiableMap(b.map);
    }
    private static void factory(Builder b,SiteType t){
        b.removeGeneric();
        // The supported crane, three belts and unobstructed aircraft gate remain part of V8.
        int rear=FinalIndustryArchitecture.end(t);
        for(int c:FinalIndustryArchitecture.centres(t)){
            int start=FinalIndustryArchitecture.start(t);
            switch(t){
                case VEHICLE_FACTORY->{
                    for(int z=start+5;z<=start+9;z++){b.eq(c+7,1,z,"airframe_jig");b.eq(c+7,2,z,"airframe_section");}
                    b.eq(c-7,1,rear-2,"jet_engine_stand");b.eq(c-7,1,rear-1,"jet_engine_stand");b.eq(c-7,1,start+4,"tool_trolley");
                }
                case DRONE_FACTORY->{b.eq(c+7,1,start+4,"electronics_bench");b.eq(c+7,2,start+4,"lab_analyser");b.eq(c-7,1,rear-3,"sample_cabinet");b.eq(c-7,2,rear-3,"sample_cabinet");}
                case EQUIPMENT_FACTORY->{b.eq(c+7,1,start+4,"cnc_machine");b.eq(c+7,2,start+4,"cnc_machine");b.eq(c-7,1,rear-2,"weapons_rack");b.eq(c-7,1,rear-1,"weapons_rack");}
                case MISSILE_FACTORY->{for(int z=start+4;z<=start+8;z++)b.eq(c+7,1,z,"rocket_cradle");b.eq(c-7,1,rear-3,"rocket_test_rig");b.eq(c-7,2,rear-3,"lab_analyser");}
                default->{}
            }
            // Tool station belongs to this production lane; it does not fill the rollout aisle.
            b.eq(c+6,1,rear+1,"tool_trolley");
        }
    }
    private static void research(Builder b){
        b.removeGeneric();
        // Eight instrument islands, with drawers and a separate reagent/fume-hood half.
        for(int x:new int[]{-8,-2})for(int z:new int[]{-2,2,6,10}){
            b.eq(x,1,z,"lab_bench");b.eq(x+1,1,z,"lab_bench");b.eq(x+1,2,z,x==-8?"lab_fumehood":"electronics_bench");
        }
        for(int z:new int[]{0,5}){
            b.eq(8,1,z,"lab_bench");
            for(int x=10;x<=11;x++)for(int y=1;y<=2;y++)for(int zz=z;zz<=z+1;zz++)b.eq(x,y,zz,"lab_chamber");
        }
        for(int x:new int[]{-8,-2}){b.eq(x,8,8,"lab_bench");b.eq(x+1,8,8,"lab_bench");b.eq(x+1,9,8,"lab_analyser");}
        for(int x:new int[]{-8,8}){b.eq(x,1,-10,"lab_bench");b.eq(x+1,1,-10,"lab_bench");}
        for(int z:new int[]{-4,2,8}){b.eq(1,1,z,"sample_cabinet");b.eq(1,2,z,"sample_cabinet");}
        // A small technical niche replaces the former room full of server rows.
        b.fill(12,1,7,12,4,13,Blocks.LIGHT_BLUE_STAINED_GLASS);b.open(12,1,8,12,3,10);
        for(int x:new int[]{-11,11}){b.prop(x,1,-17,"planter");b.prop(x+(x<0?-2:2),1,-17,"courtyard_bench");}
        for(int z:new int[]{0,7}){b.prop(9,8,z,"coffee_table");b.prop(7,8,z,"lounge_chair");b.prop(12,8,z,"lounge_chair");}
        // Science equipment is illuminated locally; keep the existing stair stringers and guards.
        for(int x:new int[]{-8,-2,8})for(int z:new int[]{-2,4,10})b.prop(x,6,z,"mounted_lamp");
    }
    private static void power(Builder b){
        b.fill(-26,0,-21,26,0,21,Blocks.SMOOTH_STONE);
        b.ring(-26,0,-21,26,21,Blocks.POLISHED_DEEPSLATE);
        b.shell(-23,-17,13,16,1,10);b.fill(-24,11,-18,14,11,17,Blocks.DEEPSLATE_TILES);
        for(int z:new int[]{-10,-2,6})b.fill(-20,11,z,10,11,z,Blocks.LIGHT_BLUE_STAINED_GLASS);
        b.open(-3,1,-17,3,5,-17);b.fill(-4,6,-18,4,6,-17,Blocks.POLISHED_DEEPSLATE);
        b.map.put(new BlockPos(0,7,-18),FunctionalBuildings.SIGNS.get(SiteType.POWER_STATION).get().defaultBlockState());
        // Glazed control room; the only operator desks are inside this room.
        b.fill(-22,1,-10,-13,5,-10,Blocks.LIGHT_BLUE_STAINED_GLASS);b.fill(-13,1,-16,-13,5,-10,Blocks.LIGHT_BLUE_STAINED_GLASS);
        b.open(-16,1,-10,-14,3,-10);b.fill(-22,5,-16,-14,5,-11,Blocks.SMOOTH_STONE);
        for(int x:new int[]{-15,-3,9}){
            for(int xx=x-1;xx<=x+1;xx++)for(int y=1;y<=4;y++)for(int z=7;z<=9;z++)b.eq(xx,y,z,"boiler_casing");
            b.port(x,4,6,"steam",Direction.NORTH);b.pipe("steam",x,4,5,x,5,5);
            b.port(x,2,10,"return",Direction.SOUTH);b.pipe("return",x,2,11,x,2,11);
            b.port(x,4,10,"flue",Direction.SOUTH);b.pipe("flue",x,4,11,x,7,11);b.pipe("flue",x,7,11,x,7,14);b.port(x,8,14,"flue",Direction.DOWN);
            for(int y=8;y<=34;y++)for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)if(dx!=0||dz!=0)b.set(x+dx,y,14+dz,y==34?Blocks.POLISHED_DEEPSLATE:y>=27&&y<=29||y>=16&&y<=18?Blocks.RED_CONCRETE:Blocks.WHITE_CONCRETE);
            b.open(x,9,14,x,34,14);b.prop(x-1,34,13,"floor_lamp");
        }
        b.pipe("steam",-15,5,5,9,5,5);b.pipe("return",-19,2,11,9,2,11);
        for(int x:new int[]{-9,3}){
            for(int xx=x-1;xx<=x+1;xx++)for(int y=1;y<=2;y++)for(int z=-3;z<=1;z++)b.eq(xx,y,z,"turbine_casing");
            for(int xx=x-1;xx<=x+1;xx++)b.eq(xx,1,-3,"generator_end");
            b.port(x,3,1,"steam",Direction.UP);b.pipe("steam",x,4,1,x,5,1);b.pipe("steam",x,5,1,x,5,5);
            b.port(x,1,-4,"return",Direction.NORTH);b.pipe("return",x,1,-5,x,1,-5);
        }
        // Condenser and feed pump. Return piping is separate from the overhead steam header.
        for(int x=-20;x<=-18;x++)for(int y=1;y<=2;y++)for(int z=-8;z<=-6;z++)b.eq(x,y,z,"heat_exchanger");
        b.port(-17,1,-7,"return",Direction.EAST);b.pipe("return",-16,1,-7,-16,1,-5);b.pipe("return",-16,1,-5,3,1,-5);
        b.port(-19,2,-5,"return",Direction.SOUTH);b.pipe("return",-19,2,-4,-19,2,11);b.eq(-19,1,5,"pump_unit");
        // Outdoor plant: transformer bank, dry cooler, water tank. No desks or racks.
        for(int x=18;x<=20;x++)for(int z=-12;z<=-10;z++)b.eq(x,1,z,"transformer_unit");
        for(int x=18;x<=20;x++)for(int z=-1;z<=1;z++)for(int y=1;y<=2;y++)b.eq(x,y,z,"cooler_unit");
        for(int x=18;x<=20;x++)for(int z=9;z<=11;z++)for(int y=1;y<=3;y++)b.eq(x,y,z,"tank_panel");
        b.eq(17,1,8,"pump_unit");
        b.port(17,2,0,"coolant",Direction.WEST);b.pipe("coolant",16,2,0,16,7,0);b.pipe("coolant",16,7,0,16,7,-7);b.pipe("coolant",-19,7,-7,16,7,-7);
        b.port(-19,3,-7,"coolant",Direction.UP);b.pipe("coolant",-19,4,-7,-19,7,-7);
        b.port(-19,2,-9,"coolant",Direction.NORTH);b.pipe("coolant",-19,2,-10,-19,7,-10);b.pipe("coolant",-19,7,-10,19,7,-10);b.pipe("coolant",19,7,-10,19,7,-3);b.pipe("coolant",19,2,-3,19,7,-3);b.port(19,2,-2,"coolant",Direction.NORTH);
        for(int z=-17;z<=15;z++){b.eq(16,0,z,"cable_tray");}
        for(int z=-16;z<=16;z+=4)b.eq(24,1,z,"safety_barrier");
        for(int x:new int[]{-12,0,8}){for(int y=1;y<=4;y++)b.eq(x,y,5,"pipe_support");}
        for(int x=-22;x<=22;x+=6)for(int z=-19;z<=17;z+=6)b.prop(x,0,z,"floor_lamp");
        for(int x=-20;x<=10;x+=6)for(int z=-14;z<=14;z+=6){b.prop(x,10,z,"mounted_lamp");if(!b.map.containsKey(new BlockPos(x,12,z)))b.prop(x,11,z,"floor_lamp");}
        for(int x:new int[]{-25,25})for(int z:new int[]{-20,20}){b.fill(x,1,z,x,3,z,Blocks.POLISHED_DEEPSLATE);b.prop(x,4,z,"post_lantern");}
    }
    private static final class Builder {
        final Map<BlockPos,BlockState> map;
        Builder(SiteType t){map=t==SiteType.POWER_STATION?new LinkedHashMap<>():new LinkedHashMap<>(DistinctIndustryArchitecture.relative(t));}
        void set(int x,int y,int z,Block block){map.put(new BlockPos(x,y,z),block.defaultBlockState());}
        void fill(int x,int y,int z,int xx,int yy,int zz,Block block){for(int a=x;a<=xx;a++)for(int b=y;b<=yy;b++)for(int c=z;c<=zz;c++)set(a,b,c,block);}
        void ring(int x,int y,int z,int xx,int zz,Block block){for(int a=x;a<=xx;a++)for(int c=z;c<=zz;c++)if(a==x||a==xx||c==z||c==zz)set(a,y,c,block);}
        void open(int x,int y,int z,int xx,int yy,int zz){map.keySet().removeIf(p->p.getX()>=x&&p.getX()<=xx&&p.getY()>=y&&p.getY()<=yy&&p.getZ()>=z&&p.getZ()<=zz);}
        void shell(int x,int z,int xx,int zz,int y,int yy){for(int a=x;a<=xx;a++)for(int c=z;c<=zz;c++)if(a==x||a==xx||c==z||c==zz)for(int b=y;b<=yy;b++)set(a,b,c,a%6==0||c%6==0?Blocks.POLISHED_DEEPSLATE:b>=3&&b<=6&&Math.floorMod(a+c,6)>=2?Blocks.GLASS:Blocks.BRICKS);}
        void prop(int x,int y,int z,String id){map.put(new BlockPos(x,y,z),FunctionalBuildings.PROPS.get(id).get().defaultBlockState());}
        void eq(int x,int y,int z,String id){equipment(new BlockPos(x,y,z),id,Direction.NORTH);}
        void equipment(BlockPos pos,String id,Direction f){var r=FunctionalBuildings.EQUIPMENT.get(id);if(r!=null)map.put(pos,r.get().defaultBlockState().setValue(BlockStateProperties.FACING,f));else map.put(pos,FunctionalBuildings.PROPS.get(id).get().defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,f));}
        void port(int x,int y,int z,String circuit,Direction normal){equipment(new BlockPos(x,y,z),"port_"+circuit,normal);}
        void pipe(String circuit,int x,int y,int z,int xx,int yy,int zz){if((x==xx?0:1)+(y==yy?0:1)+(z==zz?0:1)>1)throw new IllegalArgumentException("Axial pipe only");for(int a=Math.min(x,xx);a<=Math.max(x,xx);a++)for(int b=Math.min(y,yy);b<=Math.max(y,yy);b++)for(int c=Math.min(z,zz);c<=Math.max(z,zz);c++){var pos=new BlockPos(a,b,c);var previous=map.get(pos);if(previous!=null&&previous.getBlock() instanceof EngineeredPipe p&&!p.circuit.equals(circuit))throw new IllegalStateException("Crossed pipe circuits at "+pos);map.put(pos,FunctionalBuildings.PIPES.get(circuit).get().defaultBlockState());}}
        void finishPipes(){for(var e:new ArrayList<>(map.entrySet()))if(e.getValue().getBlock() instanceof EngineeredPipe p)map.put(e.getKey(),p.joined(e.getValue(),d->map.getOrDefault(e.getKey().relative(d),Blocks.AIR.defaultBlockState())));}
        void removeGeneric(){map.entrySet().removeIf(e->{var block=e.getValue().getBlock();if(block instanceof WorkshopProp p)return !p.kind.contains("lamp")&&!p.kind.equals("post_lantern")&&!p.kind.equals("stair_railing");var id=net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(block);return id!=null&&Set.of("detail_laboratory","detail_server","detail_rack","detail_machine","detail_drone_bench","detail_press","detail_missile_fixture","detail_conveyor").contains(id.getPath());});}
    }
}
