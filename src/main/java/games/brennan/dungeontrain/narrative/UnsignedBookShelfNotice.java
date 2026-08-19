package games.brennan.dungeontrain.narrative;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.WritableBookContent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends a gray {@link UnsignedBookMessage} line when a player puts a book &amp; quill they have written in —
 * but never signed — into a chiseled bookshelf.
 *
 * <p>Called from {@code mixin.ChiseledBookShelfBlockUnsignedBookMixin}, which injects at the head of vanilla's
 * private {@code ChiseledBookShelfBlock#addBook}. That is the one point on the insert path where the book is
 * known to be going in (rather than a click that takes one out) and the stack is still whole, and it is reached
 * only by a player's own interaction — hoppers and the mod's own shelf writers never pass through it.</p>
 *
 * <p>Flavour only: nothing here changes what happens to the book. It goes into the shelf exactly as it always
 * has, and can be taken straight back out.</p>
 *
 * <p>Silent for anything that isn't a written-in book &amp; quill — a signed written book, a plain book, an
 * enchanted or knowledge book (no {@link DataComponents#WRITABLE_BOOK_CONTENT} at all), and an untouched book
 * &amp; quill whose pages are empty or blank. A blank book carries no words to ignore, so saying the train
 * ignores it would be saying nothing happened when nothing was written.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class UnsignedBookShelfNotice {

    private UnsignedBookShelfNotice() {}

    /**
     * Minimum gap between lines for one player. Filling a shelf is six interactions in a couple of seconds, and
     * six near-identical lines would read as a bug rather than a remark. Matches the drifting-carriage line's
     * own cooldown ({@code SharedCarriageEnterEvents.MESSAGE_COOLDOWN_MS}).
     */
    private static final long MESSAGE_COOLDOWN_MS = 3000L;

    /** When each player was last told, for {@link #MESSAGE_COOLDOWN_MS}. */
    private static final Map<UUID, Long> LAST_MSG_MS = new ConcurrentHashMap<>();

    /**
     * A player is putting {@code stack} into a chiseled bookshelf. Sends the line when the stack is a book &amp;
     * quill with writing in it and the player's cooldown has elapsed; otherwise does nothing.
     */
    public static void onBookShelved(Player player, ItemStack stack) {
        if (!(player instanceof ServerPlayer serverPlayer) || player.level().isClientSide()) return;

        // One component read settles it: no other book type carries WRITABLE_BOOK_CONTENT, signed ones least
        // of all — vanilla signing swaps the component out for WRITTEN_BOOK_CONTENT.
        WritableBookContent content = stack.get(DataComponents.WRITABLE_BOOK_CONTENT);
        if (content == null || !hasWriting(content)) return;

        long now = System.currentTimeMillis();
        Long last = LAST_MSG_MS.get(serverPlayer.getUUID());
        if (last != null && now - last < MESSAGE_COOLDOWN_MS) return;
        LAST_MSG_MS.put(serverPlayer.getUUID(), now);

        serverPlayer.sendSystemMessage(UnsignedBookMessage.random(serverPlayer.getRandom()));
    }

    /**
     * Whether {@code content} holds any actual writing — a page with something other than whitespace on it.
     *
     * <p>Reads {@link Filterable#raw} rather than the filtered text: this decides whether the player wrote
     * anything, and a page that chat filtering would blank is still a page they wrote.</p>
     */
    static boolean hasWriting(WritableBookContent content) {
        for (Filterable<String> page : content.pages()) {
            if (!page.raw().isBlank()) return true;
        }
        return false;
    }

    /** Drop a departed player's cooldown, so the map doesn't accumulate UUIDs across a server's life. */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_MSG_MS.remove(event.getEntity().getUUID());
    }
}
