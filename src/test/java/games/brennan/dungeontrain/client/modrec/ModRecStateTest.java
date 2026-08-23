package games.brennan.dungeontrain.client.modrec;

import games.brennan.dungeontrain.modrec.ModRoster;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ordering and send-gating for the Mod Recommendations page. Pure — no Minecraft types involved.
 */
class ModRecStateTest {

    private static final List<ModRoster.LoadedMod> MODS = List.of(
            new ModRoster.LoadedMod("chunky", "Chunky"),
            new ModRoster.LoadedMod("jei", "Just Enough Items"),
            new ModRoster.LoadedMod("xaerominimap", "Xaero's Minimap"));

    private static List<String> ids(ModRecState s) {
        return s.tiles().stream().map(ModRecState.Tile::modId).toList();
    }

    private static ModRecState popular() {
        ModRecState s = new ModRecState(MODS);
        s.setPopularity(Map.of("jei", 400, "xaerominimap", 120, "chunky", 5));
        return s;
    }

    @Test
    @DisplayName("most-run first")
    void ordersByPopularity() {
        assertEquals(List.of("jei", "xaerominimap", "chunky"), ids(popular()));
    }

    @Test
    @DisplayName("no popularity data falls back to alphabetical by display name")
    void alphabeticalFallback() {
        // "Chunky" < "Just Enough Items" < "Xaero's Minimap" — note this is NOT the modId order,
        // which is what a naive sort would give.
        assertEquals(List.of("chunky", "jei", "xaerominimap"), ids(new ModRecState(MODS)));
    }

    @Test
    @DisplayName("a sent mod sinks below every unsent one")
    void sentSinks() {
        ModRecState s = popular();
        s.toggle("jei");
        s.setComment("indispensable");
        s.markSent();
        assertEquals(List.of("xaerominimap", "chunky", "jei"), ids(s));
        assertTrue(s.tiles().get(2).sent());
        assertEquals(1, s.sentCount());
    }

    @Test
    @DisplayName("re-sending a mod keeps its slot instead of jumping to the end")
    void resendKeepsSlot() {
        ModRecState s = popular();
        s.toggle("jei");
        s.setComment("first take");
        s.markSent();
        s.toggle("xaerominimap");
        s.setComment("also good");
        s.markSent();
        assertEquals(List.of("chunky", "jei", "xaerominimap"), ids(s));

        s.toggle("jei");                 // revise the first one
        s.setComment("second take");
        s.markSent();
        assertEquals(List.of("chunky", "jei", "xaerominimap"), ids(s));
        assertEquals(3, s.sentCount());
    }

    @Test
    @DisplayName("Send needs a comment; a bare tile click is not a recommendation")
    void commentRequired() {
        ModRecState s = popular();
        assertFalse(s.canSend());
        s.toggle("jei");
        assertFalse(s.canSend());
        s.setComment("   ");
        assertFalse(s.canSend());
        s.setComment("fast and complete");
        assertTrue(s.canSend());
    }

    @Test
    @DisplayName("a request needs the typed mod name, and becomes its own card once sent")
    void requestNeedsNameAndBecomesACard() {
        ModRecState s = popular();
        s.toggle(ModRecState.REQUEST_ID);
        assertTrue(s.isRequesting());
        s.setComment("would love this");
        assertFalse(s.canSend());
        s.setRequestedName("Create");
        assertTrue(s.canSend());
        assertEquals("Create", s.selectedName());

        s.markSent();
        assertEquals(1, s.sentCount());
        // The installed mods keep their order and a card for the requested one is appended.
        List<ModRecState.Tile> tiles = s.tiles();
        assertEquals(List.of("jei", "xaerominimap", "chunky"), ids(s).subList(0, 3));
        assertEquals(4, tiles.size());
        assertEquals("Create", tiles.get(3).displayName());
        assertTrue(tiles.get(3).sent());
        assertTrue(tiles.get(3).request());
    }

    @Test
    @DisplayName("each request gets its own card, even when the same name is sent twice")
    void everyRequestGetsACard() {
        ModRecState s = popular();
        for (String name : List.of("Create", "Farmer's Delight", "Create")) {
            s.toggle(ModRecState.REQUEST_ID);
            s.setRequestedName(name);
            s.setComment("please");
            s.markSent();
        }
        List<ModRecState.Tile> tiles = s.tiles();
        assertEquals(6, tiles.size());   // 3 installed + 3 request cards
        assertEquals(List.of("Create", "Farmer's Delight", "Create"),
                tiles.subList(3, 6).stream().map(ModRecState.Tile::displayName).toList());
        assertTrue(tiles.subList(3, 6).stream().allMatch(ModRecState.Tile::request));
    }

    @Test
    @DisplayName("request cards sit below sent mods, which sit below unsent ones")
    void cardOrderIsUnsentThenSentThenRequests() {
        ModRecState s = popular();
        s.toggle("jei");
        s.setComment("indispensable");
        s.markSent();
        s.toggle(ModRecState.REQUEST_ID);
        s.setRequestedName("Create");
        s.setComment("please");
        s.markSent();
        List<ModRecState.Tile> tiles = s.tiles();
        assertEquals(List.of("xaerominimap", "chunky"), ids(s).subList(0, 2));  // unsent
        assertEquals("jei", tiles.get(2).modId());                              // sent mod
        assertTrue(tiles.get(3).request());                                     // request card
    }

    @Test
    @DisplayName("the requested name is trimmed before it becomes a card")
    void requestNameTrimmed() {
        ModRecState s = popular();
        s.toggle(ModRecState.REQUEST_ID);
        s.setRequestedName("  Create  ");
        s.setComment("please");
        s.markSent();
        assertEquals("Create", s.tiles().get(3).displayName());
    }

    @Test
    @DisplayName("clicking the selected tile again deselects it and drops what was typed")
    void toggleClears() {
        ModRecState s = popular();
        s.toggle("jei");
        s.setComment("something");
        s.toggle("jei");
        assertEquals(null, s.selected());
        assertEquals("", s.comment());
        assertFalse(s.canSend());
    }

    @Test
    @DisplayName("selecting a different mod does not carry the previous comment over")
    void switchingClearsComment() {
        ModRecState s = popular();
        s.toggle("jei");
        s.setComment("about JEI");
        s.toggle("chunky");
        assertEquals("", s.comment());
        assertFalse(s.canSend());
    }

    @Test
    @DisplayName("a flagged mod sinks like a sent one and carries the reported marker")
    void hackReportSinksAndMarks() {
        ModRecState s = popular();
        s.toggleHack("jei");
        assertTrue(s.isReportingHack());
        s.setComment("x-ray through stone");
        assertTrue(s.canSend());
        s.markSent();

        assertEquals(List.of("xaerominimap", "chunky", "jei"), ids(s));
        ModRecState.Tile flagged = s.tiles().get(2);
        assertTrue(flagged.reported());
        assertTrue(flagged.sent());
        assertFalse(flagged.request());
        assertFalse(s.isReportingHack());
    }

    @Test
    @DisplayName("a mod is recommended or flagged, never both — the later send replaces the tile")
    void recommendAndFlagAreExclusive() {
        ModRecState s = popular();
        s.toggle("jei");
        s.setComment("indispensable");
        s.markSent();
        s.toggleHack("jei");
        s.setComment("actually it cheats");
        s.markSent();

        assertEquals(List.of("xaerominimap", "chunky", "jei"), ids(s));
        assertEquals(3, s.tiles().size());               // one tile for jei, not two
        assertTrue(s.tiles().get(2).reported());
    }

    @Test
    @DisplayName("the ⚠ icon does nothing on the 'not installed' tile — there is no mod to report")
    void requestTileCannotBeFlagged() {
        ModRecState s = popular();
        s.toggleHack(ModRecState.REQUEST_ID);
        assertEquals(null, s.selected());
        assertFalse(s.isReportingHack());
    }

    @Test
    @DisplayName("switching mode on the selected tile re-arms it and drops the typed reason")
    void switchingModeClearsComment() {
        ModRecState s = popular();
        s.toggle("jei");
        s.setComment("why I like it");
        s.toggleHack("jei");
        assertTrue(s.isReportingHack());
        assertEquals("", s.comment());

        s.setComment("what it lets you do");
        s.toggle("jei");                       // back to a recommendation
        assertFalse(s.isReportingHack());
        assertEquals("", s.comment());

        s.toggle("jei");                       // same mode twice deselects
        assertEquals(null, s.selected());
    }

    @Test
    @DisplayName("clicking the icon on the flagged tile again re-opens it, not a second card")
    void reflagKeepsOneTile() {
        ModRecState s = popular();
        s.toggleHack("jei");
        s.setComment("cheats");
        s.markSent();
        s.toggleHack("jei");
        s.setComment("cheats, in detail");
        s.markSent();
        assertEquals(3, s.tiles().size());
        assertEquals(List.of("xaerominimap", "chunky", "jei"), ids(s));
    }

    @Test
    @DisplayName("a mod with no display name falls back to its id on the tile")
    void namelessMod() {
        ModRecState s = new ModRecState(List.of(new ModRoster.LoadedMod("weirdmod", "")));
        assertEquals("weirdmod", s.tiles().get(0).displayName());
    }
}
