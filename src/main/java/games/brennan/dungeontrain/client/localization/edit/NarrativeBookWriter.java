package games.brennan.dungeontrain.client.localization.edit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Map;

/**
 * The inverse of {@link NarrativeBookFields}: writes edited prose back into a book's JSON tree at
 * the dotted paths that walk produced.
 *
 * <p>Edits the parsed tree in place rather than rebuilding it, so everything the translator did
 * not touch — ids, weights, generations, translator notes, and crucially the key order — comes
 * out exactly as it went in. {@code scripts/localization/validate-locale.py} requires each
 * locale's book files to stay shape-identical to the {@code es_es} reference, so a rebuild that
 * reordered or dropped a structural field would produce a file the repo rejects.</p>
 */
public final class NarrativeBookWriter {

    private NarrativeBookWriter() {}

    /**
     * Apply {@code overrides} (dotted field path → prose) to {@code root}. Paths that no longer
     * resolve are skipped: a book can change shape between versions, and a stale override should
     * be ignored rather than invent a field.
     *
     * @return how many overrides actually landed
     */
    public static int apply(JsonElement root, Map<String, String> overrides) {
        int applied = 0;
        for (Map.Entry<String, String> entry : overrides.entrySet()) {
            if (set(root, entry.getKey().split("\\."), 0, entry.getValue())) {
                applied++;
            }
        }
        return applied;
    }

    /** Walk {@code segments} from {@code index}, replacing the string leaf at the end. */
    private static boolean set(JsonElement node, String[] segments, int index, String value) {
        if (node == null || index >= segments.length) {
            return false;
        }
        String segment = segments[index];
        boolean last = index == segments.length - 1;

        if (node.isJsonObject()) {
            JsonObject obj = node.getAsJsonObject();
            if (!obj.has(segment)) {
                return false;
            }
            if (last) {
                if (!obj.get(segment).isJsonPrimitive()) {
                    return false;
                }
                obj.add(segment, new JsonPrimitive(value));
                return true;
            }
            return set(obj.get(segment), segments, index + 1, value);
        }

        if (node.isJsonArray()) {
            JsonArray arr = node.getAsJsonArray();
            int i = parseIndex(segment);
            if (i < 0 || i >= arr.size()) {
                return false;
            }
            if (last) {
                if (!arr.get(i).isJsonPrimitive()) {
                    return false;
                }
                arr.set(i, new JsonPrimitive(value));
                return true;
            }
            return set(arr.get(i), segments, index + 1, value);
        }
        return false;
    }

    private static int parseIndex(String segment) {
        try {
            return Integer.parseInt(segment);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
