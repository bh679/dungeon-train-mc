package games.brennan.dungeontrain.train;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import games.brennan.dungeontrain.editor.VariantConnect;
import games.brennan.dungeontrain.ship.sable.CarriagePlotResolver;
import games.brennan.dungeontrain.track.TrackGenerator;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Which fence / wall / pane cells of a Dungeon Train carriage were placed with the
 * {@link VariantConnect.Mode#LOCK} connect mode, and which arms they lock — so those arms hold for
 * the carriage's life. {@code ForcedConnectShapeMixin} consults it whenever vanilla
 * re-derives one of those blocks' shape: during the Sable lift's notify pass, and on every later
 * neighbour change.
 *
 * <h2>Keying</h2>
 * The same scheme as {@link PlayerPlacedTrainBlocks}: per carriage sub-level UUID, positions
 * relative to the plot's minimum chunk corner, so a mark stays with its block if Sable hands a
 * sub-level reloaded from holding a different plot location. The value is the locked arm mask
 * ({@link VariantConnect#NORTH} …).
 *
 * <h2>Lifetime</h2>
 * In memory only — Dungeon Train deletes every train sub-level at server stop. Entries go with
 * their sub-level ({@code SableShipyard.delete}), everything is cleared at server stop, and a change
 * of block <em>type</em> at a cell ({@code SableBlockChangeGuardMixin}) forgets it, so a player who
 * breaks or replaces the fence gets an ordinary one. Server thread only.
 */
public final class ForcedConnectCells {

    private static final Map<UUID, Long2ByteOpenHashMap> MODES = new HashMap<>();

    private ForcedConnectCells() {}

    // ---- level-facing API ----------------------------------------------------------------------

    /**
     * Record that the block at shipyard position {@code pos} locks exactly the arms in {@code arms}.
     * A position outside a carriage plot is ignored.
     */
    public static void remember(ServerLevel level, BlockPos pos, int arms) {
        if (!TrackGenerator.isShipyardChunk(pos.getX() >> 4, pos.getZ() >> 4)) return;
        ServerSubLevel subLevel = CarriagePlotResolver.subLevelAt(level, new ChunkPos(pos));
        if (subLevel == null) return;
        put(subLevel.getUniqueId(), relKey(subLevel.getPlot(), pos), arms);
    }

    /** The locked arm mask of the block at {@code pos}, or {@code -1} when it isn't locked. */
    public static int lookup(ServerLevel level, BlockPos pos) {
        if (MODES.isEmpty()) return -1;
        if (!TrackGenerator.isShipyardChunk(pos.getX() >> 4, pos.getZ() >> 4)) return -1;
        ServerSubLevel subLevel = CarriagePlotResolver.subLevelAt(level, new ChunkPos(pos));
        if (subLevel == null) return -1;
        return get(subLevel.getUniqueId(), relKey(subLevel.getPlot(), pos));
    }

    /** A block changed in {@code subLevel}'s plot; a change of block type drops any forced mode there. */
    public static void onBlockChanged(ServerSubLevel subLevel, int x, int y, int z, boolean blockTypeChanged) {
        if (!blockTypeChanged || !MODES.containsKey(subLevel.getUniqueId())) return;
        remove(subLevel.getUniqueId(), relKey(subLevel.getPlot(), new BlockPos(x, y, z)));
    }

    /** Cheap global pre-check for the shape hook: is any cell forced at all? */
    public static boolean hasAny() {
        return !MODES.isEmpty();
    }

    /** Forget a deleted sub-level. */
    public static void removeSubLevel(UUID subLevelId) {
        MODES.remove(subLevelId);
    }

    /** Forget everything — server stop. */
    public static void clear() {
        MODES.clear();
    }

    // ---- UUID-keyed core (unit-tested) ---------------------------------------------------------

    static void put(UUID subLevelId, long relKey, int arms) {
        MODES.computeIfAbsent(subLevelId, id -> new Long2ByteOpenHashMap())
            .put(relKey, (byte) (arms & VariantConnect.ALL));
    }

    /** The locked arm mask, or {@code -1} when the cell isn't locked. */
    static int get(UUID subLevelId, long relKey) {
        Long2ByteOpenHashMap map = MODES.get(subLevelId);
        if (map == null || !map.containsKey(relKey)) return -1;
        return map.get(relKey);
    }

    static void remove(UUID subLevelId, long relKey) {
        Long2ByteOpenHashMap map = MODES.get(subLevelId);
        if (map == null) return;
        map.remove(relKey);
        if (map.isEmpty()) MODES.remove(subLevelId);
    }

    // ---- keying (shared with PlayerPlacedTrainBlocks) ------------------------------------------

    private static long relKey(LevelPlot plot, BlockPos pos) {
        return PlayerPlacedTrainBlocks.relKey(plot, pos);
    }
}
