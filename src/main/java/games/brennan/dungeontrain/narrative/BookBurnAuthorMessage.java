package games.brennan.dungeontrain.narrative;

import games.brennan.dungeontrain.event.BookAuthorChatMirror;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.WrittenBookContent;

/**
 * The opt-in "The book by X burns" chat line, printed the moment a burnable Dungeon Train book
 * ignites ({@code StartingBookEvents.onEntityJoinLevel}).
 *
 * <p>Every burnable book type funnels through that one ignition point — a book closed after reading,
 * a book thrown away unread, a death drop, a signed community contribution — so this covers all of
 * them and nothing else: a vanilla or foreign written book never burns and never reaches here.</p>
 *
 * <p>The author is the plain string on the stack's {@link WrittenBookContent} (the name already shown
 * on the book's cover); a book with no written content or a blank author is silently skipped.</p>
 *
 * <p>Recipients are the players within {@link #HEARING_RANGE} blocks who have the option ON in
 * {@link BookAuthorChatMirror} — not just the thrower, because plenty of burns have no owner at all
 * (a signed contribution copy, a lectern letter) and because a burn is a thing bystanders watch
 * happen.</p>
 */
public final class BookBurnAuthorMessage {

    /** Blocks from the burning book within which a player is told who wrote it. */
    private static final double HEARING_RANGE = 32.0;

    private BookBurnAuthorMessage() {}

    /**
     * Announce {@code stack}'s author to nearby opted-in players. No-op when the book carries no
     * author, or when nobody nearby has the option on.
     */
    public static void announce(ServerLevel level, ItemEntity item, ItemStack stack) {
        if (level == null || item == null || stack == null || stack.isEmpty()) return;
        WrittenBookContent content = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (content == null) return;
        String author = content.author();
        if (author == null || author.isBlank()) return;

        Component message = Component.translatable("chat.dungeontrain.book_burns_author",
                clickToCopy(author))
            .withStyle(ChatFormatting.GRAY);

        double rangeSqr = HEARING_RANGE * HEARING_RANGE;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(item) > rangeSqr) continue;
            if (!BookAuthorChatMirror.isEnabled(player)) continue;
            player.sendSystemMessage(message);
        }
    }

    /**
     * The author's name as click-to-copy text: gold and underlined — this repo's signal that chat
     * text does something when clicked (see {@code CheatDetectionEvents}'s fix links) — carrying a
     * {@link ClickEvent.Action#COPY_TO_CLIPBOARD} of the raw name and a hover saying so. The
     * clipboard write is vanilla client behaviour; the server only describes the action.
     */
    private static Component clickToCopy(String author) {
        return Component.literal(author).withStyle(style -> style
            .withColor(ChatFormatting.GOLD)
            .withUnderlined(true)
            .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, author))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                Component.translatable("chat.dungeontrain.book_burns_author.copy"))));
    }
}
