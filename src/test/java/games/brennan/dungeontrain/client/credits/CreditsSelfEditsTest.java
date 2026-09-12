package games.brennan.dungeontrain.client.credits;

import com.google.gson.JsonParser;
import games.brennan.dungeontrain.client.credits.CreditsSelfEdits.Entry;
import games.brennan.dungeontrain.client.credits.CreditsSelfEdits.Shown;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The local overlay for this player's own credit lines — the pure rules, no disk. */
final class CreditsSelfEditsTest {

    @Test
    @DisplayName("a rename shows while the source still says the old name, and not once it has moved on")
    void renameOverlay() {
        Entry e = Entry.NONE.withRename("Ada", "Ada L");
        assertEquals(new Shown("Ada L", false), CreditsSelfEdits.apply(e, "Ada", false));
        assertEquals(new Shown("Ada L", false), CreditsSelfEdits.apply(e, "ada", false), "case-insensitive on the old name");
        assertEquals(new Shown("Ada L", false), CreditsSelfEdits.apply(e, "Ada L", false), "the relay caught up");
        assertEquals(new Shown("Lovelace", false), CreditsSelfEdits.apply(e, "Lovelace", false),
            "a name that is neither is the relay's word — a change made elsewhere is not masked");
    }

    @Test
    @DisplayName("hidden wins over everything, and the source being anonymous shows anonymous")
    void hiddenOverlay() {
        assertEquals(new Shown("", true), CreditsSelfEdits.apply(Entry.NONE.withHidden(true), "Ada", false));
        assertEquals(new Shown("", true), CreditsSelfEdits.apply(Entry.NONE.withRename("Ada", "B").withHidden(true), "Ada", false));
        assertEquals(new Shown("", true), CreditsSelfEdits.apply(Entry.NONE, "Ada", true));
        assertEquals(new Shown("Ada", false), CreditsSelfEdits.apply(Entry.NONE, "Ada", false));
    }

    @Test
    @DisplayName("a card still needs the rename only while it shows the old name and is not anonymous")
    void stillOld() {
        Entry renamed = Entry.NONE.withRename("Ada", "Ada L");
        assertTrue(CreditsSelfEdits.stillOld(renamed, "Ada", false));
        assertTrue(CreditsSelfEdits.stillOld(renamed, "ada", false));
        assertFalse(CreditsSelfEdits.stillOld(renamed, "Ada L", false), "the relay caught up");
        assertFalse(CreditsSelfEdits.stillOld(renamed, "", true), "an anonymous row says nothing about the name");
        assertFalse(CreditsSelfEdits.stillOld(Entry.NONE, "Ada", false));
    }

    @Test
    @DisplayName("an entry normalises its names, knows when it is empty, and reads back from json")
    void entryShape() {
        Entry e = new Entry(" Ada ", " Ada L ", false);
        assertEquals("Ada", e.from());
        assertEquals("Ada L", e.to());
        assertTrue(e.hasRename());
        assertFalse(new Entry("Ada", "Ada", false).hasRename(), "same name is no rename");
        assertTrue(new Entry("", "", false).isEmpty());
        assertFalse(new Entry("", "", true).isEmpty());
        assertEquals(new Entry("Ada", "Ada L", true),
            CreditsSelfEdits.parseEntry(JsonParser.parseString("{\"from\":\"Ada\",\"to\":\"Ada L\",\"hidden\":true}").getAsJsonObject()));
        assertEquals(Entry.NONE, CreditsSelfEdits.parseEntry(JsonParser.parseString("{}").getAsJsonObject()));
    }
}
