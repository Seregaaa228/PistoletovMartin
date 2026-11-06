package dev.frontier.industry.buildings;

import net.minecraft.core.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.shapes.*;

/** Static low-element Blockbench props; no individual ticking block entities. */
public final class WorkshopProp extends Block {
    public final String kind;
    public WorkshopProp(String kind){super(Properties.of().strength(2,5).sound(SoundType.METAL).noOcclusion().noLootTable().lightLevel(s->kind.equals("mounted_lamp")||kind.equals("floor_lamp")||kind.equals("wall_lamp")||kind.equals("post_lantern")?15:kind.equals("research_server")?3:0));this.kind=kind;registerDefaultState(stateDefinition.any().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.NORTH));}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b){b.add(BlockStateProperties.HORIZONTAL_FACING);}
    @Override public VoxelShape getShape(BlockState s,BlockGetter l,BlockPos p,CollisionContext c){return switch(kind){case "research_server","tool_cabinet","materials_rack","bookcase"->box(1,0,1,15,30,15);case "floor_lamp"->Shapes.block();case "stair_railing"->box(4,0,0,12,24,16);case "courtyard_bench","lounge_chair"->box(0,0,1,16,20,15);case "planter"->box(1,0,1,15,24,15);case "coffee_table"->box(1,0,1,15,10,15);case "pallet"->box(0,0,0,16,10,16);case "parts_bin"->box(1,0,1,15,12,15);case "sealed_drum"->box(1,0,1,15,15,15);case "wall_panel","wall_lamp"->switch(s.getValue(BlockStateProperties.HORIZONTAL_FACING)){case NORTH->box(1,1,12,15,15,16);case SOUTH->box(1,1,0,15,15,4);case EAST->box(0,1,1,4,15,15);default->box(12,1,1,16,15,15);};case "mounted_lamp"->box(2,9,2,14,16,14);default->box(2,0,2,14,15,14);};}
    @Override public BlockState rotate(BlockState s,Rotation r){return s.setValue(BlockStateProperties.HORIZONTAL_FACING,r.rotate(s.getValue(BlockStateProperties.HORIZONTAL_FACING)));}
    @Override public BlockState mirror(BlockState s,Mirror m){return s.rotate(m.getRotation(s.getValue(BlockStateProperties.HORIZONTAL_FACING)));}
}
