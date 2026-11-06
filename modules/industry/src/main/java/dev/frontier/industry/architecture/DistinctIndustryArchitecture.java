package dev.frontier.industry.architecture;

import dev.frontier.industry.*;
import dev.frontier.industry.buildings.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** V8: distinct building silhouettes, joined pipework, furnished public areas and real lighting. */
public final class DistinctIndustryArchitecture {
    private static final Map<SiteType,Map<BlockPos,BlockState>> CACHE=new EnumMap<>(SiteType.class);
    private DistinctIndustryArchitecture(){}
    public static Map<BlockPos,BlockState> relative(SiteType t){return CACHE.computeIfAbsent(t,DistinctIndustryArchitecture::design);}
    public static int gateHalfWidth(SiteType t){return switch(t){case VEHICLE_FACTORY->12;case DRONE_FACTORY,MISSILE_FACTORY->5;case EQUIPMENT_FACTORY->3;default->2;};}
    public static int gateHeight(SiteType t){return switch(t){case VEHICLE_FACTORY->8;case DRONE_FACTORY->5;case MISSILE_FACTORY->7;case EQUIPMENT_FACTORY->4;default->3;};}
    private static Map<BlockPos,BlockState> design(SiteType t){
        var b=new Builder(t);
        if(t.isFactory())factory(b,t);else switch(t){case RESEARCH_CENTER->research(b);case POWER_STATION->power(b);case SUBSTATION->substation(b);default->throw new IllegalStateException();}
        outdoor(b,t);lighting(b,t);return Collections.unmodifiableMap(b.map);
    }
    private static void factory(Builder b,SiteType t){
        var f=FinalIndustryArchitecture.baseBounds(t);int x1=f.minX()+2,x2=f.maxX()-2,z1=f.minZ()+3,z2=f.maxZ()-3;
        b.map.remove(FinalIndustryArchitecture.controller(t,BlockPos.ZERO,7));
        b.map.put(FinalIndustryArchitecture.controller(t,BlockPos.ZERO,8),IndustryContent.controller(t).defaultBlockState());
        int h=t==SiteType.MISSILE_FACTORY?10:t==SiteType.VEHICLE_FACTORY?9:7;
        Block accent=switch(t){case DRONE_FACTORY->Blocks.CYAN_CONCRETE;case VEHICLE_FACTORY->Blocks.BLUE_CONCRETE;case EQUIPMENT_FACTORY->Blocks.RED_TERRACOTTA;default->Blocks.ORANGE_CONCRETE;};
        for(var e:new ArrayList<>(b.map.entrySet())){
            var p=e.getKey();boolean wall=p.getX()==x1||p.getX()==x2||p.getZ()==z1||p.getZ()==z2;
            if(wall&&p.getY()==5&&!e.getValue().is(FunctionalBuildings.PROPS.get("tool_cabinet").get()))b.map.put(p,accent.defaultBlockState());
        }
        // Replace the old small canopy as well as the opening; leaving its columns would block aircraft.
        b.open(-10,1,z1-1,10,9,z1-1);int half=gateHalfWidth(t),height=gateHeight(t);
        b.open(-half,1,z1,half,height,t==SiteType.VEHICLE_FACTORY||t==SiteType.DRONE_FACTORY?FinalIndustryArchitecture.start(t)-3:z1);
        b.fill(-half-1,1,z1,-half-1,height+1,z1,Blocks.POLISHED_DEEPSLATE);
        b.fill(half+1,1,z1,half+1,height+1,z1,Blocks.POLISHED_DEEPSLATE);
        b.fill(-half,height+1,z1,half,height+1,z1,accent);
        b.fill(-half-2,height+1,z1-1,half+2,height+1,z1-1,Blocks.DEEPSLATE_TILES);
        for(int x:new int[]{-half-2,half+2})b.fill(x,1,z1-1,x,height,z1-1,Blocks.POLISHED_DEEPSLATE);
        b.map.put(new BlockPos(0,height+2,z1-1),FunctionalBuildings.SIGNS.get(t).get().defaultBlockState());
        // The aviation/vehicle hall has a broad apron and a marked clear rollout zone.
        if(t==SiteType.VEHICLE_FACTORY||t==SiteType.DRONE_FACTORY){
            for(int x=-half;x<=half;x++)for(int z=f.minZ();z<FinalIndustryArchitecture.start(t)-2;z++){
                var p=new BlockPos(x,0,z);if(b.map.containsKey(p))b.map.put(p,(Math.abs(x)==half?(z%2==0?Blocks.YELLOW_CONCRETE:Blocks.BLACK_CONCRETE):x==0&&z%3==0?Blocks.WHITE_CONCRETE:Blocks.GRAY_CONCRETE).defaultBlockState());
            }
        }
        int base=h+1;
        b.map.keySet().removeIf(p->p.getY()>=base&&p.getY()<=base+3&&p.getX()>=x1-1&&p.getX()<=x2+1&&p.getZ()>=z1-1&&p.getZ()<=z2+1);
        for(int x=x1-1;x<=x2+1;x++)for(int z=z1-1;z<=z2+1;z++){
            int rise=switch(t){case DRONE_FACTORY->Math.max(0,3-Math.abs(x)/7);case VEHICLE_FACTORY->Math.max(0,3-Math.min(Math.abs(x-12),Math.abs(x+12))/4);case EQUIPMENT_FACTORY->1;default->0;};
            boolean glass=x>x1+4&&x<x2-4&&z>z1+3&&z<z2-3&&Math.floorMod(z-z1,t==SiteType.DRONE_FACTORY?7:10)==5;
            for(int y=base;y<=base+rise;y++)b.set(x,y,z,glass?Blocks.LIGHT_BLUE_STAINED_GLASS:Blocks.DEEPSLATE_TILES);
        }
        if(t==SiteType.MISSILE_FACTORY)for(int x:new int[]{x1+1,x2-1}){
            b.fill(x-1,1,z1,x+1,13,z1+2,Blocks.GRAY_CONCRETE);
            b.fill(x-1,9,z1,x+1,10,z1+2,accent);
            b.fill(x-1,14,z1,x+1,14,z1+2,Blocks.POLISHED_DEEPSLATE);
            b.lamp(new BlockPos(x,14,z1+1));
        }
        if(t==SiteType.EQUIPMENT_FACTORY)for(int x:new int[]{x1+4,x2-4}){
            b.fill(x,10,z2-3,x+1,12,z2-2,Blocks.POLISHED_DEEPSLATE);b.lamp(new BlockPos(x,12,z2-3));
        }
        b.map.put(new BlockPos(0,height+2,z1-1),FunctionalBuildings.SIGNS.get(t).get().defaultBlockState());
        for(int x:new int[]{x1+3,x2-3}){
            var p=new BlockPos(x,base+(t==SiteType.VEHICLE_FACTORY?2:t==SiteType.DRONE_FACTORY?1:2),z2-3);
            // Plant the HVAC housing on a solid plinth, regardless of the new roof pitch.
            b.fill(x,base,z2-3,x,p.getY()-1,z2-3,Blocks.POLISHED_DEEPSLATE);b.map.put(p,IndustryContent.detail("vent").defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.NORTH));
        }
        for(var job:FinalIndustryArchitecture.workplaces(t,6))if(job.line()>=0&&job.role().equals("assembly")){
            var p=job.equipment().relative(job.facing().getOpposite(),2);b.safeProp(p,"parts_bin",job.facing());
        }
        b.safeProp(new BlockPos(x1+4,1,z1+2),"courtyard_bench",Direction.SOUTH);
        b.safeProp(new BlockPos(x1+6,1,z1+2),"planter",Direction.SOUTH);
        b.ceiling(x1+3,x2-3,z1+3,z2-2,h);
    }
    private static void research(Builder b){
        // Supported stair stringers and continuous sloping handrails outside the three-wide stair.
        for(int x:new int[]{-15,-11})for(int z=-4;z<=2;z++){
            int top=z+5;b.fill(x,1,z,x,top,z,Blocks.SMOOTH_QUARTZ);
            b.prop(new BlockPos(x,top+1,z),"stair_railing",Direction.SOUTH);
        }
        // Guard the upper-floor opening as well as the sloping stair itself.
        var sideGlass=Blocks.LIGHT_BLUE_STAINED_GLASS_PANE.defaultBlockState().setValue(BlockStateProperties.NORTH,true).setValue(BlockStateProperties.SOUTH,true);
        for(int x:new int[]{-15,-11})for(int z=-4;z<=0;z++)b.map.put(new BlockPos(x,8,z),sideGlass);
        var endGlass=Blocks.LIGHT_BLUE_STAINED_GLASS_PANE.defaultBlockState().setValue(BlockStateProperties.EAST,true).setValue(BlockStateProperties.WEST,true);
        for(int x=-14;x<=-12;x++)b.map.put(new BlockPos(x,8,-5),endGlass);
        for(int z:new int[]{-4,0,3}){b.safeProp(new BlockPos(-16,4,z),"wall_lamp",Direction.EAST);b.safeProp(new BlockPos(-15,11,z),"wall_lamp",Direction.EAST);}
        // Replace placeholder stair seats with proper furniture on the roof terrace.
        for(int z:new int[]{0,7}){
            b.open(7,8,z,12,8,z+1);
            b.prop(new BlockPos(9,8,z),"coffee_table",Direction.NORTH);
            b.prop(new BlockPos(7,8,z),"lounge_chair",Direction.EAST);
            b.prop(new BlockPos(12,8,z),"lounge_chair",Direction.WEST);
        }
        for(int x:new int[]{6,16})for(int z:new int[]{-4,13}){
            b.open(x,8,z,x,9,z);b.prop(new BlockPos(x,8,z),"planter",Direction.NORTH);
        }
        for(int z:new int[]{-3,3,9})b.safeProp(new BlockPos(1,8,z),"bookcase",Direction.WEST);
        for(int x:new int[]{-14,14}){
            b.safeProp(new BlockPos(x,1,-11),"planter",Direction.SOUTH);
            b.safeProp(new BlockPos(x,1,-9),"courtyard_bench",Direction.SOUTH);
        }
        for(int x:new int[]{-10,10}){
            b.prop(new BlockPos(x,1,-16),"courtyard_bench",Direction.NORTH);
            b.prop(new BlockPos(x+(x<0?-3:3),1,-16),"planter",Direction.NORTH);
        }
        // A cyan cornice and a white upper parapet identify the science building from outside.
        for(int x=-17;x<=17;x++)b.set(x,6,-14,Blocks.CYAN_CONCRETE);
        for(int z=-14;z<=14;z++){b.set(-17,6,z,Blocks.CYAN_CONCRETE);b.set(17,6,z,Blocks.CYAN_CONCRETE);}
        b.ceiling(-13,14,-11,11,6);b.ceiling(-13,1,-3,11,13);
    }
    private static void power(Builder b){
        var oldPipes=Set.of(IndustryContent.detail("pipe"),IndustryContent.detail("pipe_elbow"),IndustryContent.detail("pipe_riser"));
        b.map.entrySet().removeIf(e->oldPipes.contains(e.getValue().getBlock()));
        for(int x:new int[]{-6,-2})for(int y=1;y<=2;y++)b.map.remove(new BlockPos(x,y,9));
        for(int x:new int[]{-6,-2}){
            b.set(x,2,0,Blocks.CUT_COPPER);b.pipe(x,2,1,x,2,3);
            b.set(x,2,4,Blocks.CUT_COPPER);b.set(x,2,5,Blocks.CUT_COPPER);b.pipe(x,2,6,x,2,8);
            int machine=x==-6?-5:0;b.pipe(x,2,8,machine,2,8);b.pipe(machine,2,8,machine,2,10);
        }
        for(int z:new int[]{-6,6}){
            int wall=z<0?5:7;b.set(10,4,z,Blocks.CUT_COPPER);b.set(wall,4,z,Blocks.CUT_COPPER);b.pipe(wall+1,4,z,9,4,z);
            int target=3;b.pipe(target,4,z,wall-1,4,z);b.pipe(target,2,z,target,4,z);
            b.map.put(new BlockPos(target,1,z),IndustryContent.detail("machine").defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.NORTH));
        }
        // Derive all six joins from the complete layout, not separately rotated elbow guesses.
        for(var e:new ArrayList<>(b.map.entrySet()))if(e.getValue().getBlock() instanceof ProcessPipe){var p=e.getKey();b.map.put(p,ProcessPipe.connections(e.getValue(),d->b.map.getOrDefault(p.relative(d),Blocks.AIR.defaultBlockState())));}
        for(var p:FinalIndustryArchitecture.smokePoints(6)){
            b.lamp(new BlockPos(p.getX(),11,p.getZ()));
            for(int dx:new int[]{-1,1})for(int dz:new int[]{-1,1})b.lamp(new BlockPos(p.getX()+dx,28,p.getZ()+dz));
        }
        b.ceiling(-12,3,-10,2,8);b.ceiling(-12,5,6,11,10);
    }
    private static void substation(Builder b){
        b.ceiling(-4,4,5,8,4);
        b.safeProp(new BlockPos(-8,1,7),"tool_cabinet",Direction.EAST);
        b.safeProp(new BlockPos(8,1,7),"materials_rack",Direction.WEST);
    }
    private static void outdoor(Builder b,SiteType t){
        var f=FinalIndustryArchitecture.baseBounds(t);
        for(int x:new int[]{f.minX()+1,f.maxX()-1})for(int z:new int[]{f.minZ()+1,f.maxZ()-1}){
            b.fill(x,1,z,x,3,z,Blocks.POLISHED_DEEPSLATE);b.prop(new BlockPos(x,4,z),"post_lantern",Direction.NORTH);
        }
        if(t.isFactory())for(int x:new int[]{f.minX()+3,f.maxX()-3}){
            b.safeProp(new BlockPos(x,1,f.minZ()+1),"pallet",Direction.SOUTH);
            b.safeProp(new BlockPos(x+(x<0?2:-2),1,f.minZ()+1),"sealed_drum",Direction.SOUTH);
        }
    }
    private static void lighting(Builder b,SiteType t){
        var f=FinalIndustryArchitecture.baseBounds(t);
        for(int x=f.minX()+1;x<f.maxX();x+=4)for(int z=f.minZ()+1;z<f.maxZ();z+=4){
            var ground=new BlockPos(x,0,z);if(!b.map.containsKey(ground.above()))b.lamp(ground);
            if(t==SiteType.RESEARCH_CENTER){var upper=new BlockPos(x,7,z);if(b.map.containsKey(upper)&&!b.map.containsKey(upper.above()))b.lamp(upper);}
        }
        // Flush lights on the actual top surface also prevent spawning on accessible roofs.
        for(int x=f.minX()+1;x<f.maxX();x+=5)for(int z=f.minZ()+1;z<f.maxZ();z+=5){
            for(int y=Math.min(f.maxY(),16);y>=5;y--){
                var p=new BlockPos(x,y,z);var s=b.map.get(p);
                if(s!=null&&(s.is(Blocks.DEEPSLATE_TILES)||s.is(Blocks.SMOOTH_QUARTZ)||s.is(Blocks.POLISHED_DEEPSLATE))&&!b.map.containsKey(p.above())){b.lamp(p);break;}
            }
        }
    }
    private static final class Builder {
        final Map<BlockPos,BlockState> map;final Set<BlockPos> protectedAir=new HashSet<>();
        Builder(SiteType t){map=new LinkedHashMap<>(InteriorDecorations.relative(t));for(var j:FinalIndustryArchitecture.workplaces(t,6))for(int y=0;y<2;y++){var p=j.stand().above(y);protectedAir.add(p);for(var d:Direction.Plane.HORIZONTAL)protectedAir.add(p.relative(d));}}
        void set(int x,int y,int z,Block block){map.put(new BlockPos(x,y,z),block.defaultBlockState());}
        void fill(int x1,int y1,int z1,int x2,int y2,int z2,Block block){for(int x=x1;x<=x2;x++)for(int y=y1;y<=y2;y++)for(int z=z1;z<=z2;z++)set(x,y,z,block);}
        void open(int x1,int y1,int z1,int x2,int y2,int z2){map.keySet().removeIf(p->p.getX()>=x1&&p.getX()<=x2&&p.getY()>=y1&&p.getY()<=y2&&p.getZ()>=z1&&p.getZ()<=z2);}
        void prop(BlockPos p,String id,Direction face){map.put(p,FunctionalBuildings.PROPS.get(id).get().defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,face));}
        void safeProp(BlockPos p,String id,Direction face){if(!map.containsKey(p)&&!protectedAir.contains(p)&&!protectedAir.contains(p.above()))prop(p,id,face);}
        void lamp(BlockPos p){prop(p,"floor_lamp",Direction.NORTH);}
        void ceiling(int x1,int x2,int z1,int z2,int y){for(int x=x1;x<=x2;x+=6)for(int z=z1;z<=z2;z+=6)if(!map.containsKey(new BlockPos(x,y,z)))prop(new BlockPos(x,y,z),"mounted_lamp",Direction.NORTH);}
        void pipe(int x1,int y1,int z1,int x2,int y2,int z2){
            if((x1==x2?0:1)+(y1==y2?0:1)+(z1==z2?0:1)>1)throw new IllegalArgumentException("Non axial pipe route");
            for(int x=Math.min(x1,x2);x<=Math.max(x1,x2);x++)for(int y=Math.min(y1,y2);y<=Math.max(y1,y2);y++)for(int z=Math.min(z1,z2);z<=Math.max(z1,z2);z++)map.put(new BlockPos(x,y,z),FunctionalBuildings.PROCESS_PIPE.get().defaultBlockState());
        }
    }
}
