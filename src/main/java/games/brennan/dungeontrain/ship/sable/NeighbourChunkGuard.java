package games.brennan.dungeontrain.ship.sable;

import dev.ryanhcode.sable.Sable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * "May this position be read without the server thread synchronously loading a chunk?" — the
 * predicate behind {@code FluidInteractionNoSyncLoadMixin} (#1450).
 *
 * <p>Two ways to say yes:</p>
 * <ol>
 *   <li>{@link Level#hasChunkAt} — the vanilla non-blocking check ({@code getChunk(x, z, FULL,
 *       false) != null}). False for any chunk still generating, which is exactly the case that
 *       stalled the server for 5–129 s in player logs.</li>
 *   <li>The position lies inside a Sable sub-level plot ({@code Sable.HELPER.getContaining}).
 *       Carriage blocks live in far-away plot chunks that Sable holds <em>below</em> vanilla's FULL
 *       ticket level, so {@code hasChunkAt} reports them unloaded even while Sable is ticking their
 *       fluids. Gate 2 of #1450 caught this: fluids poured on a carriage never flowed. Plot space is
 *       Sable-managed, so fall through to vanilla there — identical to pre-fix behaviour on
 *       carriages.</li>
 * </ol>
 *
 * <p>Plain class, not mixin code, on purpose: Sable types are not visible to the mixin
 * transformer's classloader (the {@link games.brennan.dungeontrain.ship.TrainFluidBarrier}
 * gotcha). The Sable lookup only runs once {@code hasChunkAt} has already said no, so the hot path
 * is a single vanilla holder check.</p>
 */
public final class NeighbourChunkGuard {

    private NeighbourChunkGuard() {}

    /** @return true if {@code pos} is in a fully loaded chunk or inside a Sable sub-level plot. */
    public static boolean isReadable(final Level level, final BlockPos pos) {
        if (level.hasChunkAt(pos)) return true;
        return level instanceof ServerLevel server && Sable.HELPER.getContaining(server, pos) != null;
    }
}
