package games.brennan.dungeontrain.block.engine;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The Drifting Engine Block: what makes a carriage drift. It holds coal (only coal, at most 64), and a
 * drifting carriage burns one each time it joins a world. A carriage that leaves a world with none left
 * is retired from the relay pool — see {@link games.brennan.dungeontrain.train.engine.DriftEngine}.
 *
 * <p>{@link #LIT} follows the coal: lit while there is any, cold at zero, so a dying carriage is
 * visible from across the room. Coal goes in and never comes out — not through the screen, not
 * through a hopper, not by breaking the block. Only the carriage's builder may break it
 * ({@code EngineBlockEvents}); it takes as long as obsidian and always drops itself, empty. Set down
 * in an ordinary carriage, it makes that carriage drift.</p>
 */
public class EngineBlock extends BaseEntityBlock {

    public static final MapCodec<EngineBlock> CODEC = simpleCodec(EngineBlock::new);
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    public EngineBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(LIT, true));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LIT);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new EngineBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof EngineBlockEntity engine) player.openMenu(engine);
        return InteractionResult.CONSUME;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            if (level.getBlockEntity(pos) instanceof EngineBlockEntity
                    && level instanceof ServerLevel server) {
                EngineFuelTracker.onEngineRemoved(server, pos);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /**
     * Always drops, whatever the tool. Break speed is untouched: NeoForge times a break from the
     * player's harvest check against the block's tags (obsidian's diamond-pickaxe rule), while the drop
     * asks this method — so it mines as slowly as obsidian without the right pickaxe, yet still drops.
     */
    @Override
    public boolean canHarvestBlock(BlockState state, BlockGetter level, BlockPos pos, Player player) {
        return true;
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return AbstractContainerMenu.getRedstoneSignalFromBlockEntity(level.getBlockEntity(pos));
    }

    /** Light like a lit furnace while there is coal; dark when cold. */
    public static int lightLevel(BlockState state) {
        return state.getValue(LIT) ? 13 : 0;
    }
}
