package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
import games.brennan.dungeontrain.train.CarriageContentsPools;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link EditorTypeMenus#withPoolHeaders}: the in-world Contents menu gathers each pool's groups under a header. */
final class EditorTypeMenusPoolHeadersTest {

    @AfterEach
    void restore() {
        CarriageContentsPools.clearCache();
    }

    private static EditorTypeMenusPacket.Variant row(String id, int weight) {
        return new EditorTypeMenusPacket.Variant(id, weight, 0, -1, 1, "CONTENTS", id, id, false, false);
    }

    @Test
    @DisplayName("the bundled Enemies pool's groups follow one header row, in pool order, where the first stood")
    void groupsGatherUnderHeader() {
        List<EditorTypeMenusPacket.Variant> rows = EditorTypeMenus.withPoolHeaders(List.of(
            row("books", 4), row("enemies_nether", 60), row("cows", 2), row("enemies_undead", 67)));
        assertEquals(List.of("books", "enemies", "enemies_undead", "enemies_nether", "cows"),
            rows.stream().map(EditorTypeMenusPacket.Variant::name).toList());
        EditorTypeMenusPacket.Variant header = rows.get(1);
        assertTrue(header.poolHeader());
        assertEquals("Enemies", header.displayName());
        assertEquals(8, header.weight());
        assertTrue(rows.get(2).isPooled());
        assertEquals(EditorTypeMenusPacket.Variant.NO_GATE, rows.get(2).phaseMask());
        assertFalse(rows.get(0).isPooled());
        assertEquals(1, rows.get(0).phaseMask(), "rows outside a pool keep their gate");
    }

    @Test
    @DisplayName("a menu with no pooled rows is unchanged")
    void noPoolNoHeader() {
        List<EditorTypeMenusPacket.Variant> in = List.of(row("books", 4), row("cows", 2));
        assertEquals(in, EditorTypeMenus.withPoolHeaders(in));
    }
}
