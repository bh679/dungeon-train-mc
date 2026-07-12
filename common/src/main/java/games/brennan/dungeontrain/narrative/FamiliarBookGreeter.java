package games.brennan.dungeontrain.narrative;

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
 * "a familiar book…" line reporting how the book is doing in the wild (see {@link FamiliarBookMessage}).
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
        if (isGreeted(stack)) return;                        // this exact copy already greeted
        if (!NetworkConsentMirror.isGranted(player)) return; // the lookup sends the player's uuid
        int id = idOpt.getAsInt();
        UUID uuid = player.getUUID();

        BookStatsClient.fetch(id, uuid, stats -> {
            if (!stats.isAuthor()) return;
            // Back onto the server thread before touching the player / stack.
            player.server.execute(() -> {
                if (player.hasDisconnected()) return;
                if (isGreeted(stack)) return; // lost a race with a concurrent equip — show once
                markGreeted(stack);
                player.sendSystemMessage(FamiliarBookMessage.build(stats, player.getRandom()));
            });
        });
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
