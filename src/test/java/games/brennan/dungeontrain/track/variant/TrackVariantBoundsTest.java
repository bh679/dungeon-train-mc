package games.brennan.dungeontrain.track.variant;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.editor.CarriageContentsVariantBlocks;
import games.brennan.dungeontrain.editor.VariantState;
import games.brennan.dungeontrain.train.CarriageContents;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

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
 * <p>Runs against the real bundled sidecars rather than fixtures, since the bug was in what the
 * shipped files do on load. The expected counts are <b>read back off those files</b> rather than
 * written down here: these rooms are authored continuously (abandonedroom went 488 → 832 cells in
 * the weeks after the incident), and a hard-coded count would turn every authoring pass into a red
 * build.</p>
 */
final class TrackVariantBoundsTest {

    private static final String ROOM = "abandonedroom";

    /** abandonedroom's authored footprint, from its template. */
    private static final Vec3i REAL_SIZE = new Vec3i(20, 11, 26);

    /** What {@code PortalRoomSizes} answers with before the template has loaded. */
    private static final Vec3i BUILT_IN_SIZE = new Vec3i(11, 7, 13);

    private static int cellsTotal;
    private static int cellsWithinBuiltIn;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        JsonObject variants = readVariants(
            "/data/dungeontrain/portals/room/" + ROOM + ".variants.json");
        cellsTotal = variants.size();
        cellsWithinBuiltIn = 0;
        for (Map.Entry<String, ?> e : variants.entrySet()) {
            String[] xyz = e.getKey().split(",");
            int x = Integer.parseInt(xyz[0].trim());
            int y = Integer.parseInt(xyz[1].trim());
            int z = Integer.parseInt(xyz[2].trim());
            if (x >= 0 && x < BUILT_IN_SIZE.getX()
                && y >= 0 && y < BUILT_IN_SIZE.getY()
                && z >= 0 && z < BUILT_IN_SIZE.getZ()) {
                cellsWithinBuiltIn++;
            }
        }
        // The whole test rests on this room having cells outside the built-in box. If an authoring
        // pass ever flattens it into one, every assertion below would pass vacuously.
        assertTrue(cellsWithinBuiltIn < cellsTotal,
            ROOM + " must author cells outside the built-in footprint for this test to mean anything");
    }

    private static JsonObject readVariants(String resource) {
        try (InputStream in = TrackVariantBoundsTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing bundled resource " + resource);
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(r).getAsJsonObject().getAsJsonObject("variants");
            }
        } catch (Exception e) {
            throw new AssertionError("could not read " + resource, e);
        }
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
        assertEquals(cellsWithinBuiltIn, cropped.size(),
            "a caller asking at the built-in size still sees only the cells inside it");

        TrackVariantBlocks full = TrackVariantBlocks.loadFor(TrackKind.PORTAL_ROOM, ROOM, REAL_SIZE);
        assertEquals(cellsTotal, full.size(),
            "the room's own footprint must see every authored cell, whatever was asked for first");
    }

    @Test
    @DisplayName("the bound holds in either call order")
    void boundIsIndependentOfCallOrder() {
        TrackVariantBlocks full = TrackVariantBlocks.loadFor(TrackKind.PORTAL_ROOM, ROOM, REAL_SIZE);
        assertEquals(cellsTotal, full.size());

        TrackVariantBlocks cropped = TrackVariantBlocks.loadFor(TrackKind.PORTAL_ROOM, ROOM, BUILT_IN_SIZE);
        assertEquals(cellsWithinBuiltIn, cropped.size());

        assertEquals(cellsTotal, TrackVariantBlocks.loadFor(TrackKind.PORTAL_ROOM, ROOM, REAL_SIZE).size(),
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
        boolean fresh = view.statesAt(cell) == null;
        view.put(cell, List.of(
            VariantState.of(Blocks.STONE_BRICKS.defaultBlockState()),
            VariantState.of(Blocks.MOSSY_STONE_BRICKS.defaultBlockState())));

        TrackVariantBlocks whole = TrackVariantBlocks.loadFor(TrackKind.PORTAL_ROOM, ROOM, REAL_SIZE);
        assertNotNull(whole.statesAt(cell),
            "an edit made through a view must land on the sidecar the editor will save, not on a copy");
        assertEquals(cellsTotal + (fresh ? 1 : 0), whole.size(),
            "and it must not cost the cells the view's own footprint hid");

        assertTrue(view.remove(cell));
        assertNull(TrackVariantBlocks.loadFor(TrackKind.PORTAL_ROOM, ROOM, REAL_SIZE).statesAt(cell),
            "removal writes through too");
    }

    @Test
    @DisplayName("carriage contents sidecars bound the same way")
    void contentsSidecarBoundIsAlsoAView() {
        String id = "2ndlevel";
        JsonObject variants = readVariants("/data/dungeontrain/contents/" + id + ".variants.json");
        int total = variants.size();
        int belowY3 = 0;
        for (Map.Entry<String, ?> e : variants.entrySet()) {
            if (Integer.parseInt(e.getKey().split(",")[1].trim()) < 3) belowY3++;
        }
        assertTrue(belowY3 < total, id + " must author cells above y=2 for this test to mean anything");

        CarriageContents contents = CarriageContents.custom(id);
        assertEquals(belowY3, CarriageContentsVariantBlocks.loadFor(contents, new Vec3i(7, 3, 5)).size(),
            "cells above the shorter interior are bounded out of the view");
        assertEquals(total, CarriageContentsVariantBlocks.loadFor(contents, new Vec3i(7, 40, 5)).size(),
            "the taller interior still sees every authored cell");
    }
}
