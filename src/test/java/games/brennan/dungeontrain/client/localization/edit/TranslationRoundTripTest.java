package games.brennan.dungeontrain.client.localization.edit;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure halves of the translator round trip: CSV encoding, book-field flattening, and writing
 * edited prose back into a book's JSON. All three sit between the game and files a human edits
 * by hand, so they are exactly where a silent mangling would go unnoticed.
 *
 * <p>No Minecraft bootstrap needed — these classes deliberately take text in and give text back.
 * </p>
 */
class TranslationRoundTripTest {

    // ------------------------------------------------------------------ CSV

    @Test
    @DisplayName("plain fields are written unquoted and parse back identically")
    void csvPlainRoundTrip() {
        List<String> row = List.of("gui.dungeontrain.x", "needs_first_review", "Depart");
        assertEquals("gui.dungeontrain.x,needs_first_review,Depart", Csv.writeRow(row));
        assertEquals(List.of(row), Csv.parse(Csv.writeRow(row) + "\n"));
    }

    @Test
    @DisplayName("commas, quotes and newlines survive a write/parse round trip")
    void csvAwkwardRoundTrip() {
        // Every one of these occurs in the real data: prose has commas, book text has quoted
        // speech, and book variants run to several paragraphs.
        List<String> row = List.of("key", "One, two", "She said \"go\"", "line one\nline two");
        List<List<String>> parsed = Csv.parse(Csv.writeRow(row) + "\n");
        assertEquals(1, parsed.size());
        assertEquals(row, parsed.get(0));
    }

    @Test
    @DisplayName("a newline inside a quoted field does not start a new row")
    void csvEmbeddedNewlineIsNotARowBreak() {
        String text = "key,new_translation\nfoo,\"first\nsecond\"\nbar,baz\n";
        List<List<String>> rows = Csv.parse(text);
        assertEquals(3, rows.size());
        assertEquals("first\nsecond", rows.get(1).get(1));
        assertEquals("bar", rows.get(2).get(0));
    }

    @Test
    @DisplayName("CRLF line endings parse the same as LF")
    void csvHandlesCrlf() {
        // A translator round-tripping through Excel on Windows gets CRLF back.
        assertEquals(Csv.parse("a,b\nc,d\n"), Csv.parse("a,b\r\nc,d\r\n"));
    }

    @Test
    @DisplayName("empty trailing fields are preserved, not dropped")
    void csvKeepsEmptyTrailingFields() {
        // The review CSV's last two columns are usually blank; losing them shifts every value.
        assertEquals(List.of(List.of("key", "", "")), Csv.parse("key,,\n"));
    }

    // ------------------------------------------------------------------ book flattening

    private static final String BOOK = """
        {
          "id": "deathnote",
          "_translator_note": "keep the title short",
          "title": "Death Note",
          "author": "Anonymous",
          "generation": 0,
          "weight": 1,
          "variants": ["first variant", "second variant"],
          "unused": ["retired prose"]
        }
        """;

    @Test
    @DisplayName("every prose string is flattened to a dotted path, in document order")
    void flattenEmitsProse() {
        Map<String, String> fields = NarrativeBookFields.flatten(BOOK);
        assertEquals(List.of("title", "author", "variants.0", "variants.1"),
            List.copyOf(fields.keySet()));
        assertEquals("second variant", fields.get("variants.1"));
    }

    @Test
    @DisplayName("structural keys are never offered for translation")
    void flattenSkipsStructuralKeys() {
        // validate-locale.py requires these to match the es_es reference byte-for-byte, so
        // offering them for edit is offering a translator a way to fail CI.
        Map<String, String> fields = NarrativeBookFields.flatten(BOOK);
        assertFalse(fields.containsKey("id"));
        assertFalse(fields.containsKey("_translator_note"));
    }

    @Test
    @DisplayName("retired 'unused' prose is skipped")
    void flattenSkipsUnused() {
        assertFalse(NarrativeBookFields.flatten(BOOK).containsKey("unused.0"));
    }

    @Test
    @DisplayName("nested story shapes flatten without special-casing")
    void flattenHandlesNesting() {
        String story = """
            {"id": "s", "character": "Aelis", "story": "Part one",
             "letters": [{"index": 1, "label": "Letter one", "variants": ["body"]}]}
            """;
        Map<String, String> fields = NarrativeBookFields.flatten(story);
        assertEquals("Letter one", fields.get("letters.0.label"));
        assertEquals("body", fields.get("letters.0.variants.0"));
        assertNull(fields.get("letters.0.index"));
    }

    @Test
    @DisplayName("a top-level array (death lore) flattens by index")
    void flattenHandlesTopLevelArray() {
        String lore = """
            [{"page": "one", "weight": 2, "question": "Why?", "narration": "Because."}]
            """;
        Map<String, String> fields = NarrativeBookFields.flatten(lore);
        assertEquals("Why?", fields.get("0.question"));
        assertEquals("Because.", fields.get("0.narration"));
        assertFalse(fields.containsKey("0.page")); // structural
    }

    @Test
    @DisplayName("malformed JSON yields no fields rather than throwing")
    void flattenToleratesGarbage() {
        assertTrue(NarrativeBookFields.flatten("{not json").isEmpty());
        assertTrue(NarrativeBookFields.flatten(null).isEmpty());
    }

    // ------------------------------------------------------------------ book writing

    @Test
    @DisplayName("edited prose writes back to the same paths it was flattened from")
    void writeBackRoundTrip() {
        JsonElement root = JsonParser.parseString(BOOK);
        int applied = NarrativeBookWriter.apply(root,
            Map.of("title", "Cuaderno de muerte", "variants.1", "segunda variante"));
        assertEquals(2, applied);

        Map<String, String> fields = NarrativeBookFields.flatten(root.toString());
        assertEquals("Cuaderno de muerte", fields.get("title"));
        assertEquals("segunda variante", fields.get("variants.1"));
        assertEquals("first variant", fields.get("variants.0"));
    }

    @Test
    @DisplayName("structural fields survive a write-back untouched")
    void writeBackPreservesStructure() {
        // The exported file has to stay a drop-in replacement for the repo's copy.
        JsonElement root = JsonParser.parseString(BOOK);
        NarrativeBookWriter.apply(root, Map.of("title", "X"));
        var obj = root.getAsJsonObject();
        assertEquals("deathnote", obj.get("id").getAsString());
        assertEquals(1, obj.get("weight").getAsInt());
        assertEquals("keep the title short", obj.get("_translator_note").getAsString());
    }

    @Test
    @DisplayName("a path that no longer exists is skipped, not invented")
    void writeBackIgnoresStalePaths() {
        JsonElement root = JsonParser.parseString(BOOK);
        assertEquals(0, NarrativeBookWriter.apply(root,
            Map.of("variants.9", "nope", "nosuchfield", "nope")));
        assertFalse(root.getAsJsonObject().has("nosuchfield"));
    }

    // ------------------------------------------------------------------ edits value type

    @Test
    @DisplayName("numeric format specifiers are rewritten the way vanilla rewrites them")
    void normalizeRewritesFormatSpecifiers() {
        // Otherwise a translator typing %d makes String.format throw on every render.
        assertEquals("%s left", TranslationEdits.normalizeValue("%d left"));
        assertEquals("%1$s of %2$s", TranslationEdits.normalizeValue("%1$d of %2$d"));
        assertEquals("100% sure", TranslationEdits.normalizeValue("100% sure"));
    }

    @Test
    @DisplayName("blank and oversized values mean 'no override'")
    void normalizeRejectsUnusableValues() {
        assertNull(TranslationEdits.normalizeValue(""));
        assertNull(TranslationEdits.normalizeValue("   "));
        assertNull(TranslationEdits.normalizeValue(null));
        assertNull(TranslationEdits.normalizeValue("x".repeat(TranslationEdits.MAX_VALUE_CHARS + 1)));
    }

    @Test
    @DisplayName("clearing a key removes it instead of storing an empty string")
    void clearingRemovesTheOverride() {
        TranslationEdits edits = TranslationEdits.empty("de_de").withLang("a", "Alpha");
        assertEquals(1, edits.size());
        assertTrue(edits.withLang("a", "").isEmpty());
    }

    // ---- what a resubmit carries ----------------------------------------------
    //
    // TranslationOverrides.unsubmittedFor is the same set difference, but it reads its two sides
    // off disk. These pin the rule itself: only a value that differs from what was sent goes.

    private static java.util.Map<String, String> outstanding(TranslationEdits local,
                                                             TranslationEdits sent) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        local.lang().forEach((key, value) -> {
            if (!value.equals(sent.lang().get(key))) {
                out.put(key, value);
            }
        });
        return out;
    }

    @Test
    @DisplayName("an edit already sent is not sent again")
    void alreadySentIsNotResent() {
        TranslationEdits local = TranslationEdits.empty("de_de").withLang("a", "Alpha");
        assertTrue(outstanding(local, local).isEmpty());
    }

    @Test
    @DisplayName("re-editing a key that was already sent makes it outstanding again")
    void reEditedIsOutstandingAgain() {
        TranslationEdits sent = TranslationEdits.empty("de_de").withLang("a", "Alpha");
        TranslationEdits local = sent.withLang("a", "Alpha II");
        assertEquals(java.util.Map.of("a", "Alpha II"), outstanding(local, sent));
    }

    @Test
    @DisplayName("only the new keys go when some were sent before")
    void onlyNewKeysGo() {
        TranslationEdits sent = TranslationEdits.empty("de_de").withLang("a", "Alpha");
        TranslationEdits local = sent.withLang("b", "Beta");
        assertEquals(java.util.Map.of("b", "Beta"), outstanding(local, sent));
    }

    @Test
    @DisplayName("reverting an edit leaves nothing outstanding")
    void revertedIsNotOutstanding() {
        // An override that no longer exists is not work waiting to be sent — the relay keeps
        // whatever it already has, and there is nothing here to tell it.
        TranslationEdits sent = TranslationEdits.empty("de_de").withLang("a", "Alpha");
        assertTrue(outstanding(TranslationEdits.empty("de_de"), sent).isEmpty());
    }

    @Test
    @DisplayName("local edits win over the relay-approved layer")
    void localBeatsApproved() {
        // A translator must always see their own text, even once the relay serves a different
        // approved translation for the same key.
        TranslationEdits approved = TranslationEdits.empty("de_de")
            .withLang("a", "approved").withLang("b", "also approved");
        TranslationEdits local = TranslationEdits.empty("de_de").withLang("a", "mine");
        TranslationEdits merged = approved.mergedWith(local);
        assertEquals("mine", merged.lang().get("a"));
        assertEquals("also approved", merged.lang().get("b"));
    }
}
