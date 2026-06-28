package games.brennan.dungeontrain.client.chat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.dungeontrain.DungeonTrain;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Client-side bridge from the title-screen chat panel to the relay (see dp-relay {@code chat.js}).
 *
 * <p>There is no Minecraft server at the main menu, so this talks to the relay directly over HTTPS
 * with the build's dev/live capability URL ({@link DungeonTrain#relayBaseUrl()}), keyed by the
 * launcher's Minecraft UUID. Every call is gated on the DiscordPresence network-access consent
 * ({@link DiscordPresenceClientConfig#isGranted()}) and runs fully async — nothing blocks the render
 * thread, and any failure degrades to {@code null} (panel shows "couldn't reach Discord") rather than
 * throwing.</p>
 */
public final class RelayChatClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);

    private RelayChatClient() {}

    /** True when the panel is allowed to talk to the relay at all (consent granted). */
    public static boolean canConnect() {
        return DiscordPresenceClientConfig.isGranted();
    }

    /**
     * Fetch the player's full thread backscroll. Resolves to {@code null} on any error (no consent,
     * no thread yet, network/parse failure) — the panel renders that as an unobtrusive notice.
     */
    public static CompletableFuture<ChatHistory> fetchHistory(UUID uuid) {
        if (uuid == null || !canConnect()) {
            return CompletableFuture.completedFuture(null);
        }
        URI uri = URI.create(DungeonTrain.relayBaseUrl() + "/chat/history?uuid=" + noDashes(uuid));
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(RelayChatClient::parseHistory)
                .exceptionally(t -> {
                    LOGGER.debug("Menu chat: history fetch failed: {}", t.toString());
                    return null;
                });
    }

    /**
     * Tell the relay the player has seen {@code messageId} in their thread, so it adds a 👀 reaction
     * on Discord. Fire-and-forget; {@code channelId} is the thread id from the loaded history.
     */
    public static void markSeen(UUID uuid, String channelId, String messageId) {
        if (uuid == null || channelId == null || messageId == null || messageId.isBlank() || !canConnect()) {
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("uuid", noDashes(uuid));
        body.addProperty("channelId", channelId);
        body.addProperty("messageId", messageId);
        HttpRequest req = HttpRequest.newBuilder(URI.create(DungeonTrain.relayBaseUrl() + "/chat/seen"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .exceptionally(t -> {
                    LOGGER.debug("Menu chat: seen mark failed: {}", t.toString());
                    return null;
                });
    }

    /**
     * Post {@code content} into the player's own Discord thread (relay {@code POST /<CAP>/chat/send}),
     * which forwards it through the webhook as the player. Async; resolves to {@code true} only on a 2xx
     * — the {@link ChatOutbox} keeps a message queued until this confirms delivery (at-least-once). Any
     * error (no consent, no thread yet, network failure) resolves to {@code false} so it stays queued.
     */
    public static CompletableFuture<Boolean> sendMessage(UUID uuid, String content) {
        if (uuid == null || content == null || content.isBlank() || !canConnect()) {
            return CompletableFuture.completedFuture(false);
        }
        JsonObject body = new JsonObject();
        body.addProperty("uuid", noDashes(uuid));
        body.addProperty("content", content);
        HttpRequest req = HttpRequest.newBuilder(URI.create(DungeonTrain.relayBaseUrl() + "/chat/send"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> resp.statusCode() >= 200 && resp.statusCode() < 300)
                .exceptionally(t -> {
                    LOGGER.debug("Menu chat: send failed: {}", t.toString());
                    return false;
                });
    }

    private static String noDashes(UUID uuid) {
        return uuid.toString().replace("-", "");
    }

    // --- parsing (best-effort; any failure → null) ---

    private static ChatHistory parseHistory(HttpResponse<String> resp) {
        if (resp == null || resp.statusCode() < 200 || resp.statusCode() >= 300) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            String threadId = optString(root, "threadId");
            boolean hasMore = root.has("hasMore") && root.get("hasMore").getAsBoolean();
            List<ChatHistory.Message> messages = new ArrayList<>();
            if (root.has("messages") && root.get("messages").isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray("messages")) {
                    if (el != null && el.isJsonObject()) {
                        ChatHistory.Message m = parseMessage(el.getAsJsonObject());
                        if (m != null) {
                            messages.add(m);
                        }
                    }
                }
            }
            return new ChatHistory(threadId, messages, hasMore);
        } catch (Exception e) {
            LOGGER.debug("Menu chat: could not parse history: {}", e.toString());
            return null;
        }
    }

    private static ChatHistory.Message parseMessage(JsonObject o) {
        String id = optString(o, "id");
        if (id == null) {
            return null;
        }
        return new ChatHistory.Message(
                id,
                optString(o, "authorId"),
                optString(o, "authorName"),
                optBool(o, "isBot"),
                optBool(o, "isWebhook"),
                optString(o, "content"),
                parseEmbeds(o),
                parseAttachments(o),
                optString(o, "timestamp"),
                optBool(o, "seen"));
    }

    private static List<ChatHistory.Embed> parseEmbeds(JsonObject o) {
        List<ChatHistory.Embed> out = new ArrayList<>();
        if (o.has("embeds") && o.get("embeds").isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray("embeds")) {
                if (el == null || !el.isJsonObject()) {
                    continue;
                }
                JsonObject e = el.getAsJsonObject();
                List<ChatHistory.Field> fields = new ArrayList<>();
                if (e.has("fields") && e.get("fields").isJsonArray()) {
                    for (JsonElement fe : e.getAsJsonArray("fields")) {
                        if (fe != null && fe.isJsonObject()) {
                            JsonObject f = fe.getAsJsonObject();
                            fields.add(new ChatHistory.Field(optString(f, "name"), optString(f, "value")));
                        }
                    }
                }
                Integer color = e.has("color") && e.get("color").isJsonPrimitive() && !e.get("color").isJsonNull()
                        ? e.get("color").getAsInt() : null;
                out.add(new ChatHistory.Embed(optString(e, "title"), optString(e, "description"), color, fields));
            }
        }
        return out;
    }

    private static List<ChatHistory.Attachment> parseAttachments(JsonObject o) {
        List<ChatHistory.Attachment> out = new ArrayList<>();
        if (o.has("attachments") && o.get("attachments").isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray("attachments")) {
                if (el == null || !el.isJsonObject()) {
                    continue;
                }
                JsonObject a = el.getAsJsonObject();
                Long size = a.has("size") && a.get("size").isJsonPrimitive() && !a.get("size").isJsonNull()
                        ? a.get("size").getAsLong() : null;
                out.add(new ChatHistory.Attachment(
                        optString(a, "filename"), size, optString(a, "contentType"), optString(a, "url")));
            }
        }
        return out;
    }

    private static String optString(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    private static boolean optBool(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsBoolean();
    }
}
