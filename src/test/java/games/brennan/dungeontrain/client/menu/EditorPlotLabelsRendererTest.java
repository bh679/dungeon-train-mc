package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.menu.EditorPlotLabelsRenderer.CellKind;
import games.brennan.dungeontrain.client.menu.EditorPlotLabelsRenderer.RowKind;
import games.brennan.dungeontrain.net.EditorPlotLabelsPacket;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The panel's row walk.
 *
 * <p>Counting, hit-testing and drawing all consume {@code EditorPlotLabelsRenderer.rows}. Before
 * that they each re-derived the sequence with their own cursor, and a row inserted at a different
 * position in any one of them silently made clicks land on the wrong cell. These tests pin the
 * order and pin that a hit inside row <i>N</i> resolves to the cell row <i>N</i> actually is.</p>
 */
class EditorPlotLabelsRendererTest {

    private static final BlockPos POS = new BlockPos(0, 250, 0);

    private static EditorPlotLabelsPacket.Entry entry(String category, boolean inPlot,
                                                      int weight, int length, int width, int height) {
        return entry(category, inPlot, weight, length, width, height, "bedrock_lock");
    }

    private static EditorPlotLabelsPacket.Entry entry(String category, boolean inPlot,
                                                      int weight, int length, int width, int height,
                                                      String mode) {
        return new EditorPlotLabelsPacket.Entry(
            POS, "default", weight, category, "portal_room", "default",
            inPlot, false, false, length, width, height, mode);
    }

    /** A portal-room entry whose two Copies palettes both show {@code block}. */
    private static EditorPlotLabelsPacket.Entry entryWithBlock(String mode, String block) {
        return entryWithBlocks(mode, block, block);
    }

    /** A portal-room entry whose floor and roof icons differ. */
    private static EditorPlotLabelsPacket.Entry entryWithBlocks(String mode, String floor, String roof) {
        return new EditorPlotLabelsPacket.Entry(
            POS, "default", 1, "PORTALS", "portal_room", "default",
            true, false, false, 11, 13, 7, mode, floor, roof);
    }

    private static EditorPlotLabelsPacket.Entry portalInPlot() {
        return entry("PORTALS", true, 1, 11, 13, 7);
    }

    /** Centre of row {@code index}, counting from the top. */
    private static double rowCentreY(EditorPlotLabelsPacket.Entry e, int index) {
        double halfH = EditorPlotLabelsRenderer.halfHeight(e);
        return halfH - (index + 0.5) * EditorPlotLabelsRenderer.ROW_H;
    }

    @Test
    @DisplayName("A portal room in-plot shows name, weight, L/W/H, Walls, Contents, Books, Sky, Enter and actions")
    void portalInPlot_rowOrder() {
        assertArrayEquals(
            new RowKind[]{RowKind.NAME, RowKind.WEIGHT, RowKind.LENGTH, RowKind.WIDTH,
                RowKind.HEIGHT, RowKind.MODE, RowKind.ROOM_CONTENTS, RowKind.ROOM_BOOKS, RowKind.ROOM_SKY, RowKind.ENTER, RowKind.ACTION},
            EditorPlotLabelsRenderer.rows(portalInPlot()));
        assertEquals(11, EditorPlotLabelsRenderer.rowCount(portalInPlot()));
    }

    @Test
    @DisplayName("The Walls row is one button, and the rows around it do not shift under it")
    void modeRow_isOneButtonAndDoesNotDisplaceItsNeighbours() {
        EditorPlotLabelsPacket.Entry e = portalInPlot();
        RowKind[] rows = EditorPlotLabelsRenderer.rows(e);
        double halfW = EditorPlotLabelsRenderer.MIN_HALF_W;
        double y = rowCentreY(e, indexOf(rows, RowKind.MODE));

        // The whole row cycles, wherever on it the click lands.
        for (double x : new double[]{-halfW + 0.05, 0.0, halfW - 0.05}) {
            assertEquals(CellKind.MODE_CYCLE, EditorPlotLabelsRenderer.cellAt(e, halfW, x, y));
        }
        // And the rows either side of the insertion still resolve to themselves.
        assertEquals(CellKind.HEIGHT_TYPE, EditorPlotLabelsRenderer.cellAt(e, halfW, 0.0,
            rowCentreY(e, indexOf(rows, RowKind.HEIGHT))));
        assertEquals(CellKind.BUTTON_ENTER_INSIDE, EditorPlotLabelsRenderer.cellAt(e, halfW, 0.0,
            rowCentreY(e, indexOf(rows, RowKind.ENTER))));
    }

    @Test
    @DisplayName("An entry carrying no mode gets no Walls row — every category but portals")
    void noMode_meansNoModeRow() {
        EditorPlotLabelsPacket.Entry e =
            entry("PORTALS", true, 1, 11, 13, 7, EditorPlotLabelsPacket.NO_MODE);
        assertArrayEquals(
            new RowKind[]{RowKind.NAME, RowKind.WEIGHT, RowKind.LENGTH, RowKind.WIDTH,
                RowKind.HEIGHT, RowKind.ENTER, RowKind.ACTION},
            EditorPlotLabelsRenderer.rows(e));
    }

    @Test
    @DisplayName("Out of the plot there is no Walls row either — the same gate the steppers use")
    void modeRow_needsThePlayerInThePlot() {
        EditorPlotLabelsPacket.Entry e = entry("PORTALS", false, 1, 11, 13, 7, "endless_open");
        assertArrayEquals(new RowKind[]{RowKind.NAME, RowKind.WEIGHT},
            EditorPlotLabelsRenderer.rows(e));
    }

    @Test
    @DisplayName("Both endless modes grow a Copies row under Walls; the sealed ones do not")
    void copiesRowForBothEndlessModes() {
        // Endless Repetition defaults to laying extra corridors, so it grows the Exits row AND its
        // spacing stepper as well as Copies.
        assertArrayEquals(
            new RowKind[]{RowKind.NAME, RowKind.WEIGHT, RowKind.LENGTH, RowKind.WIDTH,
                RowKind.HEIGHT, RowKind.MODE, RowKind.COPIES, RowKind.DOOR_WALL,
                RowKind.ROOM_CONTENTS, RowKind.ROOM_BOOKS, RowKind.ROOM_SKY,
                RowKind.EXITS, RowKind.EXIT_EVERY, RowKind.ENTER, RowKind.ACTION},
            EditorPlotLabelsRenderer.rows(
                entry("PORTALS", true, 1, 11, 13, 7, "endless_repetition")));

        // Endless Open is endless, so it is asked about Exits — and answers Off, which takes the
        // spacing stepper with it. It appends tiles of floor and ceiling, so it IS asked about
        // Copies: those cells roll from the variant sidecar like any others.
        assertArrayEquals(
            new RowKind[]{RowKind.NAME, RowKind.WEIGHT, RowKind.LENGTH, RowKind.WIDTH,
                RowKind.HEIGHT, RowKind.MODE, RowKind.COPIES, RowKind.ROOM_CONTENTS,
                RowKind.ROOM_BOOKS, RowKind.ROOM_SKY, RowKind.EXITS, RowKind.ENTER, RowKind.ACTION},
            EditorPlotLabelsRenderer.rows(entry("PORTALS", true, 1, 11, 13, 7, "endless_open")));

        // Single adds a Floor row and a Roof row directly under Copies, because that is the one
        // Copies value with blocks to name. The rows either side of them must not shift — the row
        // walk is shared by the count, the hit test and the draw, so an insert in the wrong place
        // lands clicks elsewhere.
        assertArrayEquals(
            new RowKind[]{RowKind.NAME, RowKind.WEIGHT, RowKind.LENGTH, RowKind.WIDTH,
                RowKind.HEIGHT, RowKind.MODE, RowKind.COPIES, RowKind.COPIES_FLOOR,
                RowKind.COPIES_ROOF,
                RowKind.ROOM_CONTENTS, RowKind.ROOM_BOOKS, RowKind.ROOM_SKY, RowKind.EXITS, RowKind.ENTER,
                RowKind.ACTION},
            EditorPlotLabelsRenderer.rows(
                entry("PORTALS", true, 1, 11, 13, 7, "endless_open/single:minecraft:sandstone")));

        // Under Endless Repetition the same stored tag means Exact, so there is no block to show.
        assertArrayEquals(
            new RowKind[]{RowKind.NAME, RowKind.WEIGHT, RowKind.LENGTH, RowKind.WIDTH,
                RowKind.HEIGHT, RowKind.MODE, RowKind.COPIES, RowKind.DOOR_WALL,
                RowKind.ROOM_CONTENTS,
                RowKind.ROOM_BOOKS, RowKind.ROOM_SKY, RowKind.EXITS, RowKind.EXIT_EVERY, RowKind.ENTER,
                RowKind.ACTION},
            EditorPlotLabelsRenderer.rows(entry("PORTALS", true, 1, 11, 13, 7,
                "endless_repetition/single:minecraft:sandstone")));

        // Bedrock Lock repeats nothing, so it has neither.
        assertArrayEquals(
            new RowKind[]{RowKind.NAME, RowKind.WEIGHT, RowKind.LENGTH, RowKind.WIDTH,
                RowKind.HEIGHT, RowKind.MODE, RowKind.ROOM_CONTENTS, RowKind.ROOM_BOOKS, RowKind.ROOM_SKY, RowKind.ENTER,
                RowKind.ACTION},
            EditorPlotLabelsRenderer.rows(entry("PORTALS", true, 1, 11, 13, 7, "bedrock_lock")));
    }

    @Test
    @DisplayName("The Books row is one button while the room stocks nothing, and two once it does")
    void booksRowGrowsAnInlineEditButton() {
        double halfW = EditorPlotLabelsRenderer.MIN_HALF_W;

        // Off: no dials, so no button — the whole row cycles wherever the click lands.
        EditorPlotLabelsPacket.Entry off =
            entry("PORTALS", true, 1, 11, 13, 7, "bedrock_lock/exact/off/off/off");
        assertFalse(EditorPlotLabelsRenderer.hasBookEditButton(off));
        double offY = rowCentreY(off, indexOf(EditorPlotLabelsRenderer.rows(off), RowKind.ROOM_BOOKS));
        for (double x : new double[]{-halfW + 0.05, 0.0, halfW - 0.05}) {
            assertEquals(CellKind.ROOM_BOOKS_CYCLE, EditorPlotLabelsRenderer.cellAt(off, halfW, x, offY));
        }

        // Stocking an author: the value keeps the left of the row, Edit takes the right.
        EditorPlotLabelsPacket.Entry mix =
            entry("PORTALS", true, 1, 11, 13, 7, "bedrock_lock/exact/off/off/mix:2:3:1");
        assertTrue(EditorPlotLabelsRenderer.hasBookEditButton(mix));
        RowKind[] rows = EditorPlotLabelsRenderer.rows(mix);
        double mixY = rowCentreY(mix, indexOf(rows, RowKind.ROOM_BOOKS));
        assertEquals(CellKind.ROOM_BOOKS_CYCLE,
            EditorPlotLabelsRenderer.cellAt(mix, halfW, -halfW + 0.05, mixY));
        assertEquals(CellKind.ROOM_BOOKS_EDIT,
            EditorPlotLabelsRenderer.cellAt(mix, halfW, halfW - 0.05, mixY));

        // ...and it stays ONE row: nothing below it shifted to make room for a button.
        assertArrayEquals(EditorPlotLabelsRenderer.rows(off), rows);
    }

    @Test
    @DisplayName("Door Wall shows under Endless Repetition alone — the one mode whose tiles have a wall")
    void doorWallRowFollowsEndlessRepetitionOnly() {
        assertTrue(EditorPlotLabelsRenderer.hasDoorWallRow(
            entry("PORTALS", true, 1, 11, 13, 7, "endless_repetition")));
        // Endless Open writes floor and ceiling and nothing between them, so a copy there has no wall
        // to carry through the mouth's plane — the row would be a control over nothing.
        for (String mode : new String[]{"endless_open", "bedrock_lock", "bedrockless"}) {
            assertFalse(EditorPlotLabelsRenderer.hasDoorWallRow(
                entry("PORTALS", true, 1, 11, 13, 7, mode)), mode);
        }
        // …and only from inside the plot, the Walls row's own rule.
        assertFalse(EditorPlotLabelsRenderer.hasDoorWallRow(
            entry("PORTALS", false, 1, 11, 13, 7, "endless_repetition")));
    }

    @Test
    @DisplayName("A tag that never mentioned Room Walls reads Merged — the row cannot imply a change")
    void doorWallLabelDefaultsToSealed() {
        assertEquals("Room Walls: Merged",
            EditorPlotLabelsRenderer.doorWallLabel("endless_repetition"));
        assertEquals("Room Walls: Merged",
            EditorPlotLabelsRenderer.doorWallLabel("endless_repetition/dynamic"));
        assertEquals("Room Walls: Kept", EditorPlotLabelsRenderer.doorWallLabel(
            "endless_repetition/dynamic/off/lattice:8/off/none/repeated"));
    }

    @Test
    @DisplayName("Exits shows for both endless modes and neither sealed one — getting lost is not a wall property")
    void exitsRowFollowsTheEndlessModes() {
        for (String mode : new String[]{"endless_repetition", "endless_open"}) {
            assertTrue(EditorPlotLabelsRenderer.hasExitsRow(
                entry("PORTALS", true, 1, 11, 13, 7, mode)), mode);
        }
        for (String mode : new String[]{"bedrock_lock", "bedrockless"}) {
            assertFalse(EditorPlotLabelsRenderer.hasExitsRow(
                entry("PORTALS", true, 1, 11, 13, 7, mode)), mode);
        }
        // …and only from inside the plot, the Walls row's own rule.
        assertFalse(EditorPlotLabelsRenderer.hasExitsRow(
            entry("PORTALS", false, 1, 11, 13, 7, "endless_repetition")));
    }

    @Test
    @DisplayName("The spacing stepper is absent at Off — a spacing for nothing is worse than no control")
    void exitEveryRowHidesWhenNothingIsLaid() {
        assertTrue(EditorPlotLabelsRenderer.hasExitEveryRow(
            entry("PORTALS", true, 1, 11, 13, 7, "endless_repetition")));
        assertTrue(EditorPlotLabelsRenderer.hasExitEveryRow(
            entry("PORTALS", true, 1, 11, 13, 7, "endless_open/exact/off/random")));
        assertFalse(EditorPlotLabelsRenderer.hasExitEveryRow(
            entry("PORTALS", true, 1, 11, 13, 7, "endless_repetition/exact/off/off")));
        assertFalse(EditorPlotLabelsRenderer.hasExitEveryRow(
            entry("PORTALS", true, 1, 11, 13, 7, "endless_open")));
    }

    @Test
    @DisplayName("The Exits row is one button and its stepper's thirds hit their own cells")
    void exitsRowsAreHitTestedCorrectly() {
        EditorPlotLabelsPacket.Entry e =
            entry("PORTALS", true, 1, 11, 13, 7, "endless_repetition/dynamic/fit");
        RowKind[] rows = EditorPlotLabelsRenderer.rows(e);
        double halfW = EditorPlotLabelsRenderer.MIN_HALF_W;

        double exitsY = rowCentreY(e, indexOf(rows, RowKind.EXITS));
        for (double x : new double[]{-halfW + 0.05, 0.0, halfW - 0.05}) {
            assertEquals(CellKind.EXITS_CYCLE, EditorPlotLabelsRenderer.cellAt(e, halfW, x, exitsY));
        }

        double everyY = rowCentreY(e, indexOf(rows, RowKind.EXIT_EVERY));
        assertEquals(CellKind.EXIT_EVERY_DEC,
            EditorPlotLabelsRenderer.cellAt(e, halfW, -halfW + 0.05, everyY));
        assertEquals(CellKind.EXIT_EVERY_TYPE,
            EditorPlotLabelsRenderer.cellAt(e, halfW, 0.0, everyY));
        assertEquals(CellKind.EXIT_EVERY_INC,
            EditorPlotLabelsRenderer.cellAt(e, halfW, halfW - 0.05, everyY));

        // And the rows the pair was inserted between still resolve to themselves.
        assertEquals(CellKind.ROOM_CONTENTS_CYCLE, EditorPlotLabelsRenderer.cellAt(e, halfW, 0.0,
            rowCentreY(e, indexOf(rows, RowKind.ROOM_CONTENTS))));
        assertEquals(CellKind.BUTTON_ENTER_INSIDE, EditorPlotLabelsRenderer.cellAt(e, halfW, 0.0,
            rowCentreY(e, indexOf(rows, RowKind.ENTER))));
    }

    @Test
    @DisplayName("Random grows a moved-exit stepper under the spacing; the lattice does not")
    void exitMoveRowOnlyUnderRandom() {
        EditorPlotLabelsPacket.Entry random =
            entry("PORTALS", true, 1, 11, 13, 7, "endless_repetition/dynamic/off/random:4:6");
        assertArrayEquals(
            new RowKind[]{RowKind.NAME, RowKind.WEIGHT, RowKind.LENGTH, RowKind.WIDTH,
                RowKind.HEIGHT, RowKind.MODE, RowKind.COPIES, RowKind.DOOR_WALL,
                RowKind.ROOM_CONTENTS, RowKind.ROOM_BOOKS, RowKind.ROOM_SKY,
                RowKind.EXITS, RowKind.EXIT_EVERY, RowKind.EXIT_MOVE, RowKind.ENTER, RowKind.ACTION},
            EditorPlotLabelsRenderer.rows(random));
        assertEquals("Moved exit: 6/10", EditorPlotLabelsRenderer.exitMoveLabel(random.roomMode()));

        // The lattice is a walk a player could work out in advance, so its exit never moves.
        assertFalse(EditorPlotLabelsRenderer.hasExitMoveRow(
            entry("PORTALS", true, 1, 11, 13, 7, "endless_repetition")));
        assertFalse(EditorPlotLabelsRenderer.hasExitMoveRow(
            entry("PORTALS", true, 1, 11, 13, 7, "endless_repetition/exact/off/off")));
        assertFalse(EditorPlotLabelsRenderer.hasExitMoveRow(
            entry("PORTALS", true, 1, 11, 13, 7, "bedrock_lock")));

        // Its thirds hit their own cells, and the row above still resolves to itself.
        RowKind[] rows = EditorPlotLabelsRenderer.rows(random);
        double halfW = EditorPlotLabelsRenderer.MIN_HALF_W;
        double y = rowCentreY(random, indexOf(rows, RowKind.EXIT_MOVE));
        assertEquals(CellKind.EXIT_MOVE_DEC,
            EditorPlotLabelsRenderer.cellAt(random, halfW, -halfW + 0.05, y));
        assertEquals(CellKind.EXIT_MOVE_TYPE, EditorPlotLabelsRenderer.cellAt(random, halfW, 0.0, y));
        assertEquals(CellKind.EXIT_MOVE_INC,
            EditorPlotLabelsRenderer.cellAt(random, halfW, halfW - 0.05, y));
        assertEquals(CellKind.EXIT_EVERY_TYPE, EditorPlotLabelsRenderer.cellAt(random, halfW, 0.0,
            rowCentreY(random, indexOf(rows, RowKind.EXIT_EVERY))));
    }

    @Test
    @DisplayName("The Exits labels name the reading, so the same number cannot be read two ways")
    void exitsLabels() {
        assertEquals("Exits: On", EditorPlotLabelsRenderer.exitsLabel("endless_repetition"));
        assertEquals("Exits: Off", EditorPlotLabelsRenderer.exitsLabel("endless_open"));
        assertEquals("Exits: Random",
            EditorPlotLabelsRenderer.exitsLabel("endless_repetition/exact/off/random"));

        assertEquals("Every 8", EditorPlotLabelsRenderer.exitEveryLabel("endless_repetition"));
        assertEquals("Every 12",
            EditorPlotLabelsRenderer.exitEveryLabel("endless_repetition/exact/off/on:12"));
        assertEquals("1 in 5",
            EditorPlotLabelsRenderer.exitEveryLabel("endless_repetition/exact/off/random:5"));
    }

    @Test
    @DisplayName("Contents shows on every portal room, whatever the walls do — it is not a sub-mode")
    void roomContentsRowIsNotGatedOnTheWalls() {
        for (String mode : new String[]{"bedrock_lock", "endless_open", "bedrockless",
                                        "endless_repetition", "endless_repetition/dynamic/tile"}) {
            EditorPlotLabelsPacket.Entry e = entry("PORTALS", true, 1, 11, 13, 7, mode);
            assertTrue(EditorPlotLabelsRenderer.hasRoomContentsRow(e), mode);
        }
        // …but only for a portal room, and only from inside the plot — the Walls row's own rule.
        assertFalse(EditorPlotLabelsRenderer.hasRoomContentsRow(
            entry("PORTALS", true, 1, 11, 13, 7, EditorPlotLabelsPacket.NO_MODE)));
    }

    @Test
    @DisplayName("The Contents row is one button, and inserting it does not shift its neighbours")
    void roomContentsRowIsOneButton() {
        EditorPlotLabelsPacket.Entry e =
            entry("PORTALS", true, 1, 11, 13, 7, "endless_repetition/dynamic/fit");
        RowKind[] rows = EditorPlotLabelsRenderer.rows(e);
        double halfW = EditorPlotLabelsRenderer.MIN_HALF_W;
        double y = rowCentreY(e, indexOf(rows, RowKind.ROOM_CONTENTS));

        for (double x : new double[]{-halfW + 0.05, 0.0, halfW - 0.05}) {
            assertEquals(CellKind.ROOM_CONTENTS_CYCLE, EditorPlotLabelsRenderer.cellAt(e, halfW, x, y));
        }
        assertEquals(CellKind.COPIES_CYCLE, EditorPlotLabelsRenderer.cellAt(e, halfW, 0.0,
            rowCentreY(e, indexOf(rows, RowKind.COPIES))));
        assertEquals(CellKind.BUTTON_ENTER_INSIDE, EditorPlotLabelsRenderer.cellAt(e, halfW, 0.0,
            rowCentreY(e, indexOf(rows, RowKind.ENTER))));
    }

    @Test
    @DisplayName("A portal room gets the Contents BUTTON only while its Contents setting is on")
    void contentsButtonFollowsTheSetting() {
        // Off — the default, and every tag written before the setting existed.
        assertFalse(EditorPlotLabelsRenderer.hasContentsButton(
            entry("PORTALS", true, 1, 11, 13, 7, "bedrock_lock")));
        assertFalse(EditorPlotLabelsRenderer.hasContentsButton(
            entry("PORTALS", true, 1, 11, 13, 7, "endless_repetition/dynamic")));
        assertFalse(EditorPlotLabelsRenderer.hasContentsButton(
            entry("PORTALS", true, 1, 11, 13, 7, "bedrock_lock/exact/off")));

        // On, in any of its three flavours — there is a pool to steer.
        for (String value : new String[]{"fit", "exact", "tile"}) {
            assertTrue(EditorPlotLabelsRenderer.hasContentsButton(
                entry("PORTALS", true, 1, 11, 13, 7, "bedrock_lock/exact/" + value)), value);
        }
    }

    @Test
    @DisplayName("The Contents button still shows on carriages, and never outside a plot")
    void contentsButtonElsewhereIsUnchanged() {
        assertTrue(EditorPlotLabelsRenderer.hasContentsButton(
            entry("CARRIAGES", true, 1, 11, 13, 7, EditorPlotLabelsPacket.NO_MODE)));
        assertFalse(EditorPlotLabelsRenderer.hasContentsButton(
            entry("CARRIAGES", false, 1, 11, 13, 7, EditorPlotLabelsPacket.NO_MODE)));
        // Standing outside the plot hides it even with Contents on.
        assertFalse(EditorPlotLabelsRenderer.hasContentsButton(
            entry("PORTALS", false, 1, 11, 13, 7, "bedrock_lock/exact/fit")));
    }

    @Test
    @DisplayName("Turning Contents on grows the button row without disturbing the rows above it")
    void contentsButtonRowOrder() {
        EditorPlotLabelsPacket.Entry on = entry("PORTALS", true, 1, 11, 13, 7, "bedrock_lock/exact/fit");
        assertArrayEquals(
            new RowKind[]{RowKind.NAME, RowKind.WEIGHT, RowKind.LENGTH, RowKind.WIDTH,
                RowKind.HEIGHT, RowKind.MODE, RowKind.ROOM_CONTENTS, RowKind.ROOM_BOOKS, RowKind.ROOM_SKY, RowKind.ENTER,
                RowKind.ACTION, RowKind.CONTENTS},
            EditorPlotLabelsRenderer.rows(on));

        RowKind[] rows = EditorPlotLabelsRenderer.rows(on);
        double halfW = EditorPlotLabelsRenderer.MIN_HALF_W;
        assertEquals(CellKind.BUTTON_CONTENTS, EditorPlotLabelsRenderer.cellAt(on, halfW, 0.0,
            rowCentreY(on, indexOf(rows, RowKind.CONTENTS))));
        // The setting row above it still resolves to itself.
        assertEquals(CellKind.ROOM_CONTENTS_CYCLE, EditorPlotLabelsRenderer.cellAt(on, halfW, 0.0,
            rowCentreY(on, indexOf(rows, RowKind.ROOM_CONTENTS))));
    }

    @Test
    @DisplayName("The Contents row reads back what the tag says")
    void roomContentsLabel() {
        assertEquals("Contents: Off", EditorPlotLabelsRenderer.roomContentsLabel("bedrock_lock"));
        assertEquals("Contents: Fit",
            EditorPlotLabelsRenderer.roomContentsLabel("bedrock_lock/exact/fit"));
        assertEquals("Contents: Tile",
            EditorPlotLabelsRenderer.roomContentsLabel("endless_repetition/dynamic/tile"));
    }

    @Test
    @DisplayName("The Copies row is one button, and inserting it does not shift its neighbours")
    void copiesRowIsOneButton() {
        EditorPlotLabelsPacket.Entry e =
            entry("PORTALS", true, 1, 11, 13, 7, "endless_repetition/dynamic");
        RowKind[] rows = EditorPlotLabelsRenderer.rows(e);
        double halfW = EditorPlotLabelsRenderer.MIN_HALF_W;
        double y = rowCentreY(e, indexOf(rows, RowKind.COPIES));

        for (double x : new double[]{-halfW + 0.05, 0.0, halfW - 0.05}) {
            assertEquals(CellKind.COPIES_CYCLE, EditorPlotLabelsRenderer.cellAt(e, halfW, x, y));
        }
        assertEquals(CellKind.MODE_CYCLE, EditorPlotLabelsRenderer.cellAt(e, halfW, 0.0,
            rowCentreY(e, indexOf(rows, RowKind.MODE))));
        assertEquals(CellKind.BUTTON_ENTER_INSIDE, EditorPlotLabelsRenderer.cellAt(e, halfW, 0.0,
            rowCentreY(e, indexOf(rows, RowKind.ENTER))));
    }

    @Test
    @DisplayName("The Block row splits into a value half and an Edit half")
    void copiesBlockRowSplitsIntoValueAndEdit() {
        EditorPlotLabelsPacket.Entry e =
            entryWithBlock("endless_open/single", "minecraft:sandstone");
        double halfW = EditorPlotLabelsRenderer.MIN_HALF_W;

        // Left of the split sets the block from the hand; right of it opens the variant menu.
        assertFalse(EditorPlotLabelsRenderer.copiesBlockHitIsEdit(halfW, -halfW + 0.05));
        assertTrue(EditorPlotLabelsRenderer.copiesBlockHitIsEdit(halfW, halfW - 0.05));

        RowKind[] rows = EditorPlotLabelsRenderer.rows(e);
        double y = rowCentreY(e, indexOf(rows, RowKind.COPIES_FLOOR));
        assertEquals(CellKind.COPIES_FLOOR_HELD,
            EditorPlotLabelsRenderer.cellAt(e, halfW, -halfW + 0.05, y));
        assertEquals(CellKind.COPIES_FLOOR_EDIT,
            EditorPlotLabelsRenderer.cellAt(e, halfW, halfW - 0.05, y));

        // The Roof row is the same two halves, one row down — and a click on it must not report the
        // floor's cells, or authoring the roof would quietly rewrite the ground.
        double roofY = rowCentreY(e, indexOf(rows, RowKind.COPIES_ROOF));
        assertEquals(CellKind.COPIES_ROOF_HELD,
            EditorPlotLabelsRenderer.cellAt(e, halfW, -halfW + 0.05, roofY));
        assertEquals(CellKind.COPIES_ROOF_EDIT,
            EditorPlotLabelsRenderer.cellAt(e, halfW, halfW - 0.05, roofY));

        // And the rows either side of it still land where they did.
        assertEquals(CellKind.COPIES_CYCLE, EditorPlotLabelsRenderer.cellAt(e, halfW, 0.0,
            rowCentreY(e, indexOf(rows, RowKind.COPIES))));
        assertEquals(CellKind.ROOM_CONTENTS_CYCLE, EditorPlotLabelsRenderer.cellAt(e, halfW, 0.0,
            rowCentreY(e, indexOf(rows, RowKind.ROOM_CONTENTS))));
    }

    @Test
    @DisplayName("An unset Copies block still splits — the row is usable before anything is chosen")
    void copiesBlockRowSplitsWhenUnset() {
        EditorPlotLabelsPacket.Entry e = entryWithBlock("endless_open/single", "");
        double halfW = EditorPlotLabelsRenderer.MIN_HALF_W;
        assertFalse(EditorPlotLabelsRenderer.copiesBlockHitIsEdit(halfW, -halfW + 0.05));
        assertTrue(EditorPlotLabelsRenderer.copiesBlockHitIsEdit(halfW, halfW - 0.05));
    }

    @Test
    @DisplayName("Both rows read their own half of the one stored tag")
    void rowsReadTheirOwnHalfOfTheTag() {
        assertEquals("Walls: Endless Repetition",
            EditorPlotLabelsRenderer.modeLabel("endless_repetition/dynamic"));
        assertEquals("Copies: Dynamic",
            EditorPlotLabelsRenderer.copiesLabel("endless_repetition/dynamic"));
        // No sub-mode stored is the default, not a blank.
        assertEquals("Copies: Exact", EditorPlotLabelsRenderer.copiesLabel("endless_repetition"));

        assertEquals("Copies: Single",
            EditorPlotLabelsRenderer.copiesLabel("endless_open/single:minecraft:sandstone"));
        // The plane rows name the gesture, not the value: each palette is a variant of up to
        // MAX_ENTRIES candidates that lives server-side, and the plot panel draws it as icons.
        assertEquals("Floor: + held", EditorPlotLabelsRenderer.copiesBlockLabel(
            games.brennan.dungeontrain.portal.PortalRoomCopiesVariant.Plane.FLOOR));
        assertEquals("Roof: + held", EditorPlotLabelsRenderer.copiesBlockLabel(
            games.brennan.dungeontrain.portal.PortalRoomCopiesVariant.Plane.ROOF));
    }

    @Test
    @DisplayName("The air sentinel is recognised by id, so a plane set to air reads as nothing")
    void airSentinelIsRecognised() {
        // All three command-block kinds are the empty-placeholder sentinel, and the row is only
        // ever sent an id — so the check has to work off the id alone.
        assertTrue(EditorPlotLabelsRenderer.isAirSentinelId("minecraft:command_block"));
        assertTrue(EditorPlotLabelsRenderer.isAirSentinelId("minecraft:chain_command_block"));
        assertTrue(EditorPlotLabelsRenderer.isAirSentinelId("minecraft:repeating_command_block"));

        // An ordinary block is not, and neither is the unset case — those are three distinct
        // things the row draws differently: an icon, the word "nothing", and the "hold one" hint.
        assertFalse(EditorPlotLabelsRenderer.isAirSentinelId("minecraft:stone"));
        assertFalse(EditorPlotLabelsRenderer.isAirSentinelId(""));
        assertFalse(EditorPlotLabelsRenderer.isAirSentinelId(null));
    }

    @Test
    @DisplayName("One plane set to air leaves the other's icon alone")
    void planesCarryTheirOwnIcons() {
        EditorPlotLabelsPacket.Entry e = entryWithBlocks(
            "endless_open/single", "minecraft:stone", "minecraft:command_block");

        assertEquals("minecraft:stone", e.copiesFloorBlock());
        assertTrue(EditorPlotLabelsRenderer.isAirSentinelId(e.copiesRoofBlock()));
        assertFalse(EditorPlotLabelsRenderer.isAirSentinelId(e.copiesFloorBlock()),
            "a roof of air must not read back as a floor of air");
    }

    @Test
    @DisplayName("The Block row shows only where Single is what the walls actually do")
    void copiesBlockRowFollowsTheEffectiveValue() {
        assertTrue(EditorPlotLabelsRenderer.hasCopiesBlockRowFor(
            "endless_open/single:minecraft:sandstone"));
        assertFalse(EditorPlotLabelsRenderer.hasCopiesBlockRowFor("endless_open/dynamic"));
        assertFalse(EditorPlotLabelsRenderer.hasCopiesBlockRowFor("endless_open"));
        // Stored but not usable: Endless Repetition reads Single back as Exact, so no block row.
        assertFalse(EditorPlotLabelsRenderer.hasCopiesBlockRowFor(
            "endless_repetition/single:minecraft:sandstone"));
        assertFalse(EditorPlotLabelsRenderer.hasCopiesBlockRowFor(
            "bedrock_lock/single:minecraft:sandstone"));
    }

    @Test
    @DisplayName("The Walls label names the mode, and an unreadable tag shows the default it behaves as")
    void modeLabel_readsTheMode() {
        assertEquals("Walls: Endless Open", EditorPlotLabelsRenderer.modeLabel("endless_open"));
        assertEquals("Walls: Endless Repetition",
            EditorPlotLabelsRenderer.modeLabel("endless_repetition"));
        // parse is total, so a tag hand-edited into weights.json shows what the room will do rather
        // than the misspelling.
        assertEquals("Walls: Bedrock Lock", EditorPlotLabelsRenderer.modeLabel("endles_open"));
    }

    @Test
    @DisplayName("Out of the plot a portal room is just name + weight — no steppers to reach anyway")
    void portalOutOfPlot_hasNoDimensionRows() {
        EditorPlotLabelsPacket.Entry e = entry("PORTALS", false, 1, 11, 13, 7);
        assertArrayEquals(new RowKind[]{RowKind.NAME, RowKind.WEIGHT},
            EditorPlotLabelsRenderer.rows(e));
    }

    @Test
    @DisplayName("Other categories are untouched: a carriage keeps name, weight, Enter, action, Contents")
    void carriage_rowsUnchanged() {
        EditorPlotLabelsPacket.Entry e = new EditorPlotLabelsPacket.Entry(
            POS, "standard", 1, "CARRIAGES", "standard", "standard", true, false, false);
        assertArrayEquals(
            new RowKind[]{RowKind.NAME, RowKind.WEIGHT, RowKind.ENTER, RowKind.ACTION, RowKind.CONTENTS},
            EditorPlotLabelsRenderer.rows(e));
        // A carriage reports no authored size, so it can never grow dimension rows.
        assertEquals(EditorPlotLabelsPacket.NO_SIZE, e.roomLength());
    }

    @Test
    @DisplayName("A PORTALS entry with no reported size gets no steppers — nothing to step")
    void portalWithoutSize_hasNoDimensionRows() {
        EditorPlotLabelsPacket.Entry e = new EditorPlotLabelsPacket.Entry(
            POS, "default", 1, "PORTALS", "portal_room", "default", true, false, false);
        assertArrayEquals(new RowKind[]{RowKind.NAME, RowKind.WEIGHT, RowKind.ENTER, RowKind.ACTION},
            EditorPlotLabelsRenderer.rows(e));
    }

    @Test
    @DisplayName("Each stepper row's left third decrements ITS OWN axis and the right third increments it")
    void dimensionRows_hitTheirOwnAxis() {
        EditorPlotLabelsPacket.Entry e = portalInPlot();
        // halfWidth needs a Font; MIN_HALF_W is the floor and is what a short name resolves to,
        // so the thirds split is computed against it directly.
        double halfW = EditorPlotLabelsRenderer.MIN_HALF_W;
        double left = -halfW + 0.05;
        double right = halfW - 0.05;

        RowKind[] rows = EditorPlotLabelsRenderer.rows(e);
        CellKind[][] expected = {
            {CellKind.LENGTH_DEC, CellKind.LENGTH_INC, CellKind.LENGTH_TYPE},
            {CellKind.WIDTH_DEC, CellKind.WIDTH_INC, CellKind.WIDTH_TYPE},
            {CellKind.HEIGHT_DEC, CellKind.HEIGHT_INC, CellKind.HEIGHT_TYPE},
        };
        RowKind[] axes = {RowKind.LENGTH, RowKind.WIDTH, RowKind.HEIGHT};

        for (int a = 0; a < axes.length; a++) {
            int rowIdx = indexOf(rows, axes[a]);
            double y = rowCentreY(e, rowIdx);
            assertEquals(expected[a][0], EditorPlotLabelsRenderer.cellAt(e, halfW, left, y),
                axes[a] + " left third must decrement " + axes[a]);
            assertEquals(expected[a][1], EditorPlotLabelsRenderer.cellAt(e, halfW, right, y),
                axes[a] + " right third must increment " + axes[a]);
            // Every cell on the row belongs to that row's axis and no other — the number in the
            // middle types the same axis its arrows step.
            assertEquals(expected[a][2], EditorPlotLabelsRenderer.cellAt(e, halfW, 0.0, y),
                axes[a] + " middle cell must type " + axes[a] + " alone");
        }
    }

    @Test
    @DisplayName("Each dimension row reports its own command token")
    void dimensionRows_carryTheirAxisToken() {
        assertEquals("length", EditorPlotLabelsRenderer.dimensionAxis(RowKind.LENGTH));
        assertEquals("width", EditorPlotLabelsRenderer.dimensionAxis(RowKind.WIDTH));
        assertEquals("height", EditorPlotLabelsRenderer.dimensionAxis(RowKind.HEIGHT));
        assertEquals("", EditorPlotLabelsRenderer.dimensionAxis(RowKind.WEIGHT));
    }

    @Test
    @DisplayName("The rows around the steppers still resolve to themselves — no off-by-one drift")
    void neighbouringRows_stillResolveCorrectly() {
        EditorPlotLabelsPacket.Entry e = portalInPlot();
        RowKind[] rows = EditorPlotLabelsRenderer.rows(e);
        double halfW = EditorPlotLabelsRenderer.MIN_HALF_W;

        assertEquals(CellKind.NAME,
            EditorPlotLabelsRenderer.cellAt(e, halfW, 0.0, rowCentreY(e, indexOf(rows, RowKind.NAME))));
        assertEquals(CellKind.WEIGHT_DEC,
            EditorPlotLabelsRenderer.cellAt(e, halfW, -halfW + 0.05, rowCentreY(e, indexOf(rows, RowKind.WEIGHT))));
        assertEquals(CellKind.BUTTON_ENTER_INSIDE,
            EditorPlotLabelsRenderer.cellAt(e, halfW, 0.0, rowCentreY(e, indexOf(rows, RowKind.ENTER))));
        assertEquals(CellKind.ACTION_SAVE,
            EditorPlotLabelsRenderer.cellAt(e, halfW, -halfW + 0.05, rowCentreY(e, indexOf(rows, RowKind.ACTION))));
    }

    @Test
    @DisplayName("The weight row's number stays dead — only dimension rows type")
    void weightNumberIsNotATypingTarget() {
        EditorPlotLabelsPacket.Entry e = portalInPlot();
        double halfW = EditorPlotLabelsRenderer.MIN_HALF_W;
        RowKind[] rows = EditorPlotLabelsRenderer.rows(e);
        assertEquals(CellKind.NONE,
            EditorPlotLabelsRenderer.cellAt(e, halfW, 0.0, rowCentreY(e, indexOf(rows, RowKind.WEIGHT))));
    }

    @Test
    @DisplayName("A hit outside the panel resolves to nothing")
    void outsidePanel_isNone() {
        EditorPlotLabelsPacket.Entry e = portalInPlot();
        double halfW = EditorPlotLabelsRenderer.MIN_HALF_W;
        double halfH = EditorPlotLabelsRenderer.halfHeight(e);
        assertEquals(CellKind.NONE, EditorPlotLabelsRenderer.cellAt(e, halfW, 0.0, halfH + 0.1));
        assertEquals(CellKind.NONE, EditorPlotLabelsRenderer.cellAt(e, halfW, 0.0, -halfH - 0.1));
        assertEquals(CellKind.NONE,
            EditorPlotLabelsRenderer.cellAt(e, halfW, EditorPlotLabelsRenderer.MIN_HALF_W + 1.0, 0.0));
    }

    @Test
    @DisplayName("Dimension rows report their own axis, and the panel grows with them")
    void dimensionValues_mapToTheirAxis() {
        EditorPlotLabelsPacket.Entry e = entry("PORTALS", true, 1, 21, 17, 9);
        assertEquals(21, EditorPlotLabelsRenderer.dimensionValue(e, RowKind.LENGTH));
        assertEquals(17, EditorPlotLabelsRenderer.dimensionValue(e, RowKind.WIDTH));
        assertEquals(9, EditorPlotLabelsRenderer.dimensionValue(e, RowKind.HEIGHT));

        EditorPlotLabelsPacket.Entry outOfPlot = entry("PORTALS", false, 1, 21, 17, 9);
        assertNotEquals(EditorPlotLabelsRenderer.halfHeight(e),
            EditorPlotLabelsRenderer.halfHeight(outOfPlot));
    }

    // ---- panel width ----

    /** Stand-in for a Font: every glyph six pixels wide, which is close enough to vanilla's. */
    private static final java.util.function.ToIntFunction<String> SIX_PX = s -> s.length() * 6;

    @Test
    @DisplayName("The panel widens to fit the Walls label — a short name must not clip a long mode")
    void panelFitsTheWallsLabel() {
        // "default" is seven characters; "Walls: Endless Repetition" is twenty-five. Sizing off the
        // name alone is what had the label spilling out past both edges of the backdrop.
        EditorPlotLabelsPacket.Entry e = entry("PORTALS", true, 1, 11, 13, 7, "endless_repetition");
        double halfW = EditorPlotLabelsRenderer.halfWidth(e, SIX_PX);
        double labelHalfW =
            SIX_PX.applyAsInt(EditorPlotLabelsRenderer.modeLabel(e.roomMode())) * 0.025 / 2.0;
        assertTrue(halfW >= labelHalfW, "panel half-width " + halfW + " clips a " + labelHalfW + " label");
    }

    @Test
    @DisplayName("A panel with no Walls row keeps the width it always had")
    void panelWithoutModeRowIsUnchanged() {
        EditorPlotLabelsPacket.Entry withMode = entry("PORTALS", true, 1, 11, 13, 7, "endless_repetition");
        EditorPlotLabelsPacket.Entry without =
            entry("PORTALS", true, 1, 11, 13, 7, EditorPlotLabelsPacket.NO_MODE);
        assertEquals(EditorPlotLabelsRenderer.MIN_HALF_W,
            EditorPlotLabelsRenderer.halfWidth(without, SIX_PX));
        assertTrue(EditorPlotLabelsRenderer.halfWidth(withMode, SIX_PX)
            > EditorPlotLabelsRenderer.halfWidth(without, SIX_PX));
    }

    @Test
    @DisplayName("A name longer than the Walls label still wins — the widest row sets the width")
    void longNameStillWidensThePanel() {
        EditorPlotLabelsPacket.Entry longName = new EditorPlotLabelsPacket.Entry(
            POS, "a_very_long_portal_room_variant_name_indeed", 1, "PORTALS",
            "portal_room", "default", true, false, false, 11, 13, 7, "bedrock_lock");
        double halfW = EditorPlotLabelsRenderer.halfWidth(longName, SIX_PX);
        assertTrue(halfW >= SIX_PX.applyAsInt(longName.name()) * 0.025 / 2.0);
    }

    private static int indexOf(RowKind[] rows, RowKind kind) {
        for (int i = 0; i < rows.length; i++) {
            if (rows[i] == kind) return i;
        }
        throw new AssertionError("row " + kind + " not present");
    }

    @Test
    @DisplayName("The Sky row shows on every portal room and reads back what the tag says")
    void skyRowShowsOnEveryPortalRoomAndReadsItsValue() {
        // Every wall mode, on the same reasoning as Contents and Books: the sky a room stands under
        // is not a property of how it seals.
        for (String mode : new String[]{"bedrock_lock", "bedrockless", "endless_open",
                "endless_repetition"}) {
            assertTrue(EditorPlotLabelsRenderer.hasRoomSkyRow(
                entry("PORTALS", true, 1, 11, 13, 7, mode)), mode);
        }
        // ...and on nothing else.
        assertFalse(EditorPlotLabelsRenderer.hasRoomSkyRow(
            entry("PORTALS", false, 1, 11, 13, 7, "bedrock_lock")));

        assertEquals("Sky: Off", EditorPlotLabelsRenderer.roomSkyLabel("bedrock_lock"));
        assertEquals("Sky: Daylight",
            EditorPlotLabelsRenderer.roomSkyLabel("bedrock_lock/exact/off/off/off/day"));
        assertEquals("Sky: Day/Night",
            EditorPlotLabelsRenderer.roomSkyLabel("bedrock_lock/exact/off/off/off/cycle"));
        assertEquals("Sky: Nether",
            EditorPlotLabelsRenderer.roomSkyLabel("bedrock_lock/exact/off/off/off/nether"));
        assertEquals("Sky: End",
            EditorPlotLabelsRenderer.roomSkyLabel("bedrock_lock/exact/off/off/off/end"));
    }
}
