package games.brennan.dungeontrain.portal;

import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The live portal corridors, published each tick by
 * {@code games.brennan.dungeontrain.event.PortalCarriageEvents} and read by {@link PortalEditMirror}.
 *
 * <p>Exists so a block change can be resolved to a corridor without re-deriving the train's geometry.
 * The mirror is called from inside Sable's per-block-change hook, on the hot path of every block
 * change in any sub-level, so it has to answer "is this in a portal corridor?" with a map lookup and
 * a bounds check rather than by walking groups and ship AABBs.</p>
 *
 * <p>Two coordinate spaces, deliberately kept apart in the type: a carriage's blocks live in its
 * sub-level plot at shipyard coordinates, while its twin's are ordinary world blocks.</p>
 */
public final class PortalPairIndex {

    /**
     * One live corridor pairing.
     *
     * <p>Holds the {@link LevelPlot} itself rather than a sub-level id: the Sable hook already has
     * the plot in hand for the changed chunk, so matching on identity avoids a lookup on the hot
     * path, and writing the mirrored block needs the plot anyway.</p>
     *
     * @param plot        the carriage's sub-level plot, where its blocks actually live
     * @param plotOrigin  the corridor's origin in the plot's shipyard space
     * @param twinOrigin  the twin corridor's origin in world space
     * @param dims        carriage dims, which bound both corridors
     */
    public record Entry(LevelPlot plot, BlockPos plotOrigin, BlockPos twinOrigin, CarriageDims dims) {

        /** Local cell of a shipyard position, or {@code null} if it falls outside the corridor. */
        public int[] localOfPlot(int x, int y, int z) {
            return localOf(x - plotOrigin.getX(), y - plotOrigin.getY(), z - plotOrigin.getZ());
        }

        /** Local cell of a world position, or {@code null} if it falls outside the twin. */
        public int[] localOfTwin(BlockPos pos) {
            return localOf(pos.getX() - twinOrigin.getX(), pos.getY() - twinOrigin.getY(),
                pos.getZ() - twinOrigin.getZ());
        }

        private int[] localOf(int dx, int dy, int dz) {
            if (dx < 0 || dy < 0 || dz < 0
                || dx >= dims.length() || dy >= dims.height() || dz >= dims.width()) {
                return null;
            }
            return new int[] {dx, dy, dz};
        }

        public BlockPos plotPosOf(int[] local) {
            return plotOrigin.offset(local[0], local[1], local[2]);
        }

        public BlockPos twinPosOf(int[] local) {
            return twinOrigin.offset(local[0], local[1], local[2]);
        }
    }

    /** Carriage index → its live pairing. Written on the server thread, read from the Sable hook. */
    private static final Map<Integer, Entry> ENTRIES = new ConcurrentHashMap<>();

    private PortalPairIndex() {}

    public static void publish(int carriageIndex, Entry entry) {
        ENTRIES.put(carriageIndex, entry);
    }

    public static void forget(int carriageIndex) {
        ENTRIES.remove(carriageIndex);
    }

    public static void clear() {
        ENTRIES.clear();
    }

    public static boolean isEmpty() {
        return ENTRIES.isEmpty();
    }

    /** The corridor containing this shipyard position in the given plot, or {@code null}. */
    public static Entry findByPlotPos(LevelPlot plot, int x, int y, int z) {
        for (Entry entry : ENTRIES.values()) {
            if (entry.plot() != plot) continue;
            if (entry.localOfPlot(x, y, z) != null) return entry;
        }
        return null;
    }

    /** The twin corridor containing this world position, or {@code null}. */
    public static Entry findByTwinPos(BlockPos pos) {
        for (Entry entry : ENTRIES.values()) {
            if (entry.localOfTwin(pos) != null) return entry;
        }
        return null;
    }
}
