package games.brennan.dungeontrain.client.localization.edit;

import com.google.gson.JsonArray;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Who speaks, or is spoken of, in a string — for the editor's character card and filter.
 *
 * <p>Many languages cannot write a sentence about Della or Faulthurst without knowing their gender,
 * and sometimes their age: the pronoun, the verb ending and the adjective all agree with it. Most
 * of the stories are written in the first person, so the English never says. This file is where
 * the author does.</p>
 *
 * <p>Hand-authored, like {@link TranslationVariableExamples}: a lang key's name does not identify
 * a speaker, so nothing can derive this. A book unit is matched by its book path (every field of
 * Della's letters is Della's); a lang unit by its exact key first, then by the longest key prefix.
 * The filter also takes in every book whose English names the character — its {@code mentions},
 * matched as whole words.</p>
 *
 * <pre>{@code
 * { "characters": {
 *     "della_aaro": {
 *       "name": "Della Aaro", "pronouns": "she/her", "gender": "Female",
 *       "age": "Adult (mother)", "about": "…", "notes": "…",
 *       "books": ["stories/della_aaro_the_searching_mother"],
 *       "keys": [], "key_prefixes": []
 *     } } }
 * }</pre>
 *
 * <p>Fails soft: a missing or malformed file means no character buttons and an empty filter, and
 * nothing else changes.</p>
 */
public final class TranslationCharacters {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ResourceLocation FILE =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "translation_characters.json");

    /** Only Dungeon Train's own strings are mapped; the sibling mods' keys never match. */
    private static final String NAMESPACE = DungeonTrain.MOD_ID;

    /**
     * One character's card. Every text field is {@code ""} when the text never establishes it —
     * the card says "Not stated" rather than guessing.
     */
    public record Character(String id, String name, String pronouns, String gender, String age,
                            String about, String notes, List<String> books, List<String> keys,
                            List<String> keyPrefixes, List<String> mentions) {
        public Character {
            name = clean(name);
            pronouns = clean(pronouns);
            gender = clean(gender);
            age = clean(age);
            about = clean(about);
            notes = clean(notes);
            books = books == null ? List.of() : List.copyOf(books);
            keys = keys == null ? List.of() : List.copyOf(keys);
            keyPrefixes = keyPrefixes == null ? List.of() : List.copyOf(keyPrefixes);
            mentions = mentions == null ? List.of() : List.copyOf(mentions);
        }

        /**
         * Whether {@code text} names this character — any of its {@code mentions} as a whole,
         * case-sensitive word, so "Pip" finds Della's letters about her daughter but not "pipe".
         */
        public boolean isMentionedIn(String text) {
            if (text == null || text.isEmpty()) {
                return false;
            }
            for (String mention : mentions) {
                if (containsWord(text, mention)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean containsWord(String text, String word) {
            for (int at = text.indexOf(word); at >= 0; at = text.indexOf(word, at + 1)) {
                int end = at + word.length();
                boolean startOk = at == 0 || !java.lang.Character.isLetterOrDigit(text.charAt(at - 1));
                boolean endOk = end == text.length()
                    || !java.lang.Character.isLetterOrDigit(text.charAt(end));
                if (startOk && endOk) {
                    return true;
                }
            }
            return false;
        }

        /** "—" is how the hand-edited list writes "not stated"; it means the same as blank. */
        private static String clean(String value) {
            if (value == null) {
                return "";
            }
            String trimmed = value.trim();
            return trimmed.equals("—") || trimmed.equals("-") ? "" : trimmed;
        }
    }

    /** The parsed file, with the three lookups the editor needs built once. */
    public record Index(Map<String, Character> byId, Map<String, Character> byBook,
                        Map<String, Character> byKey, Map<String, Character> byPrefix) {

        static final Index EMPTY = new Index(Map.of(), Map.of(), Map.of(), Map.of());

        /** The character a unit belongs to, if any. */
        public Optional<Character> forUnit(TranslationUnit unit) {
            if (unit == null || !NAMESPACE.equals(unit.namespace())) {
                return Optional.empty();
            }
            if (unit.type() == TranslationUnit.Type.BOOK) {
                return Optional.ofNullable(byBook.get(unit.bookPath()));
            }
            Character exact = byKey.get(unit.id());
            if (exact != null) {
                return Optional.of(exact);
            }
            Character best = null;
            int bestLength = -1;
            for (Map.Entry<String, Character> entry : byPrefix.entrySet()) {
                String prefix = entry.getKey();
                if (prefix.length() > bestLength && unit.id().startsWith(prefix)) {
                    best = entry.getValue();
                    bestLength = prefix.length();
                }
            }
            return Optional.ofNullable(best);
        }

        /**
         * Which characters each book names, from the English of every field — so a filter on Pip
         * also finds her mother's letters about her. Built once per catalog by the caller.
         */
        public Map<String, java.util.Set<String>> bookMentions(List<TranslationUnit> units) {
            Map<String, java.util.Set<String>> out = new HashMap<>();
            for (TranslationUnit unit : units) {
                if (unit.type() != TranslationUnit.Type.BOOK
                    || !NAMESPACE.equals(unit.namespace())) {
                    continue;
                }
                for (Character character : byId.values()) {
                    if (character.isMentionedIn(unit.source())) {
                        out.computeIfAbsent(unit.bookPath(), path -> new java.util.HashSet<>())
                            .add(character.id());
                    }
                }
            }
            return out;
        }

        /**
         * Every character a unit belongs to: the one it is by or about ({@link #forUnit}), plus,
         * for a book field, everyone the book mentions anywhere.
         */
        public java.util.Set<String> idsFor(TranslationUnit unit,
                                            Map<String, java.util.Set<String>> bookMentions) {
            java.util.Set<String> out = new java.util.HashSet<>();
            forUnit(unit).ifPresent(character -> out.add(character.id()));
            if (unit != null && unit.type() == TranslationUnit.Type.BOOK) {
                out.addAll(bookMentions.getOrDefault(unit.bookPath(), java.util.Set.of()));
            }
            return out;
        }

        /** Every character, sorted by name — the order the filter cycles through them. */
        public List<Character> sorted() {
            List<Character> out = new ArrayList<>(byId.values());
            out.sort(Comparator.comparing(c -> c.name().toLowerCase(Locale.ROOT)));
            return out;
        }
    }

    private static volatile Index index = Index.EMPTY;

    private TranslationCharacters() {}

    /** The character {@code unit} belongs to, if any. */
    public static Optional<Character> forUnit(TranslationUnit unit) {
        return index.forUnit(unit);
    }

    /** See {@link Index#bookMentions}. */
    public static Map<String, java.util.Set<String>> bookMentions(List<TranslationUnit> units) {
        return index.bookMentions(units);
    }

    /** See {@link Index#idsFor}. */
    public static java.util.Set<String> idsFor(TranslationUnit unit,
                                               Map<String, java.util.Set<String>> bookMentions) {
        return index.idsFor(unit, bookMentions);
    }

    /** One character by id, or null. */
    public static Character byId(String id) {
        return id == null ? null : index.byId().get(id);
    }

    /** Every character, sorted by name. */
    public static List<Character> all() {
        return index.sorted();
    }

    /** Reload from the client {@link ResourceManager}; a missing file clears the index. */
    public static void load(ResourceManager resourceManager) {
        Optional<Resource> resource = resourceManager.getResource(FILE);
        if (resource.isEmpty()) {
            index = Index.EMPTY;
            LOGGER.info("[DungeonTrain] TranslationCharacters: no {} present — the translation "
                + "editor will show no character cards.", FILE);
            return;
        }
        try (InputStream in = resource.get().open()) {
            index = parse(new InputStreamReader(in, StandardCharsets.UTF_8));
            LOGGER.info("[DungeonTrain] TranslationCharacters loaded — {} character(s).",
                index.byId().size());
        } catch (Exception e) {
            index = Index.EMPTY;
            LOGGER.error("[DungeonTrain] TranslationCharacters: failed to read {} — {}",
                FILE, e.toString());
        }
    }

    /**
     * Parse the file's contents. Free of Minecraft types so the tests can run it against the repo's
     * copy; a malformed root yields an empty index rather than throwing.
     */
    public static Index parse(Reader reader) {
        JsonElement root = JsonParser.parseReader(reader);
        if (root == null || !root.isJsonObject()
            || !root.getAsJsonObject().has("characters")
            || !root.getAsJsonObject().get("characters").isJsonObject()) {
            LOGGER.error("[DungeonTrain] TranslationCharacters: no \"characters\" object.");
            return Index.EMPTY;
        }
        Map<String, Character> byId = new LinkedHashMap<>();
        Map<String, Character> byBook = new HashMap<>();
        Map<String, Character> byKey = new HashMap<>();
        Map<String, Character> byPrefix = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry
            : root.getAsJsonObject().getAsJsonObject("characters").entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                LOGGER.warn("[DungeonTrain] TranslationCharacters: {} is not an object — skipped.",
                    entry.getKey());
                continue;
            }
            JsonObject object = entry.getValue().getAsJsonObject();
            Character character = new Character(entry.getKey(), string(object, "name"),
                string(object, "pronouns"), string(object, "gender"), string(object, "age"),
                string(object, "about"), string(object, "notes"), strings(object, "books"),
                strings(object, "keys"), strings(object, "key_prefixes"),
                strings(object, "mentions"));
            if (character.name().isEmpty()) {
                LOGGER.warn("[DungeonTrain] TranslationCharacters: {} has no name — skipped.",
                    entry.getKey());
                continue;
            }
            byId.put(character.id(), character);
            character.books().forEach(book -> claim(byBook, book, character));
            character.keys().forEach(key -> claim(byKey, key, character));
            character.keyPrefixes().forEach(prefix -> claim(byPrefix, prefix, character));
        }
        return new Index(Map.copyOf(byId), Map.copyOf(byBook), Map.copyOf(byKey),
            Map.copyOf(byPrefix));
    }

    /** First claim wins; a second is a mistake in the file, so it is logged rather than silent. */
    private static void claim(Map<String, Character> map, String ref, Character character) {
        Character previous = map.putIfAbsent(ref, character);
        if (previous != null && previous != character) {
            LOGGER.warn("[DungeonTrain] TranslationCharacters: {} is claimed by both {} and {} — "
                + "keeping {}.", ref, previous.id(), character.id(), previous.id());
        }
    }

    private static String string(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static List<String> strings(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonElement item : (JsonArray) value) {
            if (item.isJsonPrimitive() && !item.getAsString().isBlank()) {
                out.add(item.getAsString());
            }
        }
        return out;
    }
}
