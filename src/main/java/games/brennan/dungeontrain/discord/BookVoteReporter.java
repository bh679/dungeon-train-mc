package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.BookVotePacket;
import games.brennan.dungeontrain.net.relay.RelayOutbox;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * Fire-and-forget uploader for a player's 👍/👎 book vote — the write half of the vote-weighted
 * book selection loop. The server-side {@link BookVotePacket} handler has already validated the
 * held-stack identity, stamped the local {@code dt_book_vote} tag, and gated on network consent;
 * this method just does the transport to the relay's {@code /books/vote} endpoint.
 *
 * <p>Mirrors {@link BookReadReporter}: a Gson-built JSON body handed to the durable
 * {@link RelayOutbox}, persisted and delivered at-least-once. {@code clientTsMs} is stamped here so
 * the relay's last-write-wins can order re-deliveries correctly even when the outbox drains hours
 * later or out of order — and a same-vote re-delivery is deduped relay-side, so at-least-once is
 * safe. The whole call is no-throw. Metadata only: identity + direction, never page text.</p>
 */
public final class BookVoteReporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    private BookVoteReporter() {}

    /** Build and fire the vote report. No-op on any error. */
    public static void report(UUID playerId, String playerName, BookVotePacket p) {
        try {
            if (playerId == null || p == null) return;
            String uuid = playerId.toString().replace("-", "");
            String json = buildPayload(uuid, playerName, p, System.currentTimeMillis()).toString();
            RelayOutbox.get().enqueue("/books/vote", json);
            LOGGER.debug("[DungeonTrain] book vote ({} on {}:{}) queued to the relay outbox.",
                p.vote(), p.bookType(), p.bookId());
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] book-vote report failed to build: {}", t.toString());
        }
    }

    /** Pure JSON assembly — package-private so the shape can be unit-tested without a running server. */
    static JsonObject buildPayload(String uuid, String playerName, BookVotePacket p, long clientTsMs) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);
        if (playerName != null && !playerName.isEmpty()) body.addProperty("player", playerName);
        body.addProperty("bookType", p.bookType());
        body.addProperty("bookId", p.bookId());
        body.addProperty("vote", p.vote());
        if (("random".equals(p.bookType()) || "starting".equals(p.bookType())) && p.variantIndex() >= 0) {
            body.addProperty("variantIndex", p.variantIndex());
        }
        body.addProperty("clientTsMs", clientTsMs);
        return body;
    }
}
