package games.brennan.dungeontrain.client.localization.edit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Flattens a narrative book's JSON into the individual prose strings a translator edits.
 *
 * <p>The four narrative shapes are all different — random/starting books are
 * {@code {title, author, variants[]}}, stories are {@code {character, story, letters[{label,
 * variants[]}]}}, and death lore is a bare array of {@code {question, narration}} — so rather
 * than four parsers this walks the tree generically and emits every <b>string leaf</b> under a
 * dotted path ({@code title}, {@code variants.0}, {@code letters.2.variants.1},
 * {@code 0.narration}). New book shapes need no code here.</p>
 *
 * <p>Two categories of leaf are skipped:</p>
 * <ul>
 *   <li>{@link #STRUCTURAL_KEYS} — ids and layout hints that {@code validate-locale.py} requires
 *       to match the {@code es_es} reference byte-for-byte. Offering them for edit is offering a
 *       translator a way to fail CI (or silently break a book's wiring).</li>
 *   <li>{@code unused} — retired variants kept in-file but never shown to a player. They are
 *       real prose, so a generic walk would queue them up alongside live text and spend a
 *       translator's attention on content nobody reads.</li>
 * </ul>
 */
public final class NarrativeBookFields {

    /** Mirrors {@code STRUCT_STR_KEYS} in {@code scripts/localization/validate-locale.py}. */
    static final Set<String> STRUCTURAL_KEYS = Set.of("id", "ref", "page", "_translator_note");

    /** Retired prose, kept in-file for history — see the class javadoc. */
    static final String UNUSED_KEY = "unused";

    /** Depth guard: the deepest real shape is {@code letters[].variants[]}, i.e. 3. */
    private static final int MAX_DEPTH = 8;

    private NarrativeBookFields() {}

    /**
     * Dotted field path → prose, in document order. Empty when {@code json} is absent or
     * unparseable — a malformed book costs the editor that book, never the whole screen.
     */
    public static Map<String, String> flatten(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            walk(JsonParser.parseString(json), "", out, 0);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
        return out;
    }

    private static void walk(JsonElement element, String path, Map<String, String> out, int depth) {
        if (element == null || depth > MAX_DEPTH) {
            return;
        }
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                String key = entry.getKey();
                if (STRUCTURAL_KEYS.contains(key) || UNUSED_KEY.equals(key)) {
                    continue;
                }
                walk(entry.getValue(), join(path, key), out, depth + 1);
            }
            return;
        }
        if (element.isJsonArray()) {
            JsonArray arr = element.getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                walk(arr.get(i), join(path, Integer.toString(i)), out, depth + 1);
            }
            return;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString() && !path.isEmpty()) {
            out.put(path, element.getAsString());
        }
    }

    private static String join(String path, String segment) {
        return path.isEmpty() ? segment : path + "." + segment;
    }
}
