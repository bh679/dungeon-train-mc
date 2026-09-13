package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.narrative.EditorBookAuthorPending;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.EditorBookAuthorPacket;

/**
 * Client-side send path for the custom author an editor-plot author signs a prop book as — see
 * {@link games.brennan.dungeontrain.mixin.client.BookEditScreenAuthorMixin}.
 *
 * <p>Nothing is remembered between signs: the author line starts blank on every sign screen so a
 * name is always typed deliberately for the book in hand.</p>
 */
public final class EditorBookAuthorClient {

    private EditorBookAuthorClient() {}

    /** Max chars the author line accepts — the server clamps to the same bound. */
    public static int maxLength() {
        return EditorBookAuthorPending.MAX_AUTHOR_LENGTH;
    }

    /**
     * Send {@code author} ahead of vanilla's edit-book packet. Called from the sign screen's
     * {@code saveChanges(true)} HEAD while inside an editor plot; blank names are never sent.
     */
    public static void sendForNextSign(String author) {
        if (author == null) return;
        String trimmed = author.trim();
        if (trimmed.isEmpty()) return;
        DungeonTrainNet.sendToServer(new EditorBookAuthorPacket(trimmed));
    }
}
