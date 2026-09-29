package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * The spawn half of the per-row {@link VariantState#connect()} mode: a fence / wall / pane variant
 * placed with a non-default mode gets its arms set once the overlay is in (see
 * {@link VariantConnect.Mode}).
 *
 * <p>The overlay placers open a {@link Scope} around their cell loop and {@link #note} every
 * non-default write. When the outermost scope on this thread closes — every neighbour of the
 * overlay is in by then — each noted position still holding a connectable block is resolved
 * through {@link VariantConnect#resolve}:</p>
 * <ul>
 *   <li><b>Auto</b> is written through {@link SilentBlockOps#setBlockSilent}, so its neighbours
 *       join back toward it.</li>
 *   <li><b>On / Off</b> are written through {@link SilentBlockOps#setBlockSilentNoCascade}, so the
 *       forced arms leave the neighbours as they are.</li>
 * </ul>
 *
 * <p>Scopes nest: an overlay called from inside another's scope defers to the outer flush.
 * A {@link #note} with no scope open resolves straight away.</p>
 */
public final class ConnectPass {

    private record Pending(ServerLevel level, BlockPos pos, VariantConnect.Mode mode) {}

    private static final class Frame {
        int depth;
        final List<Pending> pending = new ArrayList<>();
    }

    private static final ThreadLocal<Frame> FRAME = ThreadLocal.withInitial(Frame::new);

    private ConnectPass() {}

    /** An open collection scope — close it (try-with-resources) once the overlay has been placed. */
    public static final class Scope implements AutoCloseable {
        private boolean closed;

        private Scope() {}

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            Frame frame = FRAME.get();
            if (--frame.depth > 0) return;
            List<Pending> toFlush = List.copyOf(frame.pending);
            frame.pending.clear();
            for (Pending p : toFlush) apply(p.level(), p.pos(), p.mode());
        }
    }

    /** Open a scope; the outermost one flushes every {@link #note}d position when it closes. */
    public static Scope open() {
        FRAME.get().depth++;
        return new Scope();
    }

    /**
     * Mark {@code pos} (world coordinates) as holding a variant placed with connect {@code mode}.
     * Default is ignored. Resolved when the outermost scope closes, or immediately when none is open.
     */
    public static void note(ServerLevel level, BlockPos pos, VariantConnect.Mode mode) {
        if (mode == null || mode.isDefault()) return;
        Frame frame = FRAME.get();
        if (frame.depth > 0) {
            frame.pending.add(new Pending(level, pos.immutable(), mode));
        } else {
            apply(level, pos, mode);
        }
    }

    /** Resolve the block at {@code pos} under {@code mode}, writing only when its arms change. */
    public static void apply(ServerLevel level, BlockPos pos, VariantConnect.Mode mode) {
        BlockState current = level.getBlockState(pos);
        BlockState resolved = VariantConnect.resolve(current, mode, level, pos);
        if (resolved == current) return;
        if (mode == VariantConnect.Mode.AUTO) {
            SilentBlockOps.setBlockSilent(level, pos, resolved);
        } else {
            SilentBlockOps.setBlockSilentNoCascade(level, pos, resolved, null);
        }
    }
}
