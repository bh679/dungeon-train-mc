package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.bootstrap.BootstrapProgress;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.TrainAssembler;
import games.brennan.dungeontrain.train.TrainCarriageAppender;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.world.StartingDimension;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;

/**
 * Assembles the starter train at {@link ServerStartedEvent} — before any
 * player joins — so the world's first frame already contains the train
 * instead of the player seeing a brief "vanilla spawn → train materialises →
 * teleport" sequence.
 *
 * Runs at {@link EventPriority#LOW} so
 * {@link WorldLifecycleEvents#onServerStarted} (NORMAL priority, client-only)
 * commits any pending world-creation choices into
 * {@link DungeonTrainWorldData} first. Common-scope (not Dist-gated) so
 * dedicated servers also get the auto-spawn.
 *
 * Now extends the train to the server-configured carriage window via
 * {@link TrainCarriageAppender#eagerFillForBootstrap} immediately after the
 * seed is spawned, so the full train is in place before the player ever sees
 * a frame. Previously this eager fill ran post-login and produced a visible
 * ~8 s "stuck at 100%" freeze; running it here shifts that cost into the
 * world-load phase where the player perceives it as load time.
 *
 * The initial carriage window is centred on the train origin ({@code pIdx=0}).
 * Whichever side the player is later teleported to, the rolling-window
 * manager retargets on its first tick — one-shot adjustment is negligible
 * compared to the visual improvement of removing the spawn-time flash.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TrainBootstrapEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final Vector3dc TRAIN_VELOCITY = new Vector3d(2.0, 0.0, 0.0);

    /** Perpendicular Z offset of the world spawn from corridor centerline. */
    private static final int SHARED_SPAWN_PERP = 25;

    private TrainBootstrapEvents() {}

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onServerStarted(ServerStartedEvent event) {
        // SavedData lives on the overworld's data store regardless of which
        // dimension the train spawns in — a single source of truth for all
        // per-world DT choices.
        ServerLevel overworld = event.getServer().overworld();
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);

        if (!data.startsWithTrain()) {
            LOGGER.info("[DungeonTrain] startsWithTrain=false — skipping bootstrap auto-spawn");
            return;
        }

        // Show loading-screen text from this point on. Chunk prep is
        // already at 100% by the time ServerStartedEvent fires, so without
        // this the player stares at a finished progress bar with no
        // indication that DT is still working. The indicator is cleared
        // in the finally block below regardless of which step threw.
        BootstrapProgress.setPhase("Spawning Dungeon Train...");
        try {
            doBootstrap(event, overworld, data);
        } finally {
            BootstrapProgress.clear();
        }
    }

    private static void doBootstrap(
        ServerStartedEvent event, ServerLevel overworld, DungeonTrainWorldData data
    ) {

        StartingDimension startingDim = data.startingDimension();
        ServerLevel target = event.getServer().getLevel(startingDim.levelKey());
        if (target == null) {
            // Defensive: e.g. a datapack disabled the chosen dimension. Fall
            // back to overworld so the player still gets a train somewhere.
            LOGGER.warn("[DungeonTrain] Configured starting dimension {} not loaded — falling back to overworld", startingDim);
            target = overworld;
        }

        if (findTrain(target) != null) {
            LOGGER.debug("[DungeonTrain] Train already present in {} — bootstrap skipped", target.dimension().location());
            return;
        }

        int trainY = data.getTrainY();
        CarriageDims dims = data.dims();

        BootstrapProgress.setPhase("Spawning seed train...");
        ManagedShip seedShip = ensureTrainSpawned(target, data);

        BootstrapProgress.setPhase("Anchoring world spawn...");
        anchorWorldSpawnNearCorridor(target, dims, trainY);

        // Eager-fill the train to the server-configured window NOW (while
        // the world-load phase is still blocking the client's loading
        // screen), so the player's first rendered frame already shows the
        // assembled train. Previously this ran in PlayerJoinEvents.tryPlace
        // and produced a visible ~8 s "stuck at 100%" freeze post-login.
        // A failure here is non-fatal: the per-tick appender extends the
        // train at gameplay speed as a fallback. The eager-fill itself
        // updates {@link BootstrapProgress} to the counted "Assembling
        // train" phase, overwriting the indeterminate text set above.
        if (seedShip != null) {
            try {
                TrainCarriageAppender.eagerFillForBootstrap(target, seedShip);
            } catch (Throwable t) {
                LOGGER.error("[DungeonTrain] Bootstrap eager-fill failed — per-tick appender will extend gradually instead", t);
            }
        }
    }

    /**
     * Set the level's default spawn point to the precise placement that
     * {@code PlayerJoinEvents} would otherwise compute via its retry queue
     * at first login. By making the level's default spawn = the eventual
     * teleport target, the post-login {@code teleportTo} becomes a no-op
     * for position (only rotation updates), and the player sees no visual
     * position jump.
     *
     * <p>Falls back to a coarse near-corridor spawn if placement caching
     * fails — the retry teleport will still cover that case, just with a
     * visible jump.</p>
     */
    private static void anchorWorldSpawnNearCorridor(
        ServerLevel target, CarriageDims dims, int trainY
    ) {
        PlayerJoinEvents.SpawnPlacement placement =
            PlayerJoinEvents.computeAndCacheBootstrapPlacement(target, dims, trainY);
        if (placement != null) {
            target.setDefaultSpawnPos(placement.blockPos(), placement.yaw());
            LOGGER.info("[DungeonTrain] World spawn anchored at {} yaw={} (cached bootstrap placement)",
                placement.blockPos(), placement.yaw());
            return;
        }

        // Fallback (cache compute returned null): coarse near-corridor spawn.
        TrackGeometry g = TrackGeometry.from(dims, trainY);
        int spawnX = 0;
        int spawnZ = g.trackCenterZ() + SHARED_SPAWN_PERP;
        target.getChunk(spawnX >> 4, spawnZ >> 4, ChunkStatus.FULL, true);
        int surfaceY = target.getHeight(Heightmap.Types.MOTION_BLOCKING, spawnX, spawnZ);
        BlockPos spawnPos = new BlockPos(spawnX, surfaceY, spawnZ);
        target.setDefaultSpawnPos(spawnPos, 180.0F);
        LOGGER.info("[DungeonTrain] World spawn anchored at {} yaw=180 (coarse fallback, corridor +{} Z)",
            spawnPos, SHARED_SPAWN_PERP);
    }

    private static ManagedShip findTrain(ServerLevel level) {
        for (ManagedShip ship : Shipyards.of(level).findAll()) {
            if (ship.getKinematicDriver() instanceof TrainTransformProvider) {
                return ship;
            }
        }
        return null;
    }

    /**
     * Idempotently ensure a Dungeon Train seed exists in {@code target}.
     * Returns the existing train if {@link #findTrain} already finds one;
     * otherwise warms the carriage template cache for this dimension and
     * calls {@link TrainAssembler#spawnTrain} to lay down a fresh seed at
     * the standard origin.
     *
     * <p>Used by:</p>
     * <ul>
     *   <li>{@link #doBootstrap} at world-start — followed by
     *       {@link #anchorWorldSpawnNearCorridor} and
     *       {@link TrainCarriageAppender#eagerFillForBootstrap} to fully
     *       prepare the world's primary dimension before the player joins.</li>
     *   <li>{@code RespawnDimensionEvents} when a respawn rolls a different
     *       dimension than the world's starting one and that dimension
     *       hasn't had a train spawned in it yet. Eager-fill is skipped on
     *       that path — the per-tick appender extends the train at gameplay
     *       speed instead.</li>
     * </ul>
     *
     * <p>Returns {@code null} only if {@link TrainAssembler#spawnTrain}
     * itself throws (logged at ERROR); the bootstrap path tolerates this
     * and proceeds with degraded UI, and the respawn path skips its
     * subsequent teleport.</p>
     */
    public static ManagedShip ensureTrainSpawned(ServerLevel target, DungeonTrainWorldData data) {
        ManagedShip existing = findTrain(target);
        if (existing != null) return existing;

        // Open this dimension's world-load motion-grace window BEFORE any
        // carriage is spawned, so the seed group AND the bootstrap eager-fill
        // (and, on the respawn-into-a-new-dimension path, the per-tick
        // appender's first groups) all hold at their spawn position until the
        // joining client has registered every sub-level. Suppresses the
        // one-time Sable "non-existent sub-level" snapshot-vs-full-sync burst
        // at world-load. See TrainTransformProvider#beginLoadGrace.
        TrainTransformProvider.beginLoadGrace(target.dimension(), target.getGameTime());

        int trainY = data.getTrainY();
        BlockPos trainOrigin = new BlockPos(0, trainY, 0);
        CarriageDims dims = data.dims();
        Vector3dc spawnerPos = new Vector3d(trainOrigin.getX(), trainOrigin.getY(), trainOrigin.getZ());

        // Pre-load all carriage body, parts, and half-flatbed templates into
        // their in-memory caches BEFORE any spawn runs. Otherwise the first
        // spawn touching each variant pays a synchronous NBT-load-from-disk
        // on the server thread. One up-front pass amortises the disk reads
        // outside the per-spawn budget. Idempotent — ConcurrentHashMap keyed
        // on dims, so re-calling for additional dimensions is a no-op once
        // the templates are loaded.
        CarriagePlacer.warmTemplateCaches(target, dims);

        int configCount = DungeonTrainConfig.getNumCarriages();
        // Seed-only spawn; eager-fill / per-tick appender extends from here.
        // When config = 0 (auto), use a benign positive seed so the
        // seed-anchor math in TrainAssembler.spawnTrain doesn't degenerate.
        int seedCount = configCount > 0 ? configCount : DungeonTrainConfig.DEFAULT_CARRIAGES_AUTO_SEED;
        LOGGER.info("[DungeonTrain] Spawning seed of {} carriages at {} in {} dims={}x{}x{} (configCount={})",
            seedCount, trainOrigin, target.dimension().location(), dims.length(), dims.width(), dims.height(), configCount);
        try {
            return TrainAssembler.spawnTrain(target, trainOrigin, TRAIN_VELOCITY, seedCount, spawnerPos, dims);
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] Train spawn failed in {}", target.dimension().location(), t);
            return null;
        }
    }
}
