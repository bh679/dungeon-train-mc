package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * Brings a freshly stamped twin corridor back in line with the carriage it pairs with.
 *
 * <p>{@link PortalEditMirror} keeps the two block-for-block identical while both stand, but a twin
 * is re-stamped from its template whenever the pair's structure is laid again — a drift relocation,
 * an eviction, the first stamp after a restart — while the carriage, a persistent Sable sub-level,
 * keeps every block a player changed in it. The template twin then no longer matches: a wall broken
 * on the train is back on the other side, a torch placed there is gone. The carriage is the copy that
 * remembers, so the twin is copied from it, once per stamp, the first time the pair is published.</p>
 *
 * <p>Block <i>state</i> only, as the live mirror is: a chest syncs as a chest, not as its contents.
 * Writes go through {@link SilentBlockOps}, which fires no place/break events, so nothing is mirrored
 * back into the carriage.</p>
 */
public final class PortalCorridorResync {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Corridor carriage index → the stamp (game time) its twin was last synced against. */
    private static final Map<Integer, Long> SYNCED_STAMP = new HashMap<>();

    private PortalCorridorResync() {}

    /**
     * Sync {@code entry}'s twin from its carriage if it has not been synced since the pair was last
     * stamped at {@code stampedAt}. Cheap to call every tick: a map lookup when nothing changed.
     */
    public static void syncIfRestamped(ServerLevel level, PortalPairIndex.Entry entry, long stampedAt) {
        Long synced = SYNCED_STAMP.get(entry.carriageIndex());
        if (synced != null && synced == stampedAt) return;
        SYNCED_STAMP.put(entry.carriageIndex(), stampedAt);
        int changed = sync(level, entry);
        if (changed > 0) {
            LOGGER.info("[DungeonTrain] Portal corridor {} twin re-synced from its carriage: {} block(s) "
                + "the template had reset", entry.carriageIndex(), changed);
        }
    }

    /** Copy every corridor cell whose state differs from the carriage into the twin; returns the count. */
    static int sync(ServerLevel level, PortalPairIndex.Entry entry) {
        int length = PortalCorridorSize.corridorLength(entry.dims(), entry.kind());
        int height = entry.dims().height();
        int width = entry.dims().width();
        int changed = 0;
        for (int dx = 0; dx < length; dx++) {
            for (int dy = 0; dy < height; dy++) {
                for (int dz = 0; dz < width; dz++) {
                    int[] local = {dx, dy, dz};
                    BlockState want = level.getBlockState(entry.plotPosOf(local));
                    BlockPos twin = entry.twinPosOf(local);
                    if (want.equals(level.getBlockState(twin))) continue;
                    if (want.isAir()) {
                        SilentBlockOps.clearBlockSilent(level, twin);
                    } else {
                        SilentBlockOps.setBlockSilent(level, twin, want);
                    }
                    changed++;
                }
            }
        }
        return changed;
    }

    /** The server stopped: the next world's carriage indices are different corridors. */
    public static void clear() {
        SYNCED_STAMP.clear();
    }
}
