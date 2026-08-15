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
class DimensionalCarriageSettingsTest {

    private static final CarriageDims DIMS = CarriageDims.DEFAULT;
    private static final BlockPos ORIGIN = new BlockPos(200, -60, -30);

    /** One pair's key — the entry corridor's carriage index. Held still where it is not the subject. */
    private static final int PAIR = 47;

    private static PortalStructure structure(DimensionalCarriageMode mode, DimensionalCarriageCopies copies) {
        return new PortalStructure(ORIGIN, "default", DimensionalCarriageLayout.builtInSize(DIMS),
            new DimensionalCarriageSettings(mode, copies), DimensionalCarriageTiling.base());
    }

    // ---- the stored tag ----

    @Test
    @DisplayName("A room that does not repeat stores the bare mode id it always did")
    void nonRepeatingRoomsRoundTripAsBareIds() {
        assertEquals("bedrock_lock",
            new DimensionalCarriageSettings(DimensionalCarriageMode.BEDROCK_LOCK, DimensionalCarriageCopies.DYNAMIC).toTag());
        assertEquals("endless_open",
            new DimensionalCarriageSettings(DimensionalCarriageMode.ENDLESS_OPEN, DimensionalCarriageCopies.DYNAMIC).toTag());
        assertEquals("bedrockless",
            new DimensionalCarriageSettings(DimensionalCarriageMode.BEDROCKLESS, DimensionalCarriageCopies.DYNAMIC).toTag());
    }

    @Test
    @DisplayName("The sub-mode is only written when it changes something")
    void subModeOmittedAtItsDefault() {
        assertEquals("endless_repetition",
            new DimensionalCarriageSettings(DimensionalCarriageMode.ENDLESS_REPETITION, DimensionalCarriageCopies.EXACT).toTag());
        assertEquals("endless_repetition/dynamic",
            new DimensionalCarriageSettings(DimensionalCarriageMode.ENDLESS_REPETITION, DimensionalCarriageCopies.DYNAMIC).toTag());
    }

    @Test
    @DisplayName("Every settings triple round-trips through its tag")
    void roundTrip() {
        for (DimensionalCarriageMode mode : DimensionalCarriageMode.values()) {
            for (DimensionalCarriageCopies copies : DimensionalCarriageCopies.values()) {
                for (DimensionalCarriageContents contents : DimensionalCarriageContents.values()) {
                    DimensionalCarriageSettings original = new DimensionalCarriageSettings(mode, copies, contents);
                    DimensionalCarriageSettings back = DimensionalCarriageSettings.parse(original.toTag());
                    assertEquals(mode, back.mode(), original.toTag());
                    // The sub-mode only survives where it means anything; elsewhere it reads as default.
                    assertEquals(original.copiesApply() ? copies : DimensionalCarriageCopies.DEFAULT,
                        back.copies(), original.toTag());
                    assertEquals(contents, back.contents(), original.toTag());
                }
            }
        }
    }

    @Test
    @DisplayName("Contents is only written when it changes something")
    void contentsOmittedAtItsDefault() {
        assertEquals("bedrock_lock", DimensionalCarriageSettings.DEFAULT.toTag());
        assertEquals("bedrock_lock/exact/fit",
            DimensionalCarriageSettings.DEFAULT.withContents(DimensionalCarriageContents.FIT).toTag());
        assertEquals("endless_repetition/dynamic/tile",
            new DimensionalCarriageSettings(DimensionalCarriageMode.ENDLESS_REPETITION, DimensionalCarriageCopies.DYNAMIC,
                DimensionalCarriageContents.TILE).toTag());
    }

    @Test
    @DisplayName("Tags written before Contents existed still read, as unfurnished rooms")
    void shorterTagsStillParse() {
        // Every tag any earlier version could have written: one segment, or two.
        assertSame(DimensionalCarriageContents.DEFAULT, DimensionalCarriageSettings.parse("bedrock_lock").contents());
        assertSame(DimensionalCarriageContents.DEFAULT, DimensionalCarriageSettings.parse("endless_open").contents());
        DimensionalCarriageSettings twoPart = DimensionalCarriageSettings.parse("endless_repetition/dynamic");
        assertSame(DimensionalCarriageMode.ENDLESS_REPETITION, twoPart.mode());
        assertSame(DimensionalCarriageCopies.DYNAMIC, twoPart.copies());
        assertSame(DimensionalCarriageContents.DEFAULT, twoPart.contents());
    }

    @Test
    @DisplayName("A misspelt Contents segment leaves the room unfurnished without losing the rest")
    void contentsParseIsTotal() {
        DimensionalCarriageSettings s = DimensionalCarriageSettings.parse("endless_repetition/dynamic/tyle");
        assertSame(DimensionalCarriageMode.ENDLESS_REPETITION, s.mode());
        assertSame(DimensionalCarriageCopies.DYNAMIC, s.copies());
        assertSame(DimensionalCarriageContents.DEFAULT, s.contents());
    }

    @Test
    @DisplayName("Setting one control leaves the other two alone")
    void withersAreIndependent() {
        DimensionalCarriageSettings all = new DimensionalCarriageSettings(
            DimensionalCarriageMode.ENDLESS_REPETITION, DimensionalCarriageCopies.DYNAMIC, DimensionalCarriageContents.FIT);
        assertSame(DimensionalCarriageContents.FIT, all.withMode(DimensionalCarriageMode.BEDROCKLESS).contents());
        assertSame(DimensionalCarriageCopies.DYNAMIC, all.withMode(DimensionalCarriageMode.BEDROCKLESS).copies());
        assertSame(DimensionalCarriageContents.FIT, all.withCopies(DimensionalCarriageCopies.EXACT).contents());
        assertSame(DimensionalCarriageMode.ENDLESS_REPETITION,
            all.withContents(DimensionalCarriageContents.OFF).mode());
        assertSame(DimensionalCarriageCopies.DYNAMIC, all.withContents(DimensionalCarriageContents.OFF).copies());
    }

    @Test
    @DisplayName("Parsing is total on both halves — a hand-edited typo stamps a room, not an error")
    void parseIsTotal() {
        assertSame(DimensionalCarriageMode.DEFAULT, DimensionalCarriageSettings.parse(null).mode());
        assertSame(DimensionalCarriageCopies.DEFAULT, DimensionalCarriageSettings.parse(null).copies());
        assertSame(DimensionalCarriageMode.DEFAULT, DimensionalCarriageSettings.parse("nonsense/rubbish").mode());
        assertSame(DimensionalCarriageCopies.DEFAULT, DimensionalCarriageSettings.parse("nonsense/rubbish").copies());
        // A good mode with a misspelt sub-mode keeps the mode.
        assertSame(DimensionalCarriageMode.ENDLESS_REPETITION,
            DimensionalCarriageSettings.parse("endless_repetition/dinamic").mode());
        assertSame(DimensionalCarriageCopies.DEFAULT,
            DimensionalCarriageSettings.parse("endless_repetition/dinamic").copies());
    }

    // ---- extra corridors ----

    @Test
    @DisplayName("An unsaid Exits segment means what the mode wants, not a fixed value")
    void exitsDefaultComesFromTheMode() {
        // The shipped labrynth room's tag, written long before this setting existed. It has to pick
        // up its extra corridors without anybody editing weights.json.
        assertEquals(DimensionalCarriageExits.Kind.ON,
            DimensionalCarriageSettings.parse("endless_repetition/dynamic").exits().kind());
        assertEquals(DimensionalCarriageExits.DEFAULT_EVERY,
            DimensionalCarriageSettings.parse("endless_repetition/dynamic").exits().every());
        // Endless Open has sightlines across it, so the way back stays visible and it lays none.
        assertEquals(DimensionalCarriageExits.Kind.OFF,
            DimensionalCarriageSettings.parse("endless_open").exits().kind());
        assertEquals(DimensionalCarriageExits.Kind.OFF, DimensionalCarriageSettings.parse("bedrock_lock").exits().kind());
    }

    @Test
    @DisplayName("Exits is only written when it differs from what its mode would have done anyway")
    void exitsOmittedAtItsModeDefault() {
        DimensionalCarriageSettings repeating = DimensionalCarriageSettings.DEFAULT
            .withMode(DimensionalCarriageMode.ENDLESS_REPETITION);
        assertEquals("endless_repetition", repeating.toTag());
        assertEquals("endless_repetition/exact/off/off",
            repeating.withExits(DimensionalCarriageExits.OFF).toTag());
        assertEquals("endless_repetition/exact/off/random:5",
            repeating.withExits(new DimensionalCarriageExits(DimensionalCarriageExits.Kind.RANDOM, 5)).toTag());
        // The other way round: an Endless Open room that does lay them has to say so.
        assertEquals("endless_open/exact/off/on",
            DimensionalCarriageSettings.DEFAULT.withMode(DimensionalCarriageMode.ENDLESS_OPEN)
                .withExits(DimensionalCarriageExits.ON).toTag());
    }

    @Test
    @DisplayName("Every four-part settings tag round-trips")
    void exitsRoundTrip() {
        for (DimensionalCarriageMode mode : DimensionalCarriageMode.values()) {
            for (DimensionalCarriageExits.Kind kind : DimensionalCarriageExits.Kind.values()) {
                for (int every : new int[]{DimensionalCarriageExits.MIN_EVERY, 8, 13, DimensionalCarriageExits.MAX_EVERY}) {
                    DimensionalCarriageSettings original = DimensionalCarriageSettings.DEFAULT.withMode(mode)
                        .withExits(new DimensionalCarriageExits(kind, every));
                    DimensionalCarriageSettings back = DimensionalCarriageSettings.parse(original.toTag());
                    assertEquals(mode, back.mode(), original.toTag());
                    // Exits only survives where it means anything; elsewhere it reads as the mode's.
                    DimensionalCarriageExits wrote = original.effectiveExits();
                    DimensionalCarriageExits read = back.effectiveExits();
                    assertEquals(wrote.kind(), read.kind(), original.toTag());
                    // A spacing is only carried when something is being spaced — Off drops it on
                    // purpose, so there is nothing to compare there.
                    if (wrote.lays()) assertEquals(wrote.every(), read.every(), original.toTag());
                }
            }
        }
    }

    @Test
    @DisplayName("A misspelt Exits segment keeps the rest of the tag")
    void exitsParseIsTotal() {
        DimensionalCarriageSettings s = DimensionalCarriageSettings.parse("endless_repetition/dynamic/tile/randm:nine");
        assertSame(DimensionalCarriageMode.ENDLESS_REPETITION, s.mode());
        assertSame(DimensionalCarriageCopies.DYNAMIC, s.copies());
        assertSame(DimensionalCarriageContents.TILE, s.contents());
        assertEquals(DimensionalCarriageExits.Kind.ON, s.exits().kind());
        assertEquals(DimensionalCarriageExits.DEFAULT_EVERY, s.exits().every());
    }

    @Test
    @DisplayName("Changing the walls re-derives an inherited Exits but never overrides a chosen one")
    void withModeReDerivesOnlyAnInheritedExits() {
        // Never set: Endless Repetition's own default, so switching walls should take Endless Open's.
        DimensionalCarriageSettings inherited = DimensionalCarriageSettings.DEFAULT
            .withMode(DimensionalCarriageMode.ENDLESS_REPETITION);
        assertEquals(DimensionalCarriageExits.Kind.ON, inherited.exits().kind());
        assertEquals(DimensionalCarriageExits.Kind.OFF,
            inherited.withMode(DimensionalCarriageMode.ENDLESS_OPEN).exits().kind());

        // Actually chosen: it survives the switch, spacing and all.
        DimensionalCarriageSettings chosen = inherited.withExits(new DimensionalCarriageExits(DimensionalCarriageExits.Kind.RANDOM, 12));
        DimensionalCarriageSettings moved = chosen.withMode(DimensionalCarriageMode.ENDLESS_OPEN);
        assertEquals(DimensionalCarriageExits.Kind.RANDOM, moved.exits().kind());
        assertEquals(12, moved.exits().every());
    }

    @Test
    @DisplayName("Both endless modes have an Exits control; the sealed ones have nothing to put one in")
    void exitsApplyToBothEndlessModes() {
        assertTrue(DimensionalCarriageSettings.DEFAULT.withMode(DimensionalCarriageMode.ENDLESS_REPETITION).exitsApply());
        assertTrue(DimensionalCarriageSettings.DEFAULT.withMode(DimensionalCarriageMode.ENDLESS_OPEN).exitsApply());
        assertFalse(DimensionalCarriageSettings.DEFAULT.withMode(DimensionalCarriageMode.BEDROCK_LOCK).exitsApply());
        assertFalse(DimensionalCarriageSettings.DEFAULT.withMode(DimensionalCarriageMode.BEDROCKLESS).exitsApply());

        // A room whose walls were changed to a sealed mode keeps its stored value but must not act
        // on it — there would be no copies for the corridors to stand in.
        DimensionalCarriageSettings offMode = DimensionalCarriageSettings.DEFAULT
            .withMode(DimensionalCarriageMode.ENDLESS_REPETITION)
            .withExits(new DimensionalCarriageExits(DimensionalCarriageExits.Kind.RANDOM, 4))
            .withMode(DimensionalCarriageMode.BEDROCK_LOCK);
        assertEquals(DimensionalCarriageExits.Kind.RANDOM, offMode.exits().kind());
        assertEquals(DimensionalCarriageExits.Kind.OFF, offMode.effectiveExits().kind());
    }

    @Test
    @DisplayName("Only Endless Repetition makes copies, so only it has a Copies control")
    void copiesApplyOnlyToRepetition() {
        assertTrue(DimensionalCarriageSettings.DEFAULT.withMode(DimensionalCarriageMode.ENDLESS_REPETITION).copiesApply());
        assertFalse(DimensionalCarriageSettings.DEFAULT.withMode(DimensionalCarriageMode.ENDLESS_OPEN).copiesApply());
        assertFalse(DimensionalCarriageSettings.DEFAULT.withMode(DimensionalCarriageMode.BEDROCK_LOCK).copiesApply());
        assertFalse(DimensionalCarriageSettings.DEFAULT.withMode(DimensionalCarriageMode.BEDROCKLESS).copiesApply());
    }

    // ---- the seed each copy rolls from ----

    @Test
    @DisplayName("Exact: every copy shares the base room's roll, so the hall is one room repeated")
    void exactGivesEveryCopyTheSameRoll() {
        PortalStructure s = structure(DimensionalCarriageMode.ENDLESS_REPETITION, DimensionalCarriageCopies.EXACT);
        int base = s.variantIndexFor(DimensionalCarriageTiling.Tile.BASE, PAIR);
        for (DimensionalCarriageTiling.Tile tile : new DimensionalCarriageTiling.Tile[]{
            new DimensionalCarriageTiling.Tile(1, 0), new DimensionalCarriageTiling.Tile(0, 3),
            new DimensionalCarriageTiling.Tile(-4, -2)}) {
            assertEquals(base, s.variantIndexFor(tile, PAIR), tile.toString());
        }
    }

    @Test
    @DisplayName("Dynamic: copies differ from one another")
    void dynamicGivesCopiesDifferentRolls() {
        PortalStructure s = structure(DimensionalCarriageMode.ENDLESS_REPETITION, DimensionalCarriageCopies.DYNAMIC);
        Set<Integer> seen = new HashSet<>();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                seen.add(s.variantIndexFor(new DimensionalCarriageTiling.Tile(x, z), PAIR));
            }
        }
        // 49 positions; a handful of hash collisions would be tolerable, wholesale sameness is not.
        assertTrue(seen.size() > 40, "only " + seen.size() + " distinct rolls across 49 copies");
        assertNotEquals(s.variantIndexFor(DimensionalCarriageTiling.Tile.BASE, PAIR),
            s.variantIndexFor(new DimensionalCarriageTiling.Tile(1, 0), PAIR));
    }

    @Test
    @DisplayName("Dynamic is a pure function of position — walking back finds the room you left")
    void dynamicIsDeterministicPerCopy() {
        DimensionalCarriageTiling.Tile tile = new DimensionalCarriageTiling.Tile(2, -3);

        // Same room, asked twice, and again through a structure rebuilt from scratch: the roll a
        // copy gets cannot depend on when it was stamped, or retiring and re-stamping one as the
        // window slides would refill its chests.
        PortalStructure first = structure(DimensionalCarriageMode.ENDLESS_REPETITION, DimensionalCarriageCopies.DYNAMIC);
        PortalStructure second = structure(DimensionalCarriageMode.ENDLESS_REPETITION, DimensionalCarriageCopies.DYNAMIC)
            .withTiling(DimensionalCarriageTiling.base().with(tile));

        assertEquals(first.variantIndexFor(tile, PAIR), first.variantIndexFor(tile, PAIR));
        assertEquals(first.variantIndexFor(tile, PAIR), second.variantIndexFor(tile, PAIR));
    }

    @Test
    @DisplayName("Two pairs that rolled the same room name still roll different rooms")
    void differentPairsRollDifferently() {
        // The bug this pair key was added for: the index used to be the room name's hash and nothing
        // else, so every portal on the train that drew 'singlepillar' was byte-identical to the last
        // one — same block variants, same chests, same furnishing.
        PortalStructure s = structure(DimensionalCarriageMode.ENDLESS_REPETITION, DimensionalCarriageCopies.EXACT);

        Set<Integer> seen = new HashSet<>();
        for (int pairKey = 0; pairKey < 40; pairKey++) {
            seen.add(s.variantIndexFor(DimensionalCarriageTiling.Tile.BASE, pairKey));
        }
        assertTrue(seen.size() > 35, "only " + seen.size() + " distinct rolls across 40 pairs");
    }

    @Test
    @DisplayName("A pair's roll depends on where it is, never on when it was stamped")
    void samePairRollsTheSameEveryStamp() {
        DimensionalCarriageTiling.Tile tile = new DimensionalCarriageTiling.Tile(-2, 1);

        // The train drifted and the whole structure was re-stamped somewhere else, with a different
        // set of copies standing. Same pair, so the room the player walks back into is the one they
        // left — chests included. Re-rolling here would refill them every time the window slid.
        PortalStructure planned = structure(DimensionalCarriageMode.ENDLESS_REPETITION, DimensionalCarriageCopies.DYNAMIC);
        PortalStructure reStamped = planned.movedTo(ORIGIN.offset(4096, 0, 0))
            .withTiling(DimensionalCarriageTiling.base().with(tile));

        assertEquals(planned.variantIndexFor(tile, PAIR), reStamped.variantIndexFor(tile, PAIR));
    }

    @Test
    @DisplayName("Two different rooms roll differently even at the same copy position")
    void differentRoomsRollDifferently() {
        PortalStructure a = new PortalStructure(ORIGIN, "alpha", DimensionalCarriageLayout.builtInSize(DIMS),
            new DimensionalCarriageSettings(DimensionalCarriageMode.ENDLESS_REPETITION, DimensionalCarriageCopies.DYNAMIC),
            DimensionalCarriageTiling.base());
        PortalStructure b = new PortalStructure(ORIGIN, "beta", DimensionalCarriageLayout.builtInSize(DIMS),
            new DimensionalCarriageSettings(DimensionalCarriageMode.ENDLESS_REPETITION, DimensionalCarriageCopies.DYNAMIC),
            DimensionalCarriageTiling.base());
        assertNotEquals(a.variantIndexFor(DimensionalCarriageTiling.Tile.BASE, PAIR),
            b.variantIndexFor(DimensionalCarriageTiling.Tile.BASE, PAIR));
    }
}
