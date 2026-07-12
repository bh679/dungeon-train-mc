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
 * Gson-backed deserializer for the per-book {@code .json} schema under
 * {@code data/<modid>/narratives/random_books/}. Mirrors {@link StoryCodec}'s
 * forgiving style — missing optional fields default to sensible sentinels
 * ({@code "Anonymous"}, {@code "Untitled"}, {@code generation=0},
 * {@code weight=1}) so a bare-minimum file with just {@code variants} still
 * loads.
 *
 * <p>A bad file logs at the registry layer and is skipped — one corrupt
 * book shouldn't kill the whole pool load.</p>
 */
public final class RandomBookCodec {

    private RandomBookCodec() {}

    /**
     * Parse a random book from an InputStream. Caller owns the stream
     * lifecycle.
     *
     * @param in     stream containing UTF-8 JSON
     * @param fileId ResourceLocation derived from the file path; used as the
     *               book id when the JSON's {@code id} field is missing.
     * @throws RandomBookParseException on malformed JSON or schema violations.
     */
    public static RandomBookFile parse(InputStream in, ResourceLocation fileId) throws RandomBookParseException {
        JsonElement rootEl;
        try {
            rootEl = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RandomBookParseException("invalid JSON: " + e.getMessage(), e);
        }
        if (!rootEl.isJsonObject()) {
            throw new RandomBookParseException("root is not a JSON object");
        }
        JsonObject root = rootEl.getAsJsonObject();

        String title = optionalString(root, "title", "Untitled");
        String author = optionalString(root, "author", "Anonymous");
        int generation = optionalInt(root, "generation", 0);
        int weight = optionalInt(root, "weight", 1);

        if (!root.has("variants") || !root.get("variants").isJsonArray()) {
            throw new RandomBookParseException("missing or non-array 'variants' field");
        }
        JsonArray varsArr = root.getAsJsonArray("variants");
        List<String> variants = new ArrayList<>(varsArr.size());
        for (JsonElement v : varsArr) {
            if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) {
                throw new RandomBookParseException("non-string variant in book '" + title + "'");
            }
            variants.add(v.getAsString());
        }
        if (variants.isEmpty()) {
            throw new RandomBookParseException("book '" + title + "' has zero variants");
        }
        return new RandomBookFile(fileId, title, author, generation, weight, variants);
    }

    private static String optionalString(JsonObject obj, String key, String fallback) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive() || !obj.get(key).getAsJsonPrimitive().isString()) {
            return fallback;
        }
        String v = obj.get(key).getAsString();
        return v.isEmpty() ? fallback : v;
    }

    private static int optionalInt(JsonObject obj, String key, int fallback) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive() || !obj.get(key).getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        return obj.get(key).getAsInt();
    }

    /** Surfaced to the registry's per-file try/catch so logging is uniform. */
    public static final class RandomBookParseException extends Exception {
        public RandomBookParseException(String message) { super(message); }
        public RandomBookParseException(String message, Throwable cause) { super(message, cause); }
    }
}
