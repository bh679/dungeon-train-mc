package games.brennan.dungeontrain.client.videos;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Off-thread reader for the relay's anonymous {@code GET /<CAP>/videos} — the same shape of client
 * as {@code OfficialLinksFetcher}: own {@link HttpClient}, no-throw, best-effort. Results land in
 * {@link VideoCatalog}; the open {@link VideosScreen}, if any, is told on the render thread.
 *
 * <p>Every row is validated field by field and a bad row is <b>dropped, not fatal</b>: one
 * malformed entry on the relay must not blank the whole page.</p>
 */
public final class VideoCatalogFetcher {

    private static final Logger LOGGER = LogUtils.getLogger();

    // HTTP/1.1 pinned for the same reason as OfficialLinksFetcher: the relay is a cleartext-capable
    // Node server and Java's HTTP/2 client can't h2c-upgrade over plain http:// (local testing).
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    /** Bound on a single title / channel string; the relay clamps at 300 and this is belt to that brace. */
    private static final int MAX_TEXT = 300;
    private static final int MAX_URL = 500;

    private VideoCatalogFetcher() {}

    /** Fetch the catalogue off-thread. No-throw. */
    static void fetchAsync() {
        try {
            // A kid-mode client asks for the kid list: the relay drops what adults (or enough kids)
            // have flagged as not safe for kids. Nothing else about the player goes with it.
            String url = DungeonTrain.relayBaseUrl() + "/videos"
                    + (ClientDisplayConfig.getContentMode().isKid() ? "?mode=kid" : "");
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> {
                        try {
                            if (err != null) {
                                LOGGER.debug("[DungeonTrain] videos fetch failed: {}", err.toString());
                                fail();
                                return;
                            }
                            if (resp.statusCode() / 100 != 2) {
                                LOGGER.debug("[DungeonTrain] videos fetch -> HTTP {}", resp.statusCode());
                                fail();
                                return;
                            }
                            List<VideoEntry> parsed = parse(resp.body());
                            if (parsed == null) {
                                fail();
                                return;
                            }
                            VideoCatalog.accept(parsed);
                            LOGGER.info("[DungeonTrain] videos catalogue loaded from relay ({} rows)", parsed.size());
                            notifyScreen();
                        } catch (Throwable t) {
                            LOGGER.debug("[DungeonTrain] videos parse failed: {}", t.toString());
                            fail();
                        }
                    });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] videos request failed to start: {}", t.toString());
            fail();
        }
    }

    private static void fail() {
        VideoCatalog.markFailed();
        notifyScreen();
    }

    /** All Minecraft state is touched on the render thread — this runs on the HTTP completion thread. */
    private static void notifyScreen() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.execute(() -> {
            if (mc.screen instanceof VideosScreen screen) {
                screen.onCatalogChanged();
            }
        });
    }

    /**
     * {@code {ok:true, videos:[…]}} → entries, or {@code null} when the envelope itself is not what
     * the relay serves (so the caller records a failure rather than an empty catalogue).
     */
    static List<VideoEntry> parse(String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) return null;
        JsonObject obj = root.getAsJsonObject();
        if (!obj.has("videos") || !obj.get("videos").isJsonArray()) return null;
        JsonArray arr = obj.getAsJsonArray("videos");
        List<VideoEntry> out = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
            VideoEntry v = parseRow(el);
            if (v != null) out.add(v);
        }
        return out;
    }

    /** One row, or {@code null} when it lacks a usable id/url — never throws. */
    static VideoEntry parseRow(JsonElement el) {
        try {
            if (el == null || !el.isJsonObject()) return null;
            JsonObject o = el.getAsJsonObject();
            int id = intOr(o, "id", -1);
            String url = str(o, "url", MAX_URL);
            if (id < 0 || !isValidUrl(url)) return null;
            long views = o.has("views") && o.get("views").isJsonPrimitive() && o.get("views").getAsJsonPrimitive().isNumber()
                    ? o.get("views").getAsLong() : VideoEntry.VIEWS_UNKNOWN;
            String day = str(o, "day", 10);
            if (day != null && !day.matches("\\d{4}-\\d{2}-\\d{2}")) day = null;
            boolean fav = flag(o, "devFav");
            return new VideoEntry(id, url, VideoEntry.Platform.fromWire(str(o, "platform", 20)),
                    str(o, "videoId", 100), str(o, "title", MAX_TEXT), day, views, str(o, "channel", MAX_TEXT), fav,
                    flag(o, "live"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int intOr(JsonObject o, String key, int dflt) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsInt() : dflt;
    }

    /** Trimmed, whitespace-collapsed string bounded to {@code max}, or {@code null} when absent/blank. */
    private static String str(JsonObject o, String key, int max) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return null;
        String s = e.getAsString().replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** A 0/1 or true/false field; absent or anything else → false. */
    private static boolean flag(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) return false;
        var p = o.get(key).getAsJsonPrimitive();
        return p.isBoolean() ? p.getAsBoolean() : p.isNumber() && p.getAsInt() != 0;
    }

    /** Same rule as {@code OfficialLinks.isValidUrl}: http(s), no whitespace, bounded. */
    static boolean isValidUrl(String v) {
        if (v == null) return false;
        String s = v.trim();
        if (s.isEmpty() || s.length() > MAX_URL) return false;
        if (!(s.startsWith("https://") || s.startsWith("http://"))) return false;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return false;
        }
        return true;
    }
}
