package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.discord.WorldInfoReporter;
import games.brennan.dungeontrain.narrative.BookSuspensionMessage;
import games.brennan.dungeontrain.narrative.BookUploadSuspensions;
import games.brennan.dungeontrain.net.BookSuspensionSyncPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * Reads the relay's answer to a book upload and turns a refusal into something the player can see.
 *
 * <p>Books go out through the durable {@link RelayOutbox}, which has always been fire-and-forget: the
 * verdict came back long after the book burned in the writer's hand, and nothing looked at it. The
 * relay now refuses a re-upload of a book that same player already uploaded ({@code 409
 * duplicate_book}) and pauses their uploads for a doubling window, refusing everything else in the
 * meantime ({@code 403 suspended}). This class registers a {@link RelayOutbox.ResponseHandler} on
 * {@code /books/submit}, mirrors the window into {@link BookUploadSuspensions}, and tells the writer
 * in chat.</p>
 *
 * <p><b>The window is measured from {@code remainingSec}, never the relay's absolute {@code until}</b>
 * — the two machines' clocks are unrelated, and a client running minutes behind would otherwise think
 * a pause had already lapsed.</p>
 *
 * <p>Every path is no-throw and tolerant of an OLDER relay: a build that answers 200 for a duplicate
 * (as every relay did before this feature) parses to no verdict, and the game behaves exactly as it
 * did. The handler runs on the HTTP client's thread, so the chat line is posted back onto the server
 * thread before touching any player.</p>
 */
public final class SharedBookSubmitResponses {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The watched path — matches what {@code SharedBookReporter} enqueues. */
    static final String PATH = "/books/submit";

    private SharedBookSubmitResponses() {}

    /**
     * One relay refusal: who it was about, how long they are paused, and whether it was the duplicate
     * that STARTED the pause (as opposed to a later book refused by it).
     */
    record Verdict(UUID player, long remainingSec, int strikes, boolean duplicate) {}

    /** Register the {@code /books/submit} handler on the shared outbox. Called once at mod setup. */
    public static void register() {
        RelayOutbox.get().onResponse(PATH, SharedBookSubmitResponses::handle);
    }

    /** Apply one delivered response: mirror the pause, then tell the player if they are online. */
    static void handle(String requestBody, int status, String responseBody) {
        Verdict v = parse(requestBody, status, responseBody);
        if (v == null) return;
        BookUploadSuspensions.apply(v.player(), System.currentTimeMillis() + v.remainingSec() * 1000L, v.strikes());
        announce(v);
    }

    /**
     * The refusal carried by a {@code /books/submit} response, or null when there is nothing to say —
     * an accepted book, a network failure ({@code status < 0}), an unreadable body, an older relay, or
     * a response whose request we cannot attribute to a player.
     */
    static Verdict parse(String requestBody, int status, String responseBody) {
        if (status != 403 && status != 409) return null;
        JsonObject resp = asObject(responseBody);
        if (resp == null) return null;
        String error = optString(resp, "error");
        if (!"duplicate_book".equals(error) && !"suspended".equals(error)) return null;
        UUID player = playerOf(requestBody);
        if (player == null) return null;
        long remainingSec = optLong(resp, "remainingSec", 0L);
        if (remainingSec <= 0L) return null; // a lapsed window is not news
        return new Verdict(player, remainingSec, (int) optLong(resp, "strikes", 0L),
                "duplicate_book".equals(error));
    }

    /** Post the line onto the server thread; a player who has since logged off simply hears nothing. */
    private static void announce(Verdict v) {
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) return;
            server.execute(() -> {
                try {
                    ServerPlayer player = server.getPlayerList().getPlayer(v.player());
                    if (player == null) return;
                    String locale = WorldInfoReporter.clientLanguage(player);
                    player.sendSystemMessage(v.duplicate()
                            ? BookSuspensionMessage.duplicate(locale, v.remainingSec())
                            : BookSuspensionMessage.blocked(locale, v.remainingSec()));
                    // Carry the window to the screen that has to refuse the next sign.
                    PacketDistributor.sendToPlayer(player,
                            BookSuspensionSyncPacket.of(v.remainingSec(), v.strikes()));
                } catch (Throwable t) {
                    LOGGER.debug("[DungeonTrain] book suspension notice failed: {}", t.toString());
                }
            });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] book suspension notice could not be scheduled: {}", t.toString());
        }
    }

    /** The author uuid the submit was built with — the relay's answer never repeats it back. */
    private static UUID playerOf(String requestBody) {
        JsonObject req = asObject(requestBody);
        String raw = req == null ? null : optString(req, "uuid");
        if (raw == null) return null;
        String hex = raw.replace("-", "").trim();
        if (hex.length() != 32) return null;
        try {
            return UUID.fromString(hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-"
                    + hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-" + hex.substring(20));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static JsonObject asObject(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonElement el = JsonParser.parseString(json);
            return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String optString(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : null;
    }

    private static long optLong(JsonObject o, String key, long fallback) {
        JsonElement el = o.get(key);
        try {
            return el != null && el.isJsonPrimitive() ? el.getAsLong() : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }
}
