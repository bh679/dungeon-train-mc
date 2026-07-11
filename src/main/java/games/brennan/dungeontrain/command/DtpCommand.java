package games.brennan.dungeontrain.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.event.DtpPlacementService;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.TrainAssembler;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
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

/**
 * Registers {@code /dtp <x>} (OP-only, permission level 2): teleports the
 * player to world-X {@code x} and guarantees a train is there to land on.
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

    private DtpCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("dtp")
            .requires(s -> s.hasPermission(2))
            .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                .executes(ctx -> run(ctx.getSource(), DoubleArgumentType.getDouble(ctx, "x")))));
    }

    private static int run(CommandSourceStack source, double x) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }

        MinecraftServer server = source.getServer();
        DungeonTrainWorldData data = DungeonTrainWorldData.get(server.overworld());
        if (!data.startsWithTrain()) {
            source.sendFailure(Component.literal(
                "This world doesn't use the auto-train system (startsWithTrain is off)."));
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

        BlockPos origin = new BlockPos((int) Math.floor(x), trainY, 0);
        Vector3d spawnerWorldPos = new Vector3d(x, trainY, 0);
        double speed = DungeonTrainConfig.getSpeed();
        Vector3d velocity = new Vector3d(speed, 0.0, 0.0);
        int count = DungeonTrainConfig.getNumCarriages();

        LOGGER.info("[DungeonTrain] /dtp {} by {} — spawning train at origin {} speed {}",
            x, player.getName().getString(), origin, speed);

        try {
            TrainAssembler.spawnTrain(trainLevel, origin, velocity, count, spawnerWorldPos, dims);
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] /dtp spawnTrain failed", t);
            player.setInvulnerable(false);
            source.sendFailure(Component.literal(
                "spawnTrain failed: " + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }

        DtpPlacementService.enqueue(player, trainLevel, x);
        source.sendSuccess(() -> Component.literal(
            "Teleporting to X=" + x + " — spawning a train there now; you'll land on the flatbed once it settles."
        ), true);
        return 1;
    }
}
