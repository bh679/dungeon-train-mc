package games.brennan.dungeontrain.track.variant;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.util.BundledNbtScanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the <b>shipped</b> per-kind {@code weights.json} files against keys that name no template.
 *
 * <h2>Why this cannot be caught any other way</h2>
 * <p>A weights entry is only ever read <i>from the template side</i> — {@code TrackVariantWeights}
 * exposes {@code weightFor(kind, name)} / {@code gateFor} / {@code modeFor}, each a lookup <b>by a
 * name the registry already has</b>. Nothing walks the map looking for keys that match nothing, so
 * an entry for a template that does not exist is loaded, never read, and never mentioned: no build
 * failure, no warning, no log line. It simply sits there looking like configuration that applies.</p>
 *
 * <p>That is exactly how {@code "pathsmol"} survived in {@code portals/room/weights.json} — a name
 * from an editor session that was deliberately removed from {@code labrynth.group.json} in the same
 * commit and left behind here, where nothing could report it. Meanwhile the real template,
 * {@code pathssmall.nbt}, silently took the default weight and mode. Both halves of that failure are
 * invisible in play, which is what makes a build-time assertion the right place to catch it.</p>
 *
 * <h2>Scope: {@link TrackKind}s only</h2>
 * <p>Carriages ({@code data/dungeontrain/templates/weights.json}, read by {@code CarriageWeights})
 * and contents ({@code data/dungeontrain/contents/weights.json}) are <b>not</b> checked here. They
 * are not {@code TrackKind}s and their names do not come from {@code .nbt} basenames alone —
 * carriages carry code-registered names such as {@code portal_middle} that ship no template file,
 * and contents names include {@code .group.json} parents, so this test's rule would report false
 * orphans on both. They get the same guard against each registry's own name source in
 * {@code games.brennan.dungeontrain.train.BundledCarriageAndContentsWeightsTest}.</p>
 */
final class BundledTrackWeightsTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(BundledTrackWeightsTest.class);

    @Test
    @DisplayName("every shipped track weights.json key names a real bundled .nbt template")
    void everyWeightsKeyResolvesToATemplate() {
        List<String> orphans = new ArrayList<>();
        int kindsChecked = 0;

        for (TrackKind kind : TrackKind.values()) {
            String resource = kind.bundledResourcePrefix() + TrackKind.WEIGHTS_FILE;
            JsonObject weights = readIfPresent(resource);
            // Most kinds ship no weights file at all (every pillar column but the stairs adjunct).
            // Absent is a valid state — it means "every template here is at the default".
            if (weights == null) continue;
            kindsChecked++;

            Set<String> templates =
                BundledNbtScanner.scanBasenames(BundledTrackWeightsTest.class,
                    kind.bundledResourcePrefix(), LOGGER);

            // Check the scan before trusting it. An unresolvable resources directory returns an
            // empty set rather than throwing, which would turn every key on every kind into a
            // bogus orphan — a broken test masquerading as broken data. A kind that ships a
            // weights.json necessarily ships something to weigh.
            assertTrue(!templates.isEmpty(),
                "no .nbt templates found at " + kind.bundledResourcePrefix() + " for kind '"
                    + kind.id() + "', yet it ships a " + TrackKind.WEIGHTS_FILE + ". This is far "
                    + "more likely a classpath-resolution failure in BundledNbtScanner under the "
                    + "test runtime than a genuinely empty template directory — check that "
                    + "src/main/resources is on the test classpath before editing any data.");

            for (String rawKey : weights.keySet()) {
                // TrackVariantWeights.loadInto lowercases keys on the way in and every lookup
                // lowercases the name, so match the same way: case is the only normalisation.
                String key = rawKey.toLowerCase(Locale.ROOT);
                // 'default' is synthetic — TrackVariantRegistry.namesFor injects it into every pool
                // whether or not a default.nbt exists, so an entry for it is always legitimate.
                if (key.equals(TrackKind.DEFAULT_NAME)) continue;
                if (templates.contains(key)) continue;
                orphans.add(kind.id() + ":" + rawKey + "  (in " + resource + ")");
            }
        }

        assertTrue(kindsChecked > 0,
            "no bundled " + TrackKind.WEIGHTS_FILE + " was found for any TrackKind — the shipped "
                + "weights files are not reaching the test classpath, so this test is checking "
                + "nothing.");

        assertTrue(orphans.isEmpty(),
            "weights entries naming no template — each is dead config that applies to nothing, "
                + "while the template it was probably meant for silently falls through to the "
                + "default weight and mode. Either delete the entry or re-key it to the real "
                + "template name:\n    " + String.join("\n    ", orphans));
    }

    @Test
    @DisplayName("every shipped track weights.json is a JSON object")
    void everyWeightsFileIsAnObject() {
        for (TrackKind kind : TrackKind.values()) {
            String resource = kind.bundledResourcePrefix() + TrackKind.WEIGHTS_FILE;
            try (InputStream in = BundledTrackWeightsTest.class.getResourceAsStream(resource)) {
                if (in == null) continue;
                JsonElement root;
                try (InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    root = JsonParser.parseReader(r);
                }
                assertNotNull(root, resource + " must parse");
                // A non-object root makes TrackVariantWeights.loadInto drop the whole file with a
                // warning, quietly returning every template in the kind to its defaults.
                assertTrue(root.isJsonObject(), resource + " must be a JSON object");
            } catch (Exception e) {
                throw new AssertionError("bundled " + resource + " failed to read: " + e, e);
            }
        }
    }

    /** The shipped weights object at {@code resource}, or null when the kind ships none. */
    private static JsonObject readIfPresent(String resource) {
        try (InputStream in = BundledTrackWeightsTest.class.getResourceAsStream(resource)) {
            if (in == null) return null;
            try (InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonElement root = JsonParser.parseReader(r);
                return root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
            }
        } catch (Exception e) {
            throw new AssertionError("bundled " + resource + " failed to read: " + e, e);
        }
    }
}
