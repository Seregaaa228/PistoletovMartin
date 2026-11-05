package dev.frontier.industry.architecture;

import dev.frontier.industry.*;
import dev.frontier.industry.buildings.*;
import dev.frontier.industry.engine.BlueprintBounds;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** V6: three genuinely separate transport paths, supported engineering and persistent orientation. */
public final class FinalIndustryArchitecture {
    private FinalIndustryArchitecture(){}
    public record Workstation(String id,String role,int line,BlockPos equipment,BlockPos stand,Direction facing,String part,int capacity){}
    private static final Map<SiteType,Map<BlockPos,BlockState>> CACHE=new EnumMap<>(SiteType.class);
    public static BlueprintBounds baseBounds(SiteType t){return switch(t){
        case POWER_STATION->new BlueprintBounds(-17,0,-15,17,28,15);
        case SUBSTATION->new BlueprintBounds(-11,0,-11,11,10,11);
        case DRONE_FACTORY->new BlueprintBounds(-25,0,-17,25,12,19);
        case VEHICLE_FACTORY->new BlueprintBounds(-27,0,-21,27,14,21);
        case EQUIPMENT_FACTORY->new BlueprintBounds(-25,0,-19,25,13,19);
        case MISSILE_FACTORY->new BlueprintBounds(-25,0,-23,25,16,21);
        case RESEARCH_CENTER->new BlueprintBounds(-19,0,-17,19,16,17);
    };}
    public static BlueprintBounds bounds(SiteType t,int version){
        var b=version%100>=11?CampusIndustryArchitecture.baseBounds(t):version%100>=9?ReworkedIndustryArchitecture.baseBounds(t):baseBounds(t);var p=BuildingOrientation.rotate(new BlockPos(b.minX(),0,b.minZ()),version);var q=BuildingOrientation.rotate(new BlockPos(b.maxX(),0,b.maxZ()),version);
        return new BlueprintBounds(Math.min(p.getX(),q.getX()),0,Math.min(p.getZ(),q.getZ()),Math.max(p.getX(),q.getX()),b.maxY(),Math.max(p.getZ(),q.getZ()));
    }
    public static BlockPos entrance(SiteType t,BlockPos o,int v){return o.offset(BuildingOrientation.rotate(new BlockPos(0,1,(v%100>=9?ReworkedIndustryArchitecture.baseBounds(t):baseBounds(t)).minZ()),v));}
    public static BlockPos controller(SiteType t,BlockPos o,int v){if(v%100>=9)return o.offset(BuildingOrientation.rotate(ReworkedIndustryArchitecture.controller(t),v));int x=v%100==8&&t.isFactory()?DistinctIndustryArchitecture.gateHalfWidth(t)+2:2;return o.offset(BuildingOrientation.rotate(new BlockPos(x,1,baseBounds(t).minZ()+4),v));}
    public static int[] centres(SiteType t){return new int[]{t==SiteType.VEHICLE_FACTORY?-16:-14,0,t==SiteType.VEHICLE_FACTORY?16:14};}
    public static int start(SiteType t){return switch(t){case DRONE_FACTORY->-6;case VEHICLE_FACTORY->-10;case EQUIPMENT_FACTORY->-8;case MISSILE_FACTORY->-12;default->0;};}
    public static int end(SiteType t){return switch(t){case DRONE_FACTORY->9;case VEHICLE_FACTORY,EQUIPMENT_FACTORY,MISSILE_FACTORY->11;default->0;};}
    public static BlockPos lineControl(SiteType t,int line,int v){return BuildingOrientation.rotate(new BlockPos(centres(t)[line]+3,1,start(t)-2),v);}
    public static List<BlockPos> belts(SiteType t,int v){
        if(!t.isFactory())return List.of();List<BlockPos> result=new ArrayList<>();
        for(int c:centres(t))for(int x=c-1;x<=c+1;x++)for(int z=start(t);z<=end(t);z++)result.add(BuildingOrientation.rotate(new BlockPos(x,1,z),v));return result;
    }
    public static int lineAt(SiteType t,BlockPos p,int v){var unrotated=BuildingOrientation.rotate(p,6+100*((4-BuildingOrientation.quarter(v))%4));int best=0;int[] centres=centres(t);for(int i=1;i<3;i++)if(Math.abs(unrotated.getX()-centres[i])<Math.abs(unrotated.getX()-centres[best]))best=i;return best;}
    public static List<Workstation> workplaces(SiteType t,int v){
        if(v%100>=9)return ReworkedIndustryArchitecture.workplaces(t,v);
        List<Workstation> jobs=new ArrayList<>();
        if(t.isFactory()) {
            String[] roles={"supply","assembly","quality","packing"};
            for(int line=0;line<3;line++)for(int stage=0;stage<4;stage++) {
                int c=centres(t)[line],z=start(t)+stage*(end(t)-start(t))/3,x=c+(stage%2==0?-5:5);
                String part=stage==0?"packing_table":stage==2?"quality_console":stage==3?"packing_table":t==SiteType.DRONE_FACTORY?"drone_bench":t==SiteType.MISSILE_FACTORY?"missile_fixture":"press";
                jobs.add(job(t.path()+"/"+(char)('A'+line)+"/"+roles[stage],roles[stage],line,x,1,z,part));
            }
            jobs.add(job(t.path()+"/logistics/in","logistics",-1,-21,1,end(t)+3,"operator_console"));
            jobs.add(job(t.path()+"/logistics/out","logistics",-1,21,1,end(t)+3,"packing_table"));
            jobs.add(new Workstation(t.path()+"/technician","maintenance",-1,new BlockPos(0,1,end(t)+4),new BlockPos(0,1,end(t)+3),Direction.NORTH,"operator_console",1));
        } else if(t==SiteType.RESEARCH_CENTER) {
            for(int x:new int[]{-8,-2})for(int z:new int[]{-3,1,5,9})jobs.add(job("research/lab/"+x+"/"+z,"scientist",-1,x,1,z,"laboratory"));
            for(int x:new int[]{-8,-2})for(int z:new int[]{2,8})jobs.add(job("research/analytics/"+x+"/"+z,"analyst",-1,x,8,z,"operator_console"));
            for(int z:new int[]{0,7})jobs.add(job("research/server/"+z,"maintenance",-1,12,1,z,"research_server"));
            for(int x:new int[]{-8,8})jobs.add(new Workstation("research/reception/"+x,"reception",-1,new BlockPos(x,1,-10),new BlockPos(x,1,-11),Direction.NORTH,"operator_console",1));
        } else if(t==SiteType.POWER_STATION) {
            for(int x:new int[]{-10,-6,-2})jobs.add(job("power/control/"+x,"engineer",-1,x,1,-7,"operator_console"));
            for(int z:new int[]{0,3,10})jobs.add(job("power/service/"+z,"maintenance",-1,12,1,z,"operator_console"));
        } else {
            for(int x:new int[]{-3,3})for(int z:new int[]{5,8})jobs.add(job("substation/control/"+x+"/"+z,"electrician",-1,x,1,z,"operator_console"));
        }
        if(BuildingOrientation.quarter(v)==0)return List.copyOf(jobs);
        return jobs.stream().map(j->new Workstation(j.id(),j.role(),j.line(),BuildingOrientation.rotate(j.equipment(),v),BuildingOrientation.rotate(j.stand(),v),BuildingOrientation.rotate(j.facing(),v),j.part(),j.capacity())).toList();
    }
    private static Workstation job(String id,String role,int line,int x,int y,int z,String part){return new Workstation(id,role,line,new BlockPos(x,y,z),new BlockPos(x+(x<0?1:-1),y,z),x<0?Direction.EAST:Direction.WEST,part,1);}
    public static Map<BlockPos,BlockState> blocks(SiteType t,BlockPos origin,int version){
        var relative=version%100==16?V16RepairArchitecture.relative(t):version%100==15?V15DecorArchitecture.relative(t):version%100==14?FurnishedIndustryArchitecture.relative(t):version%100==13?GardenIndustryArchitecture.relative(t):version%100==12?LaboratoryIndustryArchitecture.relative(t):version%100==11?CampusIndustryArchitecture.relative(t):version%100==10?WorkshopIndustryArchitecture.relative(t):version%100==9?ReworkedIndustryArchitecture.relative(t):version%100==8?DistinctIndustryArchitecture.relative(t):version%100==7?InteriorDecorations.relative(t):CACHE.computeIfAbsent(t,FinalIndustryArchitecture::design);Map<BlockPos,BlockState> result=new LinkedHashMap<>();relative.forEach((p,s)->result.put(origin.offset(BuildingOrientation.rotate(p,version)),BuildingOrientation.rotate(s,version)));return result;
    }
    public static List<BlockPos> smokePoints(int v){return v%100>=9?ReworkedIndustryArchitecture.smokePoints().stream().map(p->BuildingOrientation.rotate(p,v)).toList():java.util.stream.IntStream.of(-8,-2,4).mapToObj(x->BuildingOrientation.rotate(new BlockPos(x,29,9),v)).toList();}
    public static Map<BlockPos,BlockState> criticalBlocks(SiteType t,BlockPos origin,int version){
        if(t!=SiteType.POWER_STATION&&t!=SiteType.SUBSTATION)return Map.of();var all=blocks(t,origin,version);Map<BlockPos,BlockState> result=new LinkedHashMap<>();
        if(version%100>=9&&t==SiteType.POWER_STATION){for(int x:new int[]{-9,3}){var p=origin.offset(BuildingOrientation.rotate(new BlockPos(x,2,-1),version));result.put(p,all.get(p));}return result;}
        for(int x:t==SiteType.POWER_STATION?new int[]{-6,-2}:new int[]{-5,5}){var p=origin.offset(BuildingOrientation.rotate(new BlockPos(x,2,t==SiteType.POWER_STATION?-1:0),version));result.put(p,all.get(p));}return result;
    }
    private static Map<BlockPos,BlockState> design(SiteType t){
        Layout b=new Layout();var f=baseBounds(t);b.fill(f.minX(),0,f.minZ(),f.maxX(),0,f.maxZ(),Blocks.SMOOTH_STONE);b.ring(f.minX(),0,f.minZ(),f.maxX(),f.maxZ(),Blocks.POLISHED_DEEPSLATE);
        if(t.isFactory())factory(b,t);else switch(t){case POWER_STATION->power(b);case SUBSTATION->substation(b);case RESEARCH_CENTER->research(b);default->throw new IllegalStateException();}
        for(var job:workplaces(t,6))b.part(job.equipment(),job.part(),job.facing());
        BlockPos c=controller(t,BlockPos.ZERO,6);b.put(c,IndustryContent.controller(t).defaultBlockState());
        return Collections.unmodifiableMap(b.map);
    }
    private static void factory(Layout b,SiteType t){
        var f=baseBounds(t);int x1=f.minX()+2,x2=f.maxX()-2,z1=f.minZ()+3,z2=f.maxZ()-3,h=t==SiteType.MISSILE_FACTORY?10:t==SiteType.VEHICLE_FACTORY?9:7;
        b.shell(x1,z1,x2,z2,1,h,t==SiteType.DRONE_FACTORY);b.door(-4,4,z1,5);b.roof(x1,z1,x2,z2,h+1,3,true);
        b.canopy(-5,5,z1-1,6);b.sign(t,0,7,z1-1);
        for(int line=0;line<3;line++) {
            int cx=centres(t)[line];
            for(int x=cx-1;x<=cx+1;x++)for(int z=start(t);z<=end(t);z++)b.put(new BlockPos(x,1,z),FunctionalBuildings.belt(Direction.SOUTH));
            BlockPos control=lineControl(t,line,6);b.put(control,FunctionalBuildings.LINE_CONTROLLER.get().defaultBlockState().setValue(LineController.LINE,line));
            b.put(new BlockPos(cx,3,start(t)-3),FunctionalBuildings.LINE_SIGNS.get(line).get().defaultBlockState());
            b.fill(cx-1,3,start(t)-2,cx+1,3,start(t)-2,Blocks.POLISHED_DEEPSLATE);
            for(int x:new int[]{cx-2,cx+2})b.fill(x,1,start(t)-2,x,3,start(t)-2,Blocks.POLISHED_DEEPSLATE);
            for(int x:new int[]{cx-3,cx+3})for(int z=start(t)-1;z<=end(t)+1;z++)b.put(x,0,z,z%2==0?Blocks.YELLOW_CONCRETE:Blocks.BLACK_CONCRETE);
            for(int z:new int[]{start(t)-2,end(t)+2})for(int x:new int[]{cx-2,cx+2})b.part(new BlockPos(x,1,z),"pallet",Direction.NORTH);
            for(int z:new int[]{start(t),start(t)+6,end(t)})b.hangingLamp(cx,h,z,h+1);
        }
        // Crane rails end at steel columns, each rooted in the full foundation.
        if(t==SiteType.VEHICLE_FACTORY||t==SiteType.MISSILE_FACTORY) {
            for(int x:new int[]{x1+1,x2-1})for(int z=z1+3;z<=z2-2;z+=7)b.fill(x,1,z,x,h-1,z,Blocks.POLISHED_DEEPSLATE);
            for(int x:new int[]{x1+1,x2-1})for(int z:new int[]{z1+3,z2-2})b.fill(x,1,z,x,h-1,z,Blocks.POLISHED_DEEPSLATE);
            for(int x:new int[]{x1+1,x2-1})for(int z=z1+3;z<=z2-2;z++)b.part(new BlockPos(x,h-1,z),"crane",Direction.NORTH);
            for(int x=x1+1;x<=x2-1;x++)b.part(new BlockPos(x,h-1,3),"crane",Direction.EAST);
            b.part(new BlockPos(0,h-3,3),"hook",Direction.NORTH);b.fill(0,h-2,3,0,h-1,3,Blocks.IRON_BARS);
        }
        for(int x:new int[]{x1+3,x2-3})for(int z:new int[]{z1+3,z2-3})b.part(new BlockPos(x,1,z),"pallet",Direction.NORTH);
        b.part(new BlockPos(x2-3,h+2,z2-3),"vent",Direction.NORTH);
    }
    private static void research(Layout b){
        b.shell(-17,-14,17,14,1,6,true);b.door(-3,3,-14,4);b.fill(-17,7,-14,17,7,14,Blocks.SMOOTH_QUARTZ);
        b.shell(-16,-6,3,13,8,13,true);b.roof(-16,-6,3,13,14,1,false);
        // Wide internal stair: seven risers, three blocks across, with full landing.
        b.open(-14,7,-4,-12,7,2);
        for(int x=-14;x<=-12;x++)for(int z=-4;z<=2;z++)b.put(new BlockPos(x,z+5,z),Blocks.QUARTZ_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.SOUTH));
        b.fill(-14,7,3,-12,7,4,Blocks.SMOOTH_QUARTZ);
        // Three-block doors separate reception, laboratory, server room and terrace.
        b.fill(4,1,-6,4,6,13,Blocks.SMOOTH_QUARTZ);b.open(4,1,0,4,3,2);
        b.fill(-17,1,-7,3,6,-7,Blocks.SMOOTH_QUARTZ);b.open(-2,1,-7,0,3,-7);
        b.open(3,8,4,3,10,6);
        for(int z=-3;z<=11;z+=7)for(int x:new int[]{-9,0,10})b.hangingLamp(x,6,z,7);
        for(int z:new int[]{-2,5,11})b.hangingLamp(-5,13,z,14);
        // Safe usable terrace, not an empty unguarded roof.
        b.ring(5,8,-5,17,14,Blocks.IRON_BARS);b.open(5,8,4,5,8,6);
        for(int z:new int[]{0,7}){
            b.fill(9,8,z,10,8,z+1,Blocks.SMOOTH_QUARTZ);
            for(int x:new int[]{7,12})b.fill(x,8,z,x,8,z+1,Blocks.OAK_STAIRS);
        }
        for(int x:new int[]{6,16})for(int z:new int[]{-4,13}){b.put(x,8,z,Blocks.BRICKS);b.put(x,9,z,Blocks.AZALEA_LEAVES);}
        for(int z:new int[]{-3,12}){b.fill(16,8,z,16,10,z,Blocks.POLISHED_DEEPSLATE);b.put(16,11,z,Blocks.SEA_LANTERN);}
        b.canopy(-4,4,-15,5);b.sign(SiteType.RESEARCH_CENTER,0,6,-15);
        // Supported technical cabinet and ducts on a dedicated rear service bay.
        for(int x:new int[]{7,11,15})b.part(new BlockPos(x,1,12),"server",Direction.NORTH);
        b.fill(10,8,12,13,8,13,Blocks.POLISHED_DEEPSLATE);b.part(new BlockPos(11,9,12),"vent",Direction.NORTH);
    }
    private static void power(Layout b){
        b.shell(-14,-12,5,4,1,8,false);b.door(-3,3,-12,4);b.roof(-14,-12,5,4,9,2,true);
        b.shell(-14,5,7,12,1,10,false);b.open(-1,1,4,1,4,5);b.fill(-14,11,5,7,11,12,Blocks.DEEPSLATE_TILES);
        for(int x:new int[]{-6,-2}){b.part(new BlockPos(x,2,-1),"turbine",Direction.NORTH);b.pipe(x,3,0,9);}
        // A third boiler and switchgear fill the former empty rear room.
        for(int x:new int[]{-10,-5,0,5})b.part(new BlockPos(x,1,10),"machine",Direction.NORTH);
        for(int x:new int[]{-8,-2,4})for(int y=12;y<=28;y++)for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)if(dx!=0||dz!=0)b.put(x+dx,y,9+dz,y==28?Blocks.POLISHED_DEEPSLATE:(y>=16&&y<=18||y>=25)?Blocks.RED_CONCRETE:Blocks.WHITE_CONCRETE);
        for(int z:new int[]{-6,6}){
            b.fill(10,1,z-1,12,1,z+1,Blocks.POLISHED_DEEPSLATE);
            for(int y=2;y<=6;y++)b.part(new BlockPos(11,y,z),"silo",Direction.NORTH);b.part(new BlockPos(11,7,z),"silo_cap",Direction.NORTH);
            // Close both ends into a tank and the solid boiler wall via flanged elbows.
            int wall=z>4?7:5;
            for(int x=wall+1;x<=10;x++)b.part(new BlockPos(x,4,z),"pipe",Direction.EAST);
            b.fill(wall+1,1,z,wall+1,3,z,Blocks.POLISHED_DEEPSLATE);
            b.put(wall,4,z,Blocks.CUT_COPPER);
        }
        b.canopy(-4,4,-13,5);b.sign(SiteType.POWER_STATION,0,6,-13);
        for(int z:new int[]{-8,0,8})for(int x:new int[]{-11,2})b.hangingLamp(x,z>4?10:8,z,z>4?11:9);
    }
    private static void substation(Layout b){
        for(int x:new int[]{-5,5}){b.fill(x-1,1,-1,x+1,1,1,Blocks.POLISHED_DEEPSLATE);b.part(new BlockPos(x,2,0),"transformer",Direction.NORTH);}
        for(int x:new int[]{-8,8}){b.fill(x,1,3,x,6,3,Blocks.POLISHED_DEEPSLATE);b.part(new BlockPos(x,7,3),"insulator",Direction.NORTH);}
        for(int x=-8;x<=8;x++){b.part(new BlockPos(x,6,3),"beam",Direction.EAST);b.part(new BlockPos(x,8,3),"pipe",Direction.EAST);}
        b.shell(-6,4,6,9,1,4,false);b.door(-1,1,4,2);b.roof(-6,4,6,9,5,1,false);b.sign(SiteType.SUBSTATION,0,4,3);
        for(int z=-7;z<=10;z++)for(int x:new int[]{-10,10})b.part(new BlockPos(x,1,z),"rail",Direction.NORTH);
        for(int x=-10;x<=10;x++){b.part(new BlockPos(x,1,10),"rail",Direction.EAST);if(Math.abs(x)>2)b.part(new BlockPos(x,1,-8),"rail",Direction.EAST);}
        b.hangingLamp(0,4,7,5);
    }
    private static final class Layout {
        final Map<BlockPos,BlockState> map=new LinkedHashMap<>();
        void put(BlockPos p,BlockState s){map.put(p,s);}
        void put(int x,int y,int z,Block block){put(new BlockPos(x,y,z),block.defaultBlockState());}
        void part(BlockPos p,String id,Direction f){Block block=FunctionalBuildings.PROPS.containsKey(id)?FunctionalBuildings.PROPS.get(id).get():IndustryContent.detail(id);put(p,block.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,f));}
        void fill(int x1,int y1,int z1,int x2,int y2,int z2,Block block){for(int x=x1;x<=x2;x++)for(int y=y1;y<=y2;y++)for(int z=z1;z<=z2;z++)put(x,y,z,block);}
        void open(int x1,int y1,int z1,int x2,int y2,int z2){for(int x=x1;x<=x2;x++)for(int y=y1;y<=y2;y++)for(int z=z1;z<=z2;z++)map.remove(new BlockPos(x,y,z));}
        void ring(int x1,int y,int z1,int x2,int z2,Block block){fill(x1,y,z1,x2,y,z1,block);fill(x1,y,z2,x2,y,z2,block);fill(x1,y,z1,x1,y,z2,block);fill(x2,y,z1,x2,y,z2,block);}
        void shell(int x1,int z1,int x2,int z2,int bottom,int top,boolean white){
            for(int y=bottom;y<=top;y++)for(int x=x1;x<=x2;x++)for(int z=z1;z<=z2;z++){
                if(x!=x1&&x!=x2&&z!=z1&&z!=z2)continue;
                boolean pier=(x==x1||x==x2)&&(z==z1||z==z2)||(z==z1||z==z2)&&(x-x1)%6==0||(x==x1||x==x2)&&(z-z1)%6==0;
                boolean window=y>=bottom+2&&y<=bottom+3&&(z==z1||z==z2?Math.floorMod(x-x1,6)>=2&&Math.floorMod(x-x1,6)<=3:Math.floorMod(z-z1,6)>=2&&Math.floorMod(z-z1,6)<=3);
                put(x,y,z,pier?(white?Blocks.SMOOTH_QUARTZ:Blocks.POLISHED_DEEPSLATE):window?(white?Blocks.LIGHT_BLUE_STAINED_GLASS:Blocks.GLASS):white?Blocks.WHITE_CONCRETE:Blocks.BRICKS);
            }
        }
        void door(int x1,int x2,int z,int h){open(x1,1,z,x2,h,z);fill(x1-1,1,z,x1-1,h+1,z,Blocks.POLISHED_DEEPSLATE);fill(x2+1,1,z,x2+1,h+1,z,Blocks.POLISHED_DEEPSLATE);fill(x1,h+1,z,x2,h+1,z,Blocks.POLISHED_DEEPSLATE);}
        void roof(int x1,int z1,int x2,int z2,int y,int rise,boolean skylights){for(int x=x1-1;x<=x2+1;x++)for(int z=z1-1;z<=z2+1;z++){int top=y+Math.max(0,Math.min(rise,Math.min(x-x1,x2-x)/4));boolean glass=skylights&&x>x1+3&&x<x2-3&&z>z1+2&&z<z2-2&&Math.floorMod(z-z1,8)==4;for(int k=y;k<=top;k++)put(x,k,z,glass?Blocks.LIGHT_BLUE_STAINED_GLASS:Blocks.DEEPSLATE_TILES);}}
        void canopy(int x1,int x2,int z,int y){fill(x1,y,z,x2,y,z,Blocks.DEEPSLATE_TILES);for(int x:new int[]{x1,x2})fill(x,1,z,x,y-1,z,Blocks.POLISHED_DEEPSLATE);}
        void sign(SiteType t,int x,int y,int z){put(new BlockPos(x,y,z),FunctionalBuildings.SIGNS.get(t).get().defaultBlockState());}
        void hangingLamp(int x,int y,int z,int roof){if(y<roof)fill(x,y+1,z,x,roof-1,z,Blocks.IRON_BARS);part(new BlockPos(x,y,z),"mounted_lamp",Direction.NORTH);}
        void pipe(int x,int y,int from,int to){for(int z=from;z<=to;z++)part(new BlockPos(x,y,z),"pipe",Direction.NORTH);for(int wall:new int[]{4,5})put(x,y,wall,Blocks.CUT_COPPER);part(new BlockPos(x,y,to),"pipe_elbow",Direction.NORTH);fill(x,1,to,x,y-1,to,Blocks.POLISHED_DEEPSLATE);int target=x==-6?-5:0;for(int bx=x;bx<=target;bx++)part(new BlockPos(bx,y,to+1),"pipe",Direction.EAST);part(new BlockPos(target,y-1,to+1),"pipe_riser",Direction.NORTH);}
    }
}
