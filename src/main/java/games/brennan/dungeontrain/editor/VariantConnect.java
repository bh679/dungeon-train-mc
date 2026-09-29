package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.WallSide;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;

/**
 * The per-row connect mode of a fence / pane / iron-bars ({@link CrossCollisionBlock}) or wall
 * ({@link WallBlock}) variant — including the stage fence and stage stone walls. Fence gates are
 * excluded (their state is a facing, not arms).
 *
 * <ul>
 *   <li>{@link Mode#DEFAULT} — place the captured state as-is (the behaviour before this flag).</li>
 *   <li>{@link Mode#AUTO} — re-derive the arms from the real neighbours once placed.</li>
 *   <li>{@link Mode#LOCK} — keep exactly the arms the author picked (the Z menu's N / E / S / W
 *       toggles), for the carriage's life. The picked arms are the entry's own stored state
 *       properties, so placement mirrors and flips carry them like any other state.</li>
 * </ul>
 * Applied after the overlay by {@link ConnectPass}, and in the editor preview.
 *
 * <p>Arms travel as a four-bit mask: {@link #NORTH} {@link #EAST} {@link #SOUTH} {@link #WEST}. For a
 * wall a set bit is {@link WallSide#LOW} and a clear one {@link WallSide#NONE}.</p>
 */
public final class VariantConnect {

    public enum Mode {
        DEFAULT, AUTO, LOCK;

        /** Lower-case JSON token ({@code "auto"} / {@code "lock"}; Default is never written). */
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public boolean isDefault() {
            return this == DEFAULT;
        }

        /** The mode for a JSON token, or {@code null} when unrecognised. */
        @Nullable
        public static Mode parse(@Nullable String token) {
            if (token == null) return null;
            for (Mode m : values()) {
                if (m.id().equals(token.trim().toLowerCase(Locale.ROOT))) return m;
            }
            return null;
        }

        /** The mode for a wire / NBT ordinal, falling back to {@link #DEFAULT} when out of range. */
        public static Mode fromOrdinal(int ordinal) {
            Mode[] all = values();
            return ordinal >= 0 && ordinal < all.length ? all[ordinal] : DEFAULT;
        }
    }

    public static final int NORTH = 1;
    public static final int EAST = 2;
    public static final int SOUTH = 4;
    public static final int WEST = 8;
    /** Every arm out. */
    public static final int ALL = NORTH | EAST | SOUTH | WEST;

    /** Arm bits in N, E, S, W order — index-aligned with the two property lists below. */
    public static final int[] ARM_BITS = {NORTH, EAST, SOUTH, WEST};

    private static final List<BooleanProperty> CROSS_ARMS = List.of(
        CrossCollisionBlock.NORTH, CrossCollisionBlock.EAST, CrossCollisionBlock.SOUTH, CrossCollisionBlock.WEST);
    private static final List<EnumProperty<WallSide>> WALL_ARMS = List.of(
        WallBlock.NORTH_WALL, WallBlock.EAST_WALL, WallBlock.SOUTH_WALL, WallBlock.WEST_WALL);

    private VariantConnect() {}

    /** True when {@code state} is a block whose connection arms the connect mode can set. */
    public static boolean canConnect(@Nullable BlockState state) {
        if (state == null) return false;
        return state.getBlock() instanceof CrossCollisionBlock || state.getBlock() instanceof WallBlock;
    }

    /** The arms {@code state} has out, as a mask; 0 for a non-connecting block. */
    public static int armMask(@Nullable BlockState state) {
        if (state == null) return 0;
        int mask = 0;
        if (state.getBlock() instanceof CrossCollisionBlock) {
            for (int i = 0; i < 4; i++) {
                if (state.hasProperty(CROSS_ARMS.get(i)) && state.getValue(CROSS_ARMS.get(i))) mask |= ARM_BITS[i];
            }
        } else if (state.getBlock() instanceof WallBlock) {
            for (int i = 0; i < 4; i++) {
                if (state.hasProperty(WALL_ARMS.get(i)) && state.getValue(WALL_ARMS.get(i)) != WallSide.NONE) {
                    mask |= ARM_BITS[i];
                }
            }
        }
        return mask;
    }

    /**
     * {@code state} with exactly the arms in {@code mask} out. A wall's arms go {@link WallSide#LOW};
     * its post stays up (a lone post, or the post vanilla raises at a junction). An arm that is
     * already out on a wall keeps its height. Non-connecting blocks come back unchanged.
     */
    public static BlockState force(BlockState state, int mask) {
        if (state.getBlock() instanceof CrossCollisionBlock) {
            BlockState out = state;
            for (int i = 0; i < 4; i++) {
                BooleanProperty arm = CROSS_ARMS.get(i);
                if (out.hasProperty(arm)) out = out.setValue(arm, (mask & ARM_BITS[i]) != 0);
            }
            return out;
        }
        if (state.getBlock() instanceof WallBlock) {
            BlockState out = state;
            for (int i = 0; i < 4; i++) {
                EnumProperty<WallSide> arm = WALL_ARMS.get(i);
                if (!out.hasProperty(arm)) continue;
                boolean want = (mask & ARM_BITS[i]) != 0;
                boolean has = out.getValue(arm) != WallSide.NONE;
                if (want != has) out = out.setValue(arm, want ? WallSide.LOW : WallSide.NONE);
            }
            return out.hasProperty(WallBlock.UP) ? out.setValue(WallBlock.UP, true) : out;
        }
        return state;
    }

    /**
     * What a {@code mode} variant looks like at {@code pos} once placed: Auto reads the neighbours;
     * Default and Lock keep {@code state} (Lock's arms are already the stored ones).
     */
    public static BlockState resolve(BlockState state, Mode mode, LevelAccessor level, BlockPos pos) {
        if (mode == Mode.AUTO && canConnect(state)) return Block.updateFromNeighbourShapes(state, level, pos);
        return state;
    }
}
