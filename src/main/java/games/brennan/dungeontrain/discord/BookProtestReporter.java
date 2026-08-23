package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.BookProtestPacket;
import games.brennan.dungeontrain.net.relay.RelayOutbox;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * Fire-and-forget uploader for an author's protest against the verdict on their own book — the
 * mirror image of {@link BookReportReporter}, and the opposite in effect: a reader's report can pull
 * a book from the pool, an author's protest changes nothing at all and only asks a person to look
 * again. The server-side {@link BookProtestPacket} handler has already validated the held-stack
 * identity, stamped {@code dt_book_protested}, and gated on network consent; this is transport only.
 *
 * <p>The relay is where authorship is actually checked — it compares this uuid against the book's
 * stored author and 403s a mismatch. The client cannot make that check (it never learns a book's
 * author uuid), which is precisely why the check lives there.</p>
 *
 * <p>Handed to the durable {@link RelayOutbox}, so it survives a restart and is delivered
 * at-least-once; the relay dedupes per (book, author), so a re-delivery is free. Metadata only —
 * never page text, and no free-text grounds: the control is one gesture, and what the author is
 * objecting to is the verdict the relay already holds.</p>
 */
public final class BookProtestReporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    private BookProtestReporter() {}

    /** Build and fire the protest. No-op on any error. */
    public static void protest(UUID playerId, String playerName, BookProtestPacket p) {
        try {
            if (playerId == null || p == null) return;
            String uuid = playerId.toString().replace("-", "");
            String json = buildPayload(uuid, playerName, p, System.currentTimeMillis()).toString();
            RelayOutbox.get().enqueue("/books/protest", json);
            LOGGER.debug("[DungeonTrain] book protest ({}:{}) queued to the relay outbox.",
                p.bookType(), p.bookId());
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] book-protest report failed to build: {}", t.toString());
        }
    }

    /** Pure JSON assembly — package-private so the shape can be unit-tested without a running server. */
    static JsonObject buildPayload(String uuid, String playerName, BookProtestPacket p, long clientTsMs) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);
        if (playerName != null && !playerName.isEmpty()) body.addProperty("player", playerName);
        body.addProperty("bookType", p.bookType());
        body.addProperty("bookId", p.bookId());
        body.addProperty("clientTsMs", clientTsMs);
        return body;
    }
}
