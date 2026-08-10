package games.brennan.dungeontrain.client.localization.edit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pulls the translations an operator has approved for this client's locale and installs them
 * beneath the player's own edits.
 *
 * <p>Copies {@code OfficialLinksFetcher}'s posture exactly: its own HTTP/1.1-pinned client,
 * fire-and-forget, no-throw, and <b>anonymous</b> — no uuid, no session, no query beyond the
 * locale code. That is deliberate and matches the relay side: an approved translation is public
 * text the mod could equally have shipped in the jar, so fetching it carries nothing about the
 * player and runs regardless of the network-consent setting. Submitting, which carries
 * player-authored text and a uuid, is consent-gated; reading is not.</p>
 *
 * <p>A failure simply leaves the shipped translation in place. The last successful response is
 * cached to disk, so an offline launch still gets whatever was approved last time rather than
 * silently reverting the player's language to the machine text.</p>
 */
public final class ApprovedTranslationsFetcher {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(8))
        .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    /**
     * One fetch per locale per session — approvals are not urgent enough to poll for. A SET rather
     * than a single locale because two locales are legitimately in play at once on a dev build: the
     * one being displayed, and the one being edited (see {@link TranslationTarget}).
     */
    private static final Set<String> FETCHED = ConcurrentHashMap.newKeySet();

    private ApprovedTranslationsFetcher() {}

    /** Fetch once for the client's current locale, if we have not already this session. */
    public static void fetchOnce() {
        Minecraft mc = Minecraft.getInstance();
        String locale = mc != null && mc.options != null ? mc.options.languageCode : null;
        fetchOnceFor(locale);
    }

    /**
     * Fetch once for {@code locale} specifically — the editor's target, which on a dev build is not
     * the language the client is displaying. Without this the editor could only ever know about
     * approvals for the DISPLAY locale, so on that build "needs a human" would never learn that a
     * string had been reviewed.
     */
    public static void fetchOnceFor(String locale) {
        if (!TranslationScreen.isEditable(locale) || !FETCHED.add(locale)) {
            return;
        }
        fetchAsync(locale);
    }

    /** Force a fetch for {@code locale}, ignoring the once-per-session guard. No-throw. */
    public static void fetchAsync(String locale) {
        try {
            String url = DungeonTrain.relayBaseUrl() + "/translations/pool?locale=" + locale;
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .whenComplete((resp, err) -> accept(locale, resp, err));
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] Translations: pool request could not start — {}", t.toString());
        }
    }

    private static void accept(String locale, HttpResponse<String> resp, Throwable err) {
        try {
            if (err != null || resp == null || resp.statusCode() / 100 != 2) {
                LOGGER.debug("[DungeonTrain] Translations: pool fetch failed for {} — {}",
                    locale, err != null ? err.toString() : "HTTP " + (resp == null ? "?" : resp.statusCode()));
                return;
            }
            TranslationEdits approved = parse(locale, resp.body());
            if (approved == null) {
                return;
            }
            // Installing touches the language, which is render-thread state.
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                return;
            }
            mc.execute(() -> {
                // The locale can have changed while the request was in flight; applying a stale
                // locale's overrides would put German text on a French client.
                boolean stored;
                if (locale.equals(TranslationOverrides.locale())) {
                    stored = TranslationOverrides.replaceApproved(approved);
                    if (stored && !approved.isEmpty()) {
                        LOGGER.info("[DungeonTrain] Translations: applied {} approved override(s) for {}.",
                            approved.size(), locale);
                    }
                } else {
                    // A locale the client is NOT displaying — the dev build's edit target. Cache the
                    // layer so the editor can tell which strings a human has already released, but
                    // do NOT install it: installing swaps the running game's text, which would drop
                    // this language's strings into an English client.
                    stored = TranslationOverrideStore.save(
                        TranslationOverrideStore.Layer.APPROVED, approved);
                    if (stored && !approved.isEmpty()) {
                        LOGGER.info("[DungeonTrain] Translations: cached {} approved override(s) for {} "
                            + "(not the displayed language, so not applied).", approved.size(), locale);
                    }
                }
                if (!stored) {
                    return;
                }
                if (!approved.isEmpty()) {
                    TranslationCatalog.invalidate();
                }
                // The editor may be open on this very locale — drop the newly-approved rows out of
                // its queue now rather than making the translator reopen the screen to find out.
                if (mc.screen instanceof TranslationScreen editor) {
                    editor.onApprovedFetched(locale);
                }
            });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] Translations: pool parse failed — {}", t.toString());
        }
    }

    /** Parse {@code {ok, locale, overrides:{lang:{},books:{}}}}, or null when malformed. */
    static TranslationEdits parse(String locale, String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) {
            return null;
        }
        JsonObject obj = root.getAsJsonObject();
        if (!obj.has("ok") || !obj.get("ok").getAsBoolean()
            || !obj.has("overrides") || !obj.get("overrides").isJsonObject()) {
            return null;
        }
        JsonObject overrides = obj.getAsJsonObject("overrides");
        return new TranslationEdits(locale, readMap(overrides, "lang"), readMap(overrides, "books"));
    }

    /**
     * One half of the overrides object. Values run through
     * {@link TranslationEdits#normalizeValue} because this is remote input — a stray {@code %d}
     * arriving from the relay must not be able to throw inside {@code String.format} on every
     * render.
     */
    private static Map<String, String> readMap(JsonObject overrides, String field) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!overrides.has(field) || !overrides.get(field).isJsonObject()) {
            return out;
        }
        for (Map.Entry<String, JsonElement> entry : overrides.getAsJsonObject(field).entrySet()) {
            if (out.size() >= TranslationEdits.MAX_ENTRIES) {
                break;
            }
            JsonElement value = entry.getValue();
            if (entry.getKey().isEmpty() || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
                continue;
            }
            String normalized = TranslationEdits.normalizeValue(value.getAsString());
            if (normalized != null) {
                out.put(entry.getKey(), normalized);
            }
        }
        return out;
    }
}
