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

    /**
     * Fetch one submission's units — the drill-down behind picking a row in the "sent in" view.
     *
     * <p>Returns the player's own text back with the one thing they do not have: the per-unit
     * verdict. Calls back with an empty list on any failure, so the pane says "nothing" rather
     * than hanging on a spinner.</p>
     */
    public static void fetchUnits(long ts, Consumer<List<SentUnit>> onResult) {
        Minecraft mc = Minecraft.getInstance();
        UUID uuid = mc != null && mc.getUser() != null ? mc.getUser().getProfileId() : null;
        if (uuid == null || ts <= 0 || !RelayChatClient.canConnect()) {
            deliverUnits(mc, onResult, List.of());
            return;
        }
        try {
            String url = DungeonTrain.relayBaseUrl() + "/translations/mine?uuid="
                + uuid.toString().replace("-", "") + "&ts=" + ts;
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .whenComplete((resp, err) -> {
                    List<SentUnit> out = List.of();
                    try {
                        if (err == null && resp != null && resp.statusCode() / 100 == 2) {
                            out = parseUnits(resp.body());
                        }
                    } catch (Throwable t) {
                        LOGGER.debug("[DungeonTrain] Translations: unit parse failed — {}", t.toString());
                    }
                    deliverUnits(mc, onResult, out);
                });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] Translations: unit request could not start — {}", t.toString());
            deliverUnits(mc, onResult, List.of());
        }
    }

    /**
     * One string inside a submission, as the relay has it now.
     *
     * <p>{@code note} is what a reviewer wrote back about this line — empty when nobody has. A
     * client older than the field simply never sees one; a relay older than it sends none.</p>
     */
    public record SentUnit(String type, String namespace, String unitId, String source,
                           String value, String flag, String note, String noteBy, long noteTs) {

        public boolean isBook() {
            return "book".equals(type);
        }

        public boolean hasNote() {
            return note != null && !note.isBlank();
        }
    }

    /**
     * A reviewer's reply, from the flat {@code ?notes=1} view — every note written to this player
     * across all of their submissions, which is what the editor marks its rows with.
     */
    public record ReviewNote(String type, String unitId, String source, String value, String flag,
                             String note, String noteBy, long noteTs) {

        /** The key both editor lookups use: type and id together, since the two bodies collide. */
        public String key() {
            return ("book".equals(type) ? "book|" : "lang|") + unitId;
        }
    }

    static List<SentUnit> parseUnits(String body) {
        List<SentUnit> out = new ArrayList<>();
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) {
            return out;
        }
        JsonObject obj = root.getAsJsonObject();
        if (!obj.has("ok") || !obj.get("ok").getAsBoolean()
            || !obj.has("units") || !obj.get("units").isJsonArray()) {
            return out;
        }
        for (JsonElement el : obj.getAsJsonArray("units")) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject row = el.getAsJsonObject();
            out.add(new SentUnit(stringOf(row, "unitType"), stringOf(row, "namespace"),
                stringOf(row, "unitId"), stringOf(row, "source"), stringOf(row, "value"),
                stringOf(row, "flag"), stringOf(row, "note"), stringOf(row, "noteBy"),
                longOf(row, "noteTs")));
        }
        return out;
    }

    /**
     * Every reply a reviewer has written to this player, newest first.
     *
     * <p>One request per editor session covers all three places a reply shows up — the marker in
     * the working list, the text above the editor, and the count on the way in — because they are
     * three views of one small answer, not three questions.</p>
     */
    public static void fetchNotes(Consumer<List<ReviewNote>> onResult) {
        Minecraft mc = Minecraft.getInstance();
        UUID uuid = mc != null && mc.getUser() != null ? mc.getUser().getProfileId() : null;
        if (uuid == null || !RelayChatClient.canConnect()) {
            deliverNotes(mc, onResult, List.of());
            return;
        }
        try {
            String url = DungeonTrain.relayBaseUrl() + "/translations/mine?uuid="
                + uuid.toString().replace("-", "") + "&notes=1&limit=" + LIMIT;
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .whenComplete((resp, err) -> {
                    List<ReviewNote> out = List.of();
                    try {
                        if (err == null && resp != null && resp.statusCode() / 100 == 2) {
                            out = parseNotes(resp.body());
                        }
                    } catch (Throwable t) {
                        LOGGER.debug("[DungeonTrain] Translations: note parse failed — {}", t.toString());
                    }
                    deliverNotes(mc, onResult, out);
                });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] Translations: note request could not start — {}", t.toString());
            deliverNotes(mc, onResult, List.of());
        }
    }

    /** Parse {@code {ok, notes:[{unitType, unitId, note, noteBy, noteTs, flag, …}]}}. */
    static List<ReviewNote> parseNotes(String body) {
        List<ReviewNote> out = new ArrayList<>();
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) {
            return out;
        }
        JsonObject obj = root.getAsJsonObject();
        if (!obj.has("ok") || !obj.get("ok").getAsBoolean()
            || !obj.has("notes") || !obj.get("notes").isJsonArray()) {
            return out;
        }
        for (JsonElement el : obj.getAsJsonArray("notes")) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject row = el.getAsJsonObject();
            String note = stringOf(row, "note");
            if (note.isBlank()) {
                continue; // a cleared note is not a reply; the relay filters these, this is the lock
            }
            out.add(new ReviewNote(stringOf(row, "unitType"), stringOf(row, "unitId"),
                stringOf(row, "source"), stringOf(row, "value"), stringOf(row, "flag"),
                note, stringOf(row, "noteBy"), longOf(row, "noteTs")));
        }
        return out;
    }

    private static void deliverNotes(Minecraft mc, Consumer<List<ReviewNote>> onResult,
                                     List<ReviewNote> result) {
        if (mc == null) {
            return;
        }
        mc.execute(() -> onResult.accept(result));
    }

    private static void deliverUnits(Minecraft mc, Consumer<List<SentUnit>> onResult,
                                     List<SentUnit> result) {
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
                intOf(row, "flagged"), intOf(row, "rejected"), false, false));
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
