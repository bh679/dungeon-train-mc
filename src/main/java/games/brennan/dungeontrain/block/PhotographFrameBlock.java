package games.brennan.dungeontrain.block;

import com.mojang.serialization.MapCodec;
import games.brennan.dungeontrain.block.entity.PhotographFrameBlockEntity;
import games.brennan.dungeontrain.compat.photo.PhotoFrameLayout;
import games.brennan.dungeontrain.train.CarriageStampGuard;
import io.github.mortuusars.exposure.Exposure;
import io.github.mortuusars.exposure.world.item.PhotographItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Exposure's hanging photo frame as blocks, so it rides the train.
 *
 * <p>Exposure hangs a {@code PhotographFrameEntity} — a {@code HangingEntity}. Sable keeps wall
 * entities at plot coordinates and never draws them at a carriage's pose, so a frame hung in a
 * carriage is invisible. Blocks and their block entities do ride, which is how Fast Paintings fixes
 * the same thing for paintings. {@link games.brennan.dungeontrain.compat.photo.PhotoFrameBlockPlacer}
 * places this block instead of the entity when a frame goes on a wall; the client draws frame and
 * photo with {@code PhotographFrameBlockRenderer}.</p>
 *
 * <p><b>Cells.</b> A frame of Exposure size {@code s} is {@code (s+1)²} cells sharing Fast Paintings'
 * {@code facing} / {@code x_offset} / {@code y_offset} convention ({@link PhotoFrameLayout}); only the
 * master holds the {@link PhotographFrameBlockEntity}. Because the names and convention match,
 * {@code PaintingTransformProcessor} re-lays a frame under a mirrored, rotated or flipped stamp. Like
 * Fast Paintings' block, this one deliberately does <b>not</b> override {@code rotate}/{@code mirror}:
 * vanilla applies those at placement, after the processor has already turned the facing.</p>
 *
 * <p><b>Breaking.</b> Every cell checks the whole group on a neighbour update; a cell whose group is
 * broken (wall gone, sibling gone) returns air from {@link #updateShape}, which vanilla turns into a
 * {@code destroyBlock} — so taking any cell or the wall behind it brings the frame down in a cascade.
 * Only the master drops anything (frame item + photo, via {@link #getDrops}). The check is skipped
 * while {@link CarriageStampGuard} is held, as Fast Paintings' is, because a template is written a
 * cell at a time.</p>
 */
public class PhotographFrameBlock extends Block implements EntityBlock {

    public static final MapCodec<PhotographFrameBlock> CODEC = simpleCodec(PhotographFrameBlock::new);

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final IntegerProperty X_OFFSET = IntegerProperty.create("x_offset", 0, PhotoFrameLayout.MAX_SIZE);
    public static final IntegerProperty Y_OFFSET = IntegerProperty.create("y_offset", 0, PhotoFrameLayout.MAX_SIZE);
    public static final BooleanProperty GLASS = BooleanProperty.create("glass");

    /** One pixel thick, against the wall behind. */
    private static final VoxelShape SHAPE_NORTH = Block.box(0, 0, 15, 16, 16, 16);
    private static final VoxelShape SHAPE_SOUTH = Block.box(0, 0, 0, 16, 16, 1);
    private static final VoxelShape SHAPE_WEST = Block.box(15, 0, 0, 16, 16, 16);
    private static final VoxelShape SHAPE_EAST = Block.box(0, 0, 0, 1, 16, 16);

    public PhotographFrameBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
            .setValue(FACING, Direction.NORTH)
            .setValue(X_OFFSET, 0)
            .setValue(Y_OFFSET, 0)
            .setValue(GLASS, false));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, X_OFFSET, Y_OFFSET, GLASS);
    }

    /** Named after Exposure's item, so it reads "Photograph Frame" in every locale Exposure ships. */
    @Override
    public String getDescriptionId() {
        return Exposure.Items.PHOTOGRAPH_FRAME.get().getDescriptionId();
    }

    public static boolean isMaster(BlockState state) {
        return state.getValue(X_OFFSET) == 0 && state.getValue(Y_OFFSET) == 0;
    }

    /** Where this cell's master sits. */
    public static BlockPos masterPos(BlockPos pos, BlockState state) {
        return PhotoFrameLayout.masterOf(pos, state.getValue(FACING), state.getValue(X_OFFSET), state.getValue(Y_OFFSET));
    }

    /** The block entity of this cell's frame, or {@code null} when the master is gone. */
    @Nullable
    public static PhotographFrameBlockEntity masterEntity(BlockGetter level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof PhotographFrameBlock)) return null;
        BlockEntity be = level.getBlockEntity(masterPos(pos, state));
        return be instanceof PhotographFrameBlockEntity frame ? frame : null;
    }

    // ---- shape -------------------------------------------------------------------------------

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case SOUTH -> SHAPE_SOUTH;
            case WEST -> SHAPE_WEST;
            case EAST -> SHAPE_EAST;
            default -> SHAPE_NORTH;
        };
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    /** The block entity renderer draws frame and photo; the block model only supplies break particles. */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    // ---- survival ----------------------------------------------------------------------------

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return isGroupIntact(level, pos, state);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (CarriageStampGuard.isActive()) return state;
        return isGroupIntact(level, pos, state) ? state : Blocks.AIR.defaultBlockState();
    }

    /**
     * True if this cell's whole frame stands: the master with its block entity, every cell of the
     * frame's size with matching offsets, and a solid wall behind each one (Exposure's own rule).
     */
    public static boolean isGroupIntact(BlockGetter level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof PhotographFrameBlock)) return false;
        Direction facing = state.getValue(FACING);
        BlockPos master = masterPos(pos, state);
        if (!(level.getBlockEntity(master) instanceof PhotographFrameBlockEntity be)) return false;
        boolean glass = state.getValue(GLASS);
        for (PhotoFrameLayout.Cell cell : PhotoFrameLayout.cells(master, facing, be.size())) {
            BlockState s = level.getBlockState(cell.pos());
            if (!(s.getBlock() instanceof PhotographFrameBlock)
                || s.getValue(FACING) != facing
                || s.getValue(GLASS) != glass
                || s.getValue(X_OFFSET) != cell.xOffset()
                || s.getValue(Y_OFFSET) != cell.yOffset()) {
                return false;
            }
            if (!level.getBlockState(PhotoFrameLayout.wallBehind(cell.pos(), facing)).isSolid()) return false;
        }
        return true;
    }

    // ---- block entity / drops ----------------------------------------------------------------

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return isMaster(state) ? new PhotographFrameBlockEntity(pos, state) : null;
    }

    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        if (!isMaster(state)) return List.of();
        if (!(params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof PhotographFrameBlockEntity be)) {
            return List.of();
        }
        if (be.suppressDrops()) return List.of();
        List<ItemStack> drops = new ArrayList<>(2);
        drops.add(be.frameItem().copyWithCount(1));
        if (be.hasPhoto()) drops.add(be.photo().copy());
        return List.copyOf(drops);
    }

    /** A creative break drops nothing, whichever cell was hit — the cascade would otherwise drop the master's items. */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide) {
            PhotographFrameBlockEntity be = masterEntity(level, pos, state);
            if (be != null) {
                if (player.isCreative()) be.markNoDrops();
                playSound(level, pos, Exposure.SoundEvents.PHOTOGRAPH_FRAME_BREAK.get());
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        PhotographFrameBlockEntity be = masterEntity(level, pos, state);
        if (be == null) return new ItemStack(Exposure.Items.PHOTOGRAPH_FRAME.get());
        return be.hasPhoto() ? be.photo().copy() : be.frameItem().copyWithCount(1);
    }

    // ---- interaction (Exposure's PhotographFrameEntity#interact) -----------------------------

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        PhotographFrameBlockEntity be = masterEntity(level, pos, state);
        if (be == null) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (stack.getItem() instanceof PhotographItem && !be.hasPhoto()) {
            if (!level.isClientSide) {
                be.setPhoto(stack);
                stack.consume(1, player);
                playSound(level, pos, Exposure.SoundEvents.PHOTOGRAPH_FRAME_ADD_ITEM.get());
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (stack.is(Items.GLOW_INK_SAC) && !be.glowing()) {
            if (!level.isClientSide) {
                be.setGlowing(true);
                stack.consume(1, player);
                playSound(level, pos, SoundEvents.GLOW_INK_SAC_USE);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (be.hasPhoto()) {
            rotate(level, pos, be);
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        PhotographFrameBlockEntity be = masterEntity(level, pos, state);
        if (be == null || !be.hasPhoto()) return InteractionResult.PASS;
        rotate(level, pos, be);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private static void rotate(Level level, BlockPos pos, PhotographFrameBlockEntity be) {
        if (level.isClientSide) return;
        be.rotatePhoto();
        playSound(level, pos, Exposure.SoundEvents.PHOTOGRAPH_FRAME_ROTATE_ITEM.get());
    }

    /**
     * Take the photo out of the frame at {@code pos}, as a left-click on Exposure's entity does; it
     * drops unless the player is in creative. Returns false when there is no photo to take.
     */
    public static boolean popPhoto(Level level, BlockPos pos, BlockState state, Player player) {
        PhotographFrameBlockEntity be = masterEntity(level, pos, state);
        if (be == null || !be.hasPhoto()) return false;
        if (level.isClientSide) return true;
        ItemStack photo = be.removePhoto();
        if (!player.isCreative()) Block.popResource(level, pos, photo);
        playSound(level, pos, Exposure.SoundEvents.PHOTOGRAPH_FRAME_REMOVE_ITEM.get());
        return true;
    }

    private static void playSound(Level level, BlockPos pos, SoundEvent sound) {
        level.playSound(null, pos, sound, SoundSource.BLOCKS, 1.0F, 1.0F);
    }
}
