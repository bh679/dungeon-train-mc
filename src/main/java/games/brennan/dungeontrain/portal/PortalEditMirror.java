package games.brennan.dungeontrain.portal;

import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Keeps a portal corridor and its twin block-for-block identical while players build in them.
 *
 * <p>They start identical — both stamped from the same source — but drift the moment anyone edits
 * one: a torch placed in the carriage leaves the twin bare, so crossing the midpoint would show it
 * appear or vanish. Every edit to either side is therefore copied to the other.</p>
 *
 * <p>Two feeds arrive here, because the two sides live in different places. A carriage's blocks are
 * in a Sable sub-level plot, so its changes come from Sable's own per-block-change hook in shipyard
 * coordinates ({@code mixin/SableBlockChangeGuardMixin}) — which has the side benefit of catching
 * redstone, pistons and explosions, not just player actions. A twin's blocks are ordinary world
 * blocks, so its changes come from the usual place/break events.</p>
 *
 * <p><b>Re-entrancy is the trap.</b> Mirroring a carriage edit writes into the plot, which fires the
 * Sable hook again, which would mirror it straight back — forever. {@link #MIRRORING} suppresses the
 * reflected change, the same thread-local idiom {@code WorldgenForceGuard} uses on that very hook.
 * The twin side needs no equivalent because it writes through {@link SilentBlockOps}, whose raw
 * {@code setBlock} never re-triggers place/break subscribers — the same trick
 * {@code EditorMirrorLiveHandler} relies on.</p>
 *
 * <p>Block <i>state</i> only. A chest mirrors as a chest, not as its contents — and it could not do
 * otherwise, since both feeds fire during {@code Level.setBlock}, before {@code BlockItem} writes a
 * placed shulker's items into the block entity. Containers are therefore handled a level up, by
 * {@link PortalContainerLink}: the pair keeps one authoritative block entity and the other copy
 * opens it. That is also what makes the silent clear below correct rather than lossy — the copy it
 * wipes has already been emptied into the one being mined.</p>
 */
public final class PortalEditMirror {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** True on this thread while a mirrored write is in flight, so the echo is ignored. */
    private static final ThreadLocal<Boolean> MIRRORING = ThreadLocal.withInitial(() -> false);

    private PortalEditMirror() {}

    public static boolean isMirroring() {
        return MIRRORING.get();
    }

    /**
     * A block changed inside a carriage's sub-level plot: copy it to the twin.
     *
     * <p>Called from Sable's hook on the hot path of every sub-level block change, so it bails on a
     * map lookup when no portals are live.</p>
     */
    public static void onCarriageBlockChanged(ServerLevel level, LevelPlot plot,
                                              int x, int y, int z, BlockState newState) {
        if (MIRRORING.get() || PortalPairIndex.isEmpty()) return;

        PortalPairIndex.Entry entry = PortalPairIndex.findByPlotPos(plot, x, y, z);
        if (entry == null) return;

        int[] local = entry.localOfPlot(x, y, z);
        if (local == null) return;

        BlockPos target = entry.twinPosOf(local);

        // Before the mirror, not after: severing wants both the cell that was broken and the twin
        // cell it maps to, and the write below is about to make the two identical again.
        PortalSever.onCarriageBlockChanged(level, entry, local, newState, target);

        MIRRORING.set(true);
        try {
            // setBlockSilent, not the section-local write: the twin's blocks are ordinary world
            // blocks that nothing subsequently relights or re-syncs, and a section-local write skips
            // both the light engine and the client update — the mirrored change would be invisible.
            // Air goes through clearBlockSilent, which drops the block entity with it; setting an air
            // state over a chest would otherwise leave the container behind. Both calls, and the
            // split between them, follow EditorMirror.
            if (newState.isAir()) {
                SilentBlockOps.clearBlockSilent(level, target);
            } else {
                SilentBlockOps.setBlockSilent(level, target, newState);
            }
        } finally {
            MIRRORING.set(false);
        }
    }

    /**
     * A block changed inside a twin corridor: copy it into the carriage's plot.
     *
     * @return true if the change was mirrored, for logging
     */
    public static boolean onTwinBlockChanged(ServerLevel level, BlockPos pos, BlockState newState) {
        if (MIRRORING.get() || PortalPairIndex.isEmpty()) return false;

        PortalPairIndex.Entry entry = PortalPairIndex.findByTwinPos(pos);
        if (entry == null) return false;

        int[] local = entry.localOfTwin(pos);
        if (local == null) return false;

        BlockPos target = entry.plotPosOf(local);

        MIRRORING.set(true);
        try {
            // Write at SHIPYARD coordinates through the ordinary level path, which is how
            // CarriageContentsPlacer puts blocks into a plot: Sable's LevelChunkMixin redirects
            // getLightEngine() to the plot's own engine and syncs the sub-level to clients.
            //
            // Poking LevelChunk.setBlockState directly — the first attempt — changed the block
            // server-side but told no client, because vanilla's client update comes from
            // Level.setBlock rather than the chunk. The edit was there and invisible.
            if (newState.isAir()) {
                SilentBlockOps.clearBlockSilent(level, target);
            } else {
                SilentBlockOps.setBlockSilent(level, target, newState);
            }
        } finally {
            MIRRORING.set(false);
        }

        LOGGER.info("[DungeonTrain] Portal edit mirrored twin→carriage: {} at local ({},{},{}) → shipyard {}",
            newState.isAir() ? "cleared" : newState.getBlock().getName().getString(),
            local[0], local[1], local[2], target);
        return true;
    }

    /** The plot chunk owning a shipyard position, mirroring {@code CarriageBlockSnapshot.chunkAt}. */
    private static LevelChunk chunkAt(LevelPlot plot, BlockPos shipLocal) {
        long key = ChunkPos.asLong(shipLocal.getX() >> 4, shipLocal.getZ() >> 4);
        for (PlotChunkHolder holder : plot.getLoadedChunks()) {
            LevelChunk chunk = holder.getChunk();
            if (chunk != null && chunk.getPos().toLong() == key) return chunk;
        }
        return null;
    }
}
