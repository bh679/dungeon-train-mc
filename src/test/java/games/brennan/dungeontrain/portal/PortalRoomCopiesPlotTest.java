package games.brennan.dungeontrain.portal;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Copies variant presented to the Block Variant menu as a one-cell plot.
 *
 * <p>The menu asks every plot the same questions and does not know which sidecar is underneath, so
 * what matters here is that this one <b>answers all of them</b> — including the ones about lock
 * groups and mirror axes it has no use for. A plot that threw on those would crash the panel rather
 * than simply not offer the button.</p>
 */
class PortalRoomCopiesPlotTest {

    private static final BlockPos ORIGIN = new BlockPos(100, -60, 40);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static VariantState of(net.minecraft.world.level.block.Block block) {
        return new VariantState(block.defaultBlockState(), null);
    }

    private static PortalRoomCopiesPlot plot(VariantState... states) {
        return new PortalRoomCopiesPlot("testroom", ORIGIN,
            PortalRoomCopiesVariant.of(List.of(states)));
    }

    @Test
    @DisplayName("The key names the room, and reads back as it")
    void keyRoundTrips() {
        PortalRoomCopiesPlot p = plot(of(Blocks.STONE));

        assertEquals("copies:testroom", p.key());
        assertTrue(PortalRoomCopiesPlot.isCopiesKey(p.key()));
        assertEquals("testroom", PortalRoomCopiesPlot.roomOf(p.key()));

        // Every other plot's key must not be mistaken for one of these.
        assertFalse(PortalRoomCopiesPlot.isCopiesKey("track:portal_room:testroom"));
        assertNull(PortalRoomCopiesPlot.roomOf("track:portal_room:testroom"));
        assertNull(PortalRoomCopiesPlot.roomOf(null));
    }

    @Test
    @DisplayName("There is exactly one address, and it is the origin cell")
    void oneCellOnly() {
        PortalRoomCopiesPlot p = plot(of(Blocks.STONE), of(Blocks.ANDESITE));

        assertNotNull(p.statesAt(PortalRoomCopiesPlot.CELL));
        assertEquals(2, p.statesAt(PortalRoomCopiesPlot.CELL).size());
        assertNull(p.statesAt(new BlockPos(1, 0, 0)));

        assertTrue(p.inBounds(PortalRoomCopiesPlot.CELL));
        assertFalse(p.inBounds(new BlockPos(1, 0, 0)));
        assertFalse(p.inBounds(new BlockPos(0, 1, 0)));
    }

    @Test
    @DisplayName("An empty variant has no cell to show, so the menu opens on nothing rather than on air")
    void emptyHasNoCell() {
        PortalRoomCopiesPlot p = plot();
        assertNull(p.statesAt(PortalRoomCopiesPlot.CELL));
        assertTrue(p.allFlaggedPositions().isEmpty());
    }

    @Test
    @DisplayName("put replaces the cell, and only the cell")
    void putReplacesTheCell() {
        PortalRoomCopiesPlot p = plot(of(Blocks.STONE));

        p.put(PortalRoomCopiesPlot.CELL, List.of(of(Blocks.ANDESITE), of(Blocks.COBBLESTONE)));
        assertEquals(List.of("minecraft:andesite", "minecraft:cobblestone"),
            p.variant().blockIds());

        // A write to any other address is dropped rather than silently landing on the one cell.
        p.put(new BlockPos(1, 0, 0), List.of(of(Blocks.SANDSTONE)));
        assertEquals(List.of("minecraft:andesite", "minecraft:cobblestone"),
            p.variant().blockIds());
    }

    @Test
    @DisplayName("remove empties the cell and reports whether there was one")
    void removeEmptiesTheCell() {
        PortalRoomCopiesPlot p = plot(of(Blocks.STONE));

        assertFalse(p.remove(new BlockPos(1, 0, 0)));
        assertTrue(p.remove(PortalRoomCopiesPlot.CELL));
        assertTrue(p.variant().isEmpty());
        // Nothing left to remove.
        assertFalse(p.remove(PortalRoomCopiesPlot.CELL));
    }

    @Test
    @DisplayName("Lock groups and mirror axes answer rather than throw — the menu asks every plot")
    void inertQuestionsAreAnswered() {
        PortalRoomCopiesPlot p = plot(of(Blocks.STONE));

        assertEquals(0, p.lockIdAt(PortalRoomCopiesPlot.CELL));
        p.setLockId(PortalRoomCopiesPlot.CELL, 4);
        assertEquals(0, p.lockIdAt(PortalRoomCopiesPlot.CELL), "a single cell has no group to join");
        assertTrue(p.positionsWithLockId(1).isEmpty());
        assertTrue(p.allLockIds().isEmpty());

        assertFalse(p.mirrorX());
        assertFalse(p.mirrorY());
        assertFalse(p.mirrorZ());
        assertFalse(p.mirrorVariants());
        p.setMirrorAxes(true, true, true);
        p.setMirrorVariants(true);
        assertFalse(p.mirrorX(), "this value has no box to mirror across");

        assertNotNull(p.groupRefs());
    }

    @Test
    @DisplayName("The reference resolver follows the cell, not a stale copy of it")
    void groupRefsFollowsTheCurrentValue() {
        PortalRoomCopiesPlot p = plot(of(Blocks.STONE));
        p.put(PortalRoomCopiesPlot.CELL, List.of(of(Blocks.ANDESITE), of(Blocks.COBBLESTONE)));

        VariantState resolved = p.groupRefs().resolve(
            PortalRoomCopiesPlot.CELL, 0, p.statesAt(PortalRoomCopiesPlot.CELL), 7L, 1);

        assertNotNull(resolved);
        assertFalse(resolved.state().is(Blocks.STONE),
            "a resolver built over the previous list would still be answering with stone");
    }
}
