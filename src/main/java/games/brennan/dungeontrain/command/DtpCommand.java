package games.brennan.dungeontrain.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.difficulty.DifficultyOffset;
import games.brennan.dungeontrain.difficulty.DifficultyProgression;
import games.brennan.dungeontrain.event.DtpPlacementService;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.TrainAssembler;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.util.OptionalInt;

/**
 * Registers {@code /dtp <x>} (OP-only, permission level 2): teleports the
 * player to world-X {@code x} and guarantees a train is there to land on.
 * {@code /dtp <band>} (e.g. {@code nether}, {@code end}, {@code spheres}) does the
 * same for the next occurrence of that band ahead of the player — see {@link BandLocator}.
 *
 * <p>Vanilla {@code /tp} (and a bare walk) can outrun the train —
 * {@link games.brennan.dungeontrain.train.TrainCarriageAppender} only
 * extends a train for players already within 128 blocks of it, so a distant
 * teleport stops the train ever catching up. {@code /dtp} instead:</p>
 * <ol>
 *   <li>Parks the player in a safe holding spot high above where the new
 *       train will assemble — clear of the assembly footprint, since
 *       {@link TrainAssembler} converts world blocks in that volume into
 *       ship blocks and drags along anything caught inside it (the open bug
 *       behind {@code /dungeontrain spawn}, issue #22).</li>
 *   <li>Spawns a fresh train seeded at {@code x} via
 *       {@link TrainAssembler#spawnTrain}, replacing whatever train
 *       currently exists — this mod runs a single persistent train.</li>
 *   <li>Queues the player in {@link DtpPlacementService}, which drops them
 *       onto the flatbed pad nearest {@code x} once the new seed group's
 *       physics settle (its {@code canonicalPos} isn't available the same
 *       tick it's assembled).</li>
 * </ol>
 */
public final class DtpCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Vertical clearance above {@code trainY} for the holding spot —
     * comfortably above {@link CarriageDims#MAX_HEIGHT} (24) so the player
     * can never be inside the new train's assembly footprint.
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

    /** Default blocks past a band's entry column for {@code /dtp <band>} (override with {@code /dtp <band> <distance>}) — just inside, not on the boundary. */
    private static final int BAND_ENTRY_INSET = 32;

    private DtpCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("dtp")
            .requires(s -> s.hasPermission(2))
            .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                .executes(ctx -> run(ctx.getSource(), DoubleArgumentType.getDouble(ctx, "x"))));
        for (TrainPhase phase : TrainPhase.values()) {
            root.then(bandLiteral(phase.token(), phase));
        }
        root.then(bandLiteral("ow", TrainPhase.OVERWORLD));
        root.then(bandLiteral("ud", TrainPhase.UPSIDE_DOWN));
        dispatcher.register(root);
    }

    /** {@code /dtp <band>}: literal children win over the {@code x} double argument, so numeric use is unaffected. */
    private static LiteralArgumentBuilder<CommandSourceStack> bandLiteral(String token, TrainPhase phase) {
        return Commands.literal(token)
            .executes(ctx -> runBand(ctx.getSource(), phase, BAND_ENTRY_INSET))
            .then(Commands.argument("distance", DoubleArgumentType.doubleArg())
                .executes(ctx -> runBand(ctx.getSource(), phase, DoubleArgumentType.getDouble(ctx, "distance"))));
    }

    /** Teleport {@code distance} blocks past the entry of the next {@code phase} band ahead of the player, via the normal {@link #run} path. */
    private static int runBand(CommandSourceStack source, TrainPhase phase, double distance) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.translatable("chat.dungeontrain.save.command_must_be_run"));
            return 0;
        }
        OptionalInt entry = BandLocator.nextBandStartX(source.getServer().overworld(), phase, player.getBlockX());
        if (entry.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.package.dtp_band_not_found", phase.displayName()));
            return 0;
        }
        return run(source, entry.getAsInt() + distance);
    }

    private static int run(CommandSourceStack source, double x) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.translatable("chat.dungeontrain.save.command_must_be_run"));
            return 0;
        }

        MinecraftServer server = source.getServer();
        DungeonTrainWorldData data = DungeonTrainWorldData.get(server.overworld());
        if (!data.startsWithTrain()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.package.world_doesn_t_use"));
            return 0;
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

        // Shift difficulty to match the destination — "as if you'd travelled this far,
        // everywhere" (same mechanism /dungeontrain difficulty <tier> uses). Set BEFORE
        // spawnTrain: positionTier reads this same offset to gate carriage template/content
        // variants as they generate, so the freshly-spawned train's content — not just mob
        // gear/loot/onboarding — already matches the destination's difficulty.
        int impliedTravelled = (int) Math.round(Math.abs(x) / BLOCKS_PER_CARRIAGE);
        int requestedTier = Math.min(DungeonTrainConfig.MAX_REQUESTED_DIFFICULTY_TIER,
            DifficultyProgression.tierForTravelled(impliedTravelled));
        int rawTravelled = DifficultyProgression.rawMaxTravelledCarriageIndex(trainLevel);
        int difficultyOffset = DifficultyProgression.travelledOffsetForRequestedTier(
            requestedTier, rawTravelled, DungeonTrainConfig.getCarriagesPerTier(), DungeonTrainConfig.getProgressionLevelDelay());
        DifficultyOffset.set(server, difficultyOffset);

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

        LOGGER.info("[DungeonTrain] /dtp {} by {} — spawning train at origin {} speed {} (configCount={}), difficulty tier {} (offset {})",
            x, player.getName().getString(), origin, speed, configCount, requestedTier, difficultyOffset);

        try {
            TrainAssembler.spawnTrain(trainLevel, origin, velocity, count, spawnerWorldPos, dims);
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] /dtp spawnTrain failed", t);
            player.setInvulnerable(false);
            source.sendFailure(Component.translatable("chat.dungeontrain.package.spawntrain_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }

        DtpPlacementService.enqueue(player, trainLevel, x);
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.package.teleporting_x_spawning_train", x, requestedTier), true);
        return 1;
    }
}
