package games.brennan.dungeontrain.train;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import games.brennan.dungeontrain.ship.CarriageDeck;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.sable.SableManagedShip;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.joml.Vector3d;
import org.joml.primitives.AABBdc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Breaks a <b>player-added</b> carriage block off the train when it collides — the reverse of what
 * a train-built block does (see {@link PlayerPlacedTrainBlocks}).
 *
 * <p>Two contact sources:</p>
 * <ul>
 *   <li><b>Terrain</b> — found by {@code TrainTickEvents.sweepFootprint}, which already walks every
 *       world cell the train overlaps; for a player-placed carriage block it calls {@link #breakOff}
 *       instead of breaking the world block.</li>
 *   <li><b>Other physics objects</b> — {@link #checkPhysicsContacts}: non-train Sable sub-levels near
 *       a carriage that carries player blocks. The train is kinematic and zero-mass, so Rapier pushes
 *       those bodies away and never reports a hit to DT; this pass looks for a player block touching
 *       one instead.</li>
 * </ul>
 *
 * <p>The block drops as items at its <em>world</em> position, with break particles and sound there.
 * Not {@code destroyBlock} at the plot position: that would spawn the drops and effect in plot
 * space, out of every player's sight and reach.</p>
 */
public final class PlayerBlockBreakOff {

    private static final Logger LOGGER = LoggerFactory.getLogger("games.brennan.dungeontrain.jitter");

    /** Extra reach, in blocks, of a carriage's box when looking for nearby physics objects. */
    private static final double CONTACT_SEARCH_MARGIN = 0.25;

    /**
     * How far outside its own cube a player block probes for a foreign block. Rapier resolves a
     * contact before the bodies interpenetrate, so two touching blocks sit face to face with at most
     * a sliver of overlap; probing a little outside the face is what sees that neighbour.
     */
    private static final double CONTACT_PROBE = 0.1;

    /** Probe points in block-local coordinates: centre, 8 corners and 6 face centres, pushed outward. */
    private static final double[][] PROBE_OFFSETS = buildProbeOffsets();

    /** Minimum ticks between {@code [sweep.breakoff]} debug lines. */
    private static final long LOG_THROTTLE_TICKS = 20;

    private static long lastLogTick = Long.MIN_VALUE;

    private PlayerBlockBreakOff() {}

    /**
     * Break the player-added block at plot position {@code plotPos} of {@code subLevel} off the train:
     * remove it from the carriage and drop it (and a container's contents) at world cell
     * {@code worldPos}. Returns whether a block was removed.
     */
    public static boolean breakOff(ServerLevel level, ServerSubLevel subLevel, BlockPos plotPos, BlockPos worldPos) {
        LevelChunk chunk = CarriageDeck.chunkInPlot(subLevel.getPlot(), plotPos);
        if (chunk == null) return false;
        BlockState state = chunk.getBlockState(plotPos);
        if (state.isAir()) return false;
        BlockEntity blockEntity = chunk.getBlockEntity(plotPos);

        List<ItemStack> drops = new ArrayList<>(Block.getDrops(state, level, worldPos, blockEntity));
        // A shulker box keeps its contents inside the dropped item (its loot table copies them), so
        // spilling them too would duplicate every item.
        if (blockEntity instanceof Container container && !(blockEntity instanceof ShulkerBoxBlockEntity)) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (!stack.isEmpty()) drops.add(stack.copy());
            }
        }

        // Evict the block entity first so the write below doesn't fail its state validation, then
        // remove the block through the ordinary level path: that syncs clients and fires Sable's
        // block-change hook (pivot re-pin, mark cleared, shared-carriage delta). Drops suppressed —
        // they are spawned in world space below.
        SilentBlockOps.evictBlockEntity(chunk, plotPos);
        level.setBlock(plotPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);

        level.levelEvent(2001, worldPos, Block.getId(state)); // break particles + sound
        for (ItemStack stack : drops) Block.popResource(level, worldPos, stack);
        logBreakOff(level, state, worldPos);
        return true;
    }

    /**
     * Break off every player-added block of {@code train} that is touching a non-train physics object,
     * up to {@code budget} blocks. Returns how many broke.
     *
     * <p>Costs nothing measurable in the normal case: a carriage without player blocks is skipped on
     * one map lookup, and one with them only goes further when a foreign sub-level's box is within
     * reach of the carriage's.</p>
     */
    public static int checkPhysicsContacts(ServerLevel level, List<Trains.Carriage> train, int budget) {
        if (budget <= 0) return 0;
        List<ServerSubLevel> foreign = null; // gathered lazily: only once a carriage carries player blocks
        Set<ServerSubLevel> seen = new HashSet<>();
        int broke = 0;
        for (Trains.Carriage carriage : train) {
            ManagedShip ship = carriage.ship();
            if (!(ship instanceof SableManagedShip sableShip) || !ship.isResident()) continue;
            ServerSubLevel subLevel = sableShip.subLevel();
            if (subLevel == null || !PlayerPlacedTrainBlocks.hasAny(subLevel.getUniqueId())) continue;
            if (!seen.add(subLevel)) continue; // several carriages can share one group sub-level

            if (foreign == null) foreign = foreignSubLevels(level);
            if (foreign.isEmpty()) return broke;
            List<ServerSubLevel> near = nearby(ship.worldAABB(), foreign);
            if (near.isEmpty()) continue;

            for (BlockPos plotPos : PlayerPlacedTrainBlocks.plotPositions(subLevel)) {
                if (CarriageDeck.blockInPlot(subLevel.getPlot(), plotPos).isAir()) continue;
                if (!touchesAny(ship, plotPos, near)) continue;
                Vector3d centre = ship.shipToWorld(
                    new Vector3d(plotPos.getX() + 0.5, plotPos.getY() + 0.5, plotPos.getZ() + 0.5));
                BlockPos worldPos = BlockPos.containing(centre.x, centre.y, centre.z);
                if (breakOff(level, subLevel, plotPos, worldPos) && ++broke >= budget) return broke;
            }
        }
        return broke;
    }

    /** Every live sub-level in {@code level} that is not part of a Dungeon Train. */
    private static List<ServerSubLevel> foreignSubLevels(ServerLevel level) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return List.of();
        List<ServerSubLevel> out = new ArrayList<>();
        for (SubLevel sub : container.getAllSubLevels()) {
            if (sub instanceof ServerSubLevel server && !server.isRemoved()
                && !SableManagedShip.isDungeonTrainManaged(server)) {
                out.add(server);
            }
        }
        return out;
    }

    /** The foreign sub-levels whose world box comes within {@link #CONTACT_SEARCH_MARGIN} of {@code box}. */
    private static List<ServerSubLevel> nearby(AABBdc box, List<ServerSubLevel> foreign) {
        List<ServerSubLevel> out = new ArrayList<>();
        for (ServerSubLevel other : foreign) {
            BoundingBox3dc b = other.boundingBox();
            if (overlaps(box.minX(), box.maxX(), b.minX(), b.maxX())
                && overlaps(box.minY(), box.maxY(), b.minY(), b.maxY())
                && overlaps(box.minZ(), box.maxZ(), b.minZ(), b.maxZ())) {
                out.add(other);
            }
        }
        return out;
    }

    private static boolean overlaps(double aMin, double aMax, double bMin, double bMax) {
        return aMin - CONTACT_SEARCH_MARGIN <= bMax && bMin <= aMax + CONTACT_SEARCH_MARGIN;
    }

    /**
     * Whether the player block at {@code plotPos} is touching a solid block of any of {@code others}.
     * Probes the block's centre, corners and face centres, each pushed {@link #CONTACT_PROBE} outward,
     * so a neighbour flush against any face, edge or corner is seen whatever its rotation.
     */
    private static boolean touchesAny(ManagedShip ship, BlockPos plotPos, List<ServerSubLevel> others) {
        for (double[] offset : PROBE_OFFSETS) {
            Vector3d world = ship.shipToWorld(new Vector3d(
                plotPos.getX() + offset[0], plotPos.getY() + offset[1], plotPos.getZ() + offset[2]));
            for (ServerSubLevel other : others) {
                Vector3d local = other.logicalPose().transformPositionInverse(new Vector3d(world));
                BlockPos cell = BlockPos.containing(local.x, local.y, local.z);
                BlockState state = CarriageDeck.blockInPlot(other.getPlot(), cell);
                if (!state.isAir() && !state.getCollisionShape(EmptyBlockGetter.INSTANCE, cell).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static double[][] buildProbeOffsets() {
        double lo = -CONTACT_PROBE;
        double hi = 1 + CONTACT_PROBE;
        List<double[]> out = new ArrayList<>();
        out.add(new double[] {0.5, 0.5, 0.5});
        for (double x : new double[] {lo, hi}) {
            for (double y : new double[] {lo, hi}) {
                for (double z : new double[] {lo, hi}) out.add(new double[] {x, y, z});
            }
        }
        out.add(new double[] {lo, 0.5, 0.5});
        out.add(new double[] {hi, 0.5, 0.5});
        out.add(new double[] {0.5, lo, 0.5});
        out.add(new double[] {0.5, hi, 0.5});
        out.add(new double[] {0.5, 0.5, lo});
        out.add(new double[] {0.5, 0.5, hi});
        return out.toArray(new double[0][]);
    }

    private static void logBreakOff(ServerLevel level, BlockState state, BlockPos worldPos) {
        long now = level.getGameTime();
        if (lastLogTick != Long.MIN_VALUE && now >= lastLogTick && now - lastLogTick < LOG_THROTTLE_TICKS) return;
        lastLogTick = now;
        LOGGER.debug("[sweep.breakoff] player block {} broke off at {}", state.getBlock(), worldPos);
    }
}
