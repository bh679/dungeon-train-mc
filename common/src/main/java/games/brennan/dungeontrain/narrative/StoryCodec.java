package games.brennan.dungeontrain.narrative;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Gson-backed deserializer for narrative {@code .json} files produced by
 * {@code scripts/narrative/txt_to_json.py}. The schema is documented in that
 * script; the parser here is forgiving by design — missing optional fields
 * default to the same sentinels the converter would emit ({@code "Anonymous"},
 * {@code "Untitled"}).
 *
 * <p>A bad story file logs and yields an empty {@link java.util.Optional}
 * rather than throwing — one corrupt narrative shouldn't kill the whole
 * registry load.</p>
 */
public final class StoryCodec {

    private StoryCodec() {}

    /**
     * Parse a story from an InputStream. Caller owns the stream lifecycle.
     *
     * @param in     stream containing UTF-8 JSON
     * @param fileId ResourceLocation derived from the file path; used as the
     *               story id when the JSON's {@code id} field is missing or
     *               doesn't match.
     * @throws StoryParseException on malformed JSON or schema violations the
     *                             registry's per-file try/catch should log.
     */
    public static StoryFile parse(InputStream in, ResourceLocation fileId) throws StoryParseException {
        JsonElement rootEl;
        try {
            rootEl = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new StoryParseException("invalid JSON: " + e.getMessage(), e);
        }
        if (!rootEl.isJsonObject()) {
            throw new StoryParseException("root is not a JSON object");
        }
        JsonObject root = rootEl.getAsJsonObject();

        String character = optionalString(root, "character", "Anonymous");
        String story = optionalString(root, "story", "Untitled");

        if (!root.has("letters") || !root.get("letters").isJsonArray()) {
            throw new StoryParseException("missing or non-array 'letters' field");
        }
        JsonArray lettersArr = root.getAsJsonArray("letters");
        List<Letter> letters = new ArrayList<>(lettersArr.size());
        int derivedIndex = 1;
        for (JsonElement el : lettersArr) {
            if (!el.isJsonObject()) {
                throw new StoryParseException("letter at position " + derivedIndex + " is not an object");
            }
            JsonObject letterObj = el.getAsJsonObject();
            int index = letterObj.has("index") && letterObj.get("index").isJsonPrimitive()
                ? letterObj.get("index").getAsInt()
                : derivedIndex;
            String label = optionalString(letterObj, "label", "Letter " + index);
            if (!letterObj.has("variants") || !letterObj.get("variants").isJsonArray()) {
                throw new StoryParseException("letter '" + label + "' missing 'variants' array");
            }
            JsonArray varsArr = letterObj.getAsJsonArray("variants");
            List<String> variants = new ArrayList<>(varsArr.size());
            for (JsonElement v : varsArr) {
                if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) {
                    throw new StoryParseException("letter '" + label + "' has non-string variant");
                }
                variants.add(v.getAsString());
            }
            if (variants.isEmpty()) {
                throw new StoryParseException("letter '" + label + "' has zero variants");
            }
            letters.add(new Letter(index, label, variants));
            derivedIndex++;
        }
        if (letters.isEmpty()) {
            throw new StoryParseException("story has zero letters");
        }
        return new StoryFile(fileId, character, story, letters);
    }

    private static String optionalString(JsonObject obj, String key, String fallback) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive() || !obj.get(key).getAsJsonPrimitive().isString()) {
            return fallback;
        }
        String v = obj.get(key).getAsString();
        return v.isEmpty() ? fallback : v;
    }

    /** Surfaced to the registry's per-file try/catch so logging is uniform. */
    public static final class StoryParseException extends Exception {
        public StoryParseException(String message) { super(message); }
        public StoryParseException(String message, Throwable cause) { super(message, cause); }
    }
}
