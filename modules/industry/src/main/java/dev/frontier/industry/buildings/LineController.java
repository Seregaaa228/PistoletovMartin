package dev.frontier.industry.buildings;

import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;

public final class LineController extends Block {
    public static final IntegerProperty LINE=IntegerProperty.create("line",0,2);
    public LineController(){super(Properties.of().strength(2,5).sound(SoundType.METAL).noLootTable());registerDefaultState(stateDefinition.any().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.NORTH).setValue(LINE,0).setValue(BlockStateProperties.POWERED,false));}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b){b.add(BlockStateProperties.HORIZONTAL_FACING,BlockStateProperties.POWERED,LINE);}
    @Override public BlockState getStateForPlacement(BlockPlaceContext c){return defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,c.getHorizontalDirection().getOpposite());}
    @Override public BlockState rotate(BlockState s,Rotation r){return s.setValue(BlockStateProperties.HORIZONTAL_FACING,r.rotate(s.getValue(BlockStateProperties.HORIZONTAL_FACING)));}
    @Override public BlockState mirror(BlockState s,Mirror m){return s.rotate(m.getRotation(s.getValue(BlockStateProperties.HORIZONTAL_FACING)));}
}
