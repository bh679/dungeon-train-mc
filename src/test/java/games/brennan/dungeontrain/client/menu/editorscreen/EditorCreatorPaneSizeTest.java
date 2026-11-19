package games.brennan.dungeontrain.client.menu.editorscreen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Blocks / Data value on a relay build's sheet: one line, each half shown only when known. */
final class EditorCreatorPaneSizeTest {

    @Test
    @DisplayName("a blob reads as KB under a megabyte and MB at one decimal above, never 0 KB")
    void dataSize() {
        assertEquals("530 KB", EditorCreatorPane.dataSize(543_128L));
        assertEquals("1 KB", EditorCreatorPane.dataSize(12L));
        assertEquals("1023 KB", EditorCreatorPane.dataSize(1024L * 1024L - 1L));
        assertEquals("1.0 MB", EditorCreatorPane.dataSize(1024L * 1024L));
        assertEquals("2.5 MB", EditorCreatorPane.dataSize(2_621_440L));
    }

    @Test
    @DisplayName("blocks and data share the line; either half alone when the other is unknown")
    void blocksData() {
        assertEquals("63669 · 530 KB", EditorCreatorPane.blocksData(63_669, 543_128L));
        assertEquals("530 KB", EditorCreatorPane.blocksData(-1, 543_128L), "before the picture bakes");
        assertEquals("63669", EditorCreatorPane.blocksData(63_669, 0L), "a relay that sent no length");
        assertEquals("", EditorCreatorPane.blocksData(-1, 0L));
    }
}
