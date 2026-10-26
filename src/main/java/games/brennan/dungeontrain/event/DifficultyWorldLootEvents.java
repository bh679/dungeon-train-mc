package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.editor.RolledItemsFingerprint;
import games.brennan.dungeontrain.mixin.ChunkMapChunksInvoker;
import games.brennan.dungeontrain.player.DifficultyPartition;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.sable.SableManagedShip;
import games.brennan.dungeontrain.train.Trains;
import games.brennan.dungeontrain.world.DifficultyLootClear;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Takes the old difficulty's loot out of the world when the difficulty changes, so nothing dropped or
 * stored under one difficulty can be picked back up under another — the world-side half of
 * {@link DifficultyIsolationEvents}, which swaps what players carry. What is cleared, and what is left
 * (untouched train loot, pending loot tables, TVs), is {@link DifficultyLootClear}'s call.
 *
 * <p>Four reaches, because not everything is loaded when the swap happens:</p>
 * <ul>
 *   <li><b>Loaded now</b> — {@link #onSwap}: every loaded chunk of every level, every loaded carriage plot
 *       and every loaded entity, in one pass.</li>
 *   <li><b>Chunks loaded later</b> — each chunk carries the difficulty its contents belong to
 *       ({@link ModDataAttachments#DIFFICULTY_LOOT_KEY}); one that loads with another difficulty's key is
 *       cleared on the next tick (its block entities may still be pending at the load event) and
 *       re-stamped. A chunk with no key is only stamped: nothing is known about it.</li>
 *   <li><b>Entities loaded later</b> — tagged with their difficulty as they join
 *       ({@link DifficultyLootClear#ENTITY_KEY}); a mismatch is cleared as it joins.</li>
 *   <li><b>Carriages Sable was holding</b> — remembered by sub-level at the swap and cleared when they
 *       come back. In memory only: the train is rebuilt from scratch on every boot.</li>
 * </ul>
 *
 * <p>Portal rooms remembered on disk are scrubbed as they are laid back ({@code PortalRoomMemory}).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DifficultyWorldLootEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How often held carriages are checked for having come back. */
    private static final int HELD_CHECK_INTERVAL_TICKS = 20;

    /** Carriages held by Sable at a swap, cleared when they come back. Server thread only. */
    private static final Set<UUID> HELD_AT_SWAP = new HashSet<>();

    /** A chunk that loaded with another difficulty's key, cleared on the next tick. */
    private record StaleChunk(ServerLevel level, ChunkPos pos) {}

    /** Rolled containers the current sweep found untouched and left; reported in the swap's log line. */
    private static int keptRolled;

    private static final Queue<StaleChunk> STALE_CHUNKS = new ConcurrentLinkedQueue<>();

    private DifficultyWorldLootEvents() {}

    /** The swap itself. Called once from {@link DifficultyIsolationEvents}, on the server thread. */
    static void onSwap(MinecraftServer server, String fromKey, String toKey) {
        long startNanos = System.nanoTime();
        keptRolled = 0;
        int containers = 0;
        int entities = 0;
        Set<UUID> sweptShips = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (LevelChunk chunk : loadedChunks(level)) {
                containers += sweepChunk(level, chunk, toKey);
            }
            for (Trains.Carriage carriage : Trains.allCarriages(level)) {
                if (!carriage.ship().isResident()) continue;
                containers += sweepPlot(level, carriage.ship(), toKey);
                sweptShips.add(carriage.ship().subLevelId());
            }
            entities += sweepEntities(level, toKey);
        }
        for (UUID trainId : Trains.registeredTrainIds()) {
            for (ManagedShip ship : Trains.knownGroups(trainId).values()) {
                if (ship != null && !sweptShips.contains(ship.subLevelId())) HELD_AT_SWAP.add(ship.subLevelId());
            }
        }
        LOGGER.info("[DungeonTrain] difficulty loot clear {} → {}: emptied {} container(s) (kept {} untouched), {} entit(y/ies); "
                + "{} held carriage(s) wait for their return; took {} ms",
            fromKey, toKey, containers, keptRolled, entities, HELD_AT_SWAP.size(), (System.nanoTime() - startNanos) / 1_000_000);
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;
        if (!DungeonTrainConfig.getDifficultyIsolatedStash()) return;
        String current = DifficultyPartition.keyFor(level);
        String recorded = chunk.getData(ModDataAttachments.DIFFICULTY_LOOT_KEY.get());
        if (DifficultyLootClear.isStale(recorded, current)) {
            STALE_CHUNKS.add(new StaleChunk(level, chunk.getPos()));
        } else if (recorded.isEmpty()) {
            chunk.setData(ModDataAttachments.DIFFICULTY_LOOT_KEY.get(), current);
        }
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Entity entity = event.getEntity();
        if (!DifficultyLootClear.handles(entity)) return;
        if (!DungeonTrainConfig.getDifficultyIsolatedStash()) return;
        String current = DifficultyPartition.keyFor(level);
        String recorded = DifficultyLootClear.entityKey(entity);
        if (recorded.isEmpty()) {
            DifficultyLootClear.fingerprintSpawn(entity); // first sighting: what it holds now is "as found"
        }
        if (DifficultyLootClear.isStale(recorded, current)) {
            if (entity instanceof ItemEntity) {
                event.setCanceled(true); // discarding mid-join is not allowed; cancelling keeps it out
                return;
            }
            DifficultyLootClear.clearEntity(entity);
        }
        DifficultyLootClear.tagEntity(entity, current);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        StaleChunk stale;
        while ((stale = STALE_CHUNKS.poll()) != null) {
            LevelChunk chunk = stale.level().getChunkSource().getChunkNow(stale.pos().x, stale.pos().z);
            if (chunk == null) continue; // unloaded again: its key is unchanged, so its next load queues it
            sweepChunk(stale.level(), chunk, DifficultyPartition.keyFor(stale.level()));
        }
        if (HELD_AT_SWAP.isEmpty()) return;
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % HELD_CHECK_INTERVAL_TICKS != 0) return;
        for (ServerLevel level : server.getAllLevels()) {
            for (Trains.Carriage carriage : Trains.allCarriages(level)) {
                ManagedShip ship = carriage.ship();
                if (!ship.isResident() || !HELD_AT_SWAP.remove(ship.subLevelId())) continue;
                int emptied = sweepPlot(level, ship, DifficultyPartition.keyFor(level));
                if (emptied > 0) {
                    LOGGER.info("[DungeonTrain] difficulty loot clear: returning carriage {} had {} container(s) emptied",
                        ship.subLevelId(), emptied);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        HELD_AT_SWAP.clear();
        STALE_CHUNKS.clear();
    }

    // ---- fingerprinting what generation placed ----

    /**
     * A carriage group has just been generated: fingerprint every stocked container in its plot that
     * the loot roll didn't (template-baked contents, a shared build's contents), so an untouched one
     * survives the next swap. Called by {@code TrainAssembler.spawnGroup}.
     */
    public static void fingerprintCarriage(ServerLevel level, ManagedShip ship) {
        if (!(ship instanceof SableManagedShip sable) || sable.subLevel() == null) return;
        for (PlotChunkHolder holder : sable.subLevel().getPlot().getLoadedChunks()) {
            LevelChunk chunk = holder.getChunk();
            if (chunk == null) continue;
            for (BlockEntity be : chunk.getBlockEntities().values()) DifficultyLootClear.fingerprintPlaced(level, be);
        }
    }

    /**
     * As {@link #fingerprintCarriage}, for a portal room just stamped from its template at
     * {@code origin} spanning {@code size}. Never for a room laid back from memory: what a player left
     * in it must not be taken as generated.
     */
    public static void fingerprintRoom(ServerLevel level, BlockPos origin, Vec3i size) {
        BlockPos max = origin.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1);
        for (int cx = origin.getX() >> 4; cx <= max.getX() >> 4; cx++) {
            for (int cz = origin.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    BlockPos p = be.getBlockPos();
                    if (p.getX() < origin.getX() || p.getY() < origin.getY() || p.getZ() < origin.getZ()
                            || p.getX() > max.getX() || p.getY() > max.getY() || p.getZ() > max.getZ()) continue;
                    DifficultyLootClear.fingerprintPlaced(level, be);
                }
            }
        }
    }

    // ---- sweeps ----------------------------------------------------------------------------------

    private static List<LevelChunk> loadedChunks(ServerLevel level) {
        List<LevelChunk> out = new ArrayList<>();
        for (ChunkHolder holder : ((ChunkMapChunksInvoker) level.getChunkSource().chunkMap).dungeontrain$getChunks()) {
            LevelChunk chunk = holder.getFullChunkFuture().getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK).orElse(null);
            if (chunk != null) out.add(chunk);
        }
        return out;
    }

    /**
     * Clear a chunk's block containers and stamp it with {@code key}. Walks block-entity positions rather
     * than the live map so entities still pending from the chunk's load are created and cleared too.
     */
    private static int sweepChunk(ServerLevel level, LevelChunk chunk, String key) {
        int emptied = 0;
        for (BlockPos pos : new ArrayList<>(chunk.getBlockEntitiesPos())) {
            BlockEntity be = chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.IMMEDIATE);
            if (be == null) continue;
            if (!DifficultyLootClear.clearBlockEntity(level, be)) {
                if (be.getPersistentData().contains(RolledItemsFingerprint.NBT_KEY)) keptRolled++;
                continue;
            }
            emptied++;
            LOGGER.debug("[DungeonTrain] difficulty loot clear: emptied {} at {} in {}",
                be.getBlockState().getBlock(), pos, level.dimension().location());
        }
        chunk.setData(ModDataAttachments.DIFFICULTY_LOOT_KEY.get(), key);
        return emptied;
    }

    /** A carriage's blocks live in its Sable plot, which the level's own chunk map doesn't hold. */
    private static int sweepPlot(ServerLevel level, ManagedShip ship, String key) {
        if (!(ship instanceof SableManagedShip sable)) return 0;
        ServerSubLevel subLevel = sable.subLevel();
        if (subLevel == null) return 0;
        int emptied = 0;
        for (PlotChunkHolder holder : subLevel.getPlot().getLoadedChunks()) {
            LevelChunk chunk = holder.getChunk();
            if (chunk != null) emptied += sweepChunk(level, chunk, key);
        }
        return emptied;
    }

    private static int sweepEntities(ServerLevel level, String key) {
        List<Entity> handled = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (DifficultyLootClear.handles(entity)) handled.add(entity);
        }
        int cleared = 0;
        for (Entity entity : handled) {
            if (DifficultyLootClear.clearEntity(entity)) {
                cleared++;
                LOGGER.debug("[DungeonTrain] difficulty loot clear: emptied {} at {} in {}",
                    entity.getType(), entity.blockPosition(), level.dimension().location());
            }
            if (!entity.isRemoved()) DifficultyLootClear.tagEntity(entity, key);
        }
        return cleared;
    }
}
