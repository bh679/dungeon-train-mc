package games.brennan.dungeontrain.ship;

import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import games.brennan.dungeontrain.ship.sable.SableManagedShip;
import games.brennan.dungeontrain.train.Trains;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.joml.Vector3d;
import org.joml.primitives.AABBdc;

import java.util.List;

/**
 * Shared "is this entity on the train?" geometry — the single source of truth for both the coarse
 * carriage-footprint test and the precise on-the-deck test. Extracted from
 * {@code event.PlayerMobAdvancementEvents} (the Reboarder push-off detection) so the remote-echo
 * encounter journal reads the same implementation rather than duplicating Sable sub-level lookups.
 *
 * <p>Two notions of "aboard":
 * <ul>
 *   <li>{@link #isOnTrainFootprint} — within a carriage's padded world AABB (bridges group joints,
 *       counts standing on a roof). Lenient capture.</li>
 *   <li>{@link #isOnCarriageDeck} — supported by an actual carriage block. A flatbed's walkable floor
 *       is narrower than its {@code worldAABB}, so a mob punched off the floor (into the open interior,
 *       beside the floor, or down onto the world-block rails) stays inside the AABB but is supported
 *       by no carriage block — the real "pushed off the deck" signal.</li>
 * </ul>
 */
public final class CarriageDeck {

    /** Horizontal pad on each carriage AABB — bridges group joints; matches BoardingProgressEvents. */
    public static final double HORIZONTAL_PADDING = 1.0;

    private CarriageDeck() {}

    /**
     * True when {@code e} is within any carriage's padded world AABB — the same geometry
     * {@code BoardingProgressEvents} uses to decide a player is "on the train" (horizontal pad to
     * bridge group joints; +1 above to count standing on a roof).
     */
    public static boolean isOnTrainFootprint(List<Trains.Carriage> carriages, Entity e) {
        double x = e.getX();
        double y = e.getY();
        double z = e.getZ();
        for (Trains.Carriage c : carriages) {
            AABBdc bb = c.ship().worldAABB();
            if (x < bb.minX() - HORIZONTAL_PADDING || x > bb.maxX() + HORIZONTAL_PADDING) continue;
            if (y < bb.minY() || y > bb.maxY() + 1.0) continue;
            if (z < bb.minZ() - HORIZONTAL_PADDING || z > bb.maxZ() + HORIZONTAL_PADDING) continue;
            return true;
        }
        return false;
    }

    /**
     * True when {@code e} is standing on a solid block that belongs to a carriage — the precise
     * "on the deck/roof" test the coarse {@link #isOnTrainFootprint} AABB can't make.
     *
     * <p>Reads the carriage's Sable sub-level blocks exactly as {@code VillagerJobSiteAssigner} /
     * {@code SoulCampfireHealEvents} do: world-AABB pre-filter, {@code worldToShip} into ship-local
     * space, then a plot block lookup.</p>
     */
    public static boolean isOnCarriageDeck(List<Trains.Carriage> carriages, Entity e) {
        double ex = e.getX();
        double ey = e.getY();
        double ez = e.getZ();
        for (Trains.Carriage c : carriages) {
            ManagedShip ship = c.ship();
            AABBdc bb = ship.worldAABB();
            if (ex < bb.minX() - HORIZONTAL_PADDING || ex > bb.maxX() + HORIZONTAL_PADDING) continue;
            if (ey < bb.minY() - 1.0 || ey > bb.maxY() + 1.0) continue;
            if (ez < bb.minZ() - HORIZONTAL_PADDING || ez > bb.maxZ() + HORIZONTAL_PADDING) continue;
            if (!(ship instanceof SableManagedShip sableShip)) continue;

            Vector3d local = new Vector3d(ex, ey, ez);
            ship.worldToShip(local);
            BlockPos feet = BlockPos.containing(local.x, local.y, local.z);
            if (isSupportedByCarriage(sableShip.subLevel().getPlot(), feet)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if the ship plot has a non-air block at the feet or directly below them (ship-local
     * coords). Checking feet and feet-1 covers both top-slab walkways and full-block floors.
     */
    private static boolean isSupportedByCarriage(LevelPlot plot, BlockPos feet) {
        long chunkKey = ChunkPos.asLong(feet.getX() >> 4, feet.getZ() >> 4);
        for (PlotChunkHolder holder : plot.getLoadedChunks()) {
            LevelChunk chunk = holder.getChunk();
            if (chunk == null || chunk.getPos().toLong() != chunkKey) continue;
            return !chunk.getBlockState(feet.below()).isAir()
                || !chunk.getBlockState(feet).isAir();
        }
        return false;
    }
}
