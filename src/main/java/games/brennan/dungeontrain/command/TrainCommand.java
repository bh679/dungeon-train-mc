package games.brennan.dungeontrain.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.difficulty.DifficultyOffset;
import games.brennan.dungeontrain.difficulty.DifficultyProgression;
import games.brennan.dungeontrain.event.CinematicIntroService;
import games.brennan.dungeontrain.event.DtpPlacementService;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.TrainAssembler;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import games.brennan.dungeontrain.event.TrainTickEvents;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import games.brennan.dungeontrain.ship.ManagedShip;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Registers OP-only (permission level 2) commands:
 *   - {@code /dungeontrain spawn} — <b>the delete-and-reset command</b>.
 *     Deletes the world's current train outright, then spawns a fresh one at the
 *     configured speed. Takes no arguments: there is only ever one train, and its
 *     carriage window is config state changed with {@code carriages <count>}, not a
 *     per-spawn choice (a literal count also let the config's 0 = "auto" sentinel
 *     through to a spawn that wipes the train and then refuses to build one). The new
 *     train is always seeded <b>on the track</b> — at the corridor's Y and Z, at the
 *     player's X — never at the player's own position, because the live
 *     {@code TrackGeometry} is derived from that origin. The player is lifted clear
 *     of the assembly footprint and set down on the flatbed. Because it starts a new run it
 *     also clears the difficulty travelled-offset (back to automatic scaling) and
 *     the world's already-placed shared-carriage exclude-list, so community builds
 *     the old train used can appear again — see {@link #resetRunState}. The
 *     deletion itself lives in {@code TrainAssembler.spawnTrain}, which hands every
 *     shared carriage back to the relay before destroying its plot.
 *   - {@code /dungeontrain speed <value>} — sets train speed in blocks/second,
 *     persists to config, and updates any active train live.
 *   - {@code /dungeontrain carriages <count>} — sets the default carriage
 *     count, persists to config, and resizes any active train live (rolling
 *     window grows or shrinks on the next tick).
 *   - {@code /dungeontrain difficulty [<tier>|auto]} — bare form reports the
 *     current effective tier; {@code <tier>} re-anchors a persistent
 *     travelled-carriage offset so the game treats every player as if they'd
 *     travelled far enough to be exactly that tier right now — shifting the HUD,
 *     onboarding stages, mob gear, villager trades, carriage achievements, and
 *     Discord together (drifting with real travel afterward, until re-anchored);
 *     {@code auto} clears the offset, resuming fully automatic scaling.
 */
public final class TrainCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Vertical clearance above {@code trainY} for the pre-spawn holding spot — comfortably above
     * {@link CarriageDims#MAX_HEIGHT} (24) so the player can never be inside the assembly footprint.
     * Mirrors {@code DtpCommand.HOLD_Y_MARGIN}; kept private to each command rather than shared,
     * since two ints don't justify a constants class.
     */
    private static final int HOLD_Y_MARGIN = 48;

    /** Safety margin below the train level's build-height ceiling for the holding spot. See {@code DtpCommand}. */
    private static final int CEILING_MARGIN = 5;

    private TrainCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("dungeontrain")
            .requires(s -> s.hasPermission(2))
            // No count argument: there is only ever one train, and its carriage window is
            // config state set with 'carriages <count>', not a per-spawn choice.
            .then(Commands.literal("spawn")
                .executes(ctx -> runSpawn(ctx.getSource())))
            .then(Commands.literal("speed")
                .then(Commands.argument("value",
                        DoubleArgumentType.doubleArg(DungeonTrainConfig.MIN_SPEED, DungeonTrainConfig.MAX_SPEED))
                    .executes(ctx -> runSpeed(ctx.getSource(), DoubleArgumentType.getDouble(ctx, "value")))))
            .then(Commands.literal("carriages")
                .then(Commands.argument("count",
                        IntegerArgumentType.integer(DungeonTrainConfig.MIN_CARRIAGES, DungeonTrainConfig.MAX_CARRIAGES))
                    .executes(ctx -> runCarriages(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "count")))))
            .then(Commands.literal("tracks")
                .then(Commands.literal("on").executes(ctx -> runTracks(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> runTracks(ctx.getSource(), false))))
            .then(Commands.literal("breakblocks")
                .executes(ctx -> runBreakBlocksStatus(ctx.getSource()))
                .then(Commands.literal("on").executes(ctx -> runBreakBlocks(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> runBreakBlocks(ctx.getSource(), false))))
            .then(Commands.literal("difficulty")
                .executes(ctx -> runDifficultyStatus(ctx.getSource()))
                .then(Commands.argument("tier",
                        IntegerArgumentType.integer(0, DungeonTrainConfig.MAX_REQUESTED_DIFFICULTY_TIER))
                    .executes(ctx -> runDifficulty(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "tier"))))
                .then(Commands.literal("auto")
                    .executes(ctx -> runDifficultyAuto(ctx.getSource()))))
            .then(Commands.literal("cinematic")
                .executes(ctx -> runCinematic(ctx.getSource(), CinematicIntroService.StartMode.SPAWN))
                .then(Commands.literal("spawn")
                    .executes(ctx -> runCinematic(ctx.getSource(), CinematicIntroService.StartMode.SPAWN)))
                .then(Commands.literal("current")
                    .executes(ctx -> runCinematic(ctx.getSource(), CinematicIntroService.StartMode.CURRENT))))
            .then(ReportCarriageCommand.build())
            .then(EditorCommand.build(buildContext))
            .then(SaveCommand.build())
            .then(ResetCommand.build())
            .then(DebugCommand.build())
            .then(PackageCommand.build())
            .then(NarrativeCommand.build())
            .then(CinematographerCommand.build())
            .then(PortalCommand.build());

        LiteralCommandNode<CommandSourceStack> registered = dispatcher.register(root);

        // `/dt` is a short alias for `/dungeontrain`. Brigadier's redirect
        // forwards every subcommand under the full name so tab-completion and
        // execution both work against the same tree — no duplicated wiring.
        dispatcher.register(Commands.literal("dt")
            .requires(s -> s.hasPermission(2))
            .redirect(registered));
    }

    private static int runSpawn(CommandSourceStack source) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }

        // Operate in the player's CURRENT dimension, not the world's nominal starting
        // dimension — TrainCarriageAppender.onLevelTick runs independently per loaded
        // level, so each dimension maintains its own train once one exists there.
        // Same reasoning as /dtp.
        ServerLevel level = player.serverLevel();

        DungeonTrainWorldData data = DungeonTrainWorldData.get(source.getServer().overworld());
        CarriageDims dims = data.dims();
        int trainY = data.getTrainY();

        // The train ALWAYS goes on the track. Only X comes from the player — the
        // corridor runs along +X, so the player's Y and Z are meaningless here and
        // actively harmful: TrainAssembler.spawnCore rebuilds the train's live
        // TrackGeometry out of origin.y/origin.z, so a player-derived origin would
        // permanently desynchronise this train's corridor from the worldgen one
        // (bed at trainY-2, rails at trainY-1, Z spanning 0..width-1) and the
        // appender would lay track at the wrong height and offset for its whole life.
        //
        // origin.z = 0 is the corridor's trackZMin, NOT its centreline — the
        // centreline (trackCenterZ) is where PLAYERS stand, half a carriage away.
        // Mixing the two puts the train beside the rails. Cf. /dtp, which likewise
        // uses Z=0 for the origin and trackCenterZ()+0.5 for the player.
        double spawnX = player.getX();
        BlockPos origin = new BlockPos((int) Math.floor(spawnX), trainY, 0);
        Vector3d spawnerWorldPos = new Vector3d(spawnX, trainY, 0); // only .x is read, to pick the seed pIdx

        double speed = DungeonTrainConfig.getSpeed();
        Vector3d velocity = new Vector3d(speed, 0.0, 0.0);

        // Seed-only spawn; the per-tick appender extends from here. Config 0 is the "auto"
        // sentinel, NOT a literal count — spawnCore rejects <= 0, and it does so AFTER
        // deleteExistingTrains has run, so passing the sentinel through would delete the
        // train and spawn nothing. Same guard as TrainBootstrapEvents.ensureTrainSpawned
        // and /dtp.
        int configCount = DungeonTrainConfig.getNumCarriages();
        int count = configCount > 0 ? configCount : DungeonTrainConfig.DEFAULT_CARRIAGES_AUTO_SEED;

        // Lift the player clear before anything assembles: spawnGroup converts every
        // world block in the footprint into ship blocks and drags along whatever is
        // caught inside — the long-standing /dungeontrain spawn bug (issue #22).
        // Hold them in the same X/Z column as the eventual flatbed so the client
        // isn't loading two separate regions.
        int holdY = Math.min(level.getMaxBuildHeight() - CEILING_MARGIN, trainY + HOLD_Y_MARGIN);
        double holdZ = TrackGeometry.from(dims, trainY).trackCenterZ() + 0.5;
        player.setInvulnerable(true);
        player.teleportTo(level, spawnX, holdY, holdZ, player.getYRot(), player.getXRot());

        LOGGER.info("[DungeonTrain] /dungeontrain spawn by {} at on-track origin {} speed {} seed {} (configCount={})",
            player.getName().getString(), origin, speed, count, configCount);

        try {
            ManagedShip ship = TrainAssembler.spawnTrain(level, origin, velocity, count, spawnerWorldPos, dims);
            int lengthBlocks = count * dims.length();
            LOGGER.info("[DungeonTrain] Spawned train ship id={} carriages={} length={} blocks",
                ship.id(), count, lengthBlocks);
            int forgotten = resetRunState(source.getServer());
            // Drops the player onto the nearest flatbed once the fresh group's physics
            // settle (canonicalPos is null the tick it's assembled) and clears the
            // invulnerability set above.
            DtpPlacementService.enqueue(player, level, spawnX);
            source.sendSuccess(() -> Component.literal(
                "Spawned a fresh train on the track at X=" + origin.getX() + " (ship id " + ship.id()
                    + ", seeded with " + count + " carriages / " + lengthBlocks + " blocks, velocity +X "
                    + speed + " m/s) — you'll land on the flatbed once it settles."
                    + " The previous train was deleted; difficulty is back on automatic scaling and "
                    + forgotten + " remembered community carriage(s) can appear again."
                    + " Use '/dungeontrain carriages <count>' to change the carriage window."
            ), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] spawnTrain failed", t);
            player.setInvulnerable(false); // else they're left invincible in the holding spot
            source.sendFailure(Component.literal(
                "spawnTrain failed: " + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    /**
     * Clear the run-scoped progression state that a deleted train leaves behind, so
     * {@code /dungeontrain spawn} really is "start over" rather than "new train, old baggage":
     *
     * <ul>
     *   <li>the difficulty travelled-offset — otherwise the fresh train's pIdx-0 seed stays anchored
     *       at whatever tier the deleted train had earned;</li>
     *   <li>the world's already-placed shared-carriage ids — otherwise every community build the
     *       previous train used stays permanently excluded from the new one.</li>
     * </ul>
     *
     * <p>Deliberately lives here and not in {@code TrainAssembler.deleteExistingTrains}: that method
     * also runs on the world-start bootstrap path (which must not wipe the exclude-list every boot)
     * and under {@code /dtp}, which sets the difficulty offset on purpose for its teleport target.</p>
     *
     * @return how many remembered shared-carriage ids were forgotten
     */
    private static int resetRunState(MinecraftServer server) {
        DifficultyOffset.clear(server);
        int forgotten = DungeonTrainWorldData.get(server.overworld()).clearUsedCarriageIds();
        LOGGER.info("[DungeonTrain] spawn reset: difficulty offset cleared; forgot {} used shared-carriage id(s)",
            forgotten);
        return forgotten;
    }

    private static int runSpeed(CommandSourceStack source, double value) {
        DungeonTrainConfig.setSpeed(value);

        MinecraftServer server = source.getServer();
        Set<UUID> updatedTrainIds = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            List<TrainTransformProvider> providers = TrainAssembler.getActiveTrainProviders(level);
            for (TrainTransformProvider p : providers) {
                p.setTargetVelocity(new Vector3d(value, 0.0, 0.0));
                updatedTrainIds.add(p.getTrainId());
            }
        }

        final int trainCount = updatedTrainIds.size();
        LOGGER.info("[DungeonTrain] /dungeontrain speed {} — updated {} active train(s)", value, trainCount);
        source.sendSuccess(() -> Component.literal(
            "Train speed set to " + value + " m/s (" + trainCount + " active train"
                + (trainCount == 1 ? "" : "s") + " updated)"
        ), true);
        return 1;
    }

    private static int runCarriages(CommandSourceStack source, int count) {
        // Per-carriage architecture: count is a config-only knob that
        // controls the appender's halfBack / halfFront window. New
        // carriages spawn on demand as the player advances; existing
        // carriages keep their pIdx and stay in place.
        DungeonTrainConfig.setNumCarriages(count);

        LOGGER.info("[DungeonTrain] /dungeontrain carriages {} — updated config; appender will respect new window on next tick", count);
        source.sendSuccess(() -> Component.literal(
            "Default carriages set to " + count
                + ". The appender will extend or trim its needed window on the next tick "
                + "as the player crosses pIdx boundaries."
        ), true);
        return 1;
    }

    /**
     * Flip train-on-contact block breaking for THIS world, live, with no restart — the A/B switch
     * for measuring the feature's performance cost against {@code [sweep.perf]}.
     *
     * <p>Writes the per-world override ({@code DungeonTrainWorldData}), which
     * {@code TrainTickEvents.sweepFootprint} re-reads once per sweep, so the next tick already
     * reflects the change. Turning it OFF is a genuine zero-cost baseline: the sweep's cheap gate
     * ({@code TrainTickEvents.canBreakAt}) short-circuits before the collision-shape query and the
     * Sable sub-level lookup, so an OFF window measures the pre-existing footprint traversal alone.
     * The {@code [sweep.perf]} accumulator window is reset on the flip so the two halves of an A/B
     * never share a window.</p>
     */
    private static int runBreakBlocks(CommandSourceStack source, boolean enabled) {
        ServerLevel level = source.getLevel();
        DungeonTrainWorldData.get(level).setBreakBlocksOnContactOverride(enabled);
        TrainTickEvents.resetSweepPerfWindow();
        LOGGER.info("[DungeonTrain] /dungeontrain breakblocks {} — per-world override set, [sweep.perf] window reset",
            enabled ? "on" : "off");
        source.sendSuccess(() -> Component.literal(
            "Train contact block-breaking is now " + (enabled ? "ON" : "OFF")
                + " for this world (takes effect next tick). [sweep.perf] window reset — compare avgMs"
                + " between an ON and an OFF window to attribute the cost."
        ), true);
        return 1;
    }

    /** Report the effective block-breaking state and whether it comes from a per-world override. */
    private static int runBreakBlocksStatus(CommandSourceStack source) {
        DungeonTrainWorldData data = DungeonTrainWorldData.get(source.getLevel());
        Boolean override = data.getBreakBlocksOnContactOverride();
        boolean effective = data.getEffectiveBreakBlocksOnContact();
        source.sendSuccess(() -> Component.literal(
            "Train contact block-breaking: " + (effective ? "ON" : "OFF")
                + (override == null ? " (following the global config default)" : " (per-world override)")
        ), false);
        return 1;
    }

    private static int runTracks(CommandSourceStack source, boolean enabled) {
        DungeonTrainConfig.setGenerateTracks(enabled);
        LOGGER.info("[DungeonTrain] /dungeontrain tracks {} — generation flag flipped", enabled ? "on" : "off");
        source.sendSuccess(() -> Component.literal(
            "Track generation is now " + (enabled ? "ON" : "OFF")
                + ". Existing tracks are preserved; this only affects future chunk loads and per-tick scans."
        ), true);
        return 1;
    }

    private static int runDifficulty(CommandSourceStack source, int requestedTier) {
        ServerLevel level = source.getLevel();
        int rawTravelled = DifficultyProgression.rawMaxTravelledCarriageIndex(level);
        int offset = DifficultyProgression.travelledOffsetForRequestedTier(
            requestedTier, rawTravelled,
            DungeonTrainConfig.getCarriagesPerTier(), DungeonTrainConfig.getProgressionLevelDelay());
        DifficultyOffset.set(source.getServer(), offset);

        LOGGER.info("[DungeonTrain] /dungeontrain difficulty {} — travelled-offset set to {} (raw progress {} carriages); treating players as difficulty {}",
            requestedTier, offset, rawTravelled, requestedTier);
        source.sendSuccess(() -> Component.literal(
            "Difficulty level set to " + requestedTier + " — the game now treats everyone as if they'd travelled "
                + formatSigned(offset) + " carriages, so the HUD, mob spawns, onboarding stages, loot, and villager"
                + " trades all shift to match. The offset stays fixed as you travel, so the effective level drifts"
                + " with real progress — run this again to re-anchor it, or 'difficulty auto' to clear it."
                + " Already-spawned mobs keep their existing gear."
        ), true);
        return 1;
    }

    private static int runDifficultyAuto(CommandSourceStack source) {
        DifficultyOffset.clear(source.getServer());

        LOGGER.info("[DungeonTrain] /dungeontrain difficulty auto — travelled-offset cleared; resuming fully automatic scaling");
        source.sendSuccess(() -> Component.literal(
            "Difficulty offset cleared. Everything now follows your real travelled distance again."
        ), true);
        return 1;
    }

    private static int runDifficultyStatus(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        int offset = DungeonTrainConfig.getDifficultyTravelledOffset();
        int effective = DifficultyProgression.currentTier(level);

        String message = offset == 0
            ? "Difficulty level is " + effective + ", fully automatic (no offset)."
            : "Difficulty level is " + effective + " (offset " + formatSigned(offset)
                + " carriages active — run 'difficulty auto' to clear).";
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    /** Formats a signed int with an explicit sign for non-negative values too, e.g. "+3" / "-4". */
    private static String formatSigned(int value) {
        return (value >= 0 ? "+" : "") + value;
    }

    private static int runCinematic(CommandSourceStack source, CinematicIntroService.StartMode requested) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }

        CinematicIntroService.StartMode used = CinematicIntroService.replay(player, requested);
        String detail = switch (used) {
            case SPAWN -> "spawn-style start";
            case CURRENT -> requested == CinematicIntroService.StartMode.SPAWN
                ? "from current view (no train nearby for spawn start)"
                : "from current view";
        };
        LOGGER.info("[DungeonTrain] /dungeontrain cinematic replay by {} (requested={}, used={})",
            player.getName().getString(), requested, used);
        source.sendSuccess(() -> Component.literal("Replaying intro cinematic — " + detail + "."), true);
        return 1;
    }
}
