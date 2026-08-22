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
        assertEquals("bedrockless",
            new PortalRoomSettings(PortalRoomMode.BEDROCKLESS, PortalRoomCopies.DYNAMIC).toTag());
    }

    @Test
    @DisplayName("Endless Open keeps a Copies choice now — it appends tiles for the setting to roll")
    void endlessOpenKeepsItsSubMode() {
        // It used to flatten to "endless_open", because Copies was gated on the mode tiling the WHOLE
        // room. An open tile is only the floor and the ceiling, but those cells roll from the variant
        // sidecar like any other, so the choice means something and has to survive the round trip.
        assertEquals("endless_open/dynamic",
            new PortalRoomSettings(PortalRoomMode.ENDLESS_OPEN, PortalRoomCopies.DYNAMIC).toTag());
        assertEquals("endless_open",
            new PortalRoomSettings(PortalRoomMode.ENDLESS_OPEN, PortalRoomCopies.EXACT).toTag());
        assertEquals(PortalRoomCopies.DYNAMIC,
            PortalRoomSettings.parse("endless_open/dynamic").copies());
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
            for (PortalRoomCopies copies : everyCopiesValue()) {
                for (PortalRoomContents contents : PortalRoomContents.values()) {
                    PortalRoomSettings original = new PortalRoomSettings(mode, copies, contents);
                    PortalRoomSettings back = PortalRoomSettings.parse(original.toTag());
                    assertEquals(mode, back.mode(), original.toTag());
                    // The sub-mode only survives where it means anything; elsewhere it reads as
                    // default. Two ways for it not to mean anything, both of them effectiveCopies':
                    // walls that append no tiles at all, and Single under Endless Repetition, where
                    // a tile is a whole room and one block for it would be a solid cube.
                    assertEquals(original.effectiveCopies(), back.copies(), original.toTag());
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
        assertEquals(PortalRoomCopies.DYNAMIC, twoPart.copies());
        assertSame(PortalRoomContents.DEFAULT, twoPart.contents());
    }

    @Test
    @DisplayName("A misspelt Contents segment leaves the room unfurnished without losing the rest")
    void contentsParseIsTotal() {
        PortalRoomSettings s = PortalRoomSettings.parse("endless_repetition/dynamic/tyle");
        assertSame(PortalRoomMode.ENDLESS_REPETITION, s.mode());
        assertEquals(PortalRoomCopies.DYNAMIC, s.copies());
        assertSame(PortalRoomContents.DEFAULT, s.contents());
    }

    @Test
    @DisplayName("Setting one control leaves the other two alone")
    void withersAreIndependent() {
        PortalRoomSettings all = new PortalRoomSettings(
            PortalRoomMode.ENDLESS_REPETITION, PortalRoomCopies.DYNAMIC, PortalRoomContents.FIT);
        assertSame(PortalRoomContents.FIT, all.withMode(PortalRoomMode.BEDROCKLESS).contents());
        assertEquals(PortalRoomCopies.DYNAMIC, all.withMode(PortalRoomMode.BEDROCKLESS).copies());
        assertSame(PortalRoomContents.FIT, all.withCopies(PortalRoomCopies.EXACT).contents());
        assertSame(PortalRoomMode.ENDLESS_REPETITION,
            all.withContents(PortalRoomContents.OFF).mode());
        assertEquals(PortalRoomCopies.DYNAMIC, all.withContents(PortalRoomContents.OFF).copies());
    }

    @Test
    @DisplayName("Parsing is total on both halves — a hand-edited typo stamps a room, not an error")
    void parseIsTotal() {
        assertSame(PortalRoomMode.DEFAULT, PortalRoomSettings.parse(null).mode());
        assertEquals(PortalRoomCopies.DEFAULT, PortalRoomSettings.parse(null).copies());
        assertSame(PortalRoomMode.DEFAULT, PortalRoomSettings.parse("nonsense/rubbish").mode());
        assertEquals(PortalRoomCopies.DEFAULT, PortalRoomSettings.parse("nonsense/rubbish").copies());
        // A good mode with a misspelt sub-mode keeps the mode.
        assertSame(PortalRoomMode.ENDLESS_REPETITION,
            PortalRoomSettings.parse("endless_repetition/dinamic").mode());
        assertEquals(PortalRoomCopies.DEFAULT,
            PortalRoomSettings.parse("endless_repetition/dinamic").copies());
    }

    // ---- extra corridors ----

    @Test
    @DisplayName("An unsaid Exits segment means what the mode wants, not a fixed value")
    void exitsDefaultComesFromTheMode() {
        // The shipped labrynth room's tag, written long before this setting existed. It has to pick
        // up its extra corridors without anybody editing weights.json.
        assertEquals(PortalRoomExits.Kind.ON,
            PortalRoomSettings.parse("endless_repetition/dynamic").exits().kind());
        assertEquals(PortalRoomExits.DEFAULT_EVERY,
            PortalRoomSettings.parse("endless_repetition/dynamic").exits().every());
        // Endless Open has sightlines across it, so the way back stays visible and it lays none.
        assertEquals(PortalRoomExits.Kind.OFF,
            PortalRoomSettings.parse("endless_open").exits().kind());
        assertEquals(PortalRoomExits.Kind.OFF, PortalRoomSettings.parse("bedrock_lock").exits().kind());
    }

    @Test
    @DisplayName("Exits is only written when it differs from what its mode would have done anyway")
    void exitsOmittedAtItsModeDefault() {
        PortalRoomSettings repeating = PortalRoomSettings.DEFAULT
            .withMode(PortalRoomMode.ENDLESS_REPETITION);
        assertEquals("endless_repetition", repeating.toTag());
        assertEquals("endless_repetition/exact/off/off",
            repeating.withExits(PortalRoomExits.OFF).toTag());
        assertEquals("endless_repetition/exact/off/random:5",
            repeating.withExits(new PortalRoomExits(PortalRoomExits.Kind.RANDOM, 5)).toTag());
        // The other way round: an Endless Open room that does lay them has to say so.
        assertEquals("endless_open/exact/off/on",
            PortalRoomSettings.DEFAULT.withMode(PortalRoomMode.ENDLESS_OPEN)
                .withExits(PortalRoomExits.ON).toTag());
    }

    @Test
    @DisplayName("Every four-part settings tag round-trips")
    void exitsRoundTrip() {
        for (PortalRoomMode mode : PortalRoomMode.values()) {
            for (PortalRoomExits.Kind kind : PortalRoomExits.Kind.values()) {
                for (int every : new int[]{PortalRoomExits.MIN_EVERY, 8, 13, PortalRoomExits.MAX_EVERY}) {
                    PortalRoomSettings original = PortalRoomSettings.DEFAULT.withMode(mode)
                        .withExits(new PortalRoomExits(kind, every));
                    PortalRoomSettings back = PortalRoomSettings.parse(original.toTag());
                    assertEquals(mode, back.mode(), original.toTag());
                    // Exits only survives where it means anything; elsewhere it reads as the mode's.
                    PortalRoomExits wrote = original.effectiveExits();
                    PortalRoomExits read = back.effectiveExits();
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
        PortalRoomSettings s = PortalRoomSettings.parse("endless_repetition/dynamic/tile/randm:nine");
        assertSame(PortalRoomMode.ENDLESS_REPETITION, s.mode());
        assertEquals(PortalRoomCopies.DYNAMIC, s.copies());
        assertSame(PortalRoomContents.TILE, s.contents());
        assertEquals(PortalRoomExits.Kind.ON, s.exits().kind());
        assertEquals(PortalRoomExits.DEFAULT_EVERY, s.exits().every());
    }

    @Test
    @DisplayName("Changing the walls re-derives an inherited Exits but never overrides a chosen one")
    void withModeReDerivesOnlyAnInheritedExits() {
        // Never set: Endless Repetition's own default, so switching walls should take Endless Open's.
        PortalRoomSettings inherited = PortalRoomSettings.DEFAULT
            .withMode(PortalRoomMode.ENDLESS_REPETITION);
        assertEquals(PortalRoomExits.Kind.ON, inherited.exits().kind());
        assertEquals(PortalRoomExits.Kind.OFF,
            inherited.withMode(PortalRoomMode.ENDLESS_OPEN).exits().kind());

        // Actually chosen: it survives the switch, spacing and all.
        PortalRoomSettings chosen = inherited.withExits(new PortalRoomExits(PortalRoomExits.Kind.RANDOM, 12));
        PortalRoomSettings moved = chosen.withMode(PortalRoomMode.ENDLESS_OPEN);
        assertEquals(PortalRoomExits.Kind.RANDOM, moved.exits().kind());
        assertEquals(12, moved.exits().every());
    }

    @Test
    @DisplayName("Both endless modes have an Exits control; the sealed ones have nothing to put one in")
    void exitsApplyToBothEndlessModes() {
        assertTrue(PortalRoomSettings.DEFAULT.withMode(PortalRoomMode.ENDLESS_REPETITION).exitsApply());
        assertTrue(PortalRoomSettings.DEFAULT.withMode(PortalRoomMode.ENDLESS_OPEN).exitsApply());
        assertFalse(PortalRoomSettings.DEFAULT.withMode(PortalRoomMode.BEDROCK_LOCK).exitsApply());
        assertFalse(PortalRoomSettings.DEFAULT.withMode(PortalRoomMode.BEDROCKLESS).exitsApply());

        // A room whose walls were changed to a sealed mode keeps its stored value but must not act
        // on it — there would be no copies for the corridors to stand in.
        PortalRoomSettings offMode = PortalRoomSettings.DEFAULT
            .withMode(PortalRoomMode.ENDLESS_REPETITION)
            .withExits(new PortalRoomExits(PortalRoomExits.Kind.RANDOM, 4))
            .withMode(PortalRoomMode.BEDROCK_LOCK);
        assertEquals(PortalRoomExits.Kind.RANDOM, offMode.exits().kind());
        assertEquals(PortalRoomExits.Kind.OFF, offMode.effectiveExits().kind());
    }

    // ---- the author lock ----

    /**
     * Every Books value worth sweeping: each kind at its default weights, plus the widest weighting
     * Random can carry — which is what makes the longest-tag assertion below a real worst case.
     */
    /**
     * Every Copies value worth sweeping: each kind, and Single at the longest block id it will
     * store — the worst case for the tag, and so for the packet cap.
     */
    private static java.util.List<PortalRoomCopies> everyCopiesValue() {
        java.util.List<PortalRoomCopies> out = new java.util.ArrayList<>();
        for (PortalRoomCopies.Kind kind : PortalRoomCopies.Kind.values()) {
            out.add(PortalRoomCopies.of(kind));
        }
        out.add(new PortalRoomCopies(PortalRoomCopies.Kind.SINGLE, LONGEST_BLOCK_ID));
        return out;
    }

    /** A block id of exactly {@link PortalRoomCopies#BLOCK_ID_MAX} characters. */
    private static final String LONGEST_BLOCK_ID =
        "minecraft:" + "a".repeat(PortalRoomCopies.BLOCK_ID_MAX - "minecraft:".length());

    private static java.util.List<PortalRoomBooks> everyBooksValue() {
        java.util.List<PortalRoomBooks> out = new java.util.ArrayList<>();
        for (PortalRoomBooks.Kind kind : PortalRoomBooks.Kind.values()) {
            out.add(new PortalRoomBooks(kind));
            out.add(new PortalRoomBooks(kind, PortalRoomBooks.MAX_WEIGHT,
                PortalRoomBooks.MAX_WEIGHT, PortalRoomBooks.MAX_WEIGHT,
                PortalRoomBooks.MAX_BOOK_BOUND, PortalRoomBooks.MAX_BOOK_BOUND));
        }
        return out;
    }

    @Test
    @DisplayName("Books is only written when a room actually locks its books")
    void booksOnlyWrittenWhenSet() {
        // Off is the default, so every tag written before this setting existed is re-written unchanged.
        assertEquals("bedrock_lock", PortalRoomSettings.parse("bedrock_lock").toTag());
        assertEquals("endless_repetition/dynamic/fit",
            PortalRoomSettings.parse("endless_repetition/dynamic/fit").toTag());

        // Set, and the earlier segments appear in front of it as placeholders — including an Exits
        // segment the author never chose, because a positional segment cannot be skipped.
        assertEquals("bedrock_lock/exact/off/off/mix",
            PortalRoomSettings.parse("bedrock_lock")
                .withBooks(new PortalRoomBooks(PortalRoomBooks.Kind.MIX)).toTag());
    }

    @Test
    @DisplayName("Every five-part settings tag round-trips")
    void booksRoundTrip() {
        for (PortalRoomMode mode : PortalRoomMode.values()) {
            for (PortalRoomContents contents : PortalRoomContents.values()) {
                for (PortalRoomBooks books : everyBooksValue()) {
                    PortalRoomSettings original = PortalRoomSettings.DEFAULT.withMode(mode)
                        .withContents(contents).withBooks(books);
                    PortalRoomSettings back = PortalRoomSettings.parse(original.toTag());
                    assertEquals(mode, back.mode(), original.toTag());
                    assertEquals(contents, back.contents(), original.toTag());
                    assertEquals(books.kind(), back.books().kind(), original.toTag());
                    // Weights survive only where they mean something — off Random they are not
                    // written at all, so that a tag from before Random existed needs no migration.
                    if (books.weightsApply()) {
                        assertEquals(books, back.books(), original.toTag());
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("Tags written before Books existed still read, as rooms that lock nothing")
    void tagsWithoutBooksReadAsUnlocked() {
        assertSame(PortalRoomBooks.Kind.OFF, PortalRoomSettings.parse("bedrock_lock").books().kind());
        assertSame(PortalRoomBooks.Kind.OFF, PortalRoomSettings.parse("endless_repetition/dynamic").books().kind());
        assertSame(PortalRoomBooks.Kind.OFF, PortalRoomSettings.parse("endless_repetition/dynamic/fit").books().kind());
        assertSame(PortalRoomBooks.Kind.OFF,
            PortalRoomSettings.parse("endless_repetition/dynamic/fit/random:12").books().kind());
    }

    @Test
    @DisplayName("A misspelt Books segment leaves the room unlocked without losing the rest")
    void booksParseIsTotal() {
        PortalRoomSettings s = PortalRoomSettings.parse("endless_repetition/dynamic/tile/on/mixx");
        assertSame(PortalRoomMode.ENDLESS_REPETITION, s.mode());
        assertEquals(PortalRoomCopies.DYNAMIC, s.copies());
        assertSame(PortalRoomContents.TILE, s.contents());
        assertEquals(PortalRoomExits.Kind.ON, s.exits().kind());
        assertSame(PortalRoomBooks.Kind.OFF, s.books().kind());
    }

    @Test
    @DisplayName("Setting the author lock leaves every other control alone")
    void withBooksLeavesTheRestAlone() {
        PortalRoomSettings before = PortalRoomSettings.DEFAULT
            .withMode(PortalRoomMode.ENDLESS_REPETITION)
            .withCopies(PortalRoomCopies.DYNAMIC)
            .withContents(PortalRoomContents.TILE)
            .withExits(new PortalRoomExits(PortalRoomExits.Kind.RANDOM, 9));
        PortalRoomSettings after = before.withBooks(new PortalRoomBooks(PortalRoomBooks.Kind.MIX));

        assertSame(before.mode(), after.mode());
        assertSame(before.copies(), after.copies());
        assertSame(before.contents(), after.contents());
        assertEquals(before.exits(), after.exits());
        assertSame(PortalRoomBooks.Kind.MIX, after.books().kind());
        // ...and it survives a walls change, which re-derives Exits but has no opinion on books.
        assertSame(PortalRoomBooks.Kind.MIX,
            after.withMode(PortalRoomMode.BEDROCK_LOCK).books().kind());
    }

    @Test
    @DisplayName("The longest tag any room can write still fits the editor status packet")
    void longestTagFitsThePacket() {
        // The packet caps this string, and a fifth segment eats into that cap — so the worst case is
        // asserted here rather than reasoned about, and a future sixth setting fails this test first
        // instead of failing a writeUtf on a live server.
        String longest = "";
        for (PortalRoomMode mode : PortalRoomMode.values()) {
            for (PortalRoomCopies copies : everyCopiesValue()) {
                for (PortalRoomContents contents : PortalRoomContents.values()) {
                    for (PortalRoomBooks books : everyBooksValue()) {
                        PortalRoomExits widest = new PortalRoomExits(PortalRoomExits.Kind.RANDOM,
                            PortalRoomExits.MAX_EVERY, PortalRoomExits.MOVE_ALWAYS);
                        String tag = new PortalRoomSettings(mode, copies, contents, widest, books).toTag();
                        if (tag.length() > longest.length()) longest = tag;
                    }
                }
            }
        }
        assertTrue(longest.length() <= games.brennan.dungeontrain.net.EditorStatusPacket.MODE_TAG_MAX,
            "longest room tag '" + longest + "' is " + longest.length() + " chars, over the packet cap");
    }

    // ---- Single, which is Endless Open's alone ----

    @Test
    @DisplayName("An Endless Open room keeps Single, and the block it repeats, through a round trip")
    void singleRoundTripsUnderEndlessOpen() {
        PortalRoomSettings open = PortalRoomSettings.DEFAULT
            .withMode(PortalRoomMode.ENDLESS_OPEN)
            .withCopies(new PortalRoomCopies(PortalRoomCopies.Kind.SINGLE, "minecraft:sandstone"));

        assertEquals("endless_open/single:minecraft:sandstone", open.toTag());

        PortalRoomSettings back = PortalRoomSettings.parse(open.toTag());
        assertEquals(PortalRoomCopies.Kind.SINGLE, back.effectiveCopies().kind());
        assertEquals("minecraft:sandstone", back.effectiveCopies().blockId());
    }

    @Test
    @DisplayName("Endless Repetition reads Single back as Exact — a tile there is a whole room")
    void singleDoesNotApplyUnderEndlessRepetition() {
        PortalRoomSettings repetition = PortalRoomSettings.DEFAULT
            .withMode(PortalRoomMode.ENDLESS_REPETITION)
            .withCopies(new PortalRoomCopies(PortalRoomCopies.Kind.SINGLE, "minecraft:sandstone"));

        assertEquals(PortalRoomCopies.Kind.EXACT, repetition.effectiveCopies().kind());
        // And the tag written is the effective value, so nothing downstream has to re-derive it.
        assertEquals("endless_repetition", repetition.toTag());
    }

    @Test
    @DisplayName("A sealed room reads Single back as Exact too — it appends no tiles at all")
    void singleDoesNotApplyWithoutTiling() {
        PortalRoomSettings sealed = PortalRoomSettings.DEFAULT
            .withMode(PortalRoomMode.BEDROCK_LOCK)
            .withCopies(PortalRoomCopies.of(PortalRoomCopies.Kind.SINGLE));

        assertEquals(PortalRoomCopies.DEFAULT, sealed.effectiveCopies());
    }

    @Test
    @DisplayName("The Copies cycle offers Single under Endless Open and skips it everywhere else")
    void copiesCycleOffersSingleOnlyWhereItApplies() {
        PortalRoomSettings open = PortalRoomSettings.DEFAULT.withMode(PortalRoomMode.ENDLESS_OPEN);
        assertEquals(PortalRoomCopies.Kind.DYNAMIC, open.nextCopies().copies().kind());
        assertEquals(PortalRoomCopies.Kind.SINGLE, open.nextCopies().nextCopies().copies().kind());

        PortalRoomSettings repetition =
            PortalRoomSettings.DEFAULT.withMode(PortalRoomMode.ENDLESS_REPETITION);
        assertEquals(PortalRoomCopies.Kind.DYNAMIC, repetition.nextCopies().copies().kind());
        // Straight back to Exact rather than stopping on an option this mode cannot use.
        assertEquals(PortalRoomCopies.Kind.EXACT,
            repetition.nextCopies().nextCopies().copies().kind());
    }

    @Test
    @DisplayName("A walls change carries the block across in hand, and drops it once saved elsewhere")
    void theBlockSurvivesAWallsChangeButNotASaveUnderOtherWalls() {
        PortalRoomSettings open = PortalRoomSettings.DEFAULT
            .withMode(PortalRoomMode.ENDLESS_OPEN)
            .withCopiesBlock("minecraft:sandstone");

        // In hand, the trip is lossless: withMode changes what the setting means, not what it says.
        PortalRoomSettings andBack = open
            .withMode(PortalRoomMode.ENDLESS_REPETITION)
            .withMode(PortalRoomMode.ENDLESS_OPEN);
        assertEquals("minecraft:sandstone", andBack.copies().blockId());

        // Saved under walls that cannot use it, it is not written — the same rule Copies has always
        // followed for a room whose walls stopped repeating, and the same one Exits follows. An
        // author who parks a room on Endless Repetition picks their block again on the way back.
        String parked = open.withMode(PortalRoomMode.ENDLESS_REPETITION).toTag();
        assertEquals(PortalRoomCopies.DEFAULT_BLOCK,
            PortalRoomSettings.parse(parked).withMode(PortalRoomMode.ENDLESS_OPEN)
                .copies().blockId());
    }

    @Test
    @DisplayName("Both endless modes append tiles, so both have a Copies control")
    void copiesApplyToBothEndlessModes() {
        assertTrue(PortalRoomSettings.DEFAULT.withMode(PortalRoomMode.ENDLESS_REPETITION).copiesApply());
        assertTrue(PortalRoomSettings.DEFAULT.withMode(PortalRoomMode.ENDLESS_OPEN).copiesApply());
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
    @DisplayName("Endless Open honours Copies too — its floor and ceiling reroll per tile")
    void endlessOpenRollsPerTileUnderDynamic() {
        PortalRoomTiling.Tile away = new PortalRoomTiling.Tile(2, -1);

        PortalStructure dynamic = structure(PortalRoomMode.ENDLESS_OPEN, PortalRoomCopies.DYNAMIC);
        assertNotEquals(dynamic.variantIndexFor(PortalRoomTiling.Tile.BASE, PAIR),
            dynamic.variantIndexFor(away, PAIR));

        PortalStructure exact = structure(PortalRoomMode.ENDLESS_OPEN, PortalRoomCopies.EXACT);
        assertEquals(exact.variantIndexFor(PortalRoomTiling.Tile.BASE, PAIR),
            exact.variantIndexFor(away, PAIR));
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

    @Test
    @DisplayName("A room says nothing about its sky unless its template asked for one")
    void skyDefaultsToOff() {
        assertSame(PortalRoomSky.NONE, PortalRoomSettings.DEFAULT.sky());
        assertSame(PortalRoomSky.NONE, PortalRoomSettings.parse("bedrockless").sky());
        // Every tag written before Sky existed — one to five segments — still reads as an unlit room.
        assertSame(PortalRoomSky.NONE, PortalRoomSettings.parse("endless_repetition/dynamic").sky());
        assertSame(PortalRoomSky.NONE,
            PortalRoomSettings.parse("endless_repetition/dynamic/fit/on/mix").sky());
        // ...and is re-written unchanged, rather than growing a segment for nothing.
        assertEquals("bedrockless", PortalRoomSettings.parse("bedrockless").toTag());
    }

    @Test
    @DisplayName("A sky round-trips through the tag, placeholders and all")
    void skyRoundTrips() {
        for (PortalRoomSky sky : PortalRoomSky.values()) {
            PortalRoomSettings original = PortalRoomSettings.DEFAULT.withSky(sky);
            assertSame(sky, PortalRoomSettings.parse(original.toTag()).sky(), original.toTag());
        }
        // The segments are positional, so the four in front of Sky are written as their own
        // defaults and must read back as exactly that.
        PortalRoomSettings day = PortalRoomSettings.DEFAULT.withSky(PortalRoomSky.DAY);
        PortalRoomSettings back = PortalRoomSettings.parse(day.toTag());
        assertEquals(PortalRoomSettings.DEFAULT.copies(), back.copies());
        assertSame(PortalRoomSettings.DEFAULT.contents(), back.contents());
        assertEquals(PortalRoomSettings.DEFAULT.books(), back.books());
    }

    @Test
    @DisplayName("The shipped tags the templates were opted in with mean what they say")
    void shippedSkyTagsParse() {
        assertSame(PortalRoomSky.DAY,
            PortalRoomSettings.parse("bedrockless/exact/off/off/off/day").sky());
        assertSame(PortalRoomSky.END,
            PortalRoomSettings.parse("bedrockless/exact/off/off/off/end").sky());
        assertSame(PortalRoomSky.DAY,
            PortalRoomSettings.parse("bedrock_lock/exact/off/off/off/day").sky());
        // window_contents carries a real Contents value in front of its sky.
        PortalRoomSettings furnished =
            PortalRoomSettings.parse("bedrockless/exact/fit/off/off/day");
        assertSame(PortalRoomSky.DAY, furnished.sky());
        assertSame(PortalRoomContents.FIT, furnished.contents());
    }

    @Test
    @DisplayName("An unreadable sky stamps an unlit room rather than failing the pair's stamp")
    void skyParseIsTotal() {
        assertSame(PortalRoomSky.NONE,
            PortalRoomSettings.parse("bedrockless/exact/off/off/off/daylihgt").sky());
        assertSame(PortalRoomSky.NONE, PortalRoomSky.parse(""));
        assertSame(PortalRoomSky.NONE, PortalRoomSky.parse(null));
        // What a client on a different build than the server is sent.
        assertSame(PortalRoomSky.NONE, PortalRoomSky.byOrdinal(-1));
        assertSame(PortalRoomSky.NONE, PortalRoomSky.byOrdinal(99));
        assertSame(PortalRoomSky.DAY, PortalRoomSky.byOrdinal(PortalRoomSky.DAY.ordinal()));
    }

    @Test
    @DisplayName("Only the clock-following sky lets the lightmap darken")
    void onlyCyclePinsNothing() {
        assertFalse(PortalRoomSky.NONE.pinsDaylight());
        assertFalse(PortalRoomSky.CYCLE.pinsDaylight());
        assertTrue(PortalRoomSky.CYCLE.lights());
        assertTrue(PortalRoomSky.DAY.pinsDaylight());
        assertTrue(PortalRoomSky.NETHER.pinsDaylight());
        assertTrue(PortalRoomSky.END.pinsDaylight());
    }
}
