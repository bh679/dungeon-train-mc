package games.brennan.dungeontrain.client.localization.edit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.chat.RelayChatClient;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Reads this player's own submission history from {@code GET /<CAP>/translations/mine}.
 *
 * <p>Own uuid only, so consent-gated like every other uuid-bearing call. A failed fetch leaves
 * the list showing whatever is still in the local outbox rather than an error — the player's
 * queued work is real information even when the relay is unreachable.</p>
 */
public final class TranslationSubmissionsClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(8))
        .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);
    private static final int LIMIT = 50;

    private TranslationSubmissionsClient() {}

    /**
     * Fetch the history and hand it to {@code onResult} on the render thread. Calls back with an
     * empty list rather than failing, so the screen always has something to render.
     */
    public static void fetch(Consumer<List<TranslationSubmission>> onResult) {
        Minecraft mc = Minecraft.getInstance();
        UUID uuid = mc != null && mc.getUser() != null ? mc.getUser().getProfileId() : null;
        if (uuid == null || !RelayChatClient.canConnect()) {
            deliver(mc, onResult, List.of());
            return;
        }
        try {
            String url = DungeonTrain.relayBaseUrl() + "/translations/mine?uuid="
                + uuid.toString().replace("-", "") + "&limit=" + LIMIT;
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .whenComplete((resp, err) -> {
                    List<TranslationSubmission> out = List.of();
                    try {
                        if (err == null && resp != null && resp.statusCode() / 100 == 2) {
                            out = parse(resp.body());
                        } else {
                            LOGGER.debug("[DungeonTrain] Translations: history fetch failed — {}",
                                err != null ? err.toString()
                                    : "HTTP " + (resp == null ? "?" : resp.statusCode()));
                        }
                    } catch (Throwable t) {
                        LOGGER.debug("[DungeonTrain] Translations: history parse failed — {}",
                            t.toString());
                    }
                    deliver(mc, onResult, out);
                });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] Translations: history request could not start — {}",
                t.toString());
            deliver(mc, onResult, List.of());
        }
    }

    private static void deliver(Minecraft mc, Consumer<List<TranslationSubmission>> onResult,
                                List<TranslationSubmission> result) {
        if (mc == null) {
            return;
        }
        mc.execute(() -> onResult.accept(result));
    }

    /** Parse {@code {ok, submissions:[{ts, locale, translator, units, approved, …}]}}. */
    static List<TranslationSubmission> parse(String body) {
        List<TranslationSubmission> out = new ArrayList<>();
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) {
            return out;
        }
        JsonObject obj = root.getAsJsonObject();
        if (!obj.has("ok") || !obj.get("ok").getAsBoolean()
            || !obj.has("submissions") || !obj.get("submissions").isJsonArray()) {
            return out;
        }
        for (JsonElement el : obj.getAsJsonArray("submissions")) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject row = el.getAsJsonObject();
            out.add(new TranslationSubmission(
                longOf(row, "ts"), stringOf(row, "locale"), stringOf(row, "translator"),
                intOf(row, "units"), intOf(row, "approved"), intOf(row, "pending"),
                intOf(row, "flagged"), intOf(row, "rejected"), false));
        }
        return out;
    }

    private static String stringOf(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    private static int intOf(JsonObject o, String key) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static long longOf(JsonObject o, String key) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsLong() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }
}
