package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.relay.RelayOutbox;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * Fire-and-forget uploader for a reader's ⚠ report on a player-written lectern letter series — the
 * lectern sibling of {@link BookReportReporter}. The server-side {@code LecternReportPacket} handler has
 * already checked reach and the lectern's book, recorded the report in the world, and gated on network
 * consent; this just hands the relay's {@code /narratives/report} body to the durable {@link RelayOutbox}.
 *
 * <p>The report is against the SERIES: {@code letterIndex} only says which letter the reader was on, for
 * the operator's review. The relay dedupes per (series, player), so at-least-once delivery is safe.
 * Metadata only, never page text. No-throw.</p>
 */
public final class NarrativeReportReporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    private NarrativeReportReporter() {}

    /** Build and fire the report. No-op on any error. */
    public static void report(UUID playerId, String playerName, String seriesId, int letterIndex) {
        try {
            if (playerId == null || seriesId == null || seriesId.isEmpty()) return;
            String uuid = playerId.toString().replace("-", "");
            String json = buildPayload(uuid, playerName, seriesId, letterIndex, System.currentTimeMillis()).toString();
            RelayOutbox.get().enqueue("/narratives/report", json);
            LOGGER.debug("[DungeonTrain] narrative series report ({} letter {}) queued to the relay outbox.",
                seriesId, letterIndex);
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] narrative series report failed to build: {}", t.toString());
        }
    }

    /** Pure JSON assembly — package-private so the shape can be unit-tested without a running server. */
    static JsonObject buildPayload(String uuid, String playerName, String seriesId, int letterIndex,
                                   long clientTsMs) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);
        if (playerName != null && !playerName.isEmpty()) body.addProperty("player", playerName);
        body.addProperty("seriesId", seriesId);
        if (letterIndex > 0) body.addProperty("letterIndex", letterIndex);
        body.addProperty("clientTsMs", clientTsMs);
        return body;
    }
}
