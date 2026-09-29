package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * The spawn half of the per-row {@link VariantState#autoConnect()} flag: a fence / wall / pane
 * variant placed with the flag on re-derives its connection arms from its real neighbours.
 *
 * <p>The overlay placers open a {@link Scope} around their cell loop and {@link #note} every
 * flagged write. When the outermost scope on this thread closes — every neighbour of the overlay is
 * in by then — each noted position still holding a connectable block gets
 * {@link Block#updateFromNeighbourShapes}, written back through {@link SilentBlockOps#setBlockSilent}
 * so its neighbours join back toward it. Blocks placed after that (a later overlay, the Sable lift)
 * reach the fence through vanilla's own neighbour-shape update, which keeps the arms right.</p>
 *
 * <p>Scopes nest: an overlay called from inside another's scope defers to the outer flush.
 * A {@link #note} with no scope open recomputes straight away.</p>
 */
public final class AutoConnectPass {

    private record Pending(ServerLevel level, BlockPos pos) {}

    private static final class Frame {
        int depth;
        final List<Pending> pending = new ArrayList<>();
    }

    private static final ThreadLocal<Frame> FRAME = ThreadLocal.withInitial(Frame::new);

    private AutoConnectPass() {}

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
            for (Pending p : toFlush) reconnect(p.level(), p.pos());
        }
    }

    /** Open a scope; the outermost one flushes every {@link #note}d position when it closes. */
    public static Scope open() {
        FRAME.get().depth++;
        return new Scope();
    }

    /**
     * Mark {@code pos} (world coordinates) as holding a variant placed with auto-connect on.
     * Recomputed when the outermost scope closes, or immediately when none is open.
     */
    public static void note(ServerLevel level, BlockPos pos) {
        Frame frame = FRAME.get();
        if (frame.depth > 0) {
            frame.pending.add(new Pending(level, pos.immutable()));
        } else {
            reconnect(level, pos);
        }
    }

    /**
     * Re-derive the connection arms of the block at {@code pos} from its neighbours, writing only
     * when they change. A no-op for anything {@link VariantConnect#canConnect} rejects.
     */
    public static void reconnect(ServerLevel level, BlockPos pos) {
        BlockState current = level.getBlockState(pos);
        if (!VariantConnect.canConnect(current)) return;
        BlockState joined = Block.updateFromNeighbourShapes(current, level, pos);
        if (joined != current) {
            SilentBlockOps.setBlockSilent(level, pos, joined);
        }
    }
}
