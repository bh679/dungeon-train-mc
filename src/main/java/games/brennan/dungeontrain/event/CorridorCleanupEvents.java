package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.track.TrackGenerator;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.world.StartingDimension;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Sweeps cross-chunk foliage spillover out of the train corridor on a
 * deferred per-tick drain. Trees rooted in chunk X+1 can place leaves
 * up to ~16 blocks into chunk X during X+1's {@code vegetal_decoration}
 * step, which fires AFTER chunk X has already finished its
 * {@code top_layer_modification} (where {@code TrackBedFeature} runs).
 * The spillover lands inside the cleared corridor without re-clearing.
 *
 * <p>Architecture: enqueue corridor chunks on {@link ChunkEvent.Load},
 * drain {@link #CHUNKS_PER_TICK} chunks per server tick. Setting blocks
 * synchronously inside the load callback was tried first and hung the
 * server thread because lighting / fluid cascades re-entered chunk
 * loading recursively. Deferring to a tick boundary breaks that path —
 * setBlock happens after every chunk-load callback in flight has
 * returned.</p>
 *
 * <p>Predicate is intentionally narrow: leaves, vines, saplings, small
 * + tall flowers. Explicitly NOT water, lava, fire, snow, bubble columns,
 * or any other replaceable block — those would cascade. Solid blocks
 * (logs, stone, dirt) above the carriage envelope are out of scope; they
 * are tree trunks that legitimately stand inside the corridor's
 * horizontal footprint above the carriage roof, and the train doesn't
 * physically interact with them anyway.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class CorridorCleanupEvents {

    private static final int CHUNKS_PER_TICK = 1;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /** ChunkPos longs that have been queued by {@link #onChunkLoad}, awaiting drain. */
    private static final Deque<Long> PENDING = new ConcurrentLinkedDeque<>();

    /** ChunkPos longs already cleaned this session. In-memory only. */
    private static final Set<Long> CLEANED = ConcurrentHashMap.newKeySet();

    private CorridorCleanupEvents() {}

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        MinecraftServer server = level.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        if (overworld == null) return;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        StartingDimension expected = data.startingDimension();
        if (!level.dimension().equals(expected.levelKey())) return;

        ChunkAccess chunk = event.getChunk();
        ChunkPos pos = chunk.getPos();
        int cx = pos.x;
        int cz = pos.z;
        if (TrackGenerator.isShipyardChunk(cx, cz)) return;

        // Fast Z-corridor prefilter — corridor is z=[trackZMin..trackZMax];
        // typically chunk cz=0 only for the default 7-wide corridor.
        CarriageDims dims = data.dims();
        TrackGeometry g = TrackGeometry.from(dims, data.getTrainY());
        int chunkMinZ = cz << 4;
        int chunkMaxZ = chunkMinZ + 15;
        if (chunkMaxZ < g.trackZMin() || chunkMinZ > g.trackZMax()) return;

        long key = ChunkPos.asLong(cx, cz);
        if (CLEANED.contains(key)) return;
        // offer() onto the tail; drain pops from head so cleanup follows
        // load order ≈ spatial proximity to the player.
        PENDING.offer(key);
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (PENDING.isEmpty()) return;

        // Resolve corridor geometry once per tick. If data isn't ready yet
        // (very early world creation), bail — pending chunks stay queued.
        MinecraftServer server = level.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        if (overworld == null) return;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        if (!level.dimension().equals(data.startingDimension().levelKey())) return;

        CarriageDims dims = data.dims();
        TrackGeometry g = TrackGeometry.from(dims, data.getTrainY());

        int budget = CHUNKS_PER_TICK;
        while (budget > 0) {
            Long key = PENDING.poll();
            if (key == null) break;
            int cx = ChunkPos.getX(key);
            int cz = ChunkPos.getZ(key);
            if (CLEANED.contains(key)) continue;
            if (!level.getChunkSource().hasChunk(cx, cz)) {
                // Chunk unloaded between enqueue and drain. Drop it; if it
                // reloads, ChunkEvent.Load re-enqueues.
                continue;
            }
            cleanCorridorChunk(level, cx, cz, dims, g);
            CLEANED.add(key);
            budget--;
        }
    }

    private static void cleanCorridorChunk(
        ServerLevel level, int cx, int cz, CarriageDims dims, TrackGeometry g
    ) {
        int chunkMinX = cx << 4;
        int chunkMaxX = chunkMinX + 15;
        int chunkMinZ = cz << 4;
        int chunkMaxZ = chunkMinZ + 15;
        int zLo = Math.max(g.trackZMin(), chunkMinZ);
        int zHi = Math.min(g.trackZMax(), chunkMaxZ);

        // Y range covers the bed + rail rows AND the carriage envelope, so
        // cross-chunk foliage spillover into template-authored air gaps in
        // the bed / rail rows also gets cleaned. The predicate is narrow
        // enough that the bed and rail blocks themselves stay intact.
        int trainY = g.bedY() + 2;
        int minY = Math.max(level.getMinBuildHeight(), g.bedY());
        int maxY = Math.min(level.getMaxBuildHeight() - 1, trainY + dims.height());
        if (maxY < minY) return;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = chunkMinX; x <= chunkMaxX; x++) {
            for (int z = zLo; z <= zHi; z++) {
                for (int y = minY; y <= maxY; y++) {
                    cursor.set(x, y, z);
                    BlockState state = level.getBlockState(cursor);
                    if (state.isAir()) continue;
                    if (!isFoliage(state)) continue;
                    level.setBlock(cursor, AIR, Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    /**
     * Narrow foliage predicate — leaves, vines, saplings, flowers. NOT
     * water / lava / fire / snow / bubble columns / any other replaceable
     * block. Catching fluids here would cascade neighbour fluid updates
     * and re-trigger chunk loads, the exact failure mode of the previous
     * synchronous attempt.
     */
    private static boolean isFoliage(BlockState state) {
        if (state.is(BlockTags.LEAVES)) return true;
        if (state.is(Blocks.VINE)) return true;
        if (state.is(BlockTags.SAPLINGS)) return true;
        if (state.is(BlockTags.SMALL_FLOWERS)) return true;
        if (state.is(BlockTags.TALL_FLOWERS)) return true;
        return false;
    }
}
