package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.narrative.EditorBookAuthorPending;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.EditorBookAuthorPacket;

/**
 * Client-side memory for the custom author an editor-plot author signs prop books as — see
 * {@link games.brennan.dungeontrain.mixin.client.BookEditScreenAuthorMixin}.
 *
 * <p>An author stocking a carriage's chest signs several books in a row; the last name they typed
 * is kept for the session so each new sign screen opens pre-filled with it (their real name until
 * they change it). Not persisted — a fresh client starts from the player name again.</p>
 */
public final class EditorBookAuthorClient {

    /** Last custom author sent for a prop book this session, or {@code null} if none yet. */
    private static String lastAuthor;

    private EditorBookAuthorClient() {}

    /** The name to pre-fill the author line with: the last one sent, else {@code fallback}. */
    public static String initialAuthor(String fallback) {
        String last = lastAuthor;
        return last == null || last.isBlank() ? fallback : last;
    }

    /** Max chars the author line accepts — the server clamps to the same bound. */
    public static int maxLength() {
        return EditorBookAuthorPending.MAX_AUTHOR_LENGTH;
    }

    /**
     * Send {@code author} ahead of vanilla's edit-book packet and remember it for the next sign.
     * Called from the sign screen's {@code saveChanges(true)} HEAD while inside an editor plot.
     */
    public static void sendForNextSign(String author) {
        if (author == null) return;
        String trimmed = author.trim();
        if (trimmed.isEmpty()) return;
        lastAuthor = trimmed;
        DungeonTrainNet.sendToServer(new EditorBookAuthorPacket(trimmed));
    }
}
