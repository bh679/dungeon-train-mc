package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.BookReportPacket;
import games.brennan.dungeontrain.net.relay.RelayOutbox;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * Fire-and-forget uploader for a reader's ⚠ report on a community book — the escalation above the
 * 👎 that {@link BookVoteReporter} carries. The server-side {@link BookReportPacket} handler has
 * already validated the held-stack identity, stamped the local {@code dt_book_reported} tag, and
 * gated on network consent; this method just does the transport to the relay's {@code /books/report}
 * endpoint.
 *
 * <p>Mirrors {@link BookVoteReporter}: a Gson-built JSON body handed to the durable
 * {@link RelayOutbox}, persisted and delivered at-least-once. {@code clientTsMs} is stamped here so
 * a report delivered hours later still carries when it was actually made — and a re-delivery is
 * deduped relay-side per (book, player), so at-least-once is safe. The whole call is no-throw.
 * Metadata only: identity, never page text and never a free-text reason (the control is one-tap by
 * design — the relay's own review is what supplies the categories).</p>
 */
public final class BookReportReporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    private BookReportReporter() {}

    /** Build and fire the report. No-op on any error. */
    public static void report(UUID playerId, String playerName, BookReportPacket p) {
        try {
            if (playerId == null || p == null) return;
            String uuid = playerId.toString().replace("-", "");
            String json = buildPayload(uuid, playerName, p, System.currentTimeMillis()).toString();
            RelayOutbox.get().enqueue("/books/report", json);
            LOGGER.debug("[DungeonTrain] book report ({}:{}) queued to the relay outbox.",
                p.bookType(), p.bookId());
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] book-report report failed to build: {}", t.toString());
        }
    }

    /** Pure JSON assembly — package-private so the shape can be unit-tested without a running server. */
    static JsonObject buildPayload(String uuid, String playerName, BookReportPacket p, long clientTsMs) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);
        if (playerName != null && !playerName.isEmpty()) body.addProperty("player", playerName);
        // Sent for symmetry with the vote payload and as a server-side assertion — the relay 400s
        // anything that isn't 'shared' rather than silently recording a report on the wrong kind.
        body.addProperty("bookType", p.bookType());
        body.addProperty("bookId", p.bookId());
        body.addProperty("clientTsMs", clientTsMs);
        return body;
    }
}
