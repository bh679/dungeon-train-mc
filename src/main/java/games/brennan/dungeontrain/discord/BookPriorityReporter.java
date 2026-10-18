package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.relay.RelayOutbox;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * Fire-and-forget spend of one Priority Book on the author's own approved book —
 * {@code POST /books/credits/spend {uuid, name, id}}. The server-side
 * {@link games.brennan.dungeontrain.net.BookPriorityPacket} handler has already validated the held
 * stack and gated on network consent; this is transport only.
 *
 * <p>The wallet is (uuid, Minecraft name): a credit is granted under the name the donation carried
 * and bound to the uuid on its first spend (relay {@code bookcredits.resolveKey}). At-least-once
 * delivery is safe: a re-sent spend on the same book answers {@code 409 already_priority} and charges
 * nothing, which {@code PriorityBookResponses} reads as done.</p>
 */
public final class BookPriorityReporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The relay route — also the outbox path {@code PriorityBookResponses} watches. */
    public static final String PATH = "/books/credits/spend";

    private BookPriorityReporter() {}

    /** Queue the spend. No-op on any error. */
    public static void spend(UUID playerId, String playerName, String bookId) {
        try {
            if (playerId == null || bookId == null || bookId.isBlank()) return;
            String uuid = playerId.toString().replace("-", "");
            RelayOutbox.get().enqueue(PATH, buildPayload(uuid, playerName, bookId).toString());
            LOGGER.debug("[DungeonTrain] priority-book spend on shared:{} queued to the relay outbox.", bookId);
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] priority-book spend failed to build: {}", t.toString());
        }
    }

    /** Pure JSON assembly — package-private so the shape can be unit-tested. */
    static JsonObject buildPayload(String uuid, String playerName, String bookId) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);
        body.addProperty("name", playerName == null ? "" : playerName);
        body.addProperty("id", bookId);
        return body;
    }
}
