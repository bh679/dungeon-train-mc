package games.brennan.dungeontrain.track.variant;

import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.editor.VariantCopyRoll;
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
 * from the rest of a dimensional carriage room. {@link VariantCopyRoll} says how the cell rolls
 * across the room's copies (follow the room, one roll for all of them, or a fresh roll in each);
 * {@link VariantCopyScope} says which tiles it applies in at all.
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
        doc.setCopyRoll(VARYING, VariantCopyRoll.VARY);
        return doc;
    }

    @Test
    @DisplayName("the roll override round-trips through the sidecar file")
    void roundTrips() {
        TrackVariantBlocks reloaded = TrackVariantBlocks.fromJsonText(
            authored().asJsonText(), TrackKind.PORTAL_ROOM, "room", ROOM);

        assertEquals(VariantCopyRoll.VARY, reloaded.copyRollAt(VARYING),
            "the cell lost its override on reload");
        assertEquals(VariantCopyRoll.DEFAULT, reloaded.copyRollAt(REPEATING),
            "a cell that never overrode its room must still follow it");
    }

    @Test
    @DisplayName("'default' is never written, and a cycled-back cell writes what it always wrote")
    void defaultRollWritesNothing() {
        TrackVariantBlocks doc = authored();
        doc.setCopyRoll(VARYING, VariantCopyRoll.DEFAULT);

        assertEquals(VariantCopyRoll.DEFAULT, doc.copyRollAt(VARYING));
        assertFalse(doc.asJsonText().contains("\"roll\""),
            "the default roll must never reach the file: " + doc.asJsonText());
    }

    @Test
    @DisplayName("'exact' round-trips too — the override that holds a cell still in a Dynamic room")
    void exactRollRoundTrips() {
        TrackVariantBlocks doc = authored();
        doc.setCopyRoll(REPEATING, VariantCopyRoll.EXACT);

        String json = doc.asJsonText();
        assertTrue(json.contains("\"roll\": \"exact\""), "exact not written: " + json);

        TrackVariantBlocks reloaded = TrackVariantBlocks.fromJsonText(
            json, TrackKind.PORTAL_ROOM, "room", ROOM);
        assertEquals(VariantCopyRoll.EXACT, reloaded.copyRollAt(REPEATING));
    }

    @Test
    @DisplayName("a file carrying the superseded boolean still reads as 'vary'")
    void legacyRerollBooleanStillReads() {
        // The shape this setting first took on the branch. Rooms authored between the two carry it,
        // and reading one as "follows the room" would silently undo what the author chose.
        String legacy = """
            {
              "schemaVersion": 10,
              "variants": {
                "1,2,3": { "reroll": true, "states": ["minecraft:stone_bricks", "minecraft:mossy_stone_bricks"] }
              }
            }
            """;
        TrackVariantBlocks reloaded = TrackVariantBlocks.fromJsonText(
            legacy, TrackKind.PORTAL_ROOM, "room", ROOM);

        assertEquals(VariantCopyRoll.VARY, reloaded.copyRollAt(VARYING));
        assertFalse(reloaded.asJsonText().contains("reroll"),
            "the old key is read, never written back: " + reloaded.asJsonText());
    }

    @Test
    @DisplayName("the roll cycle visits every state and comes back")
    void rollCycles() {
        assertEquals(VariantCopyRoll.EXACT, VariantCopyRoll.DEFAULT.next());
        assertEquals(VariantCopyRoll.VARY, VariantCopyRoll.EXACT.next());
        assertEquals(VariantCopyRoll.DEFAULT, VariantCopyRoll.VARY.next());
    }

    @Test
    @DisplayName("only an overriding cell takes the object form — everything else writes as before")
    void unflaggedCellsStayDiffClean() {
        String json = authored().asJsonText();

        assertTrue(json.contains("\"roll\": \"vary\""), "override not written: " + json);
        assertEquals(1, json.split("\"roll\"", -1).length - 1,
            "the cell that follows its room must not carry the field at all: " + json);
        assertTrue(json.contains("\"" + REPEATING.getX() + "," + REPEATING.getY() + ","
                + REPEATING.getZ() + "\": ["),
            "an unflagged, unlocked cell must still write the bare-array form: " + json);
    }

    @Test
    @DisplayName("the override rides along with a lock id when both are set")
    void coexistsWithALockId() {
        TrackVariantBlocks doc = authored();
        doc.setLockId(VARYING, 4);

        TrackVariantBlocks reloaded = TrackVariantBlocks.fromJsonText(
            doc.asJsonText(), TrackKind.PORTAL_ROOM, "room", ROOM);

        assertEquals(4, reloaded.lockIdAt(VARYING));
        assertEquals(VariantCopyRoll.VARY, reloaded.copyRollAt(VARYING));
    }

    @Test
    @DisplayName("copyOf carries the override, and removing a cell drops it")
    void copiedAndCleared() {
        TrackVariantBlocks doc = authored();
        assertEquals(VariantCopyRoll.VARY, TrackVariantBlocks.copyOf(doc).copyRollAt(VARYING),
            "a duplicated room must keep what its source was set to");

        doc.remove(VARYING);
        assertEquals(VariantCopyRoll.DEFAULT, doc.copyRollAt(VARYING),
            "an override left behind would reattach to whatever cell is authored here next");
    }

    @Test
    @DisplayName("flagging a cell that does not exist is refused")
    void refusesAnEmptyCell() {
        TrackVariantBlocks doc = TrackVariantBlocks.emptyFor(TrackKind.PORTAL_ROOM);
        assertThrows(IllegalArgumentException.class,
            () -> doc.setCopyRoll(VARYING, VariantCopyRoll.VARY));
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
        assertEquals(VariantCopyRoll.VARY, reloaded.copyRollAt(VARYING));
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
    @DisplayName("the per-copy index varies across tiles where the base tile's own index does not")
    void perCopyIndexVariesPerTile() {
        // What PortalCarriageBuilder hands the picker for a VARY cell, mirrored here: the base
        // tile's index mixed with the copy's place on the tiling grid. An EXACT cell gets that
        // base index unchanged, which is what makes every copy agree whatever the room does.
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
