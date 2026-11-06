package dev.frontier.industry.buildings;

import dev.frontier.industry.SiteType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.*;

public final class BuildingNameplate extends BaseEntityBlock {
    public final SiteType siteType;
    public final int line;
    public BuildingNameplate(SiteType type) {this(type,-1);}
    public BuildingNameplate(SiteType type,int line) {
        super(Properties.of().strength(2,5).sound(SoundType.METAL).noLootTable().noOcclusion());siteType=type;this.line=line;
        registerDefaultState(stateDefinition.any().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.NORTH));
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b){b.add(BlockStateProperties.HORIZONTAL_FACING);}
    @Override public RenderShape getRenderShape(BlockState state){return RenderShape.MODEL;}
    @Override public BlockState rotate(BlockState s,Rotation r){return s.setValue(BlockStateProperties.HORIZONTAL_FACING,r.rotate(s.getValue(BlockStateProperties.HORIZONTAL_FACING)));}
    @Override public BlockState mirror(BlockState s,Mirror m){return s.rotate(m.getRotation(s.getValue(BlockStateProperties.HORIZONTAL_FACING)));}
    @Override public VoxelShape getShape(BlockState s,BlockGetter l,BlockPos p,CollisionContext c){
        var f=s.getValue(BlockStateProperties.HORIZONTAL_FACING);
        return switch(f){case NORTH->box(-16,1,14,32,15,16);case SOUTH->box(-16,1,0,32,15,2);case EAST->box(0,1,-16,2,15,32);default->box(14,1,-16,16,15,32);};
    }
    @Override public BlockEntity newBlockEntity(BlockPos p,BlockState s){return new SignEntity(p,s);}
    public static final class SignEntity extends BlockEntity {
        public SignEntity(BlockPos p,BlockState s){super(FunctionalBuildings.SIGN_ENTITY.get(),p,s);}
        @Override public AABB getRenderBoundingBox(){return new AABB(worldPosition).inflate(1.5);}
    }
    public String[] textKeys(){return line<0?textKeys(siteType):new String[]{"frontier_industry.sign.line_"+(char)('a'+line)+".title","frontier_industry.sign.line_stages"};}
    public static String[] textKeys(SiteType type) {
        return switch(type) {
            case POWER_STATION->new String[]{"frontier_industry.sign.power_station.title","frontier_industry.sign.power_station.subtitle"};
            case SUBSTATION->new String[]{"frontier_industry.sign.substation.title","frontier_industry.sign.substation.subtitle"};
            case DRONE_FACTORY->new String[]{"frontier_industry.sign.drone_factory.title","frontier_industry.sign.drone_factory.subtitle"};
            case VEHICLE_FACTORY->new String[]{"frontier_industry.sign.vehicle_factory.title","frontier_industry.sign.vehicle_factory.subtitle"};
            case EQUIPMENT_FACTORY->new String[]{"frontier_industry.sign.equipment_factory.title","frontier_industry.sign.equipment_factory.subtitle"};
            case MISSILE_FACTORY->new String[]{"frontier_industry.sign.missile_factory.title","frontier_industry.sign.missile_factory.subtitle"};
            case RESEARCH_CENTER->new String[]{"frontier_industry.sign.research_center.title","frontier_industry.sign.research_center.subtitle"};
        };
    }
}
