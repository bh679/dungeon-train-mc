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
 *   <li>{@link Mode#ON} — every arm out, whether or not anything is there.</li>
 *   <li>{@link Mode#OFF} — a lone post, whatever is around it.</li>
 * </ul>
 * Applied after the overlay by {@link ConnectPass}, and in the editor preview.
 */
public final class VariantConnect {

    public enum Mode {
        DEFAULT, AUTO, ON, OFF;

        /** Lower-case JSON token ({@code "auto"} / {@code "on"} / {@code "off"}; Default is never written). */
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

    /**
     * {@code state} with every arm out ({@code connected}) or every arm in. A wall's arms go
     * {@link WallSide#LOW}; its post stays up either way (a lone post, or the post vanilla raises
     * at a four-way junction). Non-connecting blocks come back unchanged.
     */
    public static BlockState force(BlockState state, boolean connected) {
        if (state.getBlock() instanceof CrossCollisionBlock) {
            BlockState out = state;
            for (BooleanProperty arm : CROSS_ARMS) {
                if (out.hasProperty(arm)) out = out.setValue(arm, connected);
            }
            return out;
        }
        if (state.getBlock() instanceof WallBlock) {
            BlockState out = state;
            WallSide side = connected ? WallSide.LOW : WallSide.NONE;
            for (EnumProperty<WallSide> arm : WALL_ARMS) {
                if (out.hasProperty(arm)) out = out.setValue(arm, side);
            }
            return out.hasProperty(WallBlock.UP) ? out.setValue(WallBlock.UP, true) : out;
        }
        return state;
    }

    /**
     * What a {@code mode} variant looks like at {@code pos}: Auto reads the neighbours, On / Off
     * force the arms, Default (and any non-connecting block) returns {@code state} unchanged.
     */
    public static BlockState resolve(BlockState state, Mode mode, LevelAccessor level, BlockPos pos) {
        if (mode == null || mode.isDefault() || !canConnect(state)) return state;
        return switch (mode) {
            case AUTO -> Block.updateFromNeighbourShapes(state, level, pos);
            case ON -> force(state, true);
            case OFF -> force(state, false);
            default -> state;
        };
    }
}
