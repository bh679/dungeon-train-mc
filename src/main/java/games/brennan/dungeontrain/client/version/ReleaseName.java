package games.brennan.dungeontrain.client.version;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * What a release is called and how big it is, from the curated changelog ledger on {@code main}. Releases have no
 * name of their own, so the name is the title of the release's lead entry: its major entry when it
 * has one, otherwise its first feature, then content, then fix. {@code release.yml} stamps the
 * ledger's {@code released_in} before it pushes {@code update.json}, so the entries are there by the
 * time a notice fires.
 *
 * <p>No-throw: every failure completes with {@code null}, and the notice then goes out with the
 * version alone rather than being held back.</p>
 */
final class ReleaseName {

    /** How loudly a release is announced: the ledger's major, notable (a smaller major) or neither. */
    enum Tier { MAJOR, NOTABLE, NORMAL }

    /** A release's name, tier and one-sentence summary ({@code ""} when there is none worth showing). */
    record Info(String name, Tier tier, String summary) {}

    /** Longest summary shown in chat before it is cut with an ellipsis. */
    static final int SUMMARY_MAX = 140;

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String DEFAULT_URL =
        "https://raw.githubusercontent.com/bh679/dungeon-train-mc/main/.github/release-notes/changelog.json";

    /** Gate 2 seam — the same property the Versions page reads its ledger from. */
    static final String URL_PROPERTY = "dungeontrain.changelogUrl";

    /** Entry types in the order they lead a release; anything else ranks after them. */
    private static final List<String> TYPE_ORDER = List.of("feat", "content", "fix");

    private static final String INTERNAL_TAG = "internal";

    private static final Pattern VERSION = Pattern.compile("v?\\d+\\.\\d+(\\.\\d+)?");

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(TIMEOUT)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private ReleaseName() {}

    /** The name of release {@code version}, or {@code null}. */
    static CompletableFuture<Info> fetch(String version, String modVersion) {
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(System.getProperty(URL_PROPERTY, DEFAULT_URL)))
                .header("User-Agent", "DungeonTrain-Mod/" + modVersion)
                .header("Accept", "application/json")
                .timeout(TIMEOUT)
                .GET()
                .build();
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(null);
        }
        return CLIENT.sendAsync(req, HttpResponse.BodyHandlers.ofString())
            .thenApply(resp -> {
                if (resp.statusCode() != 200) {
                    LOGGER.debug("Update notice: changelog lookup returned HTTP {}", resp.statusCode());
                    return null;
                }
                return pick(resp.body(), version);
            })
            .exceptionally(t -> {
                LOGGER.debug("Update notice: changelog lookup failed: {}", t.toString());
                return null;
            });
    }

    /**
     * The lead entry released in {@code version} (matched on major.minor, so a cascade tag still
     * finds its release), or {@code null} when the ledger is malformed or has none. Internal entries
     * never name a release.
     */
    static Info pick(String body, String version) {
        if (body == null || body.isBlank() || !isVersion(version)) return null;
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) return null;
            JsonElement entries = root.getAsJsonObject().get("entries");
            if (entries == null || !entries.isJsonArray()) return null;
            JsonObject best = null;
            int bestRank = Integer.MAX_VALUE;
            for (JsonElement el : entries.getAsJsonArray()) {
                if (!el.isJsonObject()) continue;
                JsonObject e = el.getAsJsonObject();
                String title = string(e, "title");
                if (title == null || !releasedIn(e, version) || hasTag(e, INTERNAL_TAG)) continue;
                int rank = rank(e);
                if (rank < bestRank) {
                    best = e;
                    bestRank = rank;
                }
            }
            if (best == null) return null;
            Tier tier = bestRank == 0 ? Tier.MAJOR : bestRank == 1 ? Tier.NOTABLE : Tier.NORMAL;
            String summary = string(best, "summary");
            return new Info(string(best, "title"), tier, shortSummary(summary == null ? "" : summary));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The first sentence (or line) of a ledger summary as plain text for chat: markdown headings,
     * emphasis and links stripped, cut at {@link #SUMMARY_MAX} with an ellipsis. {@code ""} when
     * nothing readable is left, or when it is only a link.
     */
    static String shortSummary(String summary) {
        String text = summary
            .replaceAll("(?m)^\\s*#{1,6}\\s+.*$", "")              // headings
            .replaceAll("\\[([^\\]]*)]\\([^)]*\\)", "$1")     // [text](url) -> text
            .replaceAll("[*_`]", "")
            .strip();
        int nl = text.indexOf('\n');
        if (nl >= 0) text = text.substring(0, nl).strip();
        int stop = firstSentenceEnd(text);
        if (stop > 0) text = text.substring(0, stop);
        if (text.isEmpty() || text.startsWith("http://") || text.startsWith("https://")) return "";
        if (text.length() > SUMMARY_MAX) {
            int cut = text.lastIndexOf(' ', SUMMARY_MAX - 3);
            text = text.substring(0, cut > 0 ? cut : SUMMARY_MAX - 3).strip() + "...";
        }
        return text;
    }

    /** Index just past the first ". ", "! " or "? ", or -1. */
    private static int firstSentenceEnd(String text) {
        for (int i = 0; i < text.length() - 1; i++) {
            char c = text.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && text.charAt(i + 1) == ' ') return i + 1;
        }
        return -1;
    }

    private static boolean releasedIn(JsonObject e, String version) {
        JsonElement released = e.get("released");
        if (released == null || !released.isJsonPrimitive() || !released.getAsBoolean()) return false;
        String in = string(e, "released_in");
        return isVersion(in) && SemverCompare.compare(in, version) == 0;
    }

    /** Major first (a notable major after a full one), then {@link #TYPE_ORDER}, then the rest. */
    private static int rank(JsonObject e) {
        if (flag(e, "major")) return flag(e, "notable") ? 1 : 0;
        int i = TYPE_ORDER.indexOf(string(e, "type"));
        return 2 + (i < 0 ? TYPE_ORDER.size() : i);
    }

    private static boolean hasTag(JsonObject e, String tag) {
        JsonElement tags = e.get("tags");
        if (tags == null || !tags.isJsonArray()) return false;
        for (JsonElement t : (JsonArray) tags) {
            if (t.isJsonPrimitive() && tag.equals(t.getAsString())) return true;
        }
        return false;
    }

    private static boolean flag(JsonObject e, String key) {
        JsonElement v = e.get(key);
        return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean() && v.getAsBoolean();
    }

    private static String string(JsonObject e, String key) {
        JsonElement v = e.get(key);
        if (v == null || !v.isJsonPrimitive()) return null;
        String s = v.getAsString().trim();
        return s.isEmpty() ? null : s;
    }

    private static boolean isVersion(String v) {
        return v != null && VERSION.matcher(v.trim()).matches();
    }
}
