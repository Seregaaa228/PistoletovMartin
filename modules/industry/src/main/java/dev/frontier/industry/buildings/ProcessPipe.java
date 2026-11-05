package dev.frontier.industry.buildings;

import dev.frontier.industry.IndustryContent;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.shapes.*;

/** Decorative process pipe. Every connected arm meets the exact centre of its voxel face. */
public final class ProcessPipe extends Block {
    public static final BooleanProperty MOUNTED=BooleanProperty.create("mounted");
    public static final Map<Direction,BooleanProperty> CONNECTIONS=Map.of(Direction.NORTH,BlockStateProperties.NORTH,Direction.SOUTH,BlockStateProperties.SOUTH,Direction.EAST,BlockStateProperties.EAST,Direction.WEST,BlockStateProperties.WEST,Direction.UP,BlockStateProperties.UP,Direction.DOWN,BlockStateProperties.DOWN);
    public ProcessPipe(){super(Properties.of().strength(2,6).sound(SoundType.COPPER).noOcclusion().noLootTable());var s=stateDefinition.any().setValue(MOUNTED,false);for(var p:CONNECTIONS.values())s=s.setValue(p,false);registerDefaultState(s);}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b){CONNECTIONS.values().forEach(b::add);b.add(MOUNTED);}
    public static boolean socket(BlockState s){return s.getBlock() instanceof ProcessPipe||s.is(Blocks.CUT_COPPER)||s.is(IndustryContent.detail("machine"))||s.is(IndustryContent.detail("turbine"));}
    public static BlockState connections(BlockState s,java.util.function.Function<Direction,BlockState> neighbour){for(var d:Direction.values())s=s.setValue(CONNECTIONS.get(d),socket(neighbour.apply(d)));return s.setValue(MOUNTED,neighbour.apply(Direction.DOWN).is(Blocks.POLISHED_DEEPSLATE));}
    @Override public BlockState getStateForPlacement(BlockPlaceContext c){return connections(defaultBlockState(),d->c.getLevel().getBlockState(c.getClickedPos().relative(d)));}
    @Override public BlockState updateShape(BlockState s,Direction d,BlockState other,LevelAccessor l,BlockPos p,BlockPos q){s=s.setValue(CONNECTIONS.get(d),socket(other));return d==Direction.DOWN?s.setValue(MOUNTED,other.is(Blocks.POLISHED_DEEPSLATE)):s;}
    @Override public VoxelShape getShape(BlockState s,BlockGetter l,BlockPos p,CollisionContext c){VoxelShape result=box(4,4,4,12,12,12);for(var d:Direction.values())if(s.getValue(CONNECTIONS.get(d)))result=Shapes.or(result,switch(d){case NORTH->box(4,4,0,12,12,8);case SOUTH->box(4,4,8,12,12,16);case WEST->box(0,4,4,8,12,12);case EAST->box(8,4,4,16,12,12);case DOWN->box(4,0,4,12,8,12);case UP->box(4,8,4,12,16,12);});return result;}
    @Override public BlockState rotate(BlockState s,Rotation r){var out=defaultBlockState().setValue(MOUNTED,s.getValue(MOUNTED));for(var d:Direction.values())out=out.setValue(CONNECTIONS.get(r.rotate(d)),s.getValue(CONNECTIONS.get(d)));return out;}
    @Override public BlockState mirror(BlockState s,Mirror m){var out=defaultBlockState().setValue(MOUNTED,s.getValue(MOUNTED));for(var d:Direction.values())out=out.setValue(CONNECTIONS.get(m.mirror(d)),s.getValue(CONNECTIONS.get(d)));return out;}
}
