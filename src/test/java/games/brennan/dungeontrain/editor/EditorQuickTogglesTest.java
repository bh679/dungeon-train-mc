package games.brennan.dungeontrain.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class EditorQuickTogglesTest {

    @Test
    @DisplayName("Frame Off clears exactly the box's outer layer and never a cell of the interior")
    void ringIsTheOuterLayerOnly() {
        int lx = 9, ly = 7, lz = 5;
        int ring = 0;
        for (int x = 0; x < lx; x++) {
            for (int y = 0; y < ly; y++) {
                for (int z = 0; z < lz; z++) {
                    if (EditorQuickToggles.onRing(x, y, z, lx, ly, lz)) ring++;
                }
            }
        }
        assertEquals(lx * ly * lz - (lx - 2) * (ly - 2) * (lz - 2), ring);
    }
}
