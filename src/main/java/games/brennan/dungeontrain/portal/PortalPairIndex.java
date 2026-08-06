package games.brennan.dungeontrain.portal;

import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

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
     * <p>Holds the {@link LevelPlot} for identity matching (the Sable hook already has it in hand for
     * the changed chunk) and the {@link ManagedShip} for coordinate work.</p>
     *
     * <p><b>Every plot↔world conversion goes through the ship's own transform, one point at a time.</b>
     * An earlier version precomputed the corridor's plot origin once and did plain subtraction, which
     * silently assumed the plot's axes run the same way as the world's. When a ship's pose flips an
     * axis that assumption reflects every mirrored edit onto the opposite side of the corridor — a
     * block broken on the left wall vanishing from the right. Asking the ship to convert each point
     * costs a few multiplications and cannot be wrong about orientation.</p>
     *
     * @param plot          the carriage's sub-level plot, where its blocks actually live
     * @param ship          the carriage, for {@code worldToShip} / {@code shipToWorld}
     * @param carriageWorld the corridor's origin in WORLD space, read live from the ship's AABB
     * @param twinOrigin    the twin corridor's origin in world space
     * @param dims          carriage dims, which bound both corridors
     */
    public record Entry(LevelPlot plot, ManagedShip ship, Vec3 carriageWorld, BlockPos twinOrigin,
                        CarriageDims dims) {

        /** Local cell of a shipyard position, or {@code null} if it falls outside the corridor. */
        public int[] localOfPlot(int x, int y, int z) {
            Vector3d world = ship.shipToWorld(new Vector3d(x + 0.5, y + 0.5, z + 0.5));
            return localOf(
                Mth.floor(world.x - carriageWorld.x),
                Mth.floor(world.y - carriageWorld.y),
                Mth.floor(world.z - carriageWorld.z));
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

        /** The shipyard position of a local cell, via the ship's transform rather than an assumed axis. */
        public BlockPos plotPosOf(int[] local) {
            Vector3d ship3 = ship.worldToShip(new Vector3d(
                carriageWorld.x + local[0] + 0.5,
                carriageWorld.y + local[1] + 0.5,
                carriageWorld.z + local[2] + 0.5));
            return BlockPos.containing(ship3.x, ship3.y, ship3.z);
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
