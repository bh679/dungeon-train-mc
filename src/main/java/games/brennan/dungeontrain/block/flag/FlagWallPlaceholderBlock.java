package games.brennan.dungeontrain.block.flag;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Wall Dungeon Train Flag N: the wall-hung twin of {@link FlagPlaceholderBlock}, keeping the wall
 * banner's {@link #FACING} so it becomes a {@code <colour>_wall_banner} facing the same way. Placed
 * by the same item as the standing flag, and named after it, as vanilla wall banners are.
 */
public final class FlagWallPlaceholderBlock extends Block {

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    private static final Map<Direction, VoxelShape> SHAPES = Map.of(
        Direction.NORTH, Block.box(0.0, 0.0, 14.0, 16.0, 12.5, 16.0),
        Direction.SOUTH, Block.box(0.0, 0.0, 0.0, 16.0, 12.5, 2.0),
        Direction.WEST, Block.box(14.0, 0.0, 0.0, 16.0, 12.5, 16.0),
        Direction.EAST, Block.box(0.0, 0.0, 0.0, 2.0, 12.5, 16.0));

    private final int slot;

    public FlagWallPlaceholderBlock(int slot, Properties properties) {
        super(properties);
        this.slot = slot;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    /** The flag number, 1-based. */
    public int slot() {
        return slot;
    }

    @Override
    public String getDescriptionId() {
        return asItem().getDescriptionId();
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        LevelReader level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        for (Direction looking : context.getNearestLookingDirections()) {
            if (!looking.getAxis().isHorizontal()) continue;
            BlockState state = defaultBlockState().setValue(FACING, looking.getOpposite());
            if (state.canSurvive(level, pos)) return state;
        }
        return null;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return level.getBlockState(pos.relative(state.getValue(FACING).getOpposite())).isSolid();
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }
}
