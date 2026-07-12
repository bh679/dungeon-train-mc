package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.narrative.BookFactory;
import games.brennan.dungeontrain.narrative.DeathLoreStore;
import games.brennan.dungeontrain.narrative.Letter;
import games.brennan.dungeontrain.narrative.NarrativeProgress;
import games.brennan.dungeontrain.narrative.NarrativeProgressData;
import games.brennan.dungeontrain.narrative.RandomBookFactory;
import games.brennan.dungeontrain.event.StartingBookEvents;
import games.brennan.dungeontrain.narrative.RandomBookFile;
import games.brennan.dungeontrain.narrative.RandomBookRegistry;
import games.brennan.dungeontrain.narrative.StartingBookContext;
import games.brennan.dungeontrain.narrative.PlayerPlayedMarker;
import games.brennan.dungeontrain.narrative.StartingBookFactory;
import games.brennan.dungeontrain.narrative.StartingBookRegistry;
import games.brennan.dungeontrain.narrative.StoryFile;
import games.brennan.dungeontrain.narrative.StoryRegistry;
import games.brennan.dungeontrain.registry.ModBlocks;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * {@code /dungeontrain narrative} subtree — testing surface for the narrative
 * loader. Commands:
 *
 * <ul>
 *   <li>{@code /dungeontrain narrative list} — chat-print every loaded story
 *       with letter / variant counts.</li>
 *   <li>{@code /dungeontrain narrative book <story> [letter]} — give the
 *       calling player a signed book for a single Letter.</li>
 *   <li>{@code /dungeontrain narrative lectern <story> [letter]} — place a
 *       lectern at the player's look-target with the book mounted.</li>
 *   <li>{@code /dungeontrain narrative reload} — re-scan both the bundled
 *       narratives and the random-book pool without restarting the server.</li>
 *   <li>{@code /dungeontrain narrative randombook list} — chat-print every
 *       loaded random book with variant / weight / generation.</li>
 *   <li>{@code /dungeontrain narrative randombook give [basename]} — give the
 *       calling player a rolled vanilla written book from the random-book pool;
 *       no basename → pool-weighted random pick.</li>
 * </ul>
 *
 * <p>OP-only (permission level 2) is inherited from the {@code dungeontrain}
 * root literal in {@link TrainCommand}.</p>
 */
public final class NarrativeCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Tab-completion source: every loaded story basename, alphabetical. */
    private static final SuggestionProvider<CommandSourceStack> STORY_SUGGESTIONS =
        (ctx, builder) -> SharedSuggestionProvider.suggest(StoryRegistry.basenames(), builder);

    /** Tab-completion source: every loaded random-book basename, alphabetical. */
    private static final SuggestionProvider<CommandSourceStack> RANDOM_BOOK_SUGGESTIONS =
        (ctx, builder) -> SharedSuggestionProvider.suggest(RandomBookRegistry.basenames(), builder);

    /** Tab-completion source: every loaded starting-book basename, alphabetical. */
    private static final SuggestionProvider<CommandSourceStack> STARTING_BOOK_SUGGESTIONS =
        (ctx, builder) -> SharedSuggestionProvider.suggest(StartingBookRegistry.basenames(), builder);

    /** Tab-completion source: every starting-book context name (folder form). */
    private static final SuggestionProvider<CommandSourceStack> STARTING_BOOK_CONTEXT_SUGGESTIONS =
        (ctx, builder) -> {
            java.util.List<String> names = new java.util.ArrayList<>();
            for (StartingBookContext c : StartingBookContext.values()) {
                names.add(c.folderName().isEmpty() ? "default" : c.folderName());
            }
            return SharedSuggestionProvider.suggest(names, builder);
        };

    private NarrativeCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("narrative")
            .then(Commands.literal("list").executes(NarrativeCommand::runList))
            .then(Commands.literal("reload").executes(NarrativeCommand::runReload))
            .then(Commands.literal("progress").executes(NarrativeCommand::runProgress))
            .then(Commands.literal("reset").executes(NarrativeCommand::runReset))
            .then(Commands.literal("book")
                // No args — next-uncompleted-story flow.
                .executes(ctx -> runBookNext(ctx, Optional.empty()))
                .then(Commands.argument("story", StringArgumentType.string())
                    .suggests(STORY_SUGGESTIONS)
                    // Story only — next-unread of that story (cycle if complete).
                    .executes(ctx -> runBookNext(ctx, Optional.of(StringArgumentType.getString(ctx, "story"))))
                    // Story + letter — explicit override; doesn't advance progress.
                    .then(Commands.argument("letter", IntegerArgumentType.integer(1))
                        .executes(ctx -> runBookExplicit(ctx, IntegerArgumentType.getInteger(ctx, "letter"))))))
            .then(Commands.literal("lectern")
                // No args — next-uncompleted-story flow.
                .executes(ctx -> runLecternNext(ctx, Optional.empty()))
                .then(Commands.argument("story", StringArgumentType.string())
                    .suggests(STORY_SUGGESTIONS)
                    .executes(ctx -> runLecternNext(ctx, Optional.of(StringArgumentType.getString(ctx, "story"))))
                    .then(Commands.argument("letter", IntegerArgumentType.integer(1))
                        .executes(ctx -> runLecternExplicit(ctx, IntegerArgumentType.getInteger(ctx, "letter"))))))
            // narrative_lectern block — places the lazy-resolution lectern
            // block at the player's look-target. The actual book content is
            // decided per-player on right-click.
            .then(Commands.literal("spawnlectern").executes(NarrativeCommand::runSpawnLectern))
            // randombook subtree — list + give helpers for the standalone
            // random-book pool. The pool is delivered via the
            // dungeontrain:random_book placeholder item; these commands are
            // for testing without placing chests.
            .then(Commands.literal("randombook")
                .then(Commands.literal("list").executes(NarrativeCommand::runRandomBookList))
                .then(Commands.literal("progress").executes(NarrativeCommand::runRandomBookProgress))
                .then(Commands.literal("reset").executes(NarrativeCommand::runRandomBookReset))
                .then(Commands.literal("give")
                    // No basename — pool-weighted random pick.
                    .executes(NarrativeCommand::runRandomBookGiveRandom)
                    .then(Commands.argument("basename", StringArgumentType.string())
                        .suggests(RANDOM_BOOK_SUGGESTIONS)
                        .executes(NarrativeCommand::runRandomBookGiveExplicit))))
            // startingbook subtree — list, give, reload, reset helpers for the
            // welcome-book pool. The pool is delivered automatically on first
            // login and every respawn; these commands are for testing without
            // logging out + back in.
            .then(Commands.literal("startingbook")
                .then(Commands.literal("list").executes(NarrativeCommand::runStartingBookList))
                .then(Commands.literal("reload").executes(NarrativeCommand::runStartingBookReload))
                .then(Commands.literal("reset").executes(NarrativeCommand::runStartingBookReset))
                .then(Commands.literal("give")
                    // No basename — pool-weighted random pick (DEFAULT context).
                    .executes(NarrativeCommand::runStartingBookGiveRandom)
                    .then(Commands.argument("basename", StringArgumentType.string())
                        .suggests(STARTING_BOOK_SUGGESTIONS)
                        .executes(NarrativeCommand::runStartingBookGiveExplicit)))
                // fire <context> — trigger a welcome strike immediately with
                // an explicit context, bypassing the deferral queue. Test-only;
                // for Gate 2 manual coverage of all four context paths.
                .then(Commands.literal("fire")
                    .then(Commands.argument("context", StringArgumentType.string())
                        .suggests(STARTING_BOOK_CONTEXT_SUGGESTIONS)
                        .executes(NarrativeCommand::runStartingBookFire))));
    }

    private static int runList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        int total = StoryRegistry.count();
        if (total == 0) {
            source.sendSuccess(() -> Component.literal("No narratives loaded.").withStyle(ChatFormatting.YELLOW), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Narratives loaded: " + total).withStyle(ChatFormatting.GREEN), false);
        for (var id : StoryRegistry.ids()) {
            StoryRegistry.get(id).ifPresent(story -> {
                int letters = story.letters().size();
                int variants = story.letters().stream().mapToInt(l -> l.variants().size()).sum();
                String tail = id.getPath().substring(id.getPath().lastIndexOf('/') + 1);
                source.sendSuccess(() -> Component.literal(
                    String.format("  %s (%d letters, %d variants) — %s",
                        tail, letters, variants, story.story())
                ), false);
            });
        }
        return total;
    }

    private static int runReload(CommandContext<CommandSourceStack> ctx) {
        // Reload straight off the server's live ResourceManager so the command
        // reflects the currently-loaded datapacks (bundled + overrides) without a
        // full /reload. The AddReloadListenerEvent listeners handle world-load and
        // /reload automatically; this stays a DT-only fast path.
        ResourceManager rm = ctx.getSource().getServer().getResourceManager();
        StoryRegistry.load(rm);
        RandomBookRegistry.load(rm);
        StartingBookRegistry.load(rm);
        DeathLoreStore.load(rm);
        int stories = StoryRegistry.count();
        int randomBooks = RandomBookRegistry.count();
        int startingBooks = StartingBookRegistry.count();
        ctx.getSource().sendSuccess(() ->
            Component.literal("Narrative reloaded: " + stories + " stories, " + randomBooks + " random books, " + startingBooks + " starting books")
                .withStyle(ChatFormatting.GREEN),
            true);
        return stories + randomBooks + startingBooks;
    }

    private static int runRandomBookList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        int total = RandomBookRegistry.count();
        if (total == 0) {
            source.sendSuccess(() -> Component.literal("No random books loaded.").withStyle(ChatFormatting.YELLOW), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
            "Random books loaded: " + total + " (total weight: " + RandomBookRegistry.totalWeight() + ")"
        ).withStyle(ChatFormatting.GREEN), false);
        for (var id : RandomBookRegistry.ids()) {
            RandomBookRegistry.get(id).ifPresent(book -> {
                String tail = id.getPath().substring(id.getPath().lastIndexOf('/') + 1);
                source.sendSuccess(() -> Component.literal(
                    String.format("  %s — %s by %s (%d variants, weight %d, gen %d)",
                        tail, book.title(), book.author(),
                        book.variants().size(), book.weight(), book.generation())
                ), false);
            });
        }
        return total;
    }

    private static int runRandomBookGiveRandom(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (RandomBookRegistry.count() == 0) {
            ctx.getSource().sendFailure(Component.literal("No random books loaded."));
            return 0;
        }
        long seed = player.serverLevel().getGameTime() ^ player.getUUID().getLeastSignificantBits();
        Optional<ItemStack> bookOpt = RandomBookFactory.rollFromPool(seed);
        if (bookOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Random-book pool is empty (zero total weight?)"));
            return 0;
        }
        ItemStack book = bookOpt.get();
        if (!player.getInventory().add(book)) {
            player.drop(book, false);
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
            "Gave random book (pool-weighted)"
        ).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int runRandomBookGiveExplicit(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String basename = StringArgumentType.getString(ctx, "basename");
        Optional<RandomBookFile> opt = RandomBookRegistry.getByBasename(basename);
        if (opt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Unknown random book: " + basename));
            return 0;
        }
        RandomBookFile book = opt.get();
        long seed = player.serverLevel().getGameTime() ^ player.getUUID().getLeastSignificantBits();
        int variantIndex = book.pickVariantIndex(seed);
        String body = book.variants().get(variantIndex);
        ItemStack stack = RandomBookFactory.buildVanillaBook(book, body, variantIndex);
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
            "Gave random book: " + book.title() + " (variant " + (variantIndex + 1) + "/" + book.variants().size() + ")"
        ).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int runRandomBookProgress(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel overworld = player.serverLevel().getServer().overworld();
        NarrativeProgressData data = NarrativeProgressData.get(overworld);
        var snapshot = data.randomBookSnapshot();

        ctx.getSource().sendSuccess(() -> Component.literal(
            "Random-book reads for this world:"
        ).withStyle(ChatFormatting.GREEN), false);

        for (var basename : RandomBookRegistry.basenames()) {
            Optional<RandomBookFile> book = RandomBookRegistry.getByBasename(basename);
            if (book.isEmpty()) continue;
            int total = book.get().variants().size();
            NarrativeProgress p = snapshot.getOrDefault(basename, new NarrativeProgress());
            int read = p.readCount();
            String marker = read >= total ? " ✓" : "";
            ctx.getSource().sendSuccess(() -> Component.literal(
                String.format("  %s — %d/%d%s", basename, read, total, marker)
            ), false);
        }
        return snapshot.size();
    }

    // ---------------- startingbook subcommands ----------------

    private static int runStartingBookList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        int total = StartingBookRegistry.count();
        if (total == 0) {
            source.sendSuccess(() -> Component.literal("No starting books loaded.").withStyle(ChatFormatting.YELLOW), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
            "Starting books loaded: " + total + " (total weight: " + StartingBookRegistry.totalWeight() + ")"
        ).withStyle(ChatFormatting.GREEN), false);
        for (var id : StartingBookRegistry.ids()) {
            StartingBookRegistry.get(id).ifPresent(book -> {
                String tail = id.getPath().substring(id.getPath().lastIndexOf('/') + 1);
                source.sendSuccess(() -> Component.literal(
                    String.format("  %s — %s by %s (%d variants, weight %d, gen %d)",
                        tail, book.title(), book.author(),
                        book.variants().size(), book.weight(), book.generation())
                ), false);
            });
        }
        return total;
    }

    private static int runStartingBookReload(CommandContext<CommandSourceStack> ctx) {
        StartingBookRegistry.load(ctx.getSource().getServer().getResourceManager());
        int startingBooks = StartingBookRegistry.count();
        ctx.getSource().sendSuccess(() ->
            Component.literal("Starting-book pool reloaded: " + startingBooks + " books")
                .withStyle(ChatFormatting.GREEN),
            true);
        return startingBooks;
    }

    private static int runStartingBookGiveRandom(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (StartingBookRegistry.count() == 0) {
            ctx.getSource().sendFailure(Component.literal("No starting books loaded."));
            return 0;
        }
        long seed = player.serverLevel().getGameTime() ^ player.getUUID().getLeastSignificantBits();
        Optional<ItemStack> bookOpt = StartingBookFactory.rollFromPool(seed);
        if (bookOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Starting-book pool is empty (zero total weight?)"));
            return 0;
        }
        ItemStack book = bookOpt.get();
        if (!player.getInventory().add(book)) {
            player.drop(book, false);
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
            "Gave starting book (pool-weighted)"
        ).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int runStartingBookGiveExplicit(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String basename = StringArgumentType.getString(ctx, "basename");
        Optional<RandomBookFile> opt = StartingBookRegistry.getByBasename(basename);
        if (opt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Unknown starting book: " + basename));
            return 0;
        }
        RandomBookFile book = opt.get();
        long seed = player.serverLevel().getGameTime() ^ player.getUUID().getLeastSignificantBits();
        int variantIndex = book.pickVariantIndex(seed);
        String body = book.variants().get(variantIndex);
        ItemStack stack = StartingBookFactory.buildUnstampedBook(book, body, variantIndex);
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
            "Gave starting book: " + book.title() + " (variant " + (variantIndex + 1) + "/" + book.variants().size() + ")"
        ).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int runStartingBookReset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel overworld = player.serverLevel().getServer().overworld();
        NarrativeProgressData data = NarrativeProgressData.get(overworld);
        data.resetStartingBookReceived(player.getUUID());
        // Also clear the respawn-cycling variant-seen-set so the next
        // respawn rolls fresh — testers rely on this to re-exercise the
        // cycle without restarting the server.
        data.resetStartingBookVariantsSeen();
        // And clear the per-installation Nether/End dimension playlist so the
        // dimension-welcome cycle starts over on the next Nether/End run.
        PlayerPlayedMarker.clearDimensionVariantsSeen(player.getUUID());
        ctx.getSource().sendSuccess(() -> Component.literal(
            "Starting-book state reset for " + player.getName().getString()
                + " — first-login flag cleared, respawn + Nether/End cycles reset"
        ).withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /**
     * Force a welcome strike for the calling player with an explicit
     * context. Bypasses both the deferral queue and the
     * per-player-per-world receipt gate. Use this in Gate 2 testing to hit
     * each of the four context paths without naturally setting up the
     * triggers (new world / multi-player / kill-respawn).
     */
    private static int runStartingBookFire(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String raw = StringArgumentType.getString(ctx, "context");
        Optional<StartingBookContext> contextOpt = StartingBookContext.fromString(raw);
        if (contextOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                "Unknown context '" + raw + "'. Try: default, new_world, joined_world, respawn."));
            return 0;
        }
        StartingBookContext context = contextOpt.get();
        if (StartingBookRegistry.count() == 0) {
            ctx.getSource().sendFailure(Component.literal("Starting-book pool is empty."));
            return 0;
        }
        StartingBookEvents.forceFireForTest(player, context);
        ctx.getSource().sendSuccess(() -> Component.literal(
            "Fired welcome strike with context " + context.name()
                + " (pool=" + StartingBookRegistry.countFor(context) + " books; "
                + "fallback to default if empty)"
        ).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int runRandomBookReset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel overworld = player.serverLevel().getServer().overworld();
        NarrativeProgressData data = NarrativeProgressData.get(overworld);
        data.resetRandomBookProgress();
        ctx.getSource().sendSuccess(() -> Component.literal(
            "Random-book reads reset for this world"
        ).withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /**
     * Progression-aware book give. {@code basenameOpt} present → prefer that
     * story (cycle to next uncompleted if complete); absent → next-uncompleted-story.
     * Uses the world's progression cursor.
     */
    private static int runBookNext(CommandContext<CommandSourceStack> ctx, Optional<String> basenameOpt) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel overworld = player.serverLevel().getServer().overworld();
        Optional<StoryFile> preferStory = basenameOpt.flatMap(StoryRegistry::getByBasename);
        if (basenameOpt.isPresent() && preferStory.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Unknown story: " + basenameOpt.get()));
            return 0;
        }
        Optional<ItemStack> bookOpt = BookFactory.buildNext(overworld, preferStory);
        if (bookOpt.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                "All narratives complete for this world. Run `/dungeontrain narrative reset` to start over."
            ).withStyle(ChatFormatting.YELLOW), false);
            return 0;
        }
        ItemStack book = bookOpt.get();
        if (!player.getInventory().add(book)) {
            player.drop(book, false);
        }
        // Pull identity off the stamped stack so we report the actual story+letter
        // the player got (could be different from the requested one if it was complete).
        var id = games.brennan.dungeontrain.narrative.NarrativeBookTag.read(book);
        if (id.isPresent()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                "Gave book: " + id.get().storyBasename() + " — letter " + id.get().letterIndex()
            ).withStyle(ChatFormatting.GREEN), false);
        }
        return 1;
    }

    /**
     * Explicit-letter book give. Bypasses progression (does NOT advance) —
     * useful for testing specific letters.
     */
    private static int runBookExplicit(CommandContext<CommandSourceStack> ctx, int letterIndex) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String basename = StringArgumentType.getString(ctx, "story");
        Optional<StoryFile> opt = StoryRegistry.getByBasename(basename);
        if (opt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Unknown story: " + basename));
            return 0;
        }
        StoryFile story = opt.get();
        Optional<Letter> letterOpt = story.letterByIndex(letterIndex);
        if (letterOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                "Story '" + basename + "' has no letter " + letterIndex + " (max: " + story.letters().size() + ")"));
            return 0;
        }
        Letter letter = letterOpt.get();
        long seed = player.level().getGameTime() ^ player.getUUID().getLeastSignificantBits();
        ItemStack book = BookFactory.buildSignedBook(story, letter, seed);
        if (!player.getInventory().add(book)) {
            player.drop(book, false);
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
            "Gave book (explicit, no progress advance): " + story.story() + " — " + letter.label()
        ).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int runProgress(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel overworld = player.serverLevel().getServer().overworld();
        NarrativeProgressData data = NarrativeProgressData.get(overworld);
        var snapshot = data.snapshotStories();

        ctx.getSource().sendSuccess(() -> Component.literal(
            "Narrative progress for this world:"
        ).withStyle(ChatFormatting.GREEN), false);

        for (var basename : StoryRegistry.basenames()) {
            Optional<StoryFile> story = StoryRegistry.getByBasename(basename);
            if (story.isEmpty()) continue;
            int total = story.get().letters().size();
            NarrativeProgress p = snapshot.getOrDefault(basename, new NarrativeProgress());
            int read = p.readCount();
            String marker = p.isComplete(total) ? " ✓" : "";
            ctx.getSource().sendSuccess(() -> Component.literal(
                String.format("  %s — %d/%d%s", basename, read, total, marker)
            ), false);
        }

        Optional<String> next = data.nextUncompletedStory();
        if (next.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                "All stories complete."
            ).withStyle(ChatFormatting.YELLOW), false);
        } else {
            String nextBasename = next.get();
            int letterIdx = StoryRegistry.getByBasename(nextBasename)
                .map(s -> data.progressFor(nextBasename).nextUnreadLetter(s.letters().size()))
                .orElse(-1);
            ctx.getSource().sendSuccess(() -> Component.literal(
                "Next: " + nextBasename + " (letter " + letterIdx + ")"
            ).withStyle(ChatFormatting.AQUA), false);
        }
        return 1;
    }

    private static int runReset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel overworld = player.serverLevel().getServer().overworld();
        NarrativeProgressData data = NarrativeProgressData.get(overworld);
        data.resetAll();
        ctx.getSource().sendSuccess(() -> Component.literal(
            "Narrative progress reset for this world"
        ).withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /** Lectern + progression-aware book pick. */
    private static int runLecternNext(CommandContext<CommandSourceStack> ctx, Optional<String> basenameOpt) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel overworld = player.serverLevel().getServer().overworld();
        Optional<StoryFile> preferStory = basenameOpt.flatMap(StoryRegistry::getByBasename);
        if (basenameOpt.isPresent() && preferStory.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Unknown story: " + basenameOpt.get()));
            return 0;
        }
        Optional<ItemStack> bookOpt = BookFactory.buildNext(overworld, preferStory);
        if (bookOpt.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                "All narratives complete for this world."
            ).withStyle(ChatFormatting.YELLOW), false);
            return 0;
        }
        return placeLectern(ctx, player, bookOpt.get());
    }

    /** Lectern + explicit (story, letter); no progression advance. */
    private static int runLecternExplicit(CommandContext<CommandSourceStack> ctx, int letterIndex) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String basename = StringArgumentType.getString(ctx, "story");
        Optional<StoryFile> opt = StoryRegistry.getByBasename(basename);
        if (opt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Unknown story: " + basename));
            return 0;
        }
        StoryFile story = opt.get();
        Optional<Letter> letterOpt = story.letterByIndex(letterIndex);
        if (letterOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                "Story '" + basename + "' has no letter " + letterIndex));
            return 0;
        }
        Letter letter = letterOpt.get();
        long seed = player.serverLevel().getGameTime() ^ player.getUUID().getLeastSignificantBits();
        ItemStack book = BookFactory.buildSignedBook(story, letter, seed);
        return placeLectern(ctx, player, book);
    }

    /**
     * Place a {@link ModBlocks#NARRATIVE_LECTERN} at the player's look-target.
     * No book argument — the block resolves its book lazily per-player on
     * right-click.
     */
    private static int runSpawnLectern(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        Vec3 eye = player.getEyePosition();
        Vec3 reach = eye.add(player.getViewVector(1.0f).scale(6.0));
        BlockHitResult hit = level.clip(new ClipContext(
            eye, reach,
            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE,
            player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            ctx.getSource().sendFailure(Component.literal(
                "Look at a block face within 6 blocks to place the narrative lectern."));
            return 0;
        }
        BlockPos placePos = hit.getBlockPos().relative(hit.getDirection());
        if (!level.getBlockState(placePos).canBeReplaced()) {
            ctx.getSource().sendFailure(Component.literal(
                "Target position " + placePos + " is not replaceable."));
            return 0;
        }
        Direction facing = player.getDirection().getOpposite();
        BlockState lectern = ModBlocks.NARRATIVE_LECTERN.get().defaultBlockState()
            .setValue(LecternBlock.FACING, facing)
            .setValue(LecternBlock.HAS_BOOK, true);
        if (!level.setBlock(placePos, lectern, 3)) {
            ctx.getSource().sendFailure(Component.literal("Failed to place narrative lectern."));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
            "Narrative lectern placed at " + placePos.getX() + "," + placePos.getY() + "," + placePos.getZ()
                + " — book resolves per-player on right-click."
        ).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    /**
     * Shared lectern placement — raycasts to the block the player is looking
     * at within 6 blocks, places a lectern at the adjacent face, mounts
     * the given book via {@link LecternBlock#tryPlaceBook}.
     */
    private static int placeLectern(CommandContext<CommandSourceStack> ctx, ServerPlayer player, ItemStack book) {
        ServerLevel level = player.serverLevel();
        Vec3 eye = player.getEyePosition();
        Vec3 reach = eye.add(player.getViewVector(1.0f).scale(6.0));
        BlockHitResult hit = level.clip(new ClipContext(
            eye, reach,
            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE,
            player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            ctx.getSource().sendFailure(Component.literal(
                "Look at a block face within 6 blocks to place the lectern."));
            return 0;
        }

        BlockPos placePos = hit.getBlockPos().relative(hit.getDirection());
        if (!level.getBlockState(placePos).canBeReplaced()) {
            ctx.getSource().sendFailure(Component.literal(
                "Target position " + placePos + " is not replaceable."));
            return 0;
        }

        Direction facing = player.getDirection().getOpposite();
        BlockState lectern = Blocks.LECTERN.defaultBlockState()
            .setValue(LecternBlock.FACING, facing)
            .setValue(LecternBlock.HAS_BOOK, false);
        if (!level.setBlock(placePos, lectern, 3)) {
            ctx.getSource().sendFailure(Component.literal("Failed to place lectern."));
            return 0;
        }

        book.setCount(1);
        if (!LecternBlock.tryPlaceBook(player, level, placePos, lectern, book)) {
            ctx.getSource().sendFailure(Component.literal(
                "Lectern placed but failed to mount book."));
            return 0;
        }

        var id = games.brennan.dungeontrain.narrative.NarrativeBookTag.read(book);
        String label = id.map(i -> i.storyBasename() + " letter " + i.letterIndex()).orElse("(unknown)");
        ctx.getSource().sendSuccess(() -> Component.literal(
            "Lectern placed at " + placePos.getX() + "," + placePos.getY() + "," + placePos.getZ()
                + " with book: " + label
        ).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }
}
