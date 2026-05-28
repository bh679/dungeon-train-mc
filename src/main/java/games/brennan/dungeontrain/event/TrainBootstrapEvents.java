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

        int trainY = data.getTrainY();
        BlockPos trainOrigin = new BlockPos(0, trainY, 0);
        CarriageDims dims = data.dims();
        Vector3dc spawnerPos = new Vector3d(trainOrigin.getX(), trainOrigin.getY(), trainOrigin.getZ());

        // Pre-load all carriage body, parts, and half-flatbed templates into
        // their in-memory caches BEFORE any spawn runs. Otherwise the first
        // spawn touching each variant pays a synchronous NBT-load-from-disk
        // on the server thread (observed: 5-2500 ms place= variance per
        // spawnGroup during the bootstrap eager-fill). One up-front pass
        // amortises the disk reads outside the per-spawn budget.
        BootstrapProgress.setPhase("Loading carriage templates...");
        CarriagePlacer.warmTemplateCaches(target, dims);

        int configCount = DungeonTrainConfig.getNumCarriages();
        // Bootstrap only places the seed group; the eager-fill pass below
        // extends to the server-configured window. When config = 0 (auto),
        // use a benign positive seed so the seed-anchor math in
        // TrainAssembler.spawnTrain doesn't degenerate.
        int seedCount = configCount > 0 ? configCount : DungeonTrainConfig.DEFAULT_CARRIAGES_AUTO_SEED;

        // Phase 14 — shared trainId across dims. The starting dim gets the
        // primary spawn + eager-fill; the OTHER vanilla dims (OW/Nether/End
        // minus the starting one) get a matching seed train sharing this
        // UUID so the player crossing a portal lands on the "same" logical
        // train in the target dim. Without this, cross-dim travel dumps
        // the player into a dim with no train.
        java.util.UUID trainId = java.util.UUID.randomUUID();

        if (findTrain(target) == null) {
            LOGGER.info("[DungeonTrain] Bootstrap auto-spawning seed for {} carriages at {} in {} dims={}x{}x{} (configCount={}, trainId={})",
                seedCount, trainOrigin, target.dimension().location(), dims.length(), dims.width(), dims.height(), configCount, trainId);
            BootstrapProgress.setPhase("Spawning seed train...");
            ManagedShip seedShip = null;
            try {
                seedShip = TrainAssembler.spawnTrain(target, trainOrigin, TRAIN_VELOCITY, seedCount, spawnerPos, dims, trainId);
            } catch (Throwable t) {
                LOGGER.error("[DungeonTrain] Bootstrap train auto-spawn failed in starting dim {}", target.dimension().location(), t);
            }

            BootstrapProgress.setPhase("Anchoring world spawn...");
            anchorWorldSpawnNearCorridor(target, dims, trainY);

            // Eager-fill the train to the server-configured window NOW (while
            // the world-load phase is still blocking the client's loading
            // screen), so the player's first rendered frame already shows the
            // assembled train. Previously this ran in PlayerJoinEvents.tryPlace
            // and produced a visible ~8 s "stuck at 100%" freeze post-login.
            // A failure here is non-fatal: the per-tick appender extends the
            // train at gameplay speed as a fallback.
            if (seedShip != null) {
                try {
                    TrainCarriageAppender.eagerFillForBootstrap(target, seedShip);
                } catch (Throwable t) {
                    LOGGER.error("[DungeonTrain] Bootstrap eager-fill failed — per-tick appender will extend gradually instead", t);
                }
            }
        } else {
            LOGGER.debug("[DungeonTrain] Train already present in starting dim {} — primary spawn skipped, but mirrors may still need creation",
                target.dimension().location());
            // Inherit the existing train's UUID so mirrors share it.
            ManagedShip existing = findTrain(target);
            if (existing != null && existing.getKinematicDriver() instanceof TrainTransformProvider tp) {
                trainId = tp.getTrainId();
            }
        }

        // Spawn matching seed trains in the OTHER two vanilla dims so that
        // crossing a portal lands the player on a "copy" of the same logical
        // train. v1 keeps the mirrors as seed-only groups; the per-tick
        // appender extends each dim's train independently as the player
        // visits that dim. (Phase 12 polish target: per-tick sync that
        // mirrors block-level changes across the three dims.)
        BootstrapProgress.setPhase("Spawning mirror trains...");
        for (net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> mirrorDim : MIRROR_DIMS) {
            if (mirrorDim.equals(target.dimension())) continue;
            ServerLevel mirrorLevel = event.getServer().getLevel(mirrorDim);
            if (mirrorLevel == null) continue;
            if (findTrain(mirrorLevel) != null) {
                LOGGER.debug("[DungeonTrain] Mirror dim {} already has a train — skipping", mirrorDim.location());
                continue;
            }
            try {
                ManagedShip mirrorShip = TrainAssembler.spawnTrain(
                    mirrorLevel, trainOrigin, TRAIN_VELOCITY, seedCount, spawnerPos, dims, trainId);
                LOGGER.info("[DungeonTrain] Spawned mirror seed train in {} (trainId={}, ship id={})",
                    mirrorDim.location(), trainId, mirrorShip.id());
            } catch (Throwable t) {
                LOGGER.error("[DungeonTrain] Mirror train spawn failed in {} — players crossing a portal here will fall through empty air until the per-tick appender catches up",
                    mirrorDim.location(), t);
            }
        }
    }

    /** Vanilla dims we mirror trains across (Phase 14). */
    private static final java.util.List<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>> MIRROR_DIMS =
        java.util.List.of(
            net.minecraft.world.level.Level.OVERWORLD,
            net.minecraft.world.level.Level.NETHER,
            net.minecraft.world.level.Level.END);

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
