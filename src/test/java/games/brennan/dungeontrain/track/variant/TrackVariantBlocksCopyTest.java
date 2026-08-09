package games.brennan.dungeontrain.track.variant;

import games.brennan.dungeontrain.editor.VariantState;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for {@link TrackVariantBlocks#copyOf} — the editor's "new template / new sub-variant
 * from current" paths lean on it to carry a room's whole authoring payload onto the duplicate.
 *
 * <p>The two things worth pinning: <b>completeness</b> (a copy that quietly drops lock ids or the
 * mirror flags is what shipped before, and it looks fine right until the room stamps), and
 * <b>independence</b> — {@code save()} caches {@code this} under the name it wrote, so a copy that
 * shared state with its source would leave two room names aliased to one mutable sidecar.</p>
 *
 * <p>Needs a headless Minecraft bootstrap so {@link VariantState}'s {@code BlockState} resolves.</p>
 */
final class TrackVariantBlocksCopyTest {

    private static final BlockPos CELL_A = new BlockPos(1, 2, 3);
    private static final BlockPos CELL_B = new BlockPos(4, 5, 6);
    /** Deliberately outside PORTAL_ROOM's built-in box — a free-size room authors up here. */
    private static final BlockPos CELL_TALL = new BlockPos(2, 40, 2);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static List<VariantState> states() {
        return List.of(
            VariantState.of(Blocks.STONE_BRICKS.defaultBlockState()),
            VariantState.of(Blocks.MOSSY_STONE_BRICKS.defaultBlockState()));
    }

    private static TrackVariantBlocks authored() {
        TrackVariantBlocks src = TrackVariantBlocks.emptyFor(TrackKind.PORTAL_ROOM);
        src.put(CELL_A, states());
        src.put(CELL_B, states());
        src.put(CELL_TALL, states());
        src.setLockId(CELL_A, 7);
        src.setLockId(CELL_B, 7);
        return src;
    }

    @Test
    @DisplayName("copyOf carries every cell, its lock id, and the mirror flags")
    void copiesTheWholePayload() {
        TrackVariantBlocks src = authored();
        src.setMirrorAxes(true, true, false);
        src.setMirrorVariants(true);

        TrackVariantBlocks copy = TrackVariantBlocks.copyOf(src);

        assertEquals(src.size(), copy.size(), "cell count");
        for (BlockPos pos : List.of(CELL_A, CELL_B, CELL_TALL)) {
            assertEquals(src.statesAt(pos), copy.statesAt(pos), "states at " + pos);
        }
        assertEquals(src.allLockIds(), copy.allLockIds(), "lock ids");
        assertEquals(7, copy.lockIdAt(CELL_A));
        assertEquals(7, copy.lockIdAt(CELL_B), "both halves of the lock group must survive");

        assertTrue(copy.mirrorX());
        assertTrue(copy.mirrorY());
        assertFalse(copy.mirrorZ());
        assertTrue(copy.mirrorVariants(), "the 'V' toggle is authoring data like any other");
    }

    @Test
    @DisplayName("copyOf of a mirror-only sidecar keeps the axes and stays empty")
    void copiesMirrorOnlySidecar() {
        TrackVariantBlocks src = TrackVariantBlocks.emptyFor(TrackKind.PORTAL_ROOM);
        src.setMirrorAxes(true, false, false);

        TrackVariantBlocks copy = TrackVariantBlocks.copyOf(src);

        assertTrue(copy.isEmpty(), "no cells were authored");
        assertFalse(copy.isDefaultMirror(), "…but the axes are not this kind's default, so it matters");
        assertTrue(copy.mirrorX());
    }

    @Test
    @DisplayName("an untouched sidecar reports default mirror — the 'nothing to copy' signal")
    void untouchedSidecarIsDefault() {
        TrackVariantBlocks src = TrackVariantBlocks.emptyFor(TrackKind.PORTAL_ROOM);
        assertTrue(src.isEmpty());
        assertTrue(src.isDefaultMirror());
        assertTrue(TrackVariantBlocks.copyOf(src).isDefaultMirror());
    }

    @Test
    @DisplayName("the copy is independent — editing it never writes through to the source")
    void copyIsIndependent() {
        TrackVariantBlocks src = authored();
        TrackVariantBlocks copy = TrackVariantBlocks.copyOf(src);
        assertNotSame(src, copy);

        copy.put(new BlockPos(9, 9, 9), states());
        copy.setLockId(CELL_A, 3);
        copy.remove(CELL_B);
        copy.setMirrorAxes(true, true, true);
        copy.setMirrorVariants(true);

        assertEquals(3, src.size(), "source gained/lost a cell");
        assertEquals(7, src.lockIdAt(CELL_A), "source lock id was rewritten");
        assertEquals(states(), src.statesAt(CELL_B), "source cell was removed");
        assertTrue(src.isDefaultMirror(), "source mirror flags were rewritten");
    }
}
