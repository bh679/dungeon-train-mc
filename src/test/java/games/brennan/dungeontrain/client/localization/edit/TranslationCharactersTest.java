package games.brennan.dungeontrain.client.localization.edit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The character file behind the editor's "About …" link and its character filter.
 *
 * <p>Two halves: the parser and matcher on hand-written input, and a guard over the shipped file —
 * every book, key and prefix it names has to still exist, and every story with a narrator has to
 * have a card. A rename that orphans an entry would otherwise just quietly take a button away.</p>
 */
class TranslationCharactersTest {

    private static final Path FILE =
        Path.of("src/main/resources/assets/dungeontrain/translation_characters.json");
    private static final Path LANG =
        Path.of("src/main/resources/assets/dungeontrain/lang/en_us.json");
    private static final Path NARRATIVES =
        Path.of("src/main/resources/data/dungeontrain/narratives");

    private static TranslationCharacters.Index parse(String json) {
        return TranslationCharacters.parse(new StringReader(json));
    }

    private static TranslationUnit lang(String key) {
        return new TranslationUnit(TranslationUnit.Type.LANG, "dungeontrain", key, "", "", false,
            false);
    }

    private static TranslationUnit book(String path, String field) {
        return book(path, field, "");
    }

    private static TranslationUnit book(String path, String field, String english) {
        return new TranslationUnit(TranslationUnit.Type.BOOK, "dungeontrain", path + "#" + field,
            english, "", false, false);
    }

    private static final String SAMPLE = """
        { "characters": {
            "della": { "name": "Della Aaro", "pronouns": "she/her", "gender": "Female",
                       "age": "—", "books": ["stories/della"] },
            "pip":   { "name": "Pip Aaro", "mentions": ["Pip"], "books": ["stories/pip"] },
            "faul":  { "name": "Faulthurst", "keys": ["adv.faul.title"],
                       "key_prefixes": ["book.statbook."] },
            "faul_tail": { "name": "Tail Voice", "key_prefixes": ["book.statbook.tail."] }
        } }
        """;

    @Test
    @DisplayName("every field of a character's book belongs to them")
    void bookUnitsMatchByPath() {
        var index = parse(SAMPLE);
        assertEquals("della", index.forUnit(book("stories/della", "letters.2.variants.1"))
            .orElseThrow().id());
        assertEquals("della", index.forUnit(book("stories/della", "title")).orElseThrow().id());
        assertTrue(index.forUnit(book("stories/other", "title")).isEmpty());
    }

    @Test
    @DisplayName("lang keys match exactly first, then by the longest prefix")
    void langUnitsMatchByKeyThenLongestPrefix() {
        var index = parse(SAMPLE);
        assertEquals("faul", index.forUnit(lang("adv.faul.title")).orElseThrow().id());
        assertEquals("faul", index.forUnit(lang("book.statbook.open.3")).orElseThrow().id());
        assertEquals("faul_tail", index.forUnit(lang("book.statbook.tail.12")).orElseThrow().id());
        assertTrue(index.forUnit(lang("gui.something.else")).isEmpty());
    }

    @Test
    @DisplayName("a sibling mod's key never matches, even under a mapped prefix")
    void otherNamespacesNeverMatch() {
        var index = parse(SAMPLE);
        var sibling = new TranslationUnit(TranslationUnit.Type.LANG, "playermob",
            "book.statbook.open.3", "", "", false, false);
        assertTrue(index.forUnit(sibling).isEmpty());
    }

    @Test
    @DisplayName("a mention is a whole, case-sensitive word")
    void mentionsAreWholeWords() {
        var pip = parse(SAMPLE).byId().get("pip");
        assertTrue(pip.isMentionedIn("Pip waited by the door."));
        assertTrue(pip.isMentionedIn("Where is my Pip?"));
        assertTrue(!pip.isMentionedIn("The pipe rattled."));
        assertTrue(!pip.isMentionedIn("Pippa sang."));
        assertTrue(!pip.isMentionedIn("pip"));
    }

    @Test
    @DisplayName("a book belongs to its narrator and to everyone it mentions, in every field")
    void booksTakeInEveryoneTheyMention() {
        var index = parse(SAMPLE);
        var units = List.of(
            book("stories/della", "title", "The searching mother"),
            book("stories/della", "letters.0", "I am looking for Pip."),
            book("stories/other", "title", "Nobody here"));
        var mentions = index.bookMentions(units);
        assertEquals(java.util.Set.of("della", "pip"), index.idsFor(units.get(0), mentions));
        assertEquals(java.util.Set.of(), index.idsFor(units.get(2), mentions));
        // a lang line is only ever its mapped owner
        assertEquals(java.util.Set.of("faul"), index.idsFor(lang("adv.faul.title"), mentions));
    }

    @Test
    @DisplayName("a dash or a missing field reads as not stated")
    void dashesAndGapsAreBlank() {
        var della = parse(SAMPLE).byId().get("della");
        assertEquals("she/her", della.pronouns());
        assertEquals("", della.age());
        assertEquals("", della.notes());
    }

    @Test
    @DisplayName("characters sort by name for the filter")
    void sortedByName() {
        var names = parse(SAMPLE).sorted().stream()
            .map(TranslationCharacters.Character::name).toList();
        assertEquals(List.of("Della Aaro", "Faulthurst", "Pip Aaro", "Tail Voice"), names);
    }

    @Test
    @DisplayName("malformed input is an empty index, never an exception")
    void malformedInputIsEmpty() {
        assertTrue(parse("[]").byId().isEmpty());
        assertTrue(parse("{}").byId().isEmpty());
        assertTrue(parse("{ \"characters\": [] }").byId().isEmpty());
        assertTrue(parse("{ \"characters\": { \"x\": 3, \"y\": { \"pronouns\": \"he\" } } }")
            .byId().isEmpty());
    }

    // ---- the shipped file --------------------------------------------------------------------

    private static TranslationCharacters.Index shipped() throws IOException {
        try (Reader reader = Files.newBufferedReader(RepoPaths.root().resolve(FILE),
            StandardCharsets.UTF_8)) {
            return TranslationCharacters.parse(reader);
        }
    }

    private static JsonObject englishLang() throws IOException {
        try (Reader reader = Files.newBufferedReader(RepoPaths.root().resolve(LANG),
            StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    @Test
    @DisplayName("every book, key and prefix the shipped file names still exists")
    void shippedReferencesResolve() throws IOException {
        var index = shipped();
        assertTrue(index.byId().size() > 0, "the shipped character file parsed empty");
        JsonObject lang = englishLang();
        List<String> missing = new ArrayList<>();
        for (var character : index.byId().values()) {
            for (String bookPath : character.books()) {
                if (!Files.isRegularFile(
                    RepoPaths.root().resolve(NARRATIVES).resolve(bookPath + ".json"))) {
                    missing.add(character.id() + " book " + bookPath);
                }
            }
            for (String key : character.keys()) {
                if (!lang.has(key)) {
                    missing.add(character.id() + " key " + key);
                }
            }
            for (String prefix : character.keyPrefixes()) {
                if (lang.keySet().stream().noneMatch(key -> key.startsWith(prefix))) {
                    missing.add(character.id() + " prefix " + prefix);
                }
            }
        }
        assertEquals(List.of(), missing, "translation_characters.json points at strings that are gone");
    }

    /** One BOOK unit per shipped book, its whole file as the English — enough to find mentions. */
    private static List<TranslationUnit> shippedBookUnits() throws IOException {
        Path root = RepoPaths.root().resolve(NARRATIVES);
        List<TranslationUnit> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                String rel = root.relativize(file).toString().replace('\\', '/');
                out.add(book(rel.substring(0, rel.length() - ".json".length()), "all",
                    Files.readString(file, StandardCharsets.UTF_8)));
            }
        }
        return out;
    }

    @Test
    @DisplayName("every character has lines for the filter to show")
    void everyCharacterHasLines() throws IOException {
        var index = shipped();
        List<TranslationUnit> units = new ArrayList<>(shippedBookUnits());
        englishLang().keySet().forEach(key -> units.add(lang(key)));
        var mentions = index.bookMentions(units);
        java.util.Set<String> present = new java.util.HashSet<>();
        units.forEach(unit -> present.addAll(index.idsFor(unit, mentions)));
        List<String> empty = index.byId().keySet().stream()
            .filter(id -> !present.contains(id)).sorted().toList();
        assertEquals(List.of(), empty, "characters whose filter would show nothing");
    }

    @Test
    @DisplayName("a character's filter takes in the books that mention them")
    void shippedMentionsReachOtherBooks() throws IOException {
        var index = shipped();
        List<TranslationUnit> units = shippedBookUnits();
        var mentions = index.bookMentions(units);
        assertTrue(mentions.getOrDefault("stories/della_aaro_the_searching_mother", java.util.Set.of())
            .contains("pip_aaro"), "Della's letters are about Pip");
        assertTrue(mentions.getOrDefault("stories/augustus_park", java.util.Set.of())
            .contains("faulthurst"), "Augustus writes about the mouse (as Faulhurst)");
    }

    @Test
    @DisplayName("every story with a narrator has a character card")
    void everyStoryCharacterHasACard() throws IOException {
        var index = shipped();
        List<String> uncovered = new ArrayList<>();
        try (Stream<Path> files = Files.list(RepoPaths.root().resolve(NARRATIVES).resolve("stories"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                JsonObject story;
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    story = JsonParser.parseReader(reader).getAsJsonObject();
                }
                if (!story.has("character")) {
                    continue;
                }
                String name = file.getFileName().toString();
                String bookPath = "stories/" + name.substring(0, name.length() - ".json".length());
                if (!index.byBook().containsKey(bookPath)) {
                    uncovered.add(bookPath);
                }
            }
        }
        assertEquals(List.of(), uncovered,
            "stories with a \"character\" but no entry in translation_characters.json");
    }
}
