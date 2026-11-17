package games.brennan.dungeontrain.nap;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link RespawnObelisks}: any part of the three-tall obelisk finds the same bottom. */
final class RespawnObelisksTest {

    @Test
    @DisplayName("bottom, middle and top parts all lead back to the bottom block")
    void partsFindTheBottom() {
        assertEquals(0, RespawnObelisks.partIndex("bottom"));
        assertEquals(1, RespawnObelisks.partIndex("middle"));
        assertEquals(2, RespawnObelisks.partIndex("top"));
        assertEquals(0, RespawnObelisks.partIndex("something_else"));
        BlockPos bottom = new BlockPos(4, 10, -2);
        for (String shape : new String[] {"bottom", "middle", "top"}) {
            BlockPos clicked = bottom.above(RespawnObelisks.partIndex(shape));
            assertEquals(bottom, clicked.below(RespawnObelisks.partIndex(shape)), shape);
        }
    }
}
