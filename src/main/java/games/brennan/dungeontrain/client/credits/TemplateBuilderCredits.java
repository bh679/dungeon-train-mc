package games.brennan.dungeontrain.client.credits;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.template.BuilderCredit;
import games.brennan.dungeontrain.template.TemplateWeightCodec;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriageContentsWeights;
import games.brennan.dungeontrain.train.CarriageWeights;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Everyone credited as the original builder of a template that ships with the mod — the Credits
 * page's "Builders" card.
 *
 * <p>Read from the <b>bundled</b> {@code weights.json} of every kind (carriages, contents, each
 * track-side kind), straight off the classpath: the page is reachable from the title screen with no
 * world loaded, so the server-side weight stores are not up, and the jar is the one copy that is
 * always present and always matches the version being played. The user tier is deliberately not
 * read — a credit somebody typed into their own config is theirs to see in the editor, not a thank
 * you in the shipped game's credits.</p>
 *
 * <p>One person per line however many templates they built, keyed by uuid when there is one and by
 * name otherwise, counting templates; ordered most-built first, then by name. Loaded once per
 * session — the jar does not change under a running game.</p>
 */
public final class TemplateBuilderCredits {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** One builder on the card. */
    public record Builder(String uuid, String name, int templates) {
        /** What the card prints — the cached name, or the uuid for a credit with none. */
        public String display() {
            return name.isEmpty() ? uuid : name;
        }
    }

    private static List<Builder> cached;

    private TemplateBuilderCredits() {}

    /** Every credited builder, most templates first; empty when nobody is credited. */
    public static synchronized List<Builder> all() {
        if (cached == null) cached = load();
        return cached;
    }

    /** Drop the cache — for tests, and for anything that swaps the resources under the game. */
    static synchronized void reset() {
        cached = null;
    }

    private static List<Builder> load() {
        List<BuilderCredit> credits = new ArrayList<>();
        credits.addAll(creditsIn(CarriageWeights.BUNDLED_RESOURCE));
        credits.addAll(creditsIn(CarriageContentsWeights.BUNDLED_RESOURCE));
        for (TrackKind kind : TrackKind.values()) {
            credits.addAll(creditsIn(kind.bundledResourcePrefix() + TrackKind.WEIGHTS_FILE));
        }
        return aggregate(credits);
    }

    /**
     * Fold one credit per template into one line per person. Pure, so the ordering rule has a test.
     * A builder with a uuid is one person under any name; one without is one person per name.
     */
    static List<Builder> aggregate(List<BuilderCredit> credits) {
        Map<String, Builder> byPerson = new LinkedHashMap<>();
        for (BuilderCredit c : credits) {
            if (c == null || !c.known()) continue;
            String key = c.hasUuid() ? "u:" + c.uuid() : "n:" + c.name().toLowerCase(Locale.ROOT);
            Builder prev = byPerson.get(key);
            if (prev == null) {
                byPerson.put(key, new Builder(c.uuid(), c.name(), 1));
            } else {
                // The first non-empty name wins: every credit for one uuid was typed by the same
                // hand, and a later blank must not erase it.
                String name = prev.name().isEmpty() ? c.name() : prev.name();
                byPerson.put(key, new Builder(prev.uuid(), name, prev.templates() + 1));
            }
        }
        List<Builder> out = new ArrayList<>(byPerson.values());
        out.sort(Comparator.comparingInt(Builder::templates).reversed()
            .thenComparing(b -> b.display().toLowerCase(Locale.ROOT)));
        return List.copyOf(out);
    }

    /** The builder credit of every entry in one bundled weights file; empty when absent or unreadable. */
    private static List<BuilderCredit> creditsIn(String resource) {
        List<BuilderCredit> out = new ArrayList<>();
        try (InputStream in = TemplateBuilderCredits.class.getResourceAsStream(resource)) {
            if (in == null) return out;
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonElement root = JsonParser.parseReader(reader);
                if (!root.isJsonObject()) return out;
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
                    if (!e.getValue().isJsonObject()) continue;   // a bare weight credits nobody
                    JsonObject entry = e.getValue().getAsJsonObject();
                    BuilderCredit credit = TemplateWeightCodec.parseBuilder(entry);
                    if (credit != null) out.add(credit);
                }
            }
        } catch (Exception e) {
            // A credit that cannot be read costs a line on a page, never the page.
            LOGGER.warn("[DungeonTrain] Credits: could not read builder credits from {}: {}", resource, e.toString());
        }
        return out;
    }
}
