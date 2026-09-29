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
 * non-default write, passing the state they placed. When the outermost scope on this thread closes —
 * every neighbour of the overlay is in by then — each noted position still holding a connectable
 * block is resolved:</p>
 * <ul>
 *   <li><b>Auto</b> re-derives the arms from the neighbours and is written through
 *       {@link SilentBlockOps#setBlockSilent}, so its neighbours join back toward it.</li>
 *   <li><b>Lock</b> puts back the arms of the state the placer <em>placed</em> (a neighbour written
 *       later in the overlay may already have re-derived them), through
 *       {@link SilentBlockOps#setBlockSilentNoCascade}, so the neighbours are left as they are.</li>
 * </ul>
 *
 * <p>Scopes nest: an overlay called from inside another's scope defers to the outer flush.
 * A {@link #note} with no scope open resolves straight away.</p>
 *
 * <p><b>Lock holds for the carriage's life.</b> Vanilla re-derives a fence's arms whenever a
 * neighbour changes — including the Sable lift's own notify pass. So every locked cell in a
 * carriage plot is {@linkplain ForcedConnectCells#remember remembered}, and
 * {@code ForcedConnectShapeMixin} keeps its arms. Cells locked before the lift (at source-world
 * coordinates) are held in a {@linkplain #beginLiftCapture lift capture} that
 * {@code TrainAssembler} commits once it knows the shipyard origin, re-locking each one there.</p>
 */
public final class ConnectPass {

    private record Pending(ServerLevel level, BlockPos pos, VariantConnect.Mode mode, int arms) {}

    private static final class Frame {
        int depth;
        final List<Pending> pending = new ArrayList<>();
    }

    private static final ThreadLocal<Frame> FRAME = ThreadLocal.withInitial(Frame::new);

    /** Locked cells written at source-world coordinates while a train group is being placed. */
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
            for (Pending p : toFlush) apply(p.level(), p.pos(), p.mode(), p.arms());
        }
    }

    /** Open a scope; the outermost one flushes every {@link #note}d position when it closes. */
    public static Scope open() {
        FRAME.get().depth++;
        return new Scope();
    }

    /**
     * Mark {@code pos} (world coordinates) as holding a variant placed as {@code placed} with connect
     * {@code mode}. Default is ignored. Resolved when the outermost scope closes, or immediately
     * when none is open.
     */
    public static void note(ServerLevel level, BlockPos pos, VariantConnect.Mode mode, BlockState placed) {
        if (mode == null || mode.isDefault() || !VariantConnect.canConnect(placed)) return;
        int arms = VariantConnect.armMask(placed);
        Frame frame = FRAME.get();
        if (frame.depth > 0) {
            frame.pending.add(new Pending(level, pos.immutable(), mode, arms));
        } else {
            apply(level, pos, mode, arms);
        }
    }

    /**
     * Resolve the block at {@code pos}: Auto re-derives its arms, Lock sets exactly {@code arms}
     * and registers the cell so they hold (see class doc). Writes only when the arms change.
     */
    public static void apply(ServerLevel level, BlockPos pos, VariantConnect.Mode mode, int arms) {
        BlockState current = level.getBlockState(pos);
        if (!VariantConnect.canConnect(current)) return;
        if (mode == VariantConnect.Mode.AUTO) {
            BlockState joined = VariantConnect.resolve(current, mode, level, pos);
            if (joined != current) SilentBlockOps.setBlockSilent(level, pos, joined);
            return;
        }
        if (mode != VariantConnect.Mode.LOCK) return;
        BlockState locked = VariantConnect.force(current, arms);
        if (locked != current) SilentBlockOps.setBlockSilentNoCascade(level, pos, locked, null);
        hold(level, pos, arms);
    }

    private static void hold(ServerLevel level, BlockPos pos, int arms) {
        if (TrackGenerator.isShipyardChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            ForcedConnectCells.remember(level, pos, arms);
            return;
        }
        List<Pending> lift = LIFT.get();
        if (lift != null) lift.add(new Pending(level, pos.immutable(), VariantConnect.Mode.LOCK, arms));
    }

    // ---- lift capture (TrainAssembler) -----------------------------------------------------------

    /** Start collecting locked cells written at source-world coordinates for the group being placed. */
    public static void beginLiftCapture() {
        LIFT.set(new ArrayList<>());
    }

    /**
     * The group has been lifted: move every captured cell by {@code shipyardOrigin - origin},
     * remember it there and re-lock its arms (the lift's notify pass re-derived them). Ends the
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
            ForcedConnectCells.remember(level, lifted, p.arms());
            BlockState locked = VariantConnect.force(current, p.arms());
            if (locked != current) SilentBlockOps.setBlockSilentNoCascade(level, lifted, locked, null);
        }
    }

    /** Drop an uncommitted capture — the group failed to lift. Safe to call after a commit. */
    public static void endLiftCapture() {
        LIFT.remove();
    }
}
