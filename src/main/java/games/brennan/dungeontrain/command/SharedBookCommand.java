package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import games.brennan.dungeontrain.narrative.AuthorBookPool;
import games.brennan.dungeontrain.narrative.SharedBookPool;
import games.brennan.dungeontrain.narrative.WorldLanguage;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * {@code /dungeontrain sharedbook} — hand out a player-written community book from the relay, without
 * waiting for one to turn up in a chest.
 *
 * <ul>
 *   <li>{@code list} — what is in the pool snapshot right now.</li>
 *   <li>{@code give [id] [player]} — a book from the pool, built exactly as chest loot builds one.</li>
 *   <li>{@code mine [id] [player]} — a book <b>you</b> wrote, also built as a loot copy.</li>
 *   <li>{@code refresh} — ask the relay for a fresh window and, for {@code mine}, your own shelf.</li>
 * </ul>
 *
 * <h3>Why {@code mine} exists, and why it strips the state</h3>
 * <p>Testing the author's half of the vote page — the padlock, the tallies, the missing thumbs —
 * otherwise means writing a book, waiting for the relay to approve it, and then waiting for the loot
 * roll to hand it back to you, which can take a session. {@code mine} asks the relay for the caller's
 * own shelf ({@code mine=1}) and gives one of those books instead.</p>
 *
 * <p>It then <b>strips the moderation state off the pool entry before building the stack</b>. That is
 * the whole point: a book off the author's own shelf arrives already stamped, which is the ONE path
 * that has always worked. What needs testing is the other one — a copy that arrives knowing nothing,
 * exactly as a chest find does, and learns whose it is from the authorship check on the first hold
 * ({@code FamiliarBookGreeter}). Handing over a pre-stamped stack would quietly test the wrong
 * path.</p>
 *
 * <p>Both fetches are off-thread. A command that finds nothing cached starts the fetch and says so;
 * run it again a moment later. OP-only (permission 2), inherited from the {@code dungeontrain} root.</p>
 */
public final class SharedBookCommand {

    /** How many pool rows {@code list} prints before it stops. */
    private static final int LIST_LIMIT = 30;

    private SharedBookCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("sharedbook")
            .then(Commands.literal("list").executes(SharedBookCommand::runList))
            .then(Commands.literal("refresh").executes(SharedBookCommand::runRefresh))
            .then(Commands.literal("give")
                .executes(ctx -> give(ctx, false, -1, null))
                .then(Commands.argument("id", IntegerArgumentType.integer(1))
                    .executes(ctx -> give(ctx, false, IntegerArgumentType.getInteger(ctx, "id"), null))
                    .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> give(ctx, false, IntegerArgumentType.getInteger(ctx, "id"),
                            EntityArgument.getPlayer(ctx, "player")))))
                .then(Commands.argument("player", EntityArgument.player())
                    .executes(ctx -> give(ctx, false, -1, EntityArgument.getPlayer(ctx, "player")))))
            .then(Commands.literal("mine")
                .executes(ctx -> give(ctx, true, -1, null))
                .then(Commands.argument("id", IntegerArgumentType.integer(1))
                    .executes(ctx -> give(ctx, true, IntegerArgumentType.getInteger(ctx, "id"), null))
                    .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> give(ctx, true, IntegerArgumentType.getInteger(ctx, "id"),
                            EntityArgument.getPlayer(ctx, "player")))))
                .then(Commands.argument("player", EntityArgument.player())
                    .executes(ctx -> give(ctx, true, -1, EntityArgument.getPlayer(ctx, "player")))));
    }

    /**
     * Give one community book to {@code target} (the caller when null).
     *
     * @param own true to draw from the CALLER's own shelf rather than the ordinary pool
     * @param id  a specific relay pool id, or -1 for the first entry available
     */
    private static int give(CommandContext<CommandSourceStack> ctx, boolean own, int id, ServerPlayer target)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer caller = source.getPlayerOrException();
        ServerPlayer receiver = target == null ? caller : target;

        List<SharedBookPool.PoolBook> pool = own ? ownShelf(caller) : SharedBookPool.snapshot();
        if (pool.isEmpty()) {
            requestFetch(caller, own);
            source.sendFailure(Component.literal(own
                ? "Your shelf isn't here yet — asking the relay. Run this again in a moment. "
                    + "(Nothing at all? You have no books in this pool, or network consent is off.)"
                : "The pool snapshot is empty — asking the relay. Run this again in a moment."));
            return 0;
        }

        SharedBookPool.PoolBook picked = null;
        for (SharedBookPool.PoolBook book : pool) {
            if (id < 0 || book.id() == id) { picked = book; break; }
        }
        if (picked == null) {
            source.sendFailure(Component.literal("No book with id " + id + " in "
                + (own ? "your shelf" : "the pool snapshot") + " — try 'sharedbook list'."));
            return 0;
        }

        SharedBookPool.PoolBook book = picked;
        ItemStack stack = SharedBookPool.buildStack(own ? asLootCopy(book) : book);
        if (!receiver.getInventory().add(stack)) receiver.drop(stack, false);

        String where = own ? " (your own, handed over as a loot copy would arrive: no state stamped)" : "";
        source.sendSuccess(() -> Component.literal("Gave #" + book.id() + " \"" + book.title()
            + "\" by " + book.author() + " to " + receiver.getName().getString() + where)
            .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    /**
     * The same book with nothing the relay said about its state — which is what makes it a loot copy.
     *
     * <p>{@code status} is the field {@code SharedBookPool.buildStack} gates every author-only stamp
     * on, so nulling it hands over a stack that knows only what a chest find knows. The private flag
     * and the vote tallies go with it: they are stamped under the same guard, and a copy carrying a
     * tally but no state would be a shape nothing in the game produces.</p>
     */
    private static SharedBookPool.PoolBook asLootCopy(SharedBookPool.PoolBook book) {
        return new SharedBookPool.PoolBook(book.id(), book.title(), book.author(), book.pages(),
            book.lang(), book.weight(), book.kidSafe(), book.political(),
            SharedBookPool.STATUS_UNKNOWN, false, 0, 0);
    }

    /** The caller's own catalogue, or empty when it hasn't arrived (or they have nothing in it). */
    private static List<SharedBookPool.PoolBook> ownShelf(ServerPlayer caller) {
        return AuthorBookPool.booksFor(ownToken(caller), true);
    }

    /**
     * The cache key for a shelf fetched by uuid.
     *
     * <p>{@link AuthorBookPool} keys catalogues by the relay's opaque author token, but the
     * {@code mine=1} query is scoped by uuid and never sends one — so on that path the token is a
     * cache key and nothing else (it is keyed {@code token|mine}, kept apart from the same person's
     * public shelf for exactly this reason). The uuid is the natural key to use.</p>
     */
    private static String ownToken(ServerPlayer caller) {
        return "self:" + caller.getUUID();
    }

    private static void requestFetch(ServerPlayer caller, boolean own) {
        boolean kidSafe = WorldLanguage.hostFetchesKidSafeBooks(caller.getServer());
        if (own) {
            UUID owner = caller.getUUID();
            AuthorBookPool.refreshAsync(ownToken(caller), WorldLanguage.hostLocale(caller.getServer()),
                kidSafe, owner);
            return;
        }
        SharedBookPool.refreshAsync(WorldLanguage.hostLocale(caller.getServer()),
            WorldLanguage.hostUuidConsented(caller.getServer()), kidSafe);
    }

    private static int runRefresh(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer caller = ctx.getSource().getPlayerOrException();
        // Both, so one command covers whichever half the tester is about to use. AuthorBookPool
        // caches a catalogue for the session, so clear it first or 'mine' would keep serving the
        // shelf it fetched an hour ago.
        AuthorBookPool.clear();
        requestFetch(caller, false);
        requestFetch(caller, true);
        ctx.getSource().sendSuccess(() -> Component.literal(
            "Asked the relay for a fresh pool window and your own shelf.").withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int runList(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer caller = source.getPlayerOrException();
        print(source, "Pool snapshot (" + SharedBookPool.approvedTotal() + " approved in the relay):",
            SharedBookPool.snapshot());
        print(source, "Your own shelf:", ownShelf(caller));
        return 1;
    }

    private static void print(CommandSourceStack source, String heading, List<SharedBookPool.PoolBook> books) {
        source.sendSuccess(() -> Component.literal(heading).withStyle(ChatFormatting.GREEN), false);
        if (books.isEmpty()) {
            source.sendSuccess(() -> Component.literal("  (empty — try 'sharedbook refresh')")
                .withStyle(ChatFormatting.YELLOW), false);
            return;
        }
        int shown = 0;
        for (SharedBookPool.PoolBook book : books) {
            if (shown++ >= LIST_LIMIT) {
                int rest = books.size() - LIST_LIMIT;
                source.sendSuccess(() -> Component.literal("  …and " + rest + " more")
                    .withStyle(ChatFormatting.GRAY), false);
                break;
            }
            // Status and the withdrawn flag are only ever present on your own shelf; on the ordinary
            // pool every row prints without them, which is itself the thing worth seeing.
            String state = (book.status() == null ? "" : "  [" + book.status() + "]")
                + (book.isPrivate() ? " [withdrawn]" : "");
            source.sendSuccess(() -> Component.literal(
                "  #" + book.id() + "  \"" + book.title() + "\" — " + book.author() + state)
                .withStyle(ChatFormatting.GRAY), false);
        }
    }
}
