package games.brennan.dungeontrain.track.variant;

import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.editor.CarriageContentsVariantBlocks;
import games.brennan.dungeontrain.editor.VariantState;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the footprint bound on variant sidecars.
 *
 * <p>A sidecar is loaded once per session and cached under {@code kind:name} — the footprint the
 * caller asked for is <em>not</em> part of that key. It used to be applied destructively during the
 * parse that populated the cache, which made the first caller's footprint binding on every later
 * one. For a portal room that first caller is routinely wrong: a room's size lives in its template
 * and {@code PortalRoomSizes} answers with the built-in room's 11x7x13 until that template has
 * loaded, so an editor dirty-check scan running before the templates did pruned the sidecar for the
 * rest of the session. The next editor save then wrote the pruned form over the source tree. On
 * 2026-09-02 that cost {@code abandonedroom} 437 of its 488 cells, {@code deserter} 82 of 201, and
 * deleted {@code miniword}'s sidecar outright.</p>
 *
 * <p>So the bound is now a throwaway view: the cache holds the whole sidecar, each caller gets its
 * own bounded copy, and that copy's edits and saves go through to the whole sidecar rather than
 * replacing it. Read bounded, write whole — a bounded read can never become a truncated write.</p>
 *
 * <p>Uses the real bundled {@code abandonedroom} sidecar rather than a fixture — the counts below
 * are the ones the incident turned on, and reading them off the shipped file is what makes this a
 * regression test rather than a restatement of the implementation.</p>
 */
final class TrackVariantBoundsTest {

    private static final String ROOM = "abandonedroom";

    /** abandonedroom's authored footprint, from its template. */
    private static final Vec3i REAL_SIZE = new Vec3i(20, 11, 26);

    /** What {@code PortalRoomSizes} answers with before the template has loaded. */
    private static final Vec3i BUILT_IN_SIZE = new Vec3i(11, 7, 13);

    private static final int CELLS_TOTAL = 488;
    private static final int CELLS_WITHIN_BUILT_IN = 51;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** The caches are process-wide statics, so each case has to start from a cold one. */
    @BeforeEach
    void resetCaches() {
        TrackVariantBlocks.clearCache();
        CarriageContentsVariantBlocks.clearCache();
    }

    @Test
    @DisplayName("a wrong first footprint does not prune what later callers see")
    void wrongFirstFootprintDoesNotPoisonTheCache() {
        TrackVariantBlocks cropped = TrackVariantBlocks.loadFor(TrackKind.PORTAL_ROOM, ROOM, BUILT_IN_SIZE);
        assertEquals(CELLS_WITHIN_BUILT_IN, cropped.size(),
            "a caller asking at the built-in size still sees only the cells inside it");

        TrackVariantBlocks full = TrackVariantBlocks.loadFor(TrackKind.PORTAL_ROOM, ROOM, REAL_SIZE);
        assertEquals(CELLS_TOTAL, full.size(),
            "the room's own footprint must see every authored cell, whatever was asked for first");
    }

    @Test
    @DisplayName("the bound holds in either call order")
    void boundIsIndependentOfCallOrder() {
        TrackVariantBlocks full = TrackVariantBlocks.loadFor(TrackKind.PORTAL_ROOM, ROOM, REAL_SIZE);
        assertEquals(CELLS_TOTAL, full.size());

        TrackVariantBlocks cropped = TrackVariantBlocks.loadFor(TrackKind.PORTAL_ROOM, ROOM, BUILT_IN_SIZE);
        assertEquals(CELLS_WITHIN_BUILT_IN, cropped.size());

        assertEquals(CELLS_TOTAL, TrackVariantBlocks.loadFor(TrackKind.PORTAL_ROOM, ROOM, REAL_SIZE).size(),
            "the crop must not have mutated the cached sidecar on its way past");
    }

    @Test
    @DisplayName("a fitting footprint returns the cached sidecar itself, not a copy")
    void fittingFootprintIsNotCopied() {
        TrackVariantBlocks first = TrackVariantBlocks.loadFor(TrackKind.PORTAL_ROOM, ROOM, REAL_SIZE);
        TrackVariantBlocks second = TrackVariantBlocks.loadFor(TrackKind.PORTAL_ROOM, ROOM, REAL_SIZE);
        assertSame(first, second,
            "the common path must stay free — and editor edits must land on the cached instance");
        assertFalse(first.isCropped());
    }

    @Test
    @DisplayName("edits through a bounded view reach the whole sidecar")
    void viewEditsWriteThroughToTheSource() {
        TrackVariantBlocks view = TrackVariantBlocks.loadFor(TrackKind.PORTAL_ROOM, ROOM, BUILT_IN_SIZE);
        assertTrue(view.isCropped());

        BlockPos cell = new BlockPos(1, 1, 1);
        view.put(cell, List.of(
            VariantState.of(Blocks.STONE_BRICKS.defaultBlockState()),
            VariantState.of(Blocks.MOSSY_STONE_BRICKS.defaultBlockState())));

        TrackVariantBlocks whole = TrackVariantBlocks.loadFor(TrackKind.PORTAL_ROOM, ROOM, REAL_SIZE);
        assertNotNull(whole.statesAt(cell),
            "an edit made through a view must land on the sidecar the editor will save, not on a copy");
        assertEquals(CELLS_TOTAL + 1, whole.size(),
            "and it must not cost the cells the view's own footprint hid");

        assertTrue(view.remove(cell));
        assertNull(TrackVariantBlocks.loadFor(TrackKind.PORTAL_ROOM, ROOM, REAL_SIZE).statesAt(cell),
            "removal writes through too");
        assertEquals(CELLS_TOTAL, TrackVariantBlocks.loadFor(TrackKind.PORTAL_ROOM, ROOM, REAL_SIZE).size());
    }

    @Test
    @DisplayName("carriage contents sidecars bound the same way")
    void contentsSidecarBoundIsAlsoAView() {
        CarriageContents contents = CarriageContents.custom("2ndlevel");

        int shortInterior = CarriageContentsVariantBlocks
            .loadFor(contents, new Vec3i(7, 3, 5)).size();
        int fullInterior = CarriageContentsVariantBlocks
            .loadFor(contents, new Vec3i(7, 5, 5)).size();

        assertEquals(30, shortInterior, "cells above the shorter interior are bounded out of the view");
        assertEquals(60, fullInterior, "the taller interior still sees every authored cell");
    }
}
