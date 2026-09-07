package games.brennan.dungeontrain.track.variant;

import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.editor.VariantCopyScope;
import games.brennan.dungeontrain.editor.VariantState;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for the two v10 per-cell copy settings — the pair that lets one cell behave differently
 * from the rest of a dimensional carriage room that otherwise repeats one roll exactly, in every
 * tile. {@code reroll} says the cell rolls again in each copy; {@link VariantCopyScope} says which
 * tiles it applies in at all.
 *
 * <p>Three things carry it. <b>It survives the file</b>, or an author sets it and loses it on the
 * next load. <b>A cell without it writes what it always wrote</b> — the bare-array form — so every
 * sidecar authored before v10 re-saves diff-clean and the flag costs nothing to the rooms that do
 * not use it. And <b>the index it rolls at actually varies per copy</b> while the room's own index
 * does not, which is the whole of the behaviour: the flag is only ever a choice between two
 * arguments to the same deterministic picker.</p>
 *
 * <p>Needs a headless Minecraft bootstrap so {@link VariantState}'s {@code BlockState} resolves.</p>
 */
final class TrackVariantBlocksCopySettingsTest {

    private static final BlockPos VARYING = new BlockPos(1, 2, 3);
    private static final BlockPos REPEATING = new BlockPos(4, 5, 6);
    private static final Vec3i ROOM = new Vec3i(16, 16, 16);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static List<VariantState> states() {
        return List.of(
            VariantState.of(Blocks.STONE_BRICKS.defaultBlockState()),
            VariantState.of(Blocks.MOSSY_STONE_BRICKS.defaultBlockState()),
            VariantState.of(Blocks.CRACKED_STONE_BRICKS.defaultBlockState()));
    }

    private static TrackVariantBlocks authored() {
        TrackVariantBlocks doc = TrackVariantBlocks.emptyFor(TrackKind.PORTAL_ROOM);
        doc.put(VARYING, states());
        doc.put(REPEATING, states());
        doc.setRerollsPerCopy(VARYING, true);
        return doc;
    }

    @Test
    @DisplayName("the flag round-trips through the sidecar file")
    void roundTrips() {
        TrackVariantBlocks reloaded = TrackVariantBlocks.fromJsonText(
            authored().asJsonText(), TrackKind.PORTAL_ROOM, "room", ROOM);

        assertTrue(reloaded.rerollsPerCopy(VARYING), "flagged cell lost its flag on reload");
        assertFalse(reloaded.rerollsPerCopy(REPEATING), "unflagged cell gained one");
    }

    @Test
    @DisplayName("only a flagged cell takes the object form — everything else writes as before")
    void unflaggedCellsStayDiffClean() {
        String json = authored().asJsonText();

        assertTrue(json.contains("\"reroll\": true"), "flag not written: " + json);
        assertEquals(1, json.split("\"reroll\"", -1).length - 1,
            "the unflagged cell must not carry the field at all: " + json);
        assertTrue(json.contains("\"" + REPEATING.getX() + "," + REPEATING.getY() + ","
                + REPEATING.getZ() + "\": ["),
            "an unflagged, unlocked cell must still write the bare-array form: " + json);
    }

    @Test
    @DisplayName("the flag rides along with a lock id when both are set")
    void coexistsWithALockId() {
        TrackVariantBlocks doc = authored();
        doc.setLockId(VARYING, 4);

        TrackVariantBlocks reloaded = TrackVariantBlocks.fromJsonText(
            doc.asJsonText(), TrackKind.PORTAL_ROOM, "room", ROOM);

        assertEquals(4, reloaded.lockIdAt(VARYING));
        assertTrue(reloaded.rerollsPerCopy(VARYING));
    }

    @Test
    @DisplayName("copyOf carries the flag, and removing a cell drops it")
    void copiedAndCleared() {
        TrackVariantBlocks doc = authored();
        assertTrue(TrackVariantBlocks.copyOf(doc).rerollsPerCopy(VARYING),
            "a duplicated room must keep what its source repeated");

        doc.remove(VARYING);
        assertFalse(doc.rerollsPerCopy(VARYING),
            "a flag left behind would reattach itself to whatever cell is authored here next");
    }

    @Test
    @DisplayName("flagging a cell that does not exist is refused")
    void refusesAnEmptyCell() {
        TrackVariantBlocks doc = TrackVariantBlocks.emptyFor(TrackKind.PORTAL_ROOM);
        assertThrows(IllegalArgumentException.class,
            () -> doc.setRerollsPerCopy(VARYING, true));
        assertThrows(IllegalArgumentException.class,
            () -> doc.setCopyScope(VARYING, VariantCopyScope.COPIES));
    }

    // ---------- copy scope ----------

    @Test
    @DisplayName("the scope round-trips, and 'both' writes nothing at all")
    void scopeRoundTrips() {
        TrackVariantBlocks doc = authored();
        doc.setCopyScope(VARYING, VariantCopyScope.COPIES);

        String json = doc.asJsonText();
        assertTrue(json.contains("\"scope\": \"copies\""), "scope not written: " + json);
        assertFalse(json.contains("\"both\""), "the default scope must never reach the file: " + json);

        TrackVariantBlocks reloaded = TrackVariantBlocks.fromJsonText(
            json, TrackKind.PORTAL_ROOM, "room", ROOM);
        assertEquals(VariantCopyScope.COPIES, reloaded.copyScopeAt(VARYING));
        assertEquals(VariantCopyScope.BOTH, reloaded.copyScopeAt(REPEATING),
            "a cell that was never scoped must read as 'both'");
    }

    @Test
    @DisplayName("scope, reroll and a lock id coexist in one cell")
    void allThreeCoexist() {
        TrackVariantBlocks doc = authored();
        doc.setLockId(VARYING, 3);
        doc.setCopyScope(VARYING, VariantCopyScope.NOT_COPIES);

        TrackVariantBlocks reloaded = TrackVariantBlocks.fromJsonText(
            doc.asJsonText(), TrackKind.PORTAL_ROOM, "room", ROOM);

        assertEquals(3, reloaded.lockIdAt(VARYING));
        assertTrue(reloaded.rerollsPerCopy(VARYING));
        assertEquals(VariantCopyScope.NOT_COPIES, reloaded.copyScopeAt(VARYING));
    }

    @Test
    @DisplayName("setting the scope back to 'both' clears it rather than storing it")
    void scopeClearsBackToDefault() {
        TrackVariantBlocks doc = authored();
        doc.setCopyScope(VARYING, VariantCopyScope.COPIES);
        doc.setCopyScope(VARYING, VariantCopyScope.BOTH);

        assertEquals(VariantCopyScope.BOTH, doc.copyScopeAt(VARYING));
        assertFalse(doc.asJsonText().contains("\"scope\""),
            "a cell cycled back to the default must write what it always wrote");
    }

    @Test
    @DisplayName("copyOf carries the scope, and removing a cell drops it")
    void scopeCopiedAndCleared() {
        TrackVariantBlocks doc = authored();
        doc.setCopyScope(VARYING, VariantCopyScope.COPIES);
        assertEquals(VariantCopyScope.COPIES, TrackVariantBlocks.copyOf(doc).copyScopeAt(VARYING));

        doc.remove(VARYING);
        assertEquals(VariantCopyScope.BOTH, doc.copyScopeAt(VARYING),
            "a scope left behind would reattach to whatever cell is authored here next");
    }

    @Test
    @DisplayName("which tiles each scope applies in")
    void scopeSelectsTiles() {
        // true = the arrival room (Tile.BASE), false = one of the copies around it.
        assertTrue(VariantCopyScope.BOTH.appliesTo(true));
        assertTrue(VariantCopyScope.BOTH.appliesTo(false));

        assertFalse(VariantCopyScope.COPIES.appliesTo(true), "'copies' must skip the arrival room");
        assertTrue(VariantCopyScope.COPIES.appliesTo(false));

        assertTrue(VariantCopyScope.NOT_COPIES.appliesTo(true));
        assertFalse(VariantCopyScope.NOT_COPIES.appliesTo(false), "'not copies' must skip the copies");
    }

    @Test
    @DisplayName("the scope cycle visits every state and comes back")
    void scopeCycles() {
        assertEquals(VariantCopyScope.COPIES, VariantCopyScope.BOTH.next());
        assertEquals(VariantCopyScope.NOT_COPIES, VariantCopyScope.COPIES.next());
        assertEquals(VariantCopyScope.BOTH, VariantCopyScope.NOT_COPIES.next());
    }

    @Test
    @DisplayName("an unreadable scope reads as 'both' rather than dropping the cell")
    void scopeParseIsTotal() {
        assertEquals(VariantCopyScope.BOTH, VariantCopyScope.parse(null));
        assertEquals(VariantCopyScope.BOTH, VariantCopyScope.parse("nonsense"));
        assertEquals(VariantCopyScope.BOTH, VariantCopyScope.fromOrdinal(-1));
        assertEquals(VariantCopyScope.BOTH, VariantCopyScope.fromOrdinal(99));
        assertEquals(VariantCopyScope.COPIES, VariantCopyScope.parse(" COPIES "));
    }

    @Test
    @DisplayName("the per-copy index varies across tiles where the room's own index does not")
    void perCopyIndexVariesPerTile() {
        // What PortalCarriageBuilder hands the picker for a flagged cell, mirrored here: the room's
        // index mixed with the copy's place on the tiling grid. The unflagged case is the room's
        // index unchanged, which is what makes an Exact room's copies agree.
        int roomIndex = Objects.hash("crypt".hashCode(), 42);
        int[][] tiles = { {0, 0}, {1, 0}, {0, 1}, {-1, 2}, {3, -1} };
        List<VariantState> states = states();

        Set<Integer> varying = new HashSet<>();
        Set<Integer> repeating = new HashSet<>();
        for (int[] tile : tiles) {
            varying.add(CarriageVariantBlocks.pickIndexWeighted(
                VARYING, 0xC0FFEEL, Objects.hash(roomIndex, tile[0], tile[1]), states));
            repeating.add(CarriageVariantBlocks.pickIndexWeighted(
                REPEATING, 0xC0FFEEL, roomIndex, states));
        }

        assertEquals(1, repeating.size(), "an unflagged cell must draw the same index in every copy");
        assertTrue(varying.size() > 1, "a flagged cell drew the same index in all "
            + tiles.length + " copies — the mix is not varying with the tile");
    }

    @Test
    @DisplayName("a copy re-stamped later rolls what it rolled before")
    void perCopyIndexIsStablePerTile() {
        int roomIndex = Objects.hash("crypt".hashCode(), 42);
        int first = CarriageVariantBlocks.pickIndexWeighted(
            VARYING, 0xC0FFEEL, Objects.hash(roomIndex, 2, 3), states());
        for (int i = 0; i < 50; i++) {
            assertEquals(first, CarriageVariantBlocks.pickIndexWeighted(
                    VARYING, 0xC0FFEEL, Objects.hash(roomIndex, 2, 3), states()),
                "the flag must vary a cell by WHERE the copy is, never by when it was stamped");
        }
    }
}
