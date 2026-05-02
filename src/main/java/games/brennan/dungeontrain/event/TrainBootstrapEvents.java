package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.TrainAssembler;
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
        BlockPos trainOrigin = new BlockPos(0, trainY, 0);
        CarriageDims dims = data.dims();
        Vector3dc spawnerPos = new Vector3d(trainOrigin.getX(), trainOrigin.getY(), trainOrigin.getZ());

        int carriageCount = DungeonTrainConfig.getNumCarriages();
        if (carriageCount <= 0) {
            LOGGER.info("[DungeonTrain] Bootstrap auto-spawn skipped (numCarriages={} — train disabled via config)",
                carriageCount);
            anchorWorldSpawnNearCorridor(target, dims, trainY);
            return;
        }
        LOGGER.info("[DungeonTrain] Bootstrap auto-spawning {} carriages at {} in {} dims={}x{}x{}",
            carriageCount, trainOrigin, target.dimension().location(), dims.length(), dims.width(), dims.height());
        try {
            TrainAssembler.spawnTrain(target, trainOrigin, TRAIN_VELOCITY, carriageCount, spawnerPos, dims);
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] Bootstrap train auto-spawn failed", t);
        }

        anchorWorldSpawnNearCorridor(target, dims, trainY);
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
}
