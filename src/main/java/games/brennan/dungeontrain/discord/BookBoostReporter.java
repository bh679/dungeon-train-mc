package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.relay.RelayOutbox;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * Fire-and-forget boost of SOMEBODY ELSE's approved community book with one of the reader's Priority
 * Books: {@code POST /books/credits/boost {uuid, name, id}}. The reader-side sibling of
 * {@link BookPriorityReporter}; the server-side {@link games.brennan.dungeontrain.net.BookBoostPacket}
 * handler has already validated the held stack and gated on network consent, so this is transport only.
 *
 * <p>Same wallet as a spend (uuid, Minecraft name); a boost costs a tenth of a Priority Book. A reader
 * may boost the same book again, so each boost carries its own {@code nonce}: the outbox is
 * at-least-once and re-sends the stored body unchanged, and the relay answers a nonce it has already
 * charged as a success that charges nothing ({@code repeat: true}).</p>
 */
public final class BookBoostReporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The relay route, also the outbox path {@code PriorityBookResponses} watches. */
    public static final String PATH = "/books/credits/boost";

    private BookBoostReporter() {}

    /** Queue the boost. No-op on any error. */
    public static void boost(UUID playerId, String playerName, String bookId) {
        try {
            if (playerId == null || bookId == null || bookId.isBlank()) return;
            String uuid = playerId.toString().replace("-", "");
            String nonce = UUID.randomUUID().toString().replace("-", "");
            RelayOutbox.get().enqueue(PATH, buildPayload(uuid, playerName, bookId, nonce).toString());
            LOGGER.debug("[DungeonTrain] priority-book boost on shared:{} queued to the relay outbox.", bookId);
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] priority-book boost failed to build: {}", t.toString());
        }
    }

    /**
     * Pure JSON assembly: a spend's body plus {@code nonce}. Package-private so the shape can be
     * unit-tested.
     */
    static JsonObject buildPayload(String uuid, String playerName, String bookId, String nonce) {
        JsonObject body = BookPriorityReporter.buildPayload(uuid, playerName, bookId);
        body.addProperty("nonce", nonce);
        return body;
    }
}
