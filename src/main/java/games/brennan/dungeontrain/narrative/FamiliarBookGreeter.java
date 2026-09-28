package games.brennan.dungeontrain.narrative;

import games.brennan.dungeontrain.discord.WorldInfoReporter;
import games.brennan.dungeontrain.event.NetworkConsentMirror;
import games.brennan.dungeontrain.net.relay.BookStatsClient;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.OptionalInt;
import java.util.UUID;

/**
 * Greets an author who is holding a community-pool LOOT copy of a book they wrote with a quiet
 * "a familiar book…" line reporting how the book is doing in the wild (see {@link FamiliarBookMessage}),
 * and — the same relay reply, the same proof of authorship — stamps the copy with what its writer is
 * entitled to know about it, so the vote page offers them their own controls rather than a stranger's
 * (see {@link #stampOwnState}).
 *
 * <p>Only loot copies carry {@link SharedBookReadTag} (the relay pool id) — a player's own signed copy
 * burns on sharing and is never kept — so this naturally fires only when you stumble on your own book
 * out in the world as chest loot. Authorship is verified by the relay (UUID match), never by the book's
 * author-name text, so a renamed / spoofed author line can't surface someone else's stats.</p>
 *
 * <p><b>Once per item instance.</b> The greeting fires once for each distinct copy: a per-STACK marker
 * ({@link #NBT_GREETED}, in the item's CUSTOM_DATA like {@link SharedBookFoundTag}'s held marker) is
 * stamped when the line is shown, so re-holding the SAME copy stays quiet — but a second copy of the
 * same book (a fresh stack) greets again. The marker is set only on an author hit, so a transient relay
 * failure just retries next hold, and non-author holders are never mutated.</p>
 */
public final class FamiliarBookGreeter {

    /** Per-stack "already greeted this author for this copy" marker, in the item's CUSTOM_DATA. */
    private static final String NBT_GREETED = "dt_familiar_greeted";

    private FamiliarBookGreeter() {}

    /**
     * Attempt to greet {@code player} for the community book {@code stack}. No-op unless the stack is a
     * discovered loot copy (carries a pool id), this exact copy hasn't already been greeted, and the
     * player has granted network consent (the lookup sends their uuid). The gray line is sent — and the
     * copy marked — only when the relay confirms the player authored the book.
     */
    public static void maybeGreet(ServerPlayer player, ItemStack stack) {
        OptionalInt idOpt = SharedBookReadTag.readId(stack);
        if (idOpt.isEmpty()) return;
        // NOTE: the greeted marker no longer short-circuits the whole method — it gates the MESSAGE
        // only (below). The same reply also carries the state the vote page's author controls read —
        // the 👍/👎 tally, the moderation status, the withdrawn flag — and that wants refreshing every
        // time the book is picked up, not once in its life. A stack that has sat in a chest for a
        // week would otherwise report what it was worth when it was shelved, forever.
        if (!NetworkConsentMirror.isGranted(player)) return; // the lookup sends the player's uuid
        int id = idOpt.getAsInt();
        UUID uuid = player.getUUID();

        BookStatsClient.fetch(id, uuid, stats -> {
            if (!stats.isAuthor()) return;
            // Back onto the server thread before touching the player / stack.
            player.server.execute(() -> {
                if (player.hasDisconnected()) return;
                // Always refresh what the book knows about itself — cheap, idempotent, and the whole
                // point of re-asking.
                stampOwnState(stack, stats);
                if (isGreeted(stack)) return; // lost a race with a concurrent equip — show once
                markGreeted(stack);
                player.sendSystemMessage(FamiliarBookMessage.build(
                        WorldInfoReporter.clientLanguage(player), stats, player.getRandom()));
            });
        });
    }

    /**
     * Write onto {@code stack} the three facts the relay just confirmed are the holder's business,
     * because they wrote this book: how it is polling, where the train stands on it, and whether they
     * have withdrawn it.
     *
     * <p>These are what the vote page's author controls key off — the padlock, the tallies, the
     * status line, and the ABSENCE of the thumbs and the report icon. Until this existed they were
     * stamped in one place only ({@code SharedBookPool.buildStack}, from the author's-own-shelf
     * {@code mine=1} fetch), which fires only inside the writer's own author-locked portal room. A
     * loot copy of their own book — the ordinary way a writer meets their own writing — therefore
     * showed them a stranger's page: thumbs they could vote themselves up with, a report control
     * aimed at themselves, and no way to withdraw the book at all.</p>
     *
     * <p><b>Status is stamped only when the relay actually named one.</b> A relay too old to answer
     * it omits the field, and stamping {@code fromStatus(null)} would be stamping {@code PUBLIC},
     * which is a no-op — but the guard is explicit because the failure it prevents is not: a copy
     * that came off the author's own shelf carrying {@code READING} or {@code DISLIKED} must not be
     * quietly re-labelled by an endpoint that had nothing to say.</p>
     */
    static void stampOwnState(ItemStack stack, BookStatsClient.Stats stats) {
        if (stack == null || stack.isEmpty() || stats == null || !stats.isAuthor()) return;
        BookVoteCountsTag.stamp(stack, stats.votesUp(), stats.votesDown());
        if (stats.status() == null) return; // the relay did not say — leave whatever is there alone
        BookModerationTag.stamp(stack, BookModerationState.fromStatus(stats.status()));
        // Both ways, unlike the status: "put back" has to be distinguishable from "never touched",
        // and the padlock draws the state it reads. Getting this wrong would offer an author
        // "withdraw" on a book already withdrawn, and re-publish it on the next click.
        BookPrivateTag.stamp(stack, stats.isPrivate());
    }

    private static boolean isGreeted(ItemStack stack) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        return cd != null && !cd.isEmpty() && cd.copyTag().getBoolean(NBT_GREETED);
    }

    private static void markGreeted(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(NBT_GREETED, true));
    }
}
