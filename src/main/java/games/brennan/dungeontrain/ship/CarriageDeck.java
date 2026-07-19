package games.brennan.dungeontrain.ship;

import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import games.brennan.dungeontrain.ship.sable.SableManagedShip;
import games.brennan.dungeontrain.train.Trains;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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
        return !blockInPlot(plot, feet.below()).isAir() || !blockInPlot(plot, feet).isAir();
    }

    /**
     * The carriage's OWN block at a <b>world</b> position — air when the carriage has nothing there.
     *
     * <p>The block-position counterpart of {@link #isOnCarriageDeck}, and the precise test a
     * {@code worldAABB} overlap cannot make: a carriage is not a solid box. A flatbed is mostly open
     * air inside its own bounding box, so "inside the AABB" and "actually touching the train" are
     * different questions. Used by {@code TrainTickEvents.sweepFootprint} so the train only breaks
     * world blocks it genuinely collides with, not ones drifting through its empty interior.</p>
     *
     * <p><b>Do not reach for {@code level.getBlockState(worldPos)} here.</b> It returns AIR for
     * carriage blocks: DT plot chunks are loaded from NBT into {@code PlotChunkHolder}s that never
     * enter the host level's chunk source (see {@code TrainCinematographerEvents}), and Sable's
     * {@code EmbeddedPlotLevelAccessor} just offsets and delegates to the host level, so it fails the
     * same way. The plot is the only place these voxels exist — and the failure is silent (everything
     * reads as air), so it presents as "the feature does nothing" rather than as an error.</p>
     */
    public static BlockState blockAt(ManagedShip ship, BlockPos worldPos) {
        if (!(ship instanceof SableManagedShip sableShip)) return Blocks.AIR.defaultBlockState();
        Vector3d local = new Vector3d(worldPos.getX() + 0.5, worldPos.getY() + 0.5, worldPos.getZ() + 0.5);
        ship.worldToShip(local);
        return blockInPlot(sableShip.subLevel().getPlot(), BlockPos.containing(local.x, local.y, local.z));
    }

    /**
     * Block at a <b>ship-local</b> position within {@code plot}, or air when that chunk isn't loaded.
     *
     * <p>Scans {@link LevelPlot#getLoadedChunks()} for the matching chunk key rather than using
     * {@code plot.getChunk(plot.toLocal(pos))}: a plot holds only a handful of chunks (a carriage is
     * ~9x7x7) so the scan is trivial, and it is the idiom already proven in shipped code here and in
     * {@code VillagerJobSiteAssigner} / {@code SoulCampfireHealEvents}. The O(1) path depends on
     * whether {@code getChunk} bounds-checks plot-local or global coords; guessing wrong there
     * returns null, which reads as air and silently disables every caller.</p>
     */
    private static BlockState blockInPlot(LevelPlot plot, BlockPos shipLocal) {
        long chunkKey = ChunkPos.asLong(shipLocal.getX() >> 4, shipLocal.getZ() >> 4);
        for (PlotChunkHolder holder : plot.getLoadedChunks()) {
            LevelChunk chunk = holder.getChunk();
            if (chunk == null || chunk.getPos().toLong() != chunkKey) continue;
            return chunk.getBlockState(shipLocal);
        }
        return Blocks.AIR.defaultBlockState();
    }
}
