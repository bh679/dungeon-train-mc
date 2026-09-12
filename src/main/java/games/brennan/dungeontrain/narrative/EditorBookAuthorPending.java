package games.brennan.dungeontrain.narrative;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side, per-player "the next book I sign should be credited to this name" — the custom
 * author an editor-plot author typed on the sign screen, delivered ahead of vanilla's own edit-book
 * packet by {@link games.brennan.dungeontrain.net.EditorBookAuthorPacket}.
 *
 * <p>Vanilla's {@code ServerboundEditBookPacket} carries no author field — {@code signBook} always
 * stamps {@code player.getName()} — so the name travels separately and waits here until
 * {@code ServerGamePacketListenerImplSignBookMixin} consumes it. The store is <b>consume-once</b>:
 * the sign intercept {@link #take}s the entry at HEAD on every sign, whichever branch it then goes
 * down, so a name meant for a prop book can never leak onto a later community book, letter or Note.
 * Only the editor-plot branch actually applies it.</p>
 *
 * <p>Untrusted client input: the raw string is stored as received and sanitized + clamped at the
 * point of use ({@link BookSafeText#sanitizeAndClampName}).</p>
 */
public final class EditorBookAuthorPending {

    /** Longest custom author name a signed prop book will carry, in chars (after sanitizing). */
    public static final int MAX_AUTHOR_LENGTH = 32;

    private static final Map<UUID, String> PENDING = new ConcurrentHashMap<>();

    private EditorBookAuthorPending() {}

    /** Remember {@code author} for {@code player}'s next sign; a later call replaces the earlier one. */
    public static void put(UUID player, String author) {
        if (player == null || author == null) return;
        PENDING.put(player, author);
    }

    /** Remove and return the pending name for {@code player}, or {@code null} when none is waiting. */
    public static String take(UUID player) {
        return player == null ? null : PENDING.remove(player);
    }

    /** Drop any pending name — on logout, so a name never outlives the session that typed it. */
    public static void clear(UUID player) {
        if (player != null) PENDING.remove(player);
    }
}
