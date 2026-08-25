package games.brennan.dungeontrain.client.localization.edit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What each format placeholder in the English strings actually holds, for the editor to show.
 *
 * <p>Hand-authored, not generated: the call sites build most of these keys from a prefix and an
 * index ({@code chat.dungeontrain.familiar_book.} + n), so no amount of grepping
 * {@code Component.translatable(…)} recovers what the arguments are. The file is the record of a
 * human having read each string.</p>
 *
 * <p>File shape — key, then 1-based slot, matching {@link TranslationVariableScanner}'s numbering:
 * </p>
 * <pre>{@code
 * {
 *   "chat.dungeontrain.familiar_book.4": {
 *     "1": { "label": "a player name", "examples": ["Steve", "Alex"] },
 *     "2": { "label": "a duration",    "examples": ["3 minutes"] }
 *   }
 * }
 * }</pre>
 *
 * <p>Loaded on the client resource-reload seam alongside the other localization metadata (see
 * {@code LocalizationCreditsClientLoaders}). Everything fails soft: a missing or malformed file
 * leaves the map empty, which costs the tooltips their examples and nothing else. Only Dungeon
 * Train's own keys are covered — the sibling mods ship their English in their own repos, so their
 * strings fall through to the generic label.</p>
 */
public final class TranslationVariableExamples {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The shipped file, at the namespace root — same placement as translation_contributors.json. */
    private static final ResourceLocation FILE =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "translation_examples.json");

    /** Guards against one pathological entry filling the screen with a tooltip. */
    private static final int MAX_EXAMPLES = 4;

    /** key -> slot -> entry. Replaced wholesale on reload; never mutated in place. */
    private static volatile Map<String, Map<Integer, Entry>> entries = Map.of();

    /**
     * One example value: either a literal, or a lang key to be rendered in the locale being edited.
     *
     * <p>The keyed form exists because some placeholders are filled with another translated string
     * — "3 travellers" arrives as {@code …deaths.count.travellers.other}, which zh_cn renders as
     * "3 名旅人". Showing a translator the English there describes a value they will never see.</p>
     *
     * @param text the literal value, or {@code ""} for a keyed example
     * @param key  the lang key to render, or {@code ""} for a literal
     * @param args the arguments that key's own placeholders take
     */
    public record Example(String text, String key, List<String> args) {
        public Example {
            text = text == null ? "" : text;
            key = key == null ? "" : key;
            args = args == null ? List.of() : List.copyOf(args);
        }

        /** A literal example — the same in every language, so it is shown as written. */
        public static Example literal(String text) {
            return new Example(text, "", List.of());
        }

        /** An example that is itself a translated string; see {@link TranslationExampleValues}. */
        public static Example keyed(String key, List<String> args) {
            return new Example("", key, args);
        }

        public boolean isKeyed() {
            return !key.isEmpty();
        }
    }

    /**
     * One slot's curated metadata.
     *
     * @param label    what the value is, in English ("a player name")
     * @param examples a few real values; may be empty when only a label is known
     */
    public record Entry(String label, List<Example> examples) {
        public Entry {
            label = label == null ? "" : label;
            examples = examples == null ? List.of() : List.copyOf(examples);
        }
    }

    private TranslationVariableExamples() {}

    /** The entry for one slot of one key, or null when nothing is curated for it. */
    public static Entry lookup(String key, int slot) {
        Map<Integer, Entry> forKey = entries.get(key);
        return forKey == null ? null : forKey.get(slot);
    }

    /** Every curated key — used by the coverage guard, not by the screen. */
    public static Map<String, Map<Integer, Entry>> all() {
        return entries;
    }

    /** Reload from the given client {@link ResourceManager}; a missing file clears the map. */
    public static void load(ResourceManager resourceManager) {
        Optional<Resource> resource = resourceManager.getResource(FILE);
        if (resource.isEmpty()) {
            entries = Map.of();
            LOGGER.info("[DungeonTrain] TranslationVariableExamples: no {} present — "
                + "the editor's variable tooltips will have no examples.", FILE);
            return;
        }
        try (InputStream in = resource.get().open()) {
            entries = parse(new InputStreamReader(in, StandardCharsets.UTF_8));
            LOGGER.info("[DungeonTrain] TranslationVariableExamples loaded — {} key(s).",
                entries.size());
        } catch (Exception e) {
            entries = Map.of();
            LOGGER.error("[DungeonTrain] TranslationVariableExamples: failed to read {} — {}",
                FILE, e.toString());
        }
    }

    /**
     * Parse the file's contents. Free of Minecraft types so the guard test can run it against the
     * repo's copy directly; a malformed root yields an empty map rather than throwing.
     */
    public static Map<String, Map<Integer, Entry>> parse(Reader reader) {
        JsonElement root = JsonParser.parseReader(reader);
        if (root == null || !root.isJsonObject()) {
            LOGGER.error("[DungeonTrain] TranslationVariableExamples: root is not a JSON object.");
            return Map.of();
        }
        Map<String, Map<Integer, Entry>> out = new HashMap<>();
        for (Map.Entry<String, JsonElement> keyEntry : root.getAsJsonObject().entrySet()) {
            Map<Integer, Entry> slots = parseSlots(keyEntry.getKey(), keyEntry.getValue());
            if (!slots.isEmpty()) {
                out.put(keyEntry.getKey(), Map.copyOf(slots));
            }
        }
        return Map.copyOf(out);
    }

    private static Map<Integer, Entry> parseSlots(String key, JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            LOGGER.warn("[DungeonTrain] TranslationVariableExamples: {} is not an object — skipped.",
                key);
            return Map.of();
        }
        Map<Integer, Entry> slots = new HashMap<>();
        for (Map.Entry<String, JsonElement> slotEntry : element.getAsJsonObject().entrySet()) {
            int slot;
            try {
                slot = Integer.parseInt(slotEntry.getKey());
            } catch (NumberFormatException e) {
                LOGGER.warn("[DungeonTrain] TranslationVariableExamples: {} has a non-numeric slot "
                    + "\"{}\" — skipped.", key, slotEntry.getKey());
                continue;
            }
            Entry entry = parseEntry(key, slot, slotEntry.getValue());
            if (entry != null) {
                slots.put(slot, entry);
            }
        }
        return slots;
    }

    private static Entry parseEntry(String key, int slot, JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            LOGGER.warn("[DungeonTrain] TranslationVariableExamples: {} slot {} is not an object.",
                key, slot);
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        String label = object.has("label") && object.get("label").isJsonPrimitive()
            ? object.get("label").getAsString() : "";
        List<Example> examples = new ArrayList<>();
        if (object.has("examples") && object.get("examples").isJsonArray()) {
            for (JsonElement item : object.getAsJsonArray("examples")) {
                if (examples.size() >= MAX_EXAMPLES) {
                    break;
                }
                Example example = parseExample(key, slot, item);
                if (example != null) {
                    examples.add(example);
                }
            }
        }
        if (label.isBlank() && examples.isEmpty()) {
            return null; // nothing to show; treat as uncurated rather than as an empty tooltip
        }
        return new Entry(label, examples);
    }

    /** A bare string is a literal; an object names a lang key and the arguments it takes. */
    private static Example parseExample(String key, int slot, JsonElement element) {
        if (element.isJsonPrimitive()) {
            return Example.literal(element.getAsString());
        }
        if (!element.isJsonObject()) {
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        if (!object.has("key") || !object.get("key").isJsonPrimitive()) {
            LOGGER.warn("[DungeonTrain] TranslationVariableExamples: {} slot {} has an example "
                + "object with no key — skipped.", key, slot);
            return null;
        }
        List<String> args = new ArrayList<>();
        if (object.has("args") && object.get("args").isJsonArray()) {
            for (JsonElement arg : object.getAsJsonArray("args")) {
                if (arg.isJsonPrimitive()) {
                    args.add(arg.getAsString());
                }
            }
        }
        return Example.keyed(object.get("key").getAsString(), args);
    }
}
