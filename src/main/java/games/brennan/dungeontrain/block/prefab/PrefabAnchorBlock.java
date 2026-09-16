package games.brennan.dungeontrain.block.prefab;

import com.mojang.serialization.MapCodec;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.OpenPrefabAnchorPacket;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The <b>prefab anchor</b> — the marker an author places in a template to say "a prefab goes
 * here". Bound to one prefab by name ({@link PrefabAnchorBlockEntity}); at generation time
 * {@code train.PrefabResolver} replaces it with that prefab, stamped so the prefab's local
 * origin lands on the anchor and its local {@code +X} runs along {@link #FACING}.
 *
 * <p>Only ever seen in the editor: every gameplay stamp resolves anchors before a player can
 * reach them, and an anchor the resolver cannot honour (unbound, unknown name, a cycle) becomes
 * air. Randomness is not the anchor's business — put anchors bound to different prefabs in one
 * cell's block-variant pool and the pool's roll decides which lands.</p>
 *
 * <p>Right-click opens the binding screen ({@link OpenPrefabAnchorPacket}).</p>
 */
public final class PrefabAnchorBlock extends HorizontalDirectionalBlock implements EntityBlock {

    public static final MapCodec<PrefabAnchorBlock> CODEC = simpleCodec(PrefabAnchorBlock::new);

    public PrefabAnchorBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.EAST));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(FACING);
    }

    /** Faces <i>away</i> from the placer, so the prefab grows off into the space they are looking at. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
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
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PrefabAnchorBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
        if (!(level.getBlockEntity(pos) instanceof PrefabAnchorBlockEntity anchor)) return InteractionResult.PASS;
        DungeonTrainNet.sendTo(sp, new OpenPrefabAnchorPacket(pos, anchor.prefab(),
            TrackVariantRegistry.namesFor(TrackKind.PREFAB)));
        return InteractionResult.CONSUME;
    }

    /** True when {@code state} is a prefab anchor. */
    public static boolean isAnchor(BlockState state) {
        return state.getBlock() instanceof PrefabAnchorBlock;
    }

    /**
     * The rotation that takes a prefab authored with local {@code +X} forward to an anchor facing
     * {@code facing}. {@link Direction#EAST} is the identity.
     */
    public static Rotation rotationFor(Direction facing) {
        return switch (facing) {
            case SOUTH -> Rotation.CLOCKWISE_90;
            case WEST -> Rotation.CLOCKWISE_180;
            case NORTH -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }
}
