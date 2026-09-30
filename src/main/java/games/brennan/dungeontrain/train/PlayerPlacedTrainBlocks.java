package games.brennan.dungeontrain.train;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Which blocks of a Dungeon Train carriage a <b>player</b> put there, as opposed to the blocks the
 * train was built with.
 *
 * <p>The two collide with the world in opposite ways. A train-built block is part of the kinematic,
 * zero-mass train: it never breaks and it breaks the terrain it hits
 * ({@code TrainTickEvents.sweepFootprint}). A player-added block is a passenger's build bolted on:
 * when it hits terrain or another physics object it is the thing that breaks off
 * ({@link PlayerBlockBreakOff}), and what it hit is left standing.</p>
 *
 * <h2>Keying</h2>
 * Per carriage sub-level UUID, a set of positions stored <em>relative to the plot's minimum chunk
 * corner</em> ({@link #relKey}). Relative so a mark stays attached to its block if Sable ever hands a
 * sub-level reloaded from holding a different plot location; the UUID survives that reload.
 *
 * <h2>Lifetime</h2>
 * In memory only, deliberately. Dungeon Train deletes every train sub-level at server stop
 * ({@code ShipShutdownEvents}), so a player's build does not outlive the session and neither needs
 * its mark. Entries are dropped when their sub-level is deleted ({@code SableShipyard.delete}) and
 * everything is cleared at server stop, so the map cannot grow without bound.
 *
 * <p>What sets a mark: a player placing a block ({@code PlayerPlacedTrainBlockEvents}). What clears
 * one: any change of block <em>type</em> at that cell, whatever caused it
 * ({@code SableBlockChangeGuardMixin}). A property flip — a door opening, a lamp lighting — keeps
 * the mark. Server thread only.</p>
 */
public final class PlayerPlacedTrainBlocks {

    private static final Map<UUID, LongOpenHashSet> MARKS = new HashMap<>();

    private PlayerPlacedTrainBlocks() {}

    // ---- sub-level-facing API ------------------------------------------------------------------

    /** Record that a player placed the block at plot position {@code plotPos} of {@code subLevel}. */
    public static void mark(ServerSubLevel subLevel, BlockPos plotPos) {
        mark(subLevel.getUniqueId(), relKey(subLevel.getPlot(), plotPos));
    }

    /** Whether the block at plot position {@code plotPos} of {@code subLevel} was placed by a player. */
    public static boolean isPlayerPlaced(ServerSubLevel subLevel, BlockPos plotPos) {
        return isMarked(subLevel.getUniqueId(), relKey(subLevel.getPlot(), plotPos));
    }

    /**
     * A block changed in {@code subLevel}'s plot. A change of block type — broken, replaced, or
     * removed by anything — ends the player's claim on the cell. A later player placement at the same
     * cell re-marks it: the place event fires after the block change it caused.
     */
    public static void onBlockChanged(ServerSubLevel subLevel, int x, int y, int z, boolean blockTypeChanged) {
        if (!blockTypeChanged) return;
        // The overwhelming majority: no player has built on this carriage — skip before allocating.
        if (!hasAny(subLevel.getUniqueId())) return;
        unmark(subLevel.getUniqueId(), relKey(subLevel.getPlot(), new BlockPos(x, y, z)));
    }

    /** The plot positions of every player-placed block on {@code subLevel}; a fresh list. */
    public static List<BlockPos> plotPositions(ServerSubLevel subLevel) {
        LongOpenHashSet set = MARKS.get(subLevel.getUniqueId());
        if (set == null) return List.of();
        BlockPos origin = plotOrigin(subLevel.getPlot());
        List<BlockPos> out = new ArrayList<>(set.size());
        for (long rel : set) out.add(BlockPos.of(rel).offset(origin));
        return out;
    }

    // ---- UUID-keyed core (unit-tested) ---------------------------------------------------------

    static void mark(UUID subLevelId, long relKey) {
        MARKS.computeIfAbsent(subLevelId, id -> new LongOpenHashSet()).add(relKey);
    }

    static void unmark(UUID subLevelId, long relKey) {
        LongOpenHashSet set = MARKS.get(subLevelId);
        if (set == null) return;
        set.remove(relKey);
        if (set.isEmpty()) MARKS.remove(subLevelId);
    }

    static boolean isMarked(UUID subLevelId, long relKey) {
        LongOpenHashSet set = MARKS.get(subLevelId);
        return set != null && set.contains(relKey);
    }

    /** Cheap pre-check for the per-tick passes: does this carriage carry any player blocks at all? */
    public static boolean hasAny(UUID subLevelId) {
        return MARKS.containsKey(subLevelId);
    }

    /** Forget a deleted sub-level. */
    public static void removeSubLevel(UUID subLevelId) {
        MARKS.remove(subLevelId);
    }

    /** Forget everything — server stop. */
    public static void clear() {
        MARKS.clear();
    }

    // ---- keying --------------------------------------------------------------------------------

    /** {@code plotPos} relative to the plot's minimum chunk corner, packed with {@link BlockPos#asLong}. */
    static long relKey(LevelPlot plot, BlockPos plotPos) {
        return relKey(plotOrigin(plot), plotPos);
    }

    static long relKey(BlockPos origin, BlockPos plotPos) {
        return BlockPos.asLong(plotPos.getX() - origin.getX(), plotPos.getY(), plotPos.getZ() - origin.getZ());
    }

    private static BlockPos plotOrigin(LevelPlot plot) {
        ChunkPos min = plot.getChunkMin();
        return new BlockPos(min.getMinBlockX(), 0, min.getMinBlockZ());
    }
}
