package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A room's boundary settings and the seed each of its copies rolls from.
 *
 * <p>The seed rule is the load-bearing one: Dynamic is supposed to make copies differ from one
 * another <b>without</b> making a sliding window into a loot machine, and that only holds if the roll
 * is a pure function of where the copy is.</p>
 */
class PortalRoomSettingsTest {

    private static final CarriageDims DIMS = CarriageDims.DEFAULT;
    private static final BlockPos ORIGIN = new BlockPos(200, -60, -30);

    /** One pair's key — the entry corridor's carriage index. Held still where it is not the subject. */
    private static final int PAIR = 47;

    private static PortalStructure structure(PortalRoomMode mode, PortalRoomCopies copies) {
        return new PortalStructure(ORIGIN, "default", PortalRoomLayout.builtInSize(DIMS),
            new PortalRoomSettings(mode, copies), PortalRoomTiling.base());
    }

    // ---- the stored tag ----

    @Test
    @DisplayName("A room that does not repeat stores the bare mode id it always did")
    void nonRepeatingRoomsRoundTripAsBareIds() {
        assertEquals("bedrock_lock",
            new PortalRoomSettings(PortalRoomMode.BEDROCK_LOCK, PortalRoomCopies.DYNAMIC).toTag());
        assertEquals("endless_open",
            new PortalRoomSettings(PortalRoomMode.ENDLESS_OPEN, PortalRoomCopies.DYNAMIC).toTag());
        assertEquals("bedrockless",
            new PortalRoomSettings(PortalRoomMode.BEDROCKLESS, PortalRoomCopies.DYNAMIC).toTag());
    }

    @Test
    @DisplayName("The sub-mode is only written when it changes something")
    void subModeOmittedAtItsDefault() {
        assertEquals("endless_repetition",
            new PortalRoomSettings(PortalRoomMode.ENDLESS_REPETITION, PortalRoomCopies.EXACT).toTag());
        assertEquals("endless_repetition/dynamic",
            new PortalRoomSettings(PortalRoomMode.ENDLESS_REPETITION, PortalRoomCopies.DYNAMIC).toTag());
    }

    @Test
    @DisplayName("Every settings triple round-trips through its tag")
    void roundTrip() {
        for (PortalRoomMode mode : PortalRoomMode.values()) {
            for (PortalRoomCopies copies : PortalRoomCopies.values()) {
                for (PortalRoomContents contents : PortalRoomContents.values()) {
                    PortalRoomSettings original = new PortalRoomSettings(mode, copies, contents);
                    PortalRoomSettings back = PortalRoomSettings.parse(original.toTag());
                    assertEquals(mode, back.mode(), original.toTag());
                    // The sub-mode only survives where it means anything; elsewhere it reads as default.
                    assertEquals(original.copiesApply() ? copies : PortalRoomCopies.DEFAULT,
                        back.copies(), original.toTag());
                    assertEquals(contents, back.contents(), original.toTag());
                }
            }
        }
    }

    @Test
    @DisplayName("Contents is only written when it changes something")
    void contentsOmittedAtItsDefault() {
        assertEquals("bedrock_lock", PortalRoomSettings.DEFAULT.toTag());
        assertEquals("bedrock_lock/exact/fit",
            PortalRoomSettings.DEFAULT.withContents(PortalRoomContents.FIT).toTag());
        assertEquals("endless_repetition/dynamic/tile",
            new PortalRoomSettings(PortalRoomMode.ENDLESS_REPETITION, PortalRoomCopies.DYNAMIC,
                PortalRoomContents.TILE).toTag());
    }

    @Test
    @DisplayName("Tags written before Contents existed still read, as unfurnished rooms")
    void shorterTagsStillParse() {
        // Every tag any earlier version could have written: one segment, or two.
        assertSame(PortalRoomContents.DEFAULT, PortalRoomSettings.parse("bedrock_lock").contents());
        assertSame(PortalRoomContents.DEFAULT, PortalRoomSettings.parse("endless_open").contents());
        PortalRoomSettings twoPart = PortalRoomSettings.parse("endless_repetition/dynamic");
        assertSame(PortalRoomMode.ENDLESS_REPETITION, twoPart.mode());
        assertSame(PortalRoomCopies.DYNAMIC, twoPart.copies());
        assertSame(PortalRoomContents.DEFAULT, twoPart.contents());
    }

    @Test
    @DisplayName("A misspelt Contents segment leaves the room unfurnished without losing the rest")
    void contentsParseIsTotal() {
        PortalRoomSettings s = PortalRoomSettings.parse("endless_repetition/dynamic/tyle");
        assertSame(PortalRoomMode.ENDLESS_REPETITION, s.mode());
        assertSame(PortalRoomCopies.DYNAMIC, s.copies());
        assertSame(PortalRoomContents.DEFAULT, s.contents());
    }

    @Test
    @DisplayName("Setting one control leaves the other two alone")
    void withersAreIndependent() {
        PortalRoomSettings all = new PortalRoomSettings(
            PortalRoomMode.ENDLESS_REPETITION, PortalRoomCopies.DYNAMIC, PortalRoomContents.FIT);
        assertSame(PortalRoomContents.FIT, all.withMode(PortalRoomMode.BEDROCKLESS).contents());
        assertSame(PortalRoomCopies.DYNAMIC, all.withMode(PortalRoomMode.BEDROCKLESS).copies());
        assertSame(PortalRoomContents.FIT, all.withCopies(PortalRoomCopies.EXACT).contents());
        assertSame(PortalRoomMode.ENDLESS_REPETITION,
            all.withContents(PortalRoomContents.OFF).mode());
        assertSame(PortalRoomCopies.DYNAMIC, all.withContents(PortalRoomContents.OFF).copies());
    }

    @Test
    @DisplayName("Parsing is total on both halves — a hand-edited typo stamps a room, not an error")
    void parseIsTotal() {
        assertSame(PortalRoomMode.DEFAULT, PortalRoomSettings.parse(null).mode());
        assertSame(PortalRoomCopies.DEFAULT, PortalRoomSettings.parse(null).copies());
        assertSame(PortalRoomMode.DEFAULT, PortalRoomSettings.parse("nonsense/rubbish").mode());
        assertSame(PortalRoomCopies.DEFAULT, PortalRoomSettings.parse("nonsense/rubbish").copies());
        // A good mode with a misspelt sub-mode keeps the mode.
        assertSame(PortalRoomMode.ENDLESS_REPETITION,
            PortalRoomSettings.parse("endless_repetition/dinamic").mode());
        assertSame(PortalRoomCopies.DEFAULT,
            PortalRoomSettings.parse("endless_repetition/dinamic").copies());
    }

    @Test
    @DisplayName("Only Endless Repetition makes copies, so only it has a Copies control")
    void copiesApplyOnlyToRepetition() {
        assertTrue(PortalRoomSettings.DEFAULT.withMode(PortalRoomMode.ENDLESS_REPETITION).copiesApply());
        assertFalse(PortalRoomSettings.DEFAULT.withMode(PortalRoomMode.ENDLESS_OPEN).copiesApply());
        assertFalse(PortalRoomSettings.DEFAULT.withMode(PortalRoomMode.BEDROCK_LOCK).copiesApply());
        assertFalse(PortalRoomSettings.DEFAULT.withMode(PortalRoomMode.BEDROCKLESS).copiesApply());
    }

    // ---- the seed each copy rolls from ----

    @Test
    @DisplayName("Exact: every copy shares the base room's roll, so the hall is one room repeated")
    void exactGivesEveryCopyTheSameRoll() {
        PortalStructure s = structure(PortalRoomMode.ENDLESS_REPETITION, PortalRoomCopies.EXACT);
        int base = s.variantIndexFor(PortalRoomTiling.Tile.BASE, PAIR);
        for (PortalRoomTiling.Tile tile : new PortalRoomTiling.Tile[]{
            new PortalRoomTiling.Tile(1, 0), new PortalRoomTiling.Tile(0, 3),
            new PortalRoomTiling.Tile(-4, -2)}) {
            assertEquals(base, s.variantIndexFor(tile, PAIR), tile.toString());
        }
    }

    @Test
    @DisplayName("Dynamic: copies differ from one another")
    void dynamicGivesCopiesDifferentRolls() {
        PortalStructure s = structure(PortalRoomMode.ENDLESS_REPETITION, PortalRoomCopies.DYNAMIC);
        Set<Integer> seen = new HashSet<>();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                seen.add(s.variantIndexFor(new PortalRoomTiling.Tile(x, z), PAIR));
            }
        }
        // 49 positions; a handful of hash collisions would be tolerable, wholesale sameness is not.
        assertTrue(seen.size() > 40, "only " + seen.size() + " distinct rolls across 49 copies");
        assertNotEquals(s.variantIndexFor(PortalRoomTiling.Tile.BASE, PAIR),
            s.variantIndexFor(new PortalRoomTiling.Tile(1, 0), PAIR));
    }

    @Test
    @DisplayName("Dynamic is a pure function of position — walking back finds the room you left")
    void dynamicIsDeterministicPerCopy() {
        PortalRoomTiling.Tile tile = new PortalRoomTiling.Tile(2, -3);

        // Same room, asked twice, and again through a structure rebuilt from scratch: the roll a
        // copy gets cannot depend on when it was stamped, or retiring and re-stamping one as the
        // window slides would refill its chests.
        PortalStructure first = structure(PortalRoomMode.ENDLESS_REPETITION, PortalRoomCopies.DYNAMIC);
        PortalStructure second = structure(PortalRoomMode.ENDLESS_REPETITION, PortalRoomCopies.DYNAMIC)
            .withTiling(PortalRoomTiling.base().with(tile));

        assertEquals(first.variantIndexFor(tile, PAIR), first.variantIndexFor(tile, PAIR));
        assertEquals(first.variantIndexFor(tile, PAIR), second.variantIndexFor(tile, PAIR));
    }

    @Test
    @DisplayName("Two pairs that rolled the same room name still roll different rooms")
    void differentPairsRollDifferently() {
        // The bug this pair key was added for: the index used to be the room name's hash and nothing
        // else, so every portal on the train that drew 'singlepillar' was byte-identical to the last
        // one — same block variants, same chests, same furnishing.
        PortalStructure s = structure(PortalRoomMode.ENDLESS_REPETITION, PortalRoomCopies.EXACT);

        Set<Integer> seen = new HashSet<>();
        for (int pairKey = 0; pairKey < 40; pairKey++) {
            seen.add(s.variantIndexFor(PortalRoomTiling.Tile.BASE, pairKey));
        }
        assertTrue(seen.size() > 35, "only " + seen.size() + " distinct rolls across 40 pairs");
    }

    @Test
    @DisplayName("A pair's roll depends on where it is, never on when it was stamped")
    void samePairRollsTheSameEveryStamp() {
        PortalRoomTiling.Tile tile = new PortalRoomTiling.Tile(-2, 1);

        // The train drifted and the whole structure was re-stamped somewhere else, with a different
        // set of copies standing. Same pair, so the room the player walks back into is the one they
        // left — chests included. Re-rolling here would refill them every time the window slid.
        PortalStructure planned = structure(PortalRoomMode.ENDLESS_REPETITION, PortalRoomCopies.DYNAMIC);
        PortalStructure reStamped = planned.movedTo(ORIGIN.offset(4096, 0, 0))
            .withTiling(PortalRoomTiling.base().with(tile));

        assertEquals(planned.variantIndexFor(tile, PAIR), reStamped.variantIndexFor(tile, PAIR));
    }

    @Test
    @DisplayName("Two different rooms roll differently even at the same copy position")
    void differentRoomsRollDifferently() {
        PortalStructure a = new PortalStructure(ORIGIN, "alpha", PortalRoomLayout.builtInSize(DIMS),
            new PortalRoomSettings(PortalRoomMode.ENDLESS_REPETITION, PortalRoomCopies.DYNAMIC),
            PortalRoomTiling.base());
        PortalStructure b = new PortalStructure(ORIGIN, "beta", PortalRoomLayout.builtInSize(DIMS),
            new PortalRoomSettings(PortalRoomMode.ENDLESS_REPETITION, PortalRoomCopies.DYNAMIC),
            PortalRoomTiling.base());
        assertNotEquals(a.variantIndexFor(PortalRoomTiling.Tile.BASE, PAIR),
            b.variantIndexFor(PortalRoomTiling.Tile.BASE, PAIR));
    }
}
