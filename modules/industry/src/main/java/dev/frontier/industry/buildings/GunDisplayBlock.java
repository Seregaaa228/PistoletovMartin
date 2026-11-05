package dev.frontier.industry.buildings;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Original TaCZ weapon sample; it never exposes the displayed gun as inventory. */
public final class GunDisplayBlock extends BaseEntityBlock {
    private final String gunId;
    private static final VoxelShape SHAPE = Block.box(2, 0, 1, 14, 3, 15);

    public GunDisplayBlock(String gunId) {
        super(Properties.of().strength(3, 8).sound(SoundType.METAL).noLootTable().noOcclusion().noCollission());
        this.gunId = gunId;
        registerDefaultState(stateDefinition.any().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
    }

    public String gunId() { return gunId; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(BlockStateProperties.HORIZONTAL_FACING); }
    // The retained block JSONs contain obsolete homemade weapon/stand meshes.
    // Only the block entity renderer may draw a placed sample.
    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.INVISIBLE; }
    @Override public VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) { return SHAPE; }
    @Override public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(BlockStateProperties.HORIZONTAL_FACING,
            rotation.rotate(state.getValue(BlockStateProperties.HORIZONTAL_FACING)));
    }
    @Override public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(BlockStateProperties.HORIZONTAL_FACING)));
    }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new GunDisplayEntity(pos, state); }
    @Override public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        return InteractionResult.PASS;
    }

    public static final class GunDisplayEntity extends BlockEntity {
        public GunDisplayEntity(BlockPos pos, BlockState state) { super(FunctionalBuildings.GUN_DISPLAY.get(), pos, state); }
        public String gunId() { return getBlockState().getBlock() instanceof GunDisplayBlock display ? display.gunId() : ""; }
        @Override public AABB getRenderBoundingBox() {
            // The belt surface is below this cell, and the RPG slightly overhangs it.
            return new AABB(getBlockPos()).inflate(.5);
        }
        @Override protected void saveAdditional(CompoundTag tag) {
            super.saveAdditional(tag);
            tag.putString("GunId", gunId());
        }
        @Override public void load(CompoundTag tag) {
            super.load(tag);
        }
    }
}
