package games.brennan.dungeontrain.narrative;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The relay's approved-book counts per language family ({@code /books/languages}), largest first.
 *
 * <p>Answers one question for the fallback-language mix: which language does a writer get books from
 * once they are most of their own language's pool? A non-English player always gets English; an
 * English player gets the biggest OTHER family listed here ({@link #fallbackFamilyFor}).</p>
 *
 * <p>Fetched lazily, at most once per {@link #REFRESH_MS}. A relay too old to have the endpoint (404)
 * leaves the list empty, which simply means English players get no fallback — the behaviour before
 * this existed.</p>
 */
public final class BookLanguages {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Re-fetch interval. The relay memoises for 10 min; the ordering moves far slower than that. */
    static final long REFRESH_MS = 30L * 60 * 1000;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1) // see SharedBookPool — h2c would break local testing
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** One family's approved-book count. */
    public record FamilyTotal(String family, int total) {}

    private static volatile List<FamilyTotal> families = List.of();
    private static volatile long lastFetchMs = 0L;
    private static volatile boolean fetchInFlight = false;

    private BookLanguages() {}

    /** The current list (immutable, largest first), possibly empty. */
    public static List<FamilyTotal> families() {
        return families;
    }

    /**
     * The family whose books are mixed in for a player of {@code playerLang}: English for any
     * non-English player, otherwise the largest non-English family in {@code totals}, or {@code null}
     * when there is none (nothing fetched yet, or English is the only language).
     */
    public static String fallbackFamilyFor(String playerLang, List<FamilyTotal> totals) {
        String own = LanguageFamily.of(playerLang);
        if (!LanguageFamily.DEFAULT_FAMILY.equals(own)) return LanguageFamily.DEFAULT_FAMILY;
        for (FamilyTotal f : totals) {
            if (f.total() > 0 && !LanguageFamily.DEFAULT_FAMILY.equals(f.family())) return f.family();
        }
        return null;
    }

    /** {@link #fallbackFamilyFor(String, List)} against the fetched list. */
    public static String fallbackFamilyFor(String playerLang) {
        return fallbackFamilyFor(playerLang, families);
    }

    /** Fetch the list off-thread if it is stale. No-throw; at most one fetch in flight. */
    public static void refreshIfStale(boolean kidSafe, long now) {
        if (fetchInFlight || now - lastFetchMs < REFRESH_MS) return;
        fetchInFlight = true;
        lastFetchMs = now;
        try {
            String url = DungeonTrain.relayBaseUrl() + "/books/languages?pol=1" + (kidSafe ? "&kidsafe=1" : "");
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> {
                        try {
                            if (err != null) {
                                LOGGER.debug("[DungeonTrain] book-languages fetch failed: {}", err.toString());
                                return;
                            }
                            if (resp.statusCode() / 100 != 2) {
                                LOGGER.debug("[DungeonTrain] book-languages fetch -> HTTP {}", resp.statusCode());
                                return;
                            }
                            List<FamilyTotal> parsed = parse(resp.body());
                            if (!parsed.isEmpty()) families = parsed;
                            LOGGER.debug("[DungeonTrain] book-languages refreshed: {} families", parsed.size());
                        } catch (Throwable t) {
                            LOGGER.debug("[DungeonTrain] book-languages parse failed: {}", t.toString());
                        } finally {
                            fetchInFlight = false;
                        }
                    });
        } catch (Throwable t) {
            fetchInFlight = false;
            LOGGER.debug("[DungeonTrain] book-languages refresh failed to start: {}", t.toString());
        }
    }

    /** Parse {@code {ok, families:[{family,total}]}}, largest first. Malformed → empty. */
    static List<FamilyTotal> parse(String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) return List.of();
        JsonObject obj = root.getAsJsonObject();
        if (!obj.has("ok") || !obj.get("ok").getAsBoolean()) return List.of();
        if (!obj.has("families") || !obj.get("families").isJsonArray()) return List.of();
        List<FamilyTotal> out = new ArrayList<>();
        for (JsonElement el : obj.getAsJsonArray("families")) {
            if (!el.isJsonObject()) continue;
            JsonObject f = el.getAsJsonObject();
            if (!f.has("family") || f.get("family").isJsonNull() || !f.has("total")) continue;
            out.add(new FamilyTotal(f.get("family").getAsString(), f.get("total").getAsInt()));
        }
        out.sort((a, b) -> Integer.compare(b.total(), a.total()));
        return List.copyOf(out);
    }

    /** Test/reset hook. */
    static void clear() {
        families = List.of();
        lastFetchMs = 0L;
        fetchInFlight = false;
    }
}
