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
    @DisplayName("a request additionally needs the typed mod name, and never becomes a sent tile")
    void requestNeedsNameAndDoesNotSink() {
        ModRecState s = popular();
        s.toggle(ModRecState.REQUEST_ID);
        assertTrue(s.isRequesting());
        s.setComment("would love this");
        assertFalse(s.canSend());
        s.setRequestedName("Create");
        assertTrue(s.canSend());
        assertEquals("Create", s.selectedName());

        s.markSent();
        assertEquals(List.of("jei", "xaerominimap", "chunky"), ids(s));  // grid unchanged
        assertEquals(1, s.sentCount());
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
    @DisplayName("a mod with no display name falls back to its id on the tile")
    void namelessMod() {
        ModRecState s = new ModRecState(List.of(new ModRoster.LoadedMod("weirdmod", "")));
        assertEquals("weirdmod", s.tiles().get(0).displayName());
    }
}
