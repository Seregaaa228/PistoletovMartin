package dev.frontier.industry.buildings;

import net.minecraft.core.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.shapes.*;

/** One physically occupied cell of a modular machine; ports have an explicit outward normal. */
public final class ProcessEquipment extends Block {
    public final String kind;
    public ProcessEquipment(String kind){super(Properties.of().strength(3,8).sound(SoundType.METAL).noOcclusion().noLootTable());this.kind=kind;registerDefaultState(stateDefinition.any().setValue(BlockStateProperties.FACING,Direction.NORTH));}
    @Override public net.minecraft.world.InteractionResult use(BlockState state,Level level,BlockPos pos,net.minecraft.world.entity.player.Player player,net.minecraft.world.InteractionHand hand,net.minecraft.world.phys.BlockHitResult hit){
        return OutputWarehouse.accessPoint(state)?OutputWarehouse.open(level,pos,player):super.use(state,level,pos,player,hand,hit);
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b){b.add(BlockStateProperties.FACING);}
    @Override public VoxelShape getShape(BlockState s,BlockGetter l,BlockPos p,CollisionContext c){return switch(kind){case "lab_microscope","lab_analyser","lab_console"->box(1,0,1,15,14,15);case "pipe_support","safety_barrier"->box(5,0,5,11,16,11);case "cable_tray"->box(0,0,3,16,4,13);default->box(0,0,0,16,16,16);};}
    @Override public BlockState rotate(BlockState s,Rotation r){return s.setValue(BlockStateProperties.FACING,r.rotate(s.getValue(BlockStateProperties.FACING)));}
    @Override public BlockState mirror(BlockState s,Mirror m){return s.rotate(m.getRotation(s.getValue(BlockStateProperties.FACING)));}
}
