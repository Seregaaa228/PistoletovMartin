package dev.frontier.industry.architecture;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Static modular meshes: no block entities or per-frame machinery animation. */
public final class IndustrialDetailBlock extends Block {
    public static final String[] PARTS={"turbine","transformer","machine","vent","laboratory","server",
        "floor","wall_brick","wall_white","wall_metal","window","window_lab","column","beam",
        "roof","roof_saw","pipe","pipe_elbow","pipe_riser","rail","crane","hook","chimney_white",
        "chimney_red","chimney_cap","silo","silo_cap","conveyor","press","rack","duct","grate",
        "insulator","lamp","sign_power","sign_factory","sign_research","drone_bench","missile_fixture"};
    private final String part;
    public IndustrialDetailBlock(String part) {
        super(Properties.of().strength(2f,5f).sound(SoundType.METAL).noLootTable().noOcclusion()
            .lightLevel(s->part.equals("lamp")?13:0));
        this.part=part;
        registerDefaultState(stateDefinition.any().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.NORTH));
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b){b.add(BlockStateProperties.HORIZONTAL_FACING);}
    @Override public VoxelShape getShape(BlockState s,BlockGetter l,BlockPos p,CollisionContext c) {
        Direction f=s.getValue(BlockStateProperties.HORIZONTAL_FACING);
        boolean side=f==Direction.EAST||f==Direction.WEST;
        return switch(part) {
            case "floor" -> box(0,0,0,16,16,16);
            case "roof","grate" -> box(0,0,0,16,3,16);
            case "window","window_lab","wall_brick","wall_white","wall_metal" -> side?box(5,0,0,11,16,16):box(0,0,5,16,16,11);
            case "column","pipe_riser","insulator" -> box(4,0,4,12,16,12);
            case "beam","pipe","rail","crane","duct" -> side?box(0,5,4,16,13,12):box(4,5,0,12,13,16);
            case "hook" -> box(5,0,5,11,16,11);
            case "lamp","sign_power","sign_factory","sign_research" -> box(0,4,5,16,13,11);
            case "vent","conveyor","laboratory" -> box(1,0,1,15,12,15);
            default -> box(1,0,1,15,16,15);
        };
    }
    @Override public boolean propagatesSkylightDown(BlockState s,BlockGetter l,BlockPos p){return true;}
}
