package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The grammar of the Copies segment — {@code exact}, {@code dynamic},
 * {@code single:minecraft:sandstone}.
 *
 * <p>Worth its own file because the segment is the one place two separator conventions meet: the
 * settings tag splits on {@code /} and this setting splits on {@code :}, while a block id carries a
 * colon of its own. Getting the split wrong reads {@code minecraft} as the block and drops the rest.</p>
 */
class PortalRoomCopiesTest {

    @Test
    @DisplayName("The two block-less kinds round-trip as the bare words they always were")
    void blocklessKindsRoundTripUnchanged() {
        assertEquals("exact", PortalRoomCopies.EXACT.id());
        assertEquals("dynamic", PortalRoomCopies.DYNAMIC.id());
        assertEquals(PortalRoomCopies.Kind.EXACT, PortalRoomCopies.parse("exact").kind());
        assertEquals(PortalRoomCopies.Kind.DYNAMIC, PortalRoomCopies.parse("dynamic").kind());
    }

    @Test
    @DisplayName("Single carries its block, colon and all, through a round trip")
    void singleRoundTripsWithItsBlock() {
        PortalRoomCopies single =
            new PortalRoomCopies(PortalRoomCopies.Kind.SINGLE, "minecraft:sandstone");

        assertEquals("single:minecraft:sandstone", single.id());

        PortalRoomCopies back = PortalRoomCopies.parse(single.id());
        assertEquals(PortalRoomCopies.Kind.SINGLE, back.kind());
        // The whole id, not "minecraft" — this is what splitting on every colon would break.
        assertEquals("minecraft:sandstone", back.blockId());
        assertEquals(single, back);
    }

    @Test
    @DisplayName("A modded block id with more than one colon survives too")
    void moddedBlockIdSurvives() {
        PortalRoomCopies back = PortalRoomCopies.parse("single:create:refined_radiance_casing");
        assertEquals("create:refined_radiance_casing", back.blockId());
    }

    @Test
    @DisplayName("Parsing is total — a typo stamps a room rather than failing a pair")
    void parsingIsTotal() {
        assertEquals(PortalRoomCopies.DEFAULT, PortalRoomCopies.parse(null));
        assertEquals(PortalRoomCopies.DEFAULT, PortalRoomCopies.parse(""));
        assertEquals(PortalRoomCopies.DEFAULT, PortalRoomCopies.parse("   "));
        assertEquals(PortalRoomCopies.Kind.EXACT, PortalRoomCopies.parse("dynmaic").kind());
        // A kind nobody recognises still swallows its parameter rather than leaking it into the
        // block: the whole segment is one setting's business.
        assertEquals(PortalRoomCopies.Kind.EXACT, PortalRoomCopies.parse("rubbish:nonsense").kind());
    }

    @Test
    @DisplayName("A block named by nothing, or by too much, reads back as the placeholder")
    void unsetOrOverlongBlockFallsBack() {
        assertEquals(PortalRoomCopies.DEFAULT_BLOCK, PortalRoomCopies.parse("single").blockId());
        assertEquals(PortalRoomCopies.DEFAULT_BLOCK,
            PortalRoomCopies.parse("single:  ").blockId());
        // Long enough to push the whole settings tag past the packet's writeUtf cap, which would
        // throw on a live server rather than draw a wrong room.
        String tooLong = "minecraft:" + "a".repeat(PortalRoomCopies.BLOCK_ID_MAX);
        assertEquals(PortalRoomCopies.DEFAULT_BLOCK,
            PortalRoomCopies.parse("single:" + tooLong).blockId());
    }

    @Test
    @DisplayName("Ids are stored lower-cased and trimmed, so two spellings of one block agree")
    void blockIdsAreNormalised() {
        assertEquals("minecraft:sandstone",
            new PortalRoomCopies(PortalRoomCopies.Kind.SINGLE, "  Minecraft:Sandstone ").blockId());
    }

    @Test
    @DisplayName("The cycle wraps through all three where Single applies, keeping the block")
    void cycleWithSingle() {
        PortalRoomCopies exact =
            new PortalRoomCopies(PortalRoomCopies.Kind.EXACT, "minecraft:sandstone");

        PortalRoomCopies dynamic = exact.next(true);
        assertEquals(PortalRoomCopies.Kind.DYNAMIC, dynamic.kind());
        PortalRoomCopies single = dynamic.next(true);
        assertEquals(PortalRoomCopies.Kind.SINGLE, single.kind());
        assertEquals(PortalRoomCopies.Kind.EXACT, single.next(true).kind());

        // The block rides through every step, so stepping past Single and back does not lose it.
        assertEquals("minecraft:sandstone", single.blockId());
        assertEquals("minecraft:sandstone", single.next(true).blockId());
    }

    @Test
    @DisplayName("The cycle skips Single where the walls cannot use it, and still wraps")
    void cycleWithoutSingle() {
        PortalRoomCopies exact = PortalRoomCopies.EXACT;

        PortalRoomCopies dynamic = exact.next(false);
        assertEquals(PortalRoomCopies.Kind.DYNAMIC, dynamic.kind());
        // Off the end of a two-option cycle is the first option, not the one that was skipped.
        assertEquals(PortalRoomCopies.Kind.EXACT, dynamic.next(false).kind());
    }

    @Test
    @DisplayName("A room already on Single steps off it even where Single is not offered")
    void cycleLeavesSingleWhenItIsNotAllowed() {
        PortalRoomCopies single = PortalRoomCopies.of(PortalRoomCopies.Kind.SINGLE);
        assertEquals(PortalRoomCopies.Kind.EXACT, single.next(false).kind());
    }

    @Test
    @DisplayName("Only Single repeats one block")
    void onlySingleRepeatsOneBlock() {
        assertTrue(PortalRoomCopies.of(PortalRoomCopies.Kind.SINGLE).repeatsOneBlock());
        assertFalse(PortalRoomCopies.EXACT.repeatsOneBlock());
        assertFalse(PortalRoomCopies.DYNAMIC.repeatsOneBlock());
    }
}
