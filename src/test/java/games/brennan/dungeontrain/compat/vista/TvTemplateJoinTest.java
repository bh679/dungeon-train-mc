package games.brennan.dungeontrain.compat.vista;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TvTemplateJoinTest {

    @Test
    void carriedDataDropsTheScreenSizeAndKeepsTheRest() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("ConnectionWidth", 2);
        tag.putInt("ConnectionHeight", 1);
        tag.putBoolean("HasEnergy", true);
        tag.putBoolean("HasSpeaker", true);

        CompoundTag carried = TvTemplateJoin.withoutSize(tag);

        assertFalse(carried.contains("ConnectionWidth"));
        assertFalse(carried.contains("ConnectionHeight"));
        assertTrue(carried.getBoolean("HasEnergy"));
        assertTrue(carried.getBoolean("HasSpeaker"));
        assertTrue(tag.contains("ConnectionWidth"), "the source tag is left untouched");
    }

    @Test
    void scanStartsAtTheBottomRowSoScreensGrowFromBelow() {
        List<BlockPos> cells = new ArrayList<>(List.of(
            new BlockPos(1, 2, 0), new BlockPos(0, 1, 5), new BlockPos(3, 1, 0), new BlockPos(0, 1, 0)));
        cells.sort(TvTemplateJoin.SCAN_ORDER);
        assertEquals(List.of(
            new BlockPos(0, 1, 0), new BlockPos(0, 1, 5), new BlockPos(3, 1, 0), new BlockPos(1, 2, 0)), cells);
    }
}
