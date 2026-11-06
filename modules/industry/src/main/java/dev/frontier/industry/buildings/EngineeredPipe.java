package dev.frontier.industry.buildings;

import java.util.function.Function;
import net.minecraft.core.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;

/** V9 ports never auto-join terminals, structural copper or an incompatible circuit. */
public final class EngineeredPipe extends Block {
    public final String circuit;
    public EngineeredPipe(String circuit){super(Properties.of().strength(2,6).sound(SoundType.COPPER).noOcclusion().noLootTable());this.circuit=circuit;var s=stateDefinition.any().setValue(ProcessPipe.MOUNTED,false);for(var p:ProcessPipe.CONNECTIONS.values())s=s.setValue(p,false);registerDefaultState(s);}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b){ProcessPipe.CONNECTIONS.values().forEach(b::add);b.add(ProcessPipe.MOUNTED);}
    public boolean connects(BlockState other,Direction outward){return other.getBlock() instanceof EngineeredPipe p&&p.circuit.equals(circuit)||other.getBlock() instanceof ProcessEquipment equipment&&equipment.kind.equals("port_"+circuit)&&other.getValue(BlockStateProperties.FACING)==outward.getOpposite();}
    public BlockState joined(BlockState state,Function<Direction,BlockState> neighbour){for(var d:Direction.values())state=state.setValue(ProcessPipe.CONNECTIONS.get(d),connects(neighbour.apply(d),d));return state.setValue(ProcessPipe.MOUNTED,neighbour.apply(Direction.DOWN).getBlock() instanceof ProcessEquipment p&&p.kind.equals("pipe_support"));}
    @Override public BlockState updateShape(BlockState s,Direction d,BlockState other,LevelAccessor l,BlockPos p,BlockPos q){return joined(s,side->l.getBlockState(p.relative(side)));}
    @Override public net.minecraft.world.phys.shapes.VoxelShape getShape(BlockState s,BlockGetter l,BlockPos p,net.minecraft.world.phys.shapes.CollisionContext c){var shape=Block.box(4,4,4,12,12,12);for(var d:Direction.values())if(s.getValue(ProcessPipe.CONNECTIONS.get(d)))shape=net.minecraft.world.phys.shapes.Shapes.or(shape,switch(d){case NORTH->box(4,4,0,12,12,8);case SOUTH->box(4,4,8,12,12,16);case EAST->box(8,4,4,16,12,12);case WEST->box(0,4,4,8,12,12);case UP->box(4,8,4,12,16,12);case DOWN->box(4,0,4,12,8,12);});return shape;}
    @Override public BlockState rotate(BlockState s,Rotation r){var out=defaultBlockState().setValue(ProcessPipe.MOUNTED,s.getValue(ProcessPipe.MOUNTED));for(var d:Direction.values())out=out.setValue(ProcessPipe.CONNECTIONS.get(r.rotate(d)),s.getValue(ProcessPipe.CONNECTIONS.get(d)));return out;}
    @Override public BlockState mirror(BlockState s,Mirror m){var out=defaultBlockState().setValue(ProcessPipe.MOUNTED,s.getValue(ProcessPipe.MOUNTED));for(var d:Direction.values())out=out.setValue(ProcessPipe.CONNECTIONS.get(m.mirror(d)),s.getValue(ProcessPipe.CONNECTIONS.get(d)));return out;}
}
