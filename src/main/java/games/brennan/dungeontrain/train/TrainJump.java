package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.difficulty.DifficultyOffset;
import games.brennan.dungeontrain.difficulty.DifficultyProgression;
import games.brennan.dungeontrain.event.DtpPlacementService;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.joml.Vector3d;
import org.slf4j.Logger;

/**
 * Moves a player to world-X {@code x} and guarantees a train is there to land on — the core of
 * {@code /dtp <x>}, shared with the Nether-portal band jump
 * ({@link games.brennan.dungeontrain.event.NetherPortalBandJump}).
 *
 * <p>Vanilla {@code /tp} (and a bare walk) can outrun the train —
 * {@link TrainCarriageAppender} only extends a train for players already within 128 blocks of
 * it, so a distant teleport stops the train ever catching up. This instead:</p>
 * <ol>
 *   <li>Parks the player in a safe holding spot high above where the new train will assemble —
 *       clear of the assembly footprint, since {@link TrainAssembler} converts world blocks in
 *       that volume into ship blocks and drags along anything caught inside it (issue #22).</li>
 *   <li>Shifts the difficulty offset to match the destination — "as if you'd travelled this far,
 *       everywhere" — before the spawn, so the new train's content already matches.</li>
 *   <li>Spawns a fresh train seeded at {@code x} via {@link TrainAssembler#spawnTrain}, replacing
 *       whatever train currently exists — this mod runs a single persistent train.</li>
 *   <li>Queues the player in {@link DtpPlacementService}, which drops them onto the flatbed pad
 *       nearest {@code x} once the new seed group's physics settle.</li>
 * </ol>
 *
 * <p>Operates in the player's <b>current</b> dimension, not the world's nominal starting
 * dimension — each loaded level maintains its own train once one exists there.</p>
 */
public final class TrainJump {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Vertical clearance above {@code trainY} for the holding spot — comfortably above
     * {@link CarriageDims#MAX_HEIGHT} (24) so the player can never be inside the new train's
     * assembly footprint.
     */
    private static final int HOLD_Y_MARGIN = 48;

    /** Safety margin below the train level's build-height ceiling for the holding spot. */
    private static final int CEILING_MARGIN = 5;

    /**
     * Calibrated blocks-per-carriage estimate for converting a destination world-X into an
     * implied travelled-carriage count for difficulty purposes. Diff-Car
     * ({@link DifficultyProgression#rawMaxTravelledCarriageIndex}) is a path-dependent counter of
     * actual carriage-boundary crossings during play — there's no exact formula linking it to
     * world-X — so this is a best-effort approximation calibrated from a live observation
     * (world-X 35676 &harr; Diff-Car 1041), not a physical law.
     */
    private static final double BLOCKS_PER_CARRIAGE = 35676.0 / 1041.0;

    /** Outcome of {@link #jumpTo}. */
    public sealed interface Result permits Ok, NoTrainWorld, SpawnFailed {}

    /** The train is spawning at the target and the player is queued to land; {@code difficultyTier} is what the destination implies. */
    public record Ok(int difficultyTier) implements Result {}

    /** This world does not use the auto-train system; nothing was done. */
    public record NoTrainWorld() implements Result {}

    /** {@link TrainAssembler#spawnTrain} threw; the player has been released from the hold where they are. */
    public record SpawnFailed(Throwable cause) implements Result {}

    private TrainJump() {}

    /**
     * Hold {@code player} above {@code x}, spawn a fresh train there and queue the landing.
     * {@code why} only labels the log lines ({@code "/dtp"}, {@code "nether portal"}).
     */
    public static Result jumpTo(ServerPlayer player, double x, String why) {
        MinecraftServer server = player.getServer();
        if (server == null) return new NoTrainWorld();
        DungeonTrainWorldData data = DungeonTrainWorldData.get(server.overworld());
        if (!data.startsWithTrain()) return new NoTrainWorld();

        ServerLevel trainLevel = player.serverLevel();
        CarriageDims dims = data.dims();
        int trainY = data.getTrainY();
        TrackGeometry g = TrackGeometry.from(dims, trainY);

        // Hold the player clear of the assembly footprint (same X/Z chunk column as the eventual
        // flatbed so the client isn't loading two different regions) while the new train
        // assembles underneath.
        int holdY = Math.min(trainLevel.getMaxBuildHeight() - CEILING_MARGIN, trainY + HOLD_Y_MARGIN);
        double holdZ = g.trackCenterZ() + 0.5;
        player.setInvulnerable(true);
        player.teleportTo(trainLevel, x, holdY, holdZ, player.getYRot(), player.getXRot());

        // Set BEFORE spawnTrain: positionTier reads this same offset to gate carriage
        // template/content variants as they generate.
        int requestedTier = applyDestinationDifficulty(server, trainLevel, x);

        BlockPos origin = new BlockPos((int) Math.floor(x), trainY, 0);
        Vector3d spawnerWorldPos = new Vector3d(x, trainY, 0);
        double speed = DungeonTrainConfig.getSpeed();
        Vector3d velocity = new Vector3d(speed, 0.0, 0.0);

        int configCount = DungeonTrainConfig.getNumCarriages();
        // Seed-only spawn; the per-tick appender extends from here. When config = 0 (auto), use a
        // benign positive seed so the seed-anchor math in TrainAssembler.spawnTrain doesn't
        // degenerate — same guard as TrainBootstrapEvents.ensureTrainSpawned.
        int count = configCount > 0 ? configCount : DungeonTrainConfig.DEFAULT_CARRIAGES_AUTO_SEED;

        LOGGER.info("[DungeonTrain] {} → {} by {} — spawning train at origin {} speed {} (configCount={}), difficulty tier {}",
            why, x, player.getName().getString(), origin, speed, configCount, requestedTier);

        try {
            TrainAssembler.spawnTrain(trainLevel, origin, velocity, count, spawnerWorldPos, dims);
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] {} spawnTrain failed", why, t);
            player.setInvulnerable(false);
            return new SpawnFailed(t);
        }

        DtpPlacementService.enqueue(player, trainLevel, x);
        return new Ok(requestedTier);
    }

    /**
     * Shift difficulty to match the destination (same mechanism {@code /dungeontrain difficulty
     * <tier>} uses) and return the tier it was clamped to.
     */
    private static int applyDestinationDifficulty(MinecraftServer server, ServerLevel trainLevel, double x) {
        int impliedTravelled = (int) Math.round(Math.abs(x) / BLOCKS_PER_CARRIAGE);
        int requestedTier = Math.min(DungeonTrainConfig.MAX_REQUESTED_DIFFICULTY_TIER,
            DifficultyProgression.tierForTravelled(impliedTravelled));
        int rawTravelled = DifficultyProgression.rawMaxTravelledCarriageIndex(trainLevel);
        int difficultyOffset = DifficultyProgression.travelledOffsetForRequestedTier(
            requestedTier, rawTravelled, DungeonTrainConfig.getCarriagesPerTier(), DungeonTrainConfig.getProgressionLevelDelay());
        DifficultyOffset.set(server, difficultyOffset);
        return requestedTier;
    }
}
