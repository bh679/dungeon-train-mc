package games.brennan.dungeontrain.worldgen.legacy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SuperflatHeightTest {

    @Test
    @DisplayName("structures are sited on the first free block above the grass")
    void baseHeightIsAboveGrass() {
        assertEquals(76, SuperflatHeight.baseHeight(75));
        assertEquals(60, SuperflatHeight.baseHeight(LegacyBands.superflatGrassY(75, 1, -64)));
    }

    @Test
    @DisplayName("the sheet column is grass, dirt, dirt, bedrock downward from the grass; air elsewhere")
    void layerIndexMatchesSheet() {
        assertEquals(-1, SuperflatHeight.layerIndexAt(75, 76));
        assertEquals(0, SuperflatHeight.layerIndexAt(75, 75));
        assertEquals(1, SuperflatHeight.layerIndexAt(75, 74));
        assertEquals(2, SuperflatHeight.layerIndexAt(75, 73));
        assertEquals(3, SuperflatHeight.layerIndexAt(75, 72));
        assertEquals(-1, SuperflatHeight.layerIndexAt(75, 71));
    }

    @Test
    @DisplayName("no published world: no Superflat height override")
    void unpublishedAnswersNull() {
        SuperflatHeight.clear();
        assertNull(SuperflatHeight.grassYForColumn(new Object(), 0, 0));
    }
}
