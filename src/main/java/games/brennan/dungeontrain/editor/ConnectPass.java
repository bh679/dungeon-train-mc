package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.track.TrackGenerator;
import games.brennan.dungeontrain.train.ForcedConnectCells;
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
 *
 * <p><b>On / Off hold for the carriage's life.</b> Vanilla re-derives a fence's arms whenever a
 * neighbour changes — including the Sable lift's own notify pass. So every On / Off cell in a
 * carriage plot is {@linkplain ForcedConnectCells#remember remembered}, and
 * {@code ForcedConnectShapeMixin} keeps its arms. Cells forced before the lift (at source-world
 * coordinates) are held in a {@linkplain #beginLiftCapture lift capture} that
 * {@code TrainAssembler} commits once it knows the shipyard origin, re-forcing each one there.</p>
 */
public final class ConnectPass {

    private record Pending(ServerLevel level, BlockPos pos, VariantConnect.Mode mode) {}

    private static final class Frame {
        int depth;
        final List<Pending> pending = new ArrayList<>();
    }

    private static final ThreadLocal<Frame> FRAME = ThreadLocal.withInitial(Frame::new);

    /** On / Off cells forced at source-world coordinates while a train group is being placed. */
    private static final ThreadLocal<List<Pending>> LIFT = new ThreadLocal<>();

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

    /**
     * Resolve the block at {@code pos} under {@code mode}, writing only when its arms change, and
     * register an On / Off cell so its arms hold (see class doc).
     */
    public static void apply(ServerLevel level, BlockPos pos, VariantConnect.Mode mode) {
        BlockState current = level.getBlockState(pos);
        if (!VariantConnect.canConnect(current)) return;
        BlockState resolved = VariantConnect.resolve(current, mode, level, pos);
        if (resolved != current) {
            if (mode == VariantConnect.Mode.AUTO) {
                SilentBlockOps.setBlockSilent(level, pos, resolved);
            } else {
                SilentBlockOps.setBlockSilentNoCascade(level, pos, resolved, null);
            }
        }
        if (mode == VariantConnect.Mode.ON || mode == VariantConnect.Mode.OFF) hold(level, pos, mode);
    }

    private static void hold(ServerLevel level, BlockPos pos, VariantConnect.Mode mode) {
        if (TrackGenerator.isShipyardChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            ForcedConnectCells.remember(level, pos, mode);
            return;
        }
        List<Pending> lift = LIFT.get();
        if (lift != null) lift.add(new Pending(level, pos.immutable(), mode));
    }

    // ---- lift capture (TrainAssembler) -----------------------------------------------------------

    /** Start collecting On / Off cells forced at source-world coordinates for the group being placed. */
    public static void beginLiftCapture() {
        LIFT.set(new ArrayList<>());
    }

    /**
     * The group has been lifted: move every captured cell by {@code shipyardOrigin - origin},
     * remember it there and re-force its arms (the lift's notify pass re-derived them). Ends the
     * capture.
     */
    public static void commitLiftCapture(ServerLevel level, BlockPos origin, BlockPos shipyardOrigin) {
        List<Pending> lift = LIFT.get();
        LIFT.remove();
        if (lift == null || lift.isEmpty()) return;
        BlockPos shift = shipyardOrigin.subtract(origin);
        for (Pending p : lift) {
            BlockPos lifted = p.pos().offset(shift);
            BlockState current = level.getBlockState(lifted);
            if (!VariantConnect.canConnect(current)) continue;
            ForcedConnectCells.remember(level, lifted, p.mode());
            BlockState forced = VariantConnect.resolve(current, p.mode(), level, lifted);
            if (forced != current) SilentBlockOps.setBlockSilentNoCascade(level, lifted, forced, null);
        }
    }

    /** Drop an uncommitted capture — the group failed to lift. Safe to call after a commit. */
    public static void endLiftCapture() {
        LIFT.remove();
    }
}
