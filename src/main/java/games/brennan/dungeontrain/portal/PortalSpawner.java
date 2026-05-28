package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.registry.ModBlocks;
import games.brennan.dungeontrain.train.Trains;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3dc;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Auto-spawns dimensional portals along a train's track at fixed intervals.
 *
 * <h2>Trigger</h2>
 * <p>Called once per server tick by {@code TrainTickEvents.onLevelTick}
 * after the appender pass. For every loaded train, tracks how far the
 * lead carriage has moved since the last portal spawn and emits a new
 * portal pair when the threshold ({@link #PORTAL_INTERVAL_BLOCKS}) is
 * reached.</p>
 *
 * <h2>Pairing</h2>
 * <p>Each spawn produces a {@link PortalPair}: source endpoint in the
 * train's current dim, partner endpoint in the cycle's next dim
 * (OW → Nether → End → OW). The partner uses the same world coordinates
 * as the source — simple, predictable, and avoids the 1:8 Nether ratio
 * complication for v1.</p>
 *
 * <h2>Per-train counter</h2>
 * <p>{@link #LAST_PORTAL_X} is an in-memory {@code Map<(dim, trainId), Double>}
 * tracking the lead's world-X at last spawn. On world reload it starts
 * empty — first tick re-seeds from the current lead position, so a player
 * that flies past 5000 blocks while the world was unloaded won't see five
 * portals materialise the moment they reload. The {@link PortalRegistry}
 * still has the persistent portals; this map is purely for "when to spawn
 * the NEXT one."</p>
 */
public final class PortalSpawner {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Blocks of forward progress between consecutive portals along a single
     * train's track.
     *
     * <p>v1 test value: 100 blocks. Tightened from the original 1000 so a
     * quick ride during Gate-2 verification surfaces portals in seconds
     * rather than minutes. Will be bumped back toward 500–1000 once the
     * mechanic stabilises (see Phase 12 polish in
     * plans/sorted-squishing-fern.md).</p>
     */
    public static final int PORTAL_INTERVAL_BLOCKS = 100;

    /**
     * Spawn the portal frame this many blocks ahead of the lead carriage so
     * the train doesn't immediately hit a half-built portal during the tick
     * the frame gets placed. Should comfortably exceed one tick of forward
     * motion at typical train speed (~5 blocks/s) so the carriage has a few
     * ticks of approach time.
     */
    private static final int SPAWN_AHEAD_BLOCKS = 60;

    /** Chunk-radius pre-warmed around the partner endpoint. */
    private static final int PREWARM_RADIUS = 3;

    /**
     * Frame half-extent in the Z (across-track) direction. Total width =
     * {@code 2 * FRAME_HALF_WIDTH + 1} = 11 blocks; interior opening (frame
     * stripped) = 9 blocks. Default carriage width is 7; 9-block interior
     * leaves 1 block of clearance on each side.
     */
    static final int FRAME_HALF_WIDTH = 5;   // → 11 blocks wide (9 interior)

    /**
     * Frame half-extent in the Y direction. Total height = 11 blocks;
     * interior opening = 9 blocks. Default carriage height is 7; 9-block
     * interior fits the carriage with 1 block of clearance for the player
     * riding on top.
     *
     * <p>v1 size note: tightened to "just fits a default carriage." If we
     * ever bump default carriage dims past 9×9 we'll need to grow these
     * too — see CarriageDims.MAX_WIDTH / MAX_HEIGHT.</p>
     */
    static final int FRAME_HALF_HEIGHT = 5;  // → 11 blocks tall (9 interior)

    private record DimTrainKey(ResourceKey<Level> dim, UUID trainId) {}

    private static final Map<DimTrainKey, Double> LAST_PORTAL_X = new ConcurrentHashMap<>();

    private PortalSpawner() {}

    /**
     * Per-tick entry point. Iterates every train loaded in {@code level}
     * and spawns at most one portal per train per tick. Designed to be
     * called from {@code TrainTickEvents.onLevelTick} after the appender
     * pass so newly-extended carriages are already visible.
     */
    public static void tick(ServerLevel level, Map<UUID, List<Trains.Carriage>> trainsById) {
        if (trainsById.isEmpty()) return;
        for (Map.Entry<UUID, List<Trains.Carriage>> entry : trainsById.entrySet()) {
            UUID trainId = entry.getKey();
            List<Trains.Carriage> carriages = entry.getValue();
            Trains.Carriage lead = Trains.lead(carriages);
            if (lead == null) continue;

            Vector3dc leadPos = lead.ship().currentWorldPosition();
            double leadX = leadPos.x();
            DimTrainKey key = new DimTrainKey(level.dimension(), trainId);
            Double lastX = LAST_PORTAL_X.get(key);

            if (lastX == null) {
                // First observation since (re)load — seed the counter with
                // the current lead position so we don't backfill missed
                // portals all at once.
                LAST_PORTAL_X.put(key, leadX);
                continue;
            }

            if (leadX - lastX < PORTAL_INTERVAL_BLOCKS) continue;

            // Time to spawn.
            ResourceKey<Level> targetDim = nextDimInCycle(level.dimension());
            if (spawnPortalAhead(level, targetDim, lead, trainId)) {
                LAST_PORTAL_X.put(key, leadX);
            }
            // If spawn failed (e.g. target dim unresolvable), don't update —
            // the next tick will retry with the still-too-stale lastX, which
            // is fine: the train will have moved a few more blocks and we
            // try again. No risk of duplicate spawns because the position
            // shifts each tick.
        }
    }

    /**
     * Dimension cycle for v1: OW → Nether → End → OW. Custom modded dims
     * fall back to Overworld so portals always have somewhere to land
     * rather than crashing on an unmapped key.
     */
    private static ResourceKey<Level> nextDimInCycle(ResourceKey<Level> current) {
        if (current.equals(Level.OVERWORLD)) return Level.NETHER;
        if (current.equals(Level.NETHER)) return Level.END;
        if (current.equals(Level.END)) return Level.OVERWORLD;
        return Level.OVERWORLD;
    }

    /**
     * Place a portal pair: source structure {@link #SPAWN_AHEAD_BLOCKS}
     * ahead of the lead carriage in the train's dim, partner structure at
     * the same world coordinates in {@code targetDim}, both registered in
     * the {@link PortalRegistry}. Pre-warms target chunks via
     * {@link ChunkPrewarmer}.
     *
     * @return {@code true} on success, {@code false} if the target dim
     *         couldn't be resolved (server has no such dim).
     */
    private static boolean spawnPortalAhead(ServerLevel sourceLevel, ResourceKey<Level> targetDim,
                                            Trains.Carriage lead, UUID trainId) {
        Vector3dc leadPos = lead.ship().currentWorldPosition();
        BlockPos sourcePortalPos = new BlockPos(
            (int) Math.round(leadPos.x() + SPAWN_AHEAD_BLOCKS),
            (int) Math.round(leadPos.y()),
            (int) Math.round(leadPos.z())
        );
        // For v1, partner uses the same world coords. Future: scale to
        // 1:8 for Nether or carve an arrival pad if terrain interferes.
        BlockPos partnerPos = sourcePortalPos;

        MinecraftServer server = sourceLevel.getServer();
        ServerLevel targetLevel = server.getLevel(targetDim);
        if (targetLevel == null) {
            LOGGER.warn("[Portal] Cannot spawn pair — target dim {} unresolved on this server", targetDim.location());
            return false;
        }

        // Register first so we have a pair UUID for both ChunkPrewarmer
        // and the BE partner-coords.
        PortalRegistry registry = PortalRegistry.get(server);
        UUID pairId = registry.addPair(sourceLevel.dimension(), sourcePortalPos, targetDim, partnerPos);

        // Pre-warm the partner side BEFORE placing blocks there: addRegionTicket
        // is non-blocking, so by the time we call setBlock the chunks should
        // already be queued for load. setBlock will then complete its load
        // synchronously if needed (much faster with a ticket already in
        // place) and won't spike the tick as badly.
        ChunkPrewarmer.warm(targetLevel, new ChunkPos(partnerPos), PREWARM_RADIUS, pairId);

        placePortalStructure(sourceLevel, sourcePortalPos, targetDim, partnerPos);
        placePortalStructure(targetLevel, partnerPos, sourceLevel.dimension(), sourcePortalPos);

        LOGGER.info("[Portal] Spawned pair {} for trainId={}: {} {} ↔ {} {}",
            pairId, trainId,
            sourceLevel.dimension().location(), sourcePortalPos,
            targetDim.location(), partnerPos);
        return true;
    }

    /**
     * Build the multi-block portal structure centred on {@code center} in
     * {@code level}: 9-wide × 7-tall frame in the YZ plane, frame perimeter
     * is {@link ModBlocks#DIMENSIONAL_PORTAL_FRAME}, interior is
     * {@link ModBlocks#DIMENSIONAL_PORTAL_CORE} with AXIS=X (train flows
     * along +X). After placement, every interior core BE has its partner
     * coords set so transit detection (Phase 9) can resolve the partner
     * without re-consulting the registry every tick.
     *
     * <p>v1 places blocks unconditionally — terrain that happens to be in
     * the frame volume is overwritten. DT trains live in long carved track
     * corridors so the portal is almost always in air anyway. Carving a
     * clearance volume is deferred to Phase 12 polish.</p>
     */
    private static void placePortalStructure(ServerLevel level, BlockPos center,
                                             ResourceKey<Level> partnerDim, BlockPos partnerPos) {
        Block frame = ModBlocks.DIMENSIONAL_PORTAL_FRAME.get();
        Block core = ModBlocks.DIMENSIONAL_PORTAL_CORE.get();
        BlockState frameState = frame.defaultBlockState();
        BlockState coreState = core.defaultBlockState()
            .setValue(DimensionalPortalCoreBlock.AXIS, Direction.Axis.X);

        for (int dy = -FRAME_HALF_HEIGHT; dy <= FRAME_HALF_HEIGHT; dy++) {
            for (int dz = -FRAME_HALF_WIDTH; dz <= FRAME_HALF_WIDTH; dz++) {
                BlockPos pos = center.offset(0, dy, dz);
                boolean onPerimeter = Math.abs(dy) == FRAME_HALF_HEIGHT
                                   || Math.abs(dz) == FRAME_HALF_WIDTH;
                if (onPerimeter) {
                    level.setBlock(pos, frameState, Block.UPDATE_ALL);
                } else {
                    level.setBlock(pos, coreState, Block.UPDATE_ALL);
                    // Set partner data on the freshly-placed BE.
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be instanceof DimensionalPortalBlockEntity portalBE) {
                        portalBE.setPartner(partnerPos, partnerDim);
                    }
                }
            }
        }
    }

    /**
     * Drop the per-train spawn counters. Wired to server stop so a fresh
     * world doesn't inherit stale state from a previous session.
     */
    public static void clearState() {
        LAST_PORTAL_X.clear();
    }
}
