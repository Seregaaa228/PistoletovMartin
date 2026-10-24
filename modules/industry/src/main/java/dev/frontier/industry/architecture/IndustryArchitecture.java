package dev.frontier.industry.architecture;

import dev.frontier.industry.IndustryContent;
import dev.frontier.industry.SiteType;
import dev.frontier.industry.engine.Blueprint;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Version three is built from original, static architectural and machinery modules. */
public final class IndustryArchitecture {
    private IndustryArchitecture() {}
    public static Map<BlockPos,BlockState> blocks(SiteType type,BlockPos origin) {
        Layout b=new Layout();var f=Blueprint.bounds(type,3);
        b.fill(f.minX(),0,f.minZ(),f.maxX(),0,f.maxZ(),"floor");
        switch(type) {
            case POWER_STATION -> station(b);
            case SUBSTATION -> substation(b);
            case DRONE_FACTORY -> drone(b);
            case VEHICLE_FACTORY -> vehicle(b);
            case EQUIPMENT_FACTORY -> equipment(b);
            case MISSILE_FACTORY -> missile(b);
            case RESEARCH_CENTER -> laboratory(b);
        }
        BlockPos c=Blueprint.controller(type,BlockPos.ZERO,3);
        b.put(c.getX(),c.getY(),c.getZ(),IndustryContent.controller(type).defaultBlockState());
        Map<BlockPos,BlockState> result=new LinkedHashMap<>();
        b.map.forEach((p,s)->result.put(origin.offset(p),s));return result;
    }
    private static void station(Layout b) {
        b.shell(-9,-8,3,3,6,"wall_brick","window");
        b.open(-2,1,-8,1,4,-8);b.fill(-2,5,-8,1,5,-8,"beam",Direction.EAST);
        b.roof(-9,-8,3,3,7,false);b.put(-4,5,-8,"sign_power");
        b.shell(-9,4,3,8,9,"wall_metal","wall_metal");b.roof(-9,4,3,8,10,false);
        for(int x:new int[]{-6,0}) {
            for(int y=10;y<24;y++)b.put(x,y,7,(y>=14&&y<=16||y>=21)?"chimney_red":"chimney_white");
            b.put(x,24,7,"chimney_cap");
        }
        for(int x:new int[]{-6,-2}) {
            b.put(x,2,-1,"turbine");b.line(x,4,0,3,"pipe");b.put(x,4,4,"pipe_elbow");
            b.fill(x,5,4,x,8,4,"pipe_riser");
        }
        for(int z:new int[]{-3,5}) {
            b.fill(7,1,z,7,6,z,"silo");b.put(7,7,z,"silo_cap");
            b.put(7,3,z-2,"pipe_elbow");b.line(7,3,z-1,z+1,"pipe");
        }
        for(int z=-6;z<=2;z++)b.put(-10,4,z,"pipe");
        for(int y=1;y<=4;y++)b.put(-10,y,3,"pipe_riser");
        for(int z:new int[]{-5,1})for(int x:new int[]{-7,1})b.put(x,6,z,"lamp");
        b.put(-7,8,-4,"vent");b.put(1,8,1,"vent");
        b.fill(-8,8,-7,2,8,-7,"rail",Direction.EAST);
    }
    private static void substation(Layout b) {
        for(int x:new int[]{-2,2})b.put(x,2,0,"transformer");
        for(int x:new int[]{-4,4}){b.fill(x,1,3,x,5,3,"column");b.put(x,6,3,"insulator");}
        b.fill(-4,5,3,4,5,3,"beam",Direction.EAST);b.fill(-3,6,3,3,6,3,"pipe",Direction.EAST);
        for(int z=-3;z<=4;z++){b.put(-5,1,z,"rail");b.put(5,1,z,"rail");}
        b.fill(-5,1,5,5,1,5,"rail",Direction.EAST);
        b.shell(-4,-4,-1,-2,3,"wall_white","window_lab");b.open(-2,1,-4,-2,2,-4);b.roof(-4,-4,-1,-2,4,false);
        b.put(0,3,3,"lamp");
    }
    private static void drone(Layout b) {
        b.shell(-6,-5,6,5,4,"wall_white","window_lab");b.open(-1,1,-5,1,2,-5);
        b.roof(-6,-5,0,5,5,false);b.shell(1,-5,6,5,6,"wall_white","window_lab");b.roof(1,-5,6,5,7,true);
        b.open(1,1,-4,1,3,4);
        for(int z:new int[]{0,3})b.put(-4,1,z,"drone_bench");
        b.put(4,1,2,"machine");b.put(4,1,4,"server");
        b.put(-4,6,2,"vent");b.put(-2,6,2,"duct",Direction.EAST);
        b.canopy(-2,2,-6,3);b.put(3,4,-5,"sign_factory");
        for(int x:new int[]{-4,4})b.put(x,3,-3,"lamp");
    }
    private static void vehicle(Layout b) {
        b.shell(-7,-6,7,6,6,"wall_brick","window");b.open(-3,1,-6,3,4,-6);
        b.roof(-7,-6,7,6,7,true);b.put(0,5,-6,"sign_factory");
        for(int x:new int[]{-5,5}) {
            b.fill(x,1,1,x,4,1,"column");b.fill(x,1,5,x,4,5,"column");b.line(x,5,-4,5,"crane");
        }
        b.fill(-5,5,2,5,5,2,"crane",Direction.EAST);b.put(0,3,2,"hook");
        b.put(-5,1,3,"press");b.put(5,1,3,"machine");
        for(int x=-3;x<=3;x++)b.put(x,0,3,"grate");
        for(int x:new int[]{-5,5})b.put(x,1,-2,"rack");
        for(int z:new int[]{-3,3})for(int x:new int[]{-6,6})b.put(x,5,z,"lamp");
        b.put(5,8,4,"vent");b.put(5,9,4,"duct");
    }
    private static void equipment(Layout b) {
        b.shell(-6,-5,6,5,5,"wall_brick","window");b.open(-1,1,-5,1,3,-5);
        b.roof(-6,-5,6,5,6,true);b.canopy(-2,2,-6,4);b.put(3,4,-5,"sign_factory");
        for(int x:new int[]{-4,4}) {
            b.put(x,1,2,"press");b.put(x,1,-1,"machine");b.put(x,1,4,"rack");
            b.fill(x,6,4,x,9,4,"pipe_riser");b.put(x,10,4,"vent");b.put(x,5,0,"lamp");
        }
        for(int z=0;z<=3;z++)b.put(-2,1,z,"conveyor");
    }
    private static void missile(Layout b) {
        b.shell(-6,-7,6,7,7,"wall_metal","window");b.open(-2,1,-7,2,4,-7);
        b.roof(-6,-7,6,7,8,true);b.put(0,5,-7,"sign_factory");
        for(int z=-4;z<=4;z++){b.put(-1,10,z,"window_lab",Direction.WEST);b.put(1,10,z,"window_lab",Direction.EAST);b.put(0,11,z,"roof");}
        for(int z:new int[]{-2,3}){b.put(-3,1,z,"missile_fixture");b.put(4,1,z,"machine");}
        for(int z=-3;z<=5;z++)b.put(0,1,z,"conveyor");
        b.fill(-5,6,5,5,6,5,"crane",Direction.EAST);b.put(0,4,5,"hook");
        for(int z:new int[]{-4,0,4})for(int x:new int[]{-5,5})b.put(x,6,z,"lamp");b.put(4,10,5,"vent");
    }
    private static void laboratory(Layout b) {
        b.shell(-6,-5,6,5,4,"wall_white","window_lab");b.open(-1,1,-5,1,2,-5);
        b.roof(-7,-6,7,6,5,false);b.canopy(-2,2,-6,3);b.put(3,4,-5,"sign_research");
        b.shell(-3,-1,3,3,6,"wall_white","window_lab");b.open(-3,1,-1,3,4,3);b.open(-2,5,0,2,5,2);b.roof(-3,-1,3,3,7,false);
        for(int x:new int[]{-4,4})for(int z:new int[]{0,3})b.put(x,1,z,"laboratory");
        for(int x:new int[]{-4,-2,4})b.put(x,1,4,"server");
        b.put(5,6,1,"vent");b.put(5,6,2,"duct");b.put(5,6,3,"vent");
        for(int y=6;y<=8;y++)b.put(-5,y,3,"pipe_riser");b.put(-5,9,3,"insulator");
        for(int z:new int[]{-3,3})b.put(0,4,z,"lamp");
    }
    private static final class Layout {
        final Map<BlockPos,BlockState> map=new LinkedHashMap<>();
        void put(int x,int y,int z,String part){put(x,y,z,part,Direction.NORTH);}
        void put(int x,int y,int z,String part,Direction f){put(x,y,z,IndustryContent.detail(part).defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,f));}
        void put(int x,int y,int z,BlockState s){map.put(new BlockPos(x,y,z),s);}
        void fill(int x1,int y1,int z1,int x2,int y2,int z2,String p){fill(x1,y1,z1,x2,y2,z2,p,Direction.NORTH);}
        void fill(int x1,int y1,int z1,int x2,int y2,int z2,String p,Direction f){for(int x=x1;x<=x2;x++)for(int y=y1;y<=y2;y++)for(int z=z1;z<=z2;z++)put(x,y,z,p,f);}
        void line(int x,int y,int z1,int z2,String part){fill(x,y,z1,x,y,z2,part);}
        void open(int x1,int y1,int z1,int x2,int y2,int z2){for(int x=x1;x<=x2;x++)for(int y=y1;y<=y2;y++)for(int z=z1;z<=z2;z++)map.remove(new BlockPos(x,y,z));}
        void shell(int x1,int z1,int x2,int z2,int h,String wall,String window) {
            for(int y=1;y<=h;y++)for(int x=x1;x<=x2;x++)for(int z=z1;z<=z2;z++) {
                if(x!=x1&&x!=x2&&z!=z1&&z!=z2)continue;
                boolean col=(x==x1||x==x2)&&(z==z1||z==z2)||(z==z1||z==z2)&&(x-x1)%4==0||(x==x1||x==x2)&&(z-z1)%4==0;
                Direction face=z==z1?Direction.NORTH:z==z2?Direction.SOUTH:x==x1?Direction.WEST:Direction.EAST;
                put(x,y,z,col?"column":y==1||y==h?wall:window,face);
            }
        }
        void roof(int x1,int z1,int x2,int z2,int y,boolean saw) {
            for(int x=x1;x<=x2;x++)for(int z=z1;z<=z2;z++) {
                int rise=saw?Math.floorMod(z-z1,3)/2:0;
                put(x,y+rise,z,saw&&Math.floorMod(z-z1,3)==1?"roof_saw":"roof");
            }
        }
        void canopy(int x1,int x2,int z,int y){fill(x1,y,z,x2,y,z,"roof");put(x1,1,z,"column");put(x1,2,z,"column");put(x2,1,z,"column");put(x2,2,z,"column");}
    }
}
