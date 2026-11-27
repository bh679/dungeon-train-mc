package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.difficulty.BoardingProgressData;
import games.brennan.dungeontrain.difficulty.DifficultyOffset;
import games.brennan.dungeontrain.difficulty.DifficultyProgression;
import games.brennan.dungeontrain.player.PlayerRunState;
import games.brennan.dungeontrain.player.RunRestore;
import games.brennan.dungeontrain.registry.ModDataAttachments;
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
 *       whatever train currently exists — this mod runs a single persistent train. Its carriages
 *       number on from the depth the player is moved to (the {@code pIdxBase} seam a nap wake also
 *       uses), so echoes, carriage difficulty, loot, carriage ids and the HUD read that depth
 *       rather than the start of a run.</li>
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
     * implied travelled-carriage count ({@link #impliedCarriage}). Also the nap's X estimate. Diff-Car
     * ({@link DifficultyProgression#rawMaxTravelledCarriageIndex}) is a path-dependent counter of
     * actual carriage-boundary crossings during play — there's no exact formula linking it to
     * world-X — so this is a best-effort approximation calibrated from a live observation
     * (world-X 35676 &harr; Diff-Car 1041), not a physical law.
     */
    public static final double BLOCKS_PER_CARRIAGE = 35676.0 / 1041.0;

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
     * The carriage number a train at world-X {@code x} implies for a run that rode there: signed, so
     * a destination behind spawn numbers backwards, floored like the nap's estimate. Pure.
     */
    public static int impliedCarriage(double x) {
        return (int) Math.floor(x / BLOCKS_PER_CARRIAGE);
    }

    /**
     * The carriage number a restored run's train continues from: the run's own travelled count,
     * which is the sum of its carriage-number steps and so the number of the carriage it reached
     * from spawn. A run that never booked any travel (0) falls back to the X estimate. Pure.
     */
    public static int restoreBase(int travelled, double x) {
        return travelled != 0 ? travelled : impliedCarriage(x);
    }

    /**
     * {@code /dtp}: move {@code player} to {@code x} "as if they'd ridden there". The new train numbers
     * on from {@link #impliedCarriage}, the player's own travelled count becomes that number, and the
     * global difficulty offset is cleared, since the depth now lives in the carriage numbers and the
     * offset would count it twice ({@link DifficultyProgression#positionTier} adds it to
     * {@code |pIdx|}). The world's carriage counter is raised to match.
     */
    public static Result dtp(ServerPlayer player, double x) {
        return relocate(player, x, impliedCarriage(x), true);
    }

    /**
     * Run restore: move {@code player} to {@code x} with the train numbered on from {@code pIdxBase}
     * ({@link #restoreBase}), leaving difficulty and run state to the restore, which set them first.
     */
    public static Result restore(ServerPlayer player, double x, int pIdxBase) {
        return relocate(player, x, pIdxBase, false);
    }

    /**
     * Relocate {@code player} to world-X {@code x} on a fresh train in their current dimension, its
     * carriages numbered from {@code pIdxBase} (rounded down to a group by
     * {@link TrainAssembler#spawnTrain}).
     *
     * @param asTravelled {@code true} (the {@code /dtp} behaviour) makes {@code pIdxBase} the player's
     *        travelled count too; {@code false} leaves it to the player's own run state
     */
    private static Result relocate(ServerPlayer player, double x, int pIdxBase, boolean asTravelled) {
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

        // Before spawnTrain: positionTier reads the offset as the seed's carriages generate.
        int tier = asTravelled ? travelTo(server, player, trainLevel, pIdxBase) : -1;

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

        LOGGER.info("[DungeonTrain] Relocating {} to x={} — spawning train at origin {} speed {} (configCount={}) pIdxBase={}, difficulty tier {}",
            player.getName().getString(), x, origin, speed, configCount, pIdxBase, asTravelled ? tier : "unchanged");

        try {
            TrainAssembler.spawnTrain(trainLevel, origin, velocity, count, spawnerWorldPos, dims, pIdxBase);
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] Relocation spawnTrain failed", t);
            player.setInvulnerable(false);
            return Result.failed(Component.translatable("chat.dungeontrain.package.spawntrain_failed",
                t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
        }

        // The new train numbers its carriages from pIdxBase, so the boarding leader's last carriage
        // now points into a train that no longer exists: left in place, the first scan on the new
        // train books the gap between the two numberings as carriages travelled (it took a restored
        // run from 237 to 219). Dropping the leader makes the next scan re-anchor with no delta.
        BoardingProgressData.get(trainLevel).clearLeader();
        BoardingProgressEvents.sendPlayerHudPacket(player);   // the HUD shows the new depth at once

        DtpPlacementService.enqueue(player, trainLevel, x, holdY, holdZ);
        return new Result(true, tier, null);
    }

    /**
     * {@code /dtp}'s "as if you'd ridden this far": the player's own travelled count becomes
     * {@code carriage} (a plain {@code advanceTravelled}, so no cart stats or milestones are awarded
     * for a teleport), the world's carriage counter is raised to it, and the global difficulty offset
     * is cleared (a world that used {@code /dtp} before still carries one, which on top of the new
     * carriage numbers would count the depth twice). Returns the tier that depth reads as.
     */
    private static int travelTo(MinecraftServer server, ServerPlayer player, ServerLevel trainLevel, int carriage) {
        DifficultyOffset.clear(server);
        PlayerRunState run = player.getData(ModDataAttachments.PLAYER_RUN_STATE.get());
        run.advanceTravelled(travelDelta(run.travelledCarriageIndex(), carriage));
        RunRestore.raiseWorldProgress(trainLevel, carriage);
        return Math.min(DungeonTrainConfig.MAX_REQUESTED_DIFFICULTY_TIER, DifficultyProgression.tierForTravelled(carriage));
    }

    /** The signed step that takes a travelled count from {@code current} to {@code target}. Pure. */
    static int travelDelta(int current, int target) {
        return target - current;
    }
}
