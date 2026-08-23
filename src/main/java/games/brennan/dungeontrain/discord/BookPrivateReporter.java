package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.BookPrivatePacket;
import games.brennan.dungeontrain.net.relay.RelayOutbox;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * Fire-and-forget uploader for an author withdrawing their own book from circulation, or putting it
 * back. The server-side {@link BookPrivatePacket} handler has already validated the held-stack
 * identity, stamped {@code dt_book_private}, and gated on network consent; this is transport only.
 *
 * <p>Authorship is checked relay-side (403 on a mismatch), for the same reason it is for a protest:
 * the client never learns a book's author uuid, so it cannot make that check itself.</p>
 *
 * <p>Unlike a report this is a VALUE, not an event, and that changes what at-least-once delivery
 * means. The payload carries the desired end state rather than "toggle", so a re-delivered message
 * lands the author where they asked to be instead of flipping them back — the outbox can retry, and
 * duplicate delivery is idempotent by construction.</p>
 *
 * <p>The relay field is named {@code id} rather than {@code bookId}: {@code /books/private} takes a
 * pool id like {@code /books/stats} and {@code /books/credits/spend} do, not the (bookType, bookId)
 * pair the vote and report routes take.</p>
 */
public final class BookPrivateReporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    private BookPrivateReporter() {}

    /** Build and fire the withdraw/restore. No-op on any error. */
    public static void setPrivate(UUID playerId, String playerName, BookPrivatePacket p) {
        try {
            if (playerId == null || p == null) return;
            String uuid = playerId.toString().replace("-", "");
            String json = buildPayload(uuid, playerName, p, System.currentTimeMillis()).toString();
            RelayOutbox.get().enqueue("/books/private", json);
            LOGGER.debug("[DungeonTrain] book private={} ({}:{}) queued to the relay outbox.",
                p.makePrivate(), p.bookType(), p.bookId());
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] book-private report failed to build: {}", t.toString());
        }
    }

    /** Pure JSON assembly — package-private so the shape can be unit-tested without a running server. */
    static JsonObject buildPayload(String uuid, String playerName, BookPrivatePacket p, long clientTsMs) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);
        if (playerName != null && !playerName.isEmpty()) body.addProperty("player", playerName);
        // `id`, not `bookId` — see the class note.
        body.addProperty("id", p.bookId());
        body.addProperty("private", p.makePrivate());
        body.addProperty("clientTsMs", clientTsMs);
        return body;
    }
}
