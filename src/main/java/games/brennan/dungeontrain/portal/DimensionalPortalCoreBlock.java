package games.brennan.dungeontrain.portal;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Active centre block of a dimensional train portal. Backed by a
 * {@link DimensionalPortalBlockEntity} that holds the partner portal's
 * coordinates + target dimension.
 *
 * <p>Behaviour summary:
 * <ul>
 *   <li>Pass-through: {@link #getCollisionShape} returns {@link Shapes#empty()}
 *       so entities walk straight through. Detection of player and carriage
 *       crossings is done by the per-tick scanners (added in Phase 9 / 10),
 *       not by this block's own logic.</li>
 *   <li>Horizontal axis state ({@link BlockStateProperties#HORIZONTAL_AXIS},
 *       values {@link Direction.Axis#X} / {@link Direction.Axis#Z}) so the
 *       portal can stand perpendicular to whichever direction the track is
 *       laid. Vertical axis (Y) is not supported in v1 — train tracks are
 *       always horizontal.</li>
 *   <li>Light source so the portal volume is visible at night through the
 *       swirl texture.</li>
 * </ul>
 *
 * <p>The block itself does NOT teleport entities. That logic lives in
 * {@code PlayerPortalCrossListener} (players) and
 * {@code CarriageTransitDetector} (carriage sub-levels), both of which read
 * the partner coords from the {@link DimensionalPortalBlockEntity}.</p>
 */
public class DimensionalPortalCoreBlock extends Block implements EntityBlock {

    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;

    public static final MapCodec<DimensionalPortalCoreBlock> CODEC = simpleCodec(DimensionalPortalCoreBlock::new);

    public DimensionalPortalCoreBlock(Properties properties) {
        super(properties);
        registerDefaultState(this.stateDefinition.any().setValue(AXIS, Direction.Axis.X));
    }

    @Override
    protected MapCodec<? extends DimensionalPortalCoreBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AXIS);
    }

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DimensionalPortalBlockEntity(pos, state);
    }

    /**
     * No collision — entities walk through the portal volume freely. Crossing
     * detection is handled by per-tick scanners that read the block entity.
     */
    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    /** Vanilla nether-portal-style axis rotation (X ↔ Z on 90° rotation). */
    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return switch (rotation) {
            case COUNTERCLOCKWISE_90, CLOCKWISE_90 -> switch (state.getValue(AXIS)) {
                case X -> state.setValue(AXIS, Direction.Axis.Z);
                case Z -> state.setValue(AXIS, Direction.Axis.X);
                default -> state;
            };
            default -> state;
        };
    }

    /** Mirroring leaves the axis unchanged — the axis already describes orientation symmetrically. */
    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state;
    }
}
