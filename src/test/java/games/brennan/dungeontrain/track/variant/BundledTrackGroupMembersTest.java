package games.brennan.dungeontrain.track.variant;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.editor.TrackVariantGroupStore;
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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every member of a shipped {@code .group.json} must name a template that actually ships.
 *
 * <p>The sibling of {@link BundledTrackWeightsTest}, against the same class of invisible data rot.
 * A group member is read from the parent's side — the pool asks a group for its variants and picks
 * one — so a member naming a file that does not exist is never reported as missing. It is simply
 * chosen, and then the portal room falls back to its built-in geometry
 * ({@link TrackKind#hasBuiltInFallback}), which looks like an empty room rather than like an
 * error.</p>
 *
 * <p>That is exactly how {@code authorlibrary} and {@code test} survived in
 * {@code portals/room/default.group.json}: both were added beside templates that did ship, neither
 * ever had an {@code .nbt} committed, and the editor dutifully opened a plot for each. In play,
 * {@code test} carried weight 1 — one pick in five within its group went to a room with nothing in
 * it.</p>
 *
 * <h2>Scope: {@link TrackKind}s only</h2>
 * <p>Carriage and contents groups are not checked here, for the reason
 * {@link BundledTrackWeightsTest} gives about their weights: their names include code-registered
 * entries that ship no file of their own, which would read as false orphans.</p>
 */
final class BundledTrackGroupMembersTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(BundledTrackGroupMembersTest.class);

    @Test
    @DisplayName("every shipped group member names a real bundled .nbt template")
    void everyGroupMemberResolvesToATemplate() {
        List<String> orphans = new ArrayList<>();
        int groupsChecked = 0;

        for (TrackKind kind : TrackKind.values()) {
            Set<String> parents = BundledNbtScanner.scanBasenames(BundledTrackGroupMembersTest.class,
                kind.bundledResourcePrefix(), LOGGER, TrackVariantGroupStore.EXT);
            if (parents.isEmpty()) continue;

            Set<String> templates = BundledNbtScanner.scanBasenames(BundledTrackGroupMembersTest.class,
                kind.bundledResourcePrefix(), LOGGER);
            // The same sanity check the weights test makes, and for the same reason: an
            // unresolvable resources directory scans to nothing, which would turn every member of
            // every group into a bogus orphan — a broken test wearing the face of broken data.
            assertTrue(!templates.isEmpty(),
                "no .nbt templates found at " + kind.bundledResourcePrefix() + " for kind '"
                    + kind.id() + "', yet it ships " + parents.size() + " group sidecar(s). This is "
                    + "far more likely a classpath-resolution failure in BundledNbtScanner under "
                    + "the test runtime than a genuinely empty template directory.");

            for (String parent : parents) {
                String resource = kind.bundledResourcePrefix() + parent + TrackVariantGroupStore.EXT;
                JsonObject group = readObject(resource);
                if (group == null) continue;
                groupsChecked++;
                JsonElement variants = group.get("variants");
                if (variants == null || !variants.isJsonArray()) continue;
                for (JsonElement el : (JsonArray) variants) {
                    if (!el.isJsonObject()) continue;
                    JsonElement id = el.getAsJsonObject().get("id");
                    if (id == null || !id.isJsonPrimitive()) continue;
                    String name = id.getAsString().toLowerCase(Locale.ROOT);
                    if (templates.contains(name)) continue;
                    orphans.add(kind.id() + ":" + parent + " → " + id.getAsString()
                        + "  (in " + resource + ")");
                }
            }
        }

        assertTrue(groupsChecked > 0,
            "no bundled " + TrackVariantGroupStore.EXT + " was found for any TrackKind — the "
                + "shipped group sidecars are not reaching the test classpath, so this test is "
                + "checking nothing.");

        assertTrue(orphans.isEmpty(),
            "group members naming no template — each opens an empty plot in the editor and, in "
                + "play, spends its weight on a room that falls back to built-in geometry. Either "
                + "ship the template or drop the member:\n    " + String.join("\n    ", orphans));
    }

    /** The shipped JSON object at {@code resource}, or null when it is absent or not an object. */
    private static JsonObject readObject(String resource) {
        try (InputStream in = BundledTrackGroupMembersTest.class.getResourceAsStream(resource)) {
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
