package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.difficulty.BoardingProgressData;
import games.brennan.dungeontrain.difficulty.DifficultyOffset;
import games.brennan.dungeontrain.difficulty.DifficultyProgression;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.TrainAssembler;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.joml.Vector3d;
import org.slf4j.Logger;

/**
 * Moves a player to world-X {@code x} and guarantees a train is there to land on — the body of
 * {@code /dtp}, shared with the admin inventory restore's {@code run} block
 * ({@link games.brennan.dungeontrain.player.InventoryRestore}), which relocates without a command
 * and so without the OP that would make the run Free Play.
 *
 * <p>Vanilla {@code /tp} (and a bare walk) can outrun the train —
 * {@link games.brennan.dungeontrain.train.TrainCarriageAppender} only extends a train for players
 * already within 128 blocks of it, so a distant teleport stops the train ever catching up. This
 * instead:</p>
 * <ol>
 *   <li>Parks the player in a safe holding spot high above where the new train will assemble —
 *       clear of the assembly footprint, since {@link TrainAssembler} converts world blocks in that
 *       volume into ship blocks and drags along anything caught inside it (issue #22).</li>
 *   <li>Spawns a fresh train seeded at {@code x} via {@link TrainAssembler#spawnTrain}, replacing
 *       whatever train currently exists — this mod runs a single persistent train.</li>
 *   <li>Queues the player in {@link DtpPlacementService}, which drops them onto the flatbed pad
 *       nearest {@code x} once the new seed group's physics settle.</li>
 * </ol>
 */
public final class TrainRelocation {

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

    private TrainRelocation() {}

    /**
     * Outcome of {@link #relocate}. On success {@code tier} is the difficulty tier the offset was
     * set to, or {@code -1} when the caller asked to leave difficulty alone; on failure
     * {@code failure} says why, and nothing was moved or spawned.
     */
    public record Result(boolean ok, int tier, Component failure) {
        static Result failed(Component why) {
            return new Result(false, -1, why);
        }
    }

    /**
     * Relocate {@code player} to world-X {@code x} on a fresh train in their current dimension.
     *
     * @param setDifficultyOffset {@code true} (the {@code /dtp} behaviour) shifts difficulty "as if
     *        you'd travelled this far, everywhere"; {@code false} leaves it to the player's own run
     *        state, which the restore sets before calling this
     */
    public static Result relocate(ServerPlayer player, double x, boolean setDifficultyOffset) {
        // A dead client never reports the teleport, so the player's chunk tickets stay behind, Sable culls
        // the new seed at once and the wait times out, with the old train already deleted.
        if (player.isDeadOrDying()) {
            return Result.failed(Component.literal(player.getName().getString() + " is dead: respawn before using /dtp."));
        }

        MinecraftServer server = player.getServer();
        if (server == null) {
            return Result.failed(Component.translatable("chat.dungeontrain.package.world_doesn_t_use"));
        }
        DungeonTrainWorldData data = DungeonTrainWorldData.get(server.overworld());
        if (!data.startsWithTrain()) {
            return Result.failed(Component.translatable("chat.dungeontrain.package.world_doesn_t_use"));
        }

        // Operate in the player's CURRENT dimension, not the world's nominal starting
        // dimension — TrainCarriageAppender.onLevelTick runs independently per loaded
        // level, so each dimension maintains its own train once one exists there
        // (see TrainBootstrapEvents.ensureTrainSpawned's per-target-level design, used
        // by RespawnDimensionEvents for cross-dimension respawns). Forcing the player
        // back to the "starting" dimension would be a surprising side effect if they
        // ran /dtp while already riding a Nether- or End-side train.
        ServerLevel trainLevel = player.serverLevel();

        CarriageDims dims = data.dims();
        int trainY = data.getTrainY();
        TrackGeometry g = TrackGeometry.from(dims, trainY);

        // Hold the player clear of the assembly footprint (same X/Z chunk column
        // as the eventual flatbed so the client isn't loading two different
        // regions) while the new train assembles underneath.
        int holdY = Math.min(trainLevel.getMaxBuildHeight() - CEILING_MARGIN, trainY + HOLD_Y_MARGIN);
        double holdZ = g.trackCenterZ() + 0.5;
        player.setInvulnerable(true);
        player.teleportTo(trainLevel, x, holdY, holdZ, player.getYRot(), player.getXRot());

        int tier = setDifficultyOffset ? shiftDifficulty(server, trainLevel, x) : -1;

        BlockPos origin = new BlockPos((int) Math.floor(x), trainY, 0);
        Vector3d spawnerWorldPos = new Vector3d(x, trainY, 0);
        double speed = DungeonTrainConfig.getSpeed();
        Vector3d velocity = new Vector3d(speed, 0.0, 0.0);

        int configCount = DungeonTrainConfig.getNumCarriages();
        // Seed-only spawn; the per-tick appender extends from here. When config = 0
        // (auto), use a benign positive seed so the seed-anchor math in
        // TrainAssembler.spawnTrain doesn't degenerate — same guard as
        // TrainBootstrapEvents.ensureTrainSpawned.
        int count = configCount > 0 ? configCount : DungeonTrainConfig.DEFAULT_CARRIAGES_AUTO_SEED;

        LOGGER.info("[DungeonTrain] Relocating {} to x={} — spawning train at origin {} speed {} (configCount={}), difficulty tier {}",
            player.getName().getString(), x, origin, speed, configCount, setDifficultyOffset ? tier : "unchanged");

        try {
            TrainAssembler.spawnTrain(trainLevel, origin, velocity, count, spawnerWorldPos, dims);
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] Relocation spawnTrain failed", t);
            player.setInvulnerable(false);
            return Result.failed(Component.translatable("chat.dungeontrain.package.spawntrain_failed",
                t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
        }

        // The new train numbers its carriages afresh, so the boarding leader's last carriage now
        // points into a train that no longer exists: left in place, the first scan on the new train
        // books the gap between the two numberings as carriages travelled (it took a restored run
        // from 237 to 219). Dropping the leader makes the next scan re-anchor with no delta.
        BoardingProgressData.get(trainLevel).clearLeader();

        DtpPlacementService.enqueue(player, trainLevel, x, holdY, holdZ);
        return new Result(true, tier, null);
    }

    /**
     * Shift difficulty to match the destination — "as if you'd travelled this far, everywhere"
     * (same mechanism {@code /dungeontrain difficulty <tier>} uses). Runs BEFORE spawnTrain:
     * positionTier reads this same offset to gate carriage template/content variants as they
     * generate, so the freshly-spawned train's content already matches the destination's difficulty.
     */
    private static int shiftDifficulty(MinecraftServer server, ServerLevel trainLevel, double x) {
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
