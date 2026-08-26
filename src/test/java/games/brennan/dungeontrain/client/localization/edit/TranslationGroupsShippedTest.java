package games.brennan.dungeontrain.client.localization.edit;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The grouping rule against the text the mod actually ships.
 *
 * <p>{@link TranslationGroupsTest} pins the rule; this pins the rule to reality. The sets it finds
 * are real content — thirteen wordings of one chat message, thirty-nine death narrations — and a
 * refactor that quietly stopped finding them would still pass every unit test while leaving the
 * editor with nothing to group.</p>
 */
class TranslationGroupsShippedTest {

    private static final Path LANG =
        Path.of("src/main/resources/assets/dungeontrain/lang/en_us.json");
    private static final Path DEATH_LORE =
        Path.of("src/main/resources/data/dungeontrain/death_lore/default.json");
    private static final Path FIELD_NOTES = Path.of(
        "src/main/resources/data/dungeontrain/narratives/random_books/adventurers_field_notes.json");

    private static List<TranslationUnit> langUnits() throws IOException {
        List<TranslationUnit> units = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(RepoPaths.root().resolve(LANG),
                StandardCharsets.UTF_8)) {
            for (Map.Entry<String, JsonElement> entry
                    : JsonParser.parseReader(reader).getAsJsonObject().entrySet()) {
                units.add(new TranslationUnit(TranslationUnit.Type.LANG, "dungeontrain",
                    entry.getKey(), entry.getValue().getAsString(), "", true));
            }
        }
        return units;
    }

    private static List<TranslationUnit> bookUnits(Path book, String bookPath) throws IOException {
        String json = Files.readString(RepoPaths.root().resolve(book), StandardCharsets.UTF_8);
        List<TranslationUnit> units = new ArrayList<>();
        for (Map.Entry<String, String> field : NarrativeBookFields.flatten(json).entrySet()) {
            units.add(new TranslationUnit(TranslationUnit.Type.BOOK, "dungeontrain",
                bookPath + "#" + field.getKey(), field.getValue(), "", true));
        }
        return units;
    }

    private static int setSize(Map<String, List<TranslationUnit>> index, String id,
                               List<TranslationUnit> all) {
        for (TranslationUnit unit : all) {
            if (unit.id().equals(id)) {
                return TranslationGroups.membersOf(index, unit).size();
            }
        }
        return -1; // the string is gone, which the assertion will report as loudly as a wrong count
    }

    @Test
    @DisplayName("the shipped English still groups into real sets of variations")
    void langSetsAreFound() throws IOException {
        List<TranslationUnit> units = langUnits();
        Map<String, List<TranslationUnit>> index = TranslationGroups.index(units);
        assertTrue(index.size() > 20,
            "expected dozens of sets in the shipped English, found " + index.size());
        // Thirteen wordings of one chat line — the case that prompted this feature.
        assertEquals(13, setSize(index, "chat.dungeontrain.familiar_book.1", units));
        // And a string that is one of a kind stays one of a kind.
        assertEquals(0, setSize(index, "gui.dungeontrain.options.title", units));
    }

    @Test
    @DisplayName("a book's variants are one set, and death lore groups by the field it repeats")
    void bookSetsAreFound() throws IOException {
        List<TranslationUnit> variants =
            bookUnits(FIELD_NOTES, "random_books/adventurers_field_notes");
        Map<String, List<TranslationUnit>> variantIndex = TranslationGroups.index(variants);
        assertEquals(1, variantIndex.size(), "a random book has exactly one set — its variants");
        assertTrue(variantIndex.values().iterator().next().size() > 10);
        // title and author are single fields, so they are in no set.
        assertEquals(0, setSize(variantIndex, "random_books/adventurers_field_notes#title", variants));

        // Death lore is a bare array of entries, so its sets run ACROSS the entries: every entry's
        // question is one set, every entry's narration another, and the two are never mixed.
        List<TranslationUnit> lore = bookUnits(DEATH_LORE, "death_lore/default");
        Map<String, List<TranslationUnit>> loreIndex = TranslationGroups.index(lore);
        int questions = setSize(loreIndex, "death_lore/default#0.question", lore);
        assertTrue(questions > 30, "expected every death-lore question in one set, got " + questions);
        assertEquals(questions, setSize(loreIndex, "death_lore/default#0.narration", lore));
        // A field only one entry carries (subline) is not a set, however the walk reaches it.
        for (List<TranslationUnit> members : loreIndex.values()) {
            assertTrue(members.size() > 1, "a set of one is not a set");
        }
    }
}
