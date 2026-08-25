package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.BookKidRejectPacket;
import games.brennan.dungeontrain.net.relay.RelayOutbox;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * Fire-and-forget uploader for a kid-safe tester's "Remove for kids" verdict on a community book —
 * the narrower sibling of the ⚠ report {@link BookReportReporter} carries. The server-side
 * {@link BookKidRejectPacket} handler has already validated the held-stack identity, confirmed the
 * player is on the tester roster, stamped the local {@code dt_book_kid_rejected} tag, and gated on
 * network consent; this method just does the transport to the relay's {@code /books/kidreject}
 * endpoint.
 *
 * <p>Mirrors {@link BookReportReporter}: a Gson-built JSON body handed to the durable
 * {@link RelayOutbox}, persisted and delivered at-least-once. {@code clientTsMs} is stamped here so a
 * verdict delivered hours later still carries when it was actually made — and a re-delivery is
 * idempotent relay-side (a book already rated unsafe answers {@code changed:false} without
 * re-writing), so at-least-once is safe. The whole call is no-throw. Metadata only: identity, never
 * page text and never a free-text reason — the control is one-tap by design.</p>
 */
public final class BookKidRejectReporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    private BookKidRejectReporter() {}

    /** Build and fire the verdict. No-op on any error. */
    public static void report(UUID playerId, String playerName, BookKidRejectPacket p) {
        try {
            if (playerId == null || p == null) return;
            String uuid = playerId.toString().replace("-", "");
            String json = buildPayload(uuid, playerName, p, System.currentTimeMillis()).toString();
            RelayOutbox.get().enqueue("/books/kidreject", json);
            LOGGER.debug("[DungeonTrain] kid-reject ({}:{}) queued to the relay outbox.",
                p.bookType(), p.bookId());
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] kid-reject failed to build: {}", t.toString());
        }
    }

    /** Pure JSON assembly — package-private so the shape can be unit-tested without a running server. */
    static JsonObject buildPayload(String uuid, String playerName, BookKidRejectPacket p, long clientTsMs) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);
        if (playerName != null && !playerName.isEmpty()) body.addProperty("player", playerName);
        // Sent for symmetry with the report payload and as a server-side assertion — the relay 400s
        // anything that isn't 'shared' rather than silently re-rating the wrong kind of book.
        body.addProperty("bookType", p.bookType());
        body.addProperty("bookId", p.bookId());
        body.addProperty("clientTsMs", clientTsMs);
        return body;
    }
}
