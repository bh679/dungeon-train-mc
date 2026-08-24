package games.brennan.dungeontrain.client.localization.edit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.localization.RelayReviewedCount;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * How much of every language has since been reviewed, in one call at startup -- what keeps the
 * language list's rings honest for the languages the player is NOT currently playing in.
 *
 * <p>The rings come from counts baked when the jar was cut. {@link RelayReviewedCount} already
 * brings one language up to date, by counting the approved overrides this client has downloaded
 * and applied -- but the pool is fetched per locale and only ever for the one being played, so on
 * the language screen exactly one row is current and the other hundred and thirty are as stale as
 * the build. A player deciding which language to switch to is looking at the wrong numbers for
 * every language except the one they are already in.</p>
 *
 * <p>So: one request, every locale, at the same title-screen moment the pool fetch already
 * happens. Anonymous, and UNGATED for that reason: it carries no uuid and asks nothing about this
 * player, exactly like {@code /translations/pool}, which {@link ApprovedTranslationsFetcher} also
 * fetches without consulting the network-consent setting. Gating it would have been a quiet
 * mistake rather than a cautious one — consent defaults to off, so the rings would have stayed at
 * their build-time values for very nearly everybody.</p>
 *
 * <p><b>Trusted, where {@link RelayReviewedCount} is not.</b> That class deliberately validates
 * each approval against the local provenance manifest, because an approval for a line the manifest
 * never called AI-unreviewed has not reduced how much machine translation the player is reading. A
 * bare per-locale total cannot be checked that way. It is used only for locales whose pool this
 * client has NOT applied -- the locally verified count always wins where there is one -- and it can
 * only ever shrink a ring towards zero, never past it, so the worst a wrong figure does is flatter
 * a language. The alternative was leaving those hundred and thirty rows frankly wrong instead.</p>
 */
public final class TranslationCoverageClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(8))
        .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);

    /** Fired once per client run, like the pool fetch this rides alongside. */
    private static final AtomicBoolean FETCHED = new AtomicBoolean();

    /** Locale to the number of its AI-unreviewed lines an approval has since covered. */
    private static final Map<String, Integer> COVERAGE = new HashMap<>();
    /** Locale to everyone credited for an approved translation of it, and how much they did. */
    private static final Map<String, List<Credit>> CREDITS = new HashMap<>();

    /** One person's credited work in one language, as the relay reports it. */
    public record Credit(String name, int units) {}

    private TranslationCoverageClient() {}

    /** Fetch once for the whole client run. Never throws, never blocks, never retries. */
    public static void fetchOnce() {
        if (FETCHED.compareAndSet(false, true)) {
            fetchAsync();
        }
    }

    /** How many of {@code locale}'s unreviewed lines the relay says are now reviewed. */
    public static synchronized int approvedFor(String locale) {
        if (locale == null || locale.isBlank()) {
            return 0;
        }
        return COVERAGE.getOrDefault(locale.toLowerCase(Locale.ROOT), 0);
    }

    /**
     * Everyone the relay credits for {@code locale}, most-recently-started last. Never null.
     *
     * <p>Distinct from {@link RelayTranslationCredits}, which holds the same fact for the ONE locale
     * whose pool this client downloaded — that is what the editor needs, this is what a credits page
     * listing every language needs.</p>
     */
    public static synchronized List<Credit> creditsFor(String locale) {
        if (locale == null || locale.isBlank()) {
            return List.of();
        }
        return CREDITS.getOrDefault(locale.toLowerCase(Locale.ROOT), List.of());
    }

    /** Every locale the relay has credited anybody for. */
    public static synchronized Map<String, List<Credit>> allCredits() {
        return Map.copyOf(CREDITS);
    }

    /** For tests, and for a client that has changed relay. */
    public static synchronized void clear() {
        COVERAGE.clear();
        CREDITS.clear();
        FETCHED.set(false);
    }

    private static void fetchAsync() {
        try {
            HttpRequest req = HttpRequest.newBuilder(
                    URI.create(DungeonTrain.relayBaseUrl() + "/translations/coverage"))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .whenComplete((resp, err) -> {
                    if (err != null || resp == null || resp.statusCode() / 100 != 2) {
                        // Includes a relay older than the endpoint. Debug only: the rings fall back
                        // to the baked counts, which is exactly where they were before this existed.
                        LOGGER.debug("[DungeonTrain] Translations: coverage unavailable -- {}",
                            err != null ? err.toString()
                                : "HTTP " + (resp == null ? "?" : resp.statusCode()));
                        return;
                    }
                    apply(parse(resp.body()), parseCredits(resp.body()));
                });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] Translations: coverage fetch failed -- {}", t.toString());
        }
    }

    /**
     * {@code {"locales":{"zh_cn":{"reviewed":1047},"de_de":{"reviewed":3}}}} to a map.
     *
     * <p>Also accepts a bare number per locale, so the relay is free to answer with the short form
     * without this needing a release to keep up.</p>
     */
    static Map<String, Integer> parse(String body) {
        Map<String, Integer> out = new HashMap<>();
        try {
            JsonElement root = JsonParser.parseString(body == null ? "" : body);
            if (!root.isJsonObject()) {
                return out;
            }
            JsonElement locales = root.getAsJsonObject().get("locales");
            if (locales == null || !locales.isJsonObject()) {
                return out;
            }
            for (Map.Entry<String, JsonElement> entry : locales.getAsJsonObject().entrySet()) {
                int reviewed = readReviewed(entry.getValue());
                if (reviewed > 0) {
                    out.put(entry.getKey().toLowerCase(Locale.ROOT), reviewed);
                }
            }
        } catch (Exception e) {
            return new HashMap<>(); // a malformed body leaves the baked counts standing
        }
        return out;
    }

    private static int readReviewed(JsonElement value) {
        try {
            if (value.isJsonPrimitive()) {
                return value.getAsInt();
            }
            if (value.isJsonObject()) {
                JsonObject obj = value.getAsJsonObject();
                JsonElement reviewed = obj.get("reviewed");
                return reviewed != null && reviewed.isJsonPrimitive() ? reviewed.getAsInt() : 0;
            }
        } catch (Exception e) {
            return 0;
        }
        return 0;
    }

    /**
     * {@code {"locales":{"de_de":{"contributors":[{"name":"Ada","units":12}]}}}} to a map.
     *
     * <p>A blank name is dropped again here even though the relay already drops them: declining
     * credit has to hold at every layer it could surface through, not just the first one.</p>
     */
    static Map<String, List<Credit>> parseCredits(String body) {
        Map<String, List<Credit>> out = new HashMap<>();
        try {
            JsonElement root = JsonParser.parseString(body == null ? "" : body);
            if (!root.isJsonObject()) {
                return out;
            }
            JsonElement locales = root.getAsJsonObject().get("locales");
            if (locales == null || !locales.isJsonObject()) {
                return out;
            }
            for (Map.Entry<String, JsonElement> entry : locales.getAsJsonObject().entrySet()) {
                if (!entry.getValue().isJsonObject()) {
                    continue;
                }
                JsonElement arr = entry.getValue().getAsJsonObject().get("contributors");
                if (arr == null || !arr.isJsonArray()) {
                    continue;
                }
                List<Credit> credits = new ArrayList<>();
                for (JsonElement el : arr.getAsJsonArray()) {
                    if (!el.isJsonObject()) {
                        continue;
                    }
                    JsonObject obj = el.getAsJsonObject();
                    String name = obj.has("name") && obj.get("name").isJsonPrimitive()
                        ? obj.get("name").getAsString().trim() : "";
                    int units = obj.has("units") && obj.get("units").isJsonPrimitive()
                        ? obj.get("units").getAsInt() : 0;
                    if (!name.isEmpty() && units > 0) {
                        credits.add(new Credit(name, units));
                    }
                }
                if (!credits.isEmpty()) {
                    out.put(entry.getKey().toLowerCase(Locale.ROOT), List.copyOf(credits));
                }
            }
        } catch (Exception e) {
            return new HashMap<>();
        }
        return out;
    }

    private static void apply(Map<String, Integer> parsed, Map<String, List<Credit>> credits) {
        if (parsed.isEmpty() && credits.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        // Onto the client thread: the rings read this while rendering, and nothing else about the
        // language screen is synchronised against a network callback.
        mc.execute(() -> {
            synchronized (TranslationCoverageClient.class) {
                COVERAGE.clear();
                COVERAGE.putAll(parsed);
                CREDITS.clear();
                CREDITS.putAll(credits);
            }
            LOGGER.debug("[DungeonTrain] Translations: coverage for {} locale(s), credits for {}",
                parsed.size(), credits.size());
        });
    }
}
