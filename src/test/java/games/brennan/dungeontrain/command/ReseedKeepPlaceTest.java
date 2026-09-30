package games.brennan.dungeontrain.command;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a reseed leaves the author: where they stood anywhere within three chunks of the test copy, the
 * copy's arrival spot from further off. Once only the copy's own box counted, so an author looking
 * at it from beside or above was pulled back to the doorway on every press.
 */
class ReseedKeepPlaceTest {

    /** A carriage-sized copy in the basement test band. */
    private static final BoundingBox BOX = new BoundingBox(100, -60, 640, 108, -54, 644);
    private static final int M = PortalTestCommand.RESEED_KEEP_MARGIN;

    @Test
    @DisplayName("inside the copy keeps its place")
    void inside() {
        assertTrue(PortalTestCommand.keepsPlaceOnReseed(BOX, new BlockPos(104, -59, 642)));
    }

    @Test
    @DisplayName("one chunk outside the copy on any axis still keeps its place")
    void withinAChunk() {
        assertTrue(PortalTestCommand.keepsPlaceOnReseed(BOX, new BlockPos(100 - M, -59, 642)));
        assertTrue(PortalTestCommand.keepsPlaceOnReseed(BOX, new BlockPos(108 + M, -59, 642)));
        assertTrue(PortalTestCommand.keepsPlaceOnReseed(BOX, new BlockPos(104, -54 + M, 642)));
        assertTrue(PortalTestCommand.keepsPlaceOnReseed(BOX, new BlockPos(104, -59, 640 - M)));
        assertTrue(PortalTestCommand.keepsPlaceOnReseed(BOX, new BlockPos(104, -59, 644 + M)));
    }

    @Test
    @DisplayName("further than a chunk out goes back to the arrival spot")
    void beyondAChunk() {
        assertFalse(PortalTestCommand.keepsPlaceOnReseed(BOX, new BlockPos(100 - M - 1, -59, 642)));
        assertFalse(PortalTestCommand.keepsPlaceOnReseed(BOX, new BlockPos(108 + M + 1, -59, 642)));
        assertFalse(PortalTestCommand.keepsPlaceOnReseed(BOX, new BlockPos(104, -54 + M + 1, 642)));
        assertFalse(PortalTestCommand.keepsPlaceOnReseed(BOX, new BlockPos(104, -59, 644 + M + 1)));
    }
}
