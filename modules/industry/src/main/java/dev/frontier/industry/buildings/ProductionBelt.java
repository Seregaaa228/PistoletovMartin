package dev.frontier.industry.buildings;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.shapes.*;

/** Real transport, independent of production accounting: never spawns or duplicates products. */
public final class ProductionBelt extends Block {
    public static final double SPEED=.075;
    private static final VoxelShape SHAPE=box(0,0,0,16,12,16);
    public ProductionBelt() {
        super(Properties.of().strength(2,5).sound(SoundType.METAL).noLootTable().noOcclusion());
        registerDefaultState(stateDefinition.any().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.SOUTH).setValue(BlockStateProperties.POWERED,false));
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder) {
        builder.add(BlockStateProperties.HORIZONTAL_FACING,BlockStateProperties.POWERED);
    }
    @Override public VoxelShape getShape(BlockState s,BlockGetter level,BlockPos p,CollisionContext context){return SHAPE;}
    @Override public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext c){return defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,c.getHorizontalDirection());}
    @Override public BlockState rotate(BlockState s,net.minecraft.world.level.block.Rotation r){return s.setValue(BlockStateProperties.HORIZONTAL_FACING,r.rotate(s.getValue(BlockStateProperties.HORIZONTAL_FACING)));}
    @Override public BlockState mirror(BlockState s,net.minecraft.world.level.block.Mirror m){return s.rotate(m.getRotation(s.getValue(BlockStateProperties.HORIZONTAL_FACING)));}
    @Override public void entityInside(BlockState state,Level level,BlockPos pos,Entity entity){carry(state,level,pos,entity);}
    @Override public void stepOn(Level level,BlockPos pos,BlockState state,Entity entity){carry(state,level,pos,entity);super.stepOn(level,pos,state,entity);}
    public static void carry(BlockState state,Level level,BlockPos pos,Entity entity) {
        if(level.isClientSide || !state.getValue(BlockStateProperties.POWERED) || entity.isShiftKeyDown()
            || entity.isPassenger() || entity.getY()<pos.getY()+.65 || entity.getY()>pos.getY()+1.1)return;
        Direction f=state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        var velocity=entity.getDeltaMovement();
        entity.setDeltaMovement(f.getStepX()*SPEED,velocity.y,f.getStepZ()*SPEED);
        entity.hurtMarked=true;
    }
}
