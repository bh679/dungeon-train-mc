package games.brennan.dungeontrain.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import games.brennan.dungeontrain.train.TrainJump;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.BandLabel;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;

/**
 * Registers {@code /dtp <x>} (OP-only, permission level 2): teleports the
 * player to world-X {@code x} and guarantees a train is there to land on.
 * {@code /dtp <band>} (every phase token and alias, plus the styled occurrences — see
 * {@link DtpTarget}) does the same for that band's occurrence in a lap — see {@link BandLocator}:
 * {@code /dtp <band> <distance|subsection> <lap>} — a subsection is one of the band's F3+4 stages
 * ({@code /dtp nether mountain_3}, see {@link SubsectionLocator}) — where {@code lap} is 0-based
 * {@link games.brennan.dungeontrain.worldgen.WorldGenCycle#cycleIndex} (the same numbering
 * {@code /dungeontrain debug overworld-laps} prints) and defaults to lap 0, wherever the player is.
 * {@code /dtp next [distance]} jumps just inside the next band ahead of the player — "band" as the
 * F3+4 debug panel names it ({@link BandLabel#bandAt}), so Void gaps and styled occurrences count.
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

    /** Default blocks past a band's entry column for {@code /dtp <band>} (override with {@code /dtp <band> <distance>}) — just inside, not on the boundary. */
    private static final int BAND_ENTRY_INSET = 32;

    /** Lap {@code /dtp <band>} jumps to when none is given — the first. */
    private static final int DEFAULT_LAP = 0;

    /** {@code /dtp next} — not a {@link games.brennan.dungeontrain.worldgen.TrainPhase} token or alias, so it can't shadow a band. */
    private static final String NEXT_TOKEN = "next";

    /** {@code /dtp <band> <where>}: a distance past the band's entry, or a subsection token. */
    private static final String WHERE_ARG = "where";

    private DtpCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("dtp")
            .requires(s -> s.hasPermission(2))
            .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                .executes(ctx -> run(ctx.getSource(), DoubleArgumentType.getDouble(ctx, "x"))))
            .then(Commands.literal(NEXT_TOKEN)
                .executes(ctx -> runNext(ctx.getSource(), BAND_ENTRY_INSET))
                .then(Commands.argument("distance", DoubleArgumentType.doubleArg())
                    .executes(ctx -> runNext(ctx.getSource(), DoubleArgumentType.getDouble(ctx, "distance")))));
        for (DtpTarget target : DtpTarget.all()) {
            root.then(bandLiteral(target));
        }
        dispatcher.register(root);
    }

    /**
     * {@code /dtp <band> [<where> [lap]]}: literal children win over the {@code x} double argument, so numeric use is
     * unaffected. {@code where} is one word — a number is a distance past the band's entry (the old
     * {@code <distance>}), anything else a subsection token ({@link SubsectionLocator}). One argument rather than
     * a double and a word side by side, which Brigadier can't reliably choose between.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> bandLiteral(DtpTarget target) {
        return Commands.literal(target.token())
            .executes(ctx -> runBand(ctx.getSource(), target, BAND_ENTRY_INSET, DEFAULT_LAP))
            .then(Commands.argument(WHERE_ARG, StringArgumentType.word())
                .suggests((ctx, builder) -> suggestSubsections(ctx.getSource(), target, builder))
                .executes(ctx -> runWhere(ctx.getSource(), target, StringArgumentType.getString(ctx, WHERE_ARG), DEFAULT_LAP))
                .then(Commands.argument("lap", IntegerArgumentType.integer(0))
                    .executes(ctx -> runWhere(ctx.getSource(), target, StringArgumentType.getString(ctx, WHERE_ARG),
                        IntegerArgumentType.getInteger(ctx, "lap")))));
    }

    /** Suggests the band's lap-0 subsection tokens (the layout is the same shape every lap). */
    private static CompletableFuture<Suggestions> suggestSubsections(CommandSourceStack source, DtpTarget target,
                                                                     SuggestionsBuilder builder) {
        SubsectionLocator.of(source.getServer().overworld(), target, DEFAULT_LAP)
            .ifPresent(subs -> SharedSuggestionProvider.suggest(subs.offeredTokens(), builder));
        return builder.buildFuture();
    }

    /** {@code where} is a number → distance past the entry; otherwise a subsection token. */
    private static int runWhere(CommandSourceStack source, DtpTarget target, String where, int lap) {
        try {
            return runBand(source, target, Double.parseDouble(where), lap);
        } catch (NumberFormatException notANumber) {
            return runSubsection(source, target, where, lap);
        }
    }

    /** Teleport just inside subsection {@code token} of the {@code target} band's occurrence in {@code lap}. */
    private static int runSubsection(CommandSourceStack source, DtpTarget target, String token, int lap) {
        ServerLevel overworld = source.getServer().overworld();
        Optional<SubsectionLocator.Subsections> subs = SubsectionLocator.of(overworld, target, lap);
        if (subs.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.package.dtp_band_not_found_in_lap", target.displayName(), lap));
            return 0;
        }
        int index = subs.get().indexOf(token);
        OptionalLong x = SubsectionLocator.targetX(WorldGenCycle.fromConfig(), subs.get(), index, BAND_ENTRY_INSET);
        if (x.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.package.dtp_subsection_not_found",
                token, target.displayName(), String.join(", ", subs.get().offeredTokens())));
            return 0;
        }
        return run(source, x.getAsLong());
    }

    /** Teleport {@code distance} blocks past the entry of the {@code target} band's occurrence in {@code lap}, via the normal {@link #run} path. */
    private static int runBand(CommandSourceStack source, DtpTarget target, double distance, int lap) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.translatable("chat.dungeontrain.save.command_must_be_run"));
            return 0;
        }
        OptionalInt entry = BandLocator.bandStartXInLap(source.getServer().overworld(), target, lap);
        if (entry.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.package.dtp_band_not_found_in_lap", target.displayName(), lap));
            return 0;
        }
        return run(source, entry.getAsInt() + distance);
    }

    /** Teleport {@code distance} blocks past the entry of the next band ahead of the player, via the normal {@link #run} path. */
    private static int runNext(CommandSourceStack source, double distance) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.translatable("chat.dungeontrain.save.command_must_be_run"));
            return 0;
        }
        ServerLevel overworld = source.getServer().overworld();
        if (!DungeonTrainWorldData.get(overworld).startsWithTrain()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.package.world_doesn_t_use"));
            return 0;
        }
        int fromX = player.getBlockX();
        String current = BandLabel.bandAt(overworld, fromX);
        OptionalInt entry = BandLocator.nextBandStartX(WorldGenCycle.fromConfig(),
            x -> !BandLabel.bandAt(overworld, x).equals(current), fromX);
        if (entry.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.package.dtp_next_band_not_found", current));
            return 0;
        }
        double targetX = entry.getAsInt() + distance;
        String next = BandLabel.bandAt(overworld, (int) Math.floor(targetX));
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.package.dtp_next_band", current, next), true);
        return run(source, targetX);
    }

    private static int run(CommandSourceStack source, double x) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.translatable("chat.dungeontrain.save.command_must_be_run"));
            return 0;
        }

        // The hold / difficulty / spawn / deferred-landing sequence lives in TrainJump so the
        // Nether-portal band jump can share it; this command only maps the outcome to chat.
        TrainJump.Result result = TrainJump.jumpTo(player, x, "/dtp");
        if (result instanceof TrainJump.NoTrainWorld) {
            source.sendFailure(Component.translatable("chat.dungeontrain.package.world_doesn_t_use"));
            return 0;
        }
        if (result instanceof TrainJump.SpawnFailed failed) {
            Throwable t = failed.cause();
            source.sendFailure(Component.translatable("chat.dungeontrain.package.spawntrain_failed", t.getClass().getSimpleName(), t.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
        int requestedTier = ((TrainJump.Ok) result).difficultyTier();
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.package.teleporting_x_spawning_train", x, requestedTier), true);
        return 1;
    }
}
