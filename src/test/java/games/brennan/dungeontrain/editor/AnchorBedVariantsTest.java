package games.brennan.dungeontrain.editor;

import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for the respawn anchor charge range ({@link VariantCharges}) and white bed colour
 * ({@link VariantBedColours}) block-variant options: tokens, spawn-time application, determinism,
 * both bed halves agreeing, and the sidecar round-trip that must stay diff-clean for older templates.
 */
final class AnchorBedVariantsTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static BlockState bed(Block block, Direction facing, BedPart part) {
        return block.defaultBlockState().setValue(BedBlock.FACING, facing).setValue(BedBlock.PART, part);
    }

    // ---- charges ----

    @Test
    @DisplayName("an obelisk's gem range reaches 6; an anchor still tops out at 4")
    void gemRangeUpToSix() {
        assertEquals(6, VariantCharges.MAX_GEMS);
        assertEquals(4, VariantCharges.maxFor(Blocks.RESPAWN_ANCHOR.defaultBlockState()));
        assertEquals(4, VariantCharges.maxFor(Blocks.STONE.defaultBlockState()));
        assertFalse(VariantCharges.isObelisk(Blocks.RESPAWN_ANCHOR.defaultBlockState()));
        assertTrue(VariantCharges.isChargeable(Blocks.RESPAWN_ANCHOR.defaultBlockState()));
        assertFalse(VariantCharges.isChargeable(Blocks.STONE.defaultBlockState()));

        VariantCharges six = VariantCharges.range(6, 6);
        assertEquals(six, VariantCharges.fromInt(six.toInt()), "6 fits the packed wire form");
        assertEquals(six, VariantCharges.parse(six.id()));
        assertEquals(6, six.rollCount(7L, 1L, 0, VariantCharges.MAX_GEMS));
        assertEquals(4, six.rollCount(7L, 1L, 0, VariantCharges.MAX), "clamped to the block's max");
        assertEquals(0, VariantCharges.AS_PLACED.rollCount(7L, 1L, 0, VariantCharges.MAX_GEMS), "default holds none");
        assertEquals(VariantCharges.range(2, 4), VariantCharges.range(2, 6).clampedTo(VariantCharges.MAX));
        assertSame(VariantCharges.AS_PLACED, VariantCharges.AS_PLACED.clampedTo(VariantCharges.MAX));

        BlockState anchor = Blocks.RESPAWN_ANCHOR.defaultBlockState();
        assertEquals(4, VariantCharges.range(5, 6).apply(anchor, 7L, 1L, 0).getValue(RespawnAnchorBlock.CHARGE),
            "an anchor never takes more than 4");

        VariantCharges range = VariantCharges.range(0, 6);
        Set<Integer> seen = new HashSet<>();
        for (int carriage = 0; carriage < 400; carriage++) {
            int n = range.rollCount(99L, 5L, carriage, VariantCharges.MAX_GEMS);
            assertTrue(n >= 0 && n <= 6);
            assertEquals(n, range.rollCount(99L, 5L, carriage, VariantCharges.MAX_GEMS), "deterministic");
            seen.add(n);
        }
        assertEquals(7, seen.size(), "every count 0..6 comes up");
    }

    @Test
    @DisplayName("Charge tokens and packed ints round-trip; malformed tokens are rejected")
    void chargeTokens() {
        assertSame(VariantCharges.AS_PLACED, VariantCharges.parse(""));
        assertSame(VariantCharges.AS_PLACED, VariantCharges.parse(null));
        assertEquals(VariantCharges.range(1, 4), VariantCharges.parse("1-4"));
        assertEquals(VariantCharges.range(0, 0), VariantCharges.parse("0-0"));
        assertNull(VariantCharges.parse("3-1"));
        assertNull(VariantCharges.parse("0-7"), "past the obelisk's 6");
        assertEquals(VariantCharges.range(0, 6), VariantCharges.parse("0-6"), "an obelisk's full gem range");
        assertNull(VariantCharges.parse("lots"));
        assertNull(VariantCharges.parse("2"));
        assertEquals("", VariantCharges.AS_PLACED.id());
        for (VariantCharges c : List.of(VariantCharges.AS_PLACED, VariantCharges.range(0, 4),
                VariantCharges.range(2, 2), VariantCharges.range(1, 3))) {
            assertEquals(c, VariantCharges.fromInt(c.toInt()));
            assertEquals(c, VariantCharges.parse(c.id()));
        }
        assertEquals(VariantCharges.range(3, 3), VariantCharges.range(3, 1), "max follows min up");
        assertEquals(VariantCharges.range(0, 6), VariantCharges.range(-2, 9), "clamped to 0..6");
        assertTrue(VariantCharges.isAnchor(Blocks.RESPAWN_ANCHOR.defaultBlockState()));
        assertFalse(VariantCharges.isAnchor(Blocks.STONE.defaultBlockState()));
    }

    @Test
    @DisplayName("A charge range rolls inside itself, deterministically, and covers every charge")
    void chargeRangeApplies() {
        BlockState anchor = Blocks.RESPAWN_ANCHOR.defaultBlockState().setValue(RespawnAnchorBlock.CHARGE, 2);
        assertSame(anchor, VariantCharges.AS_PLACED.apply(anchor, 7L, 1L, 0));
        assertSame(Blocks.STONE.defaultBlockState(),
            VariantCharges.range(0, 4).apply(Blocks.STONE.defaultBlockState(), 7L, 1L, 0));
        assertEquals(4, VariantCharges.range(4, 4).apply(anchor, 7L, 1L, 0).getValue(RespawnAnchorBlock.CHARGE));

        VariantCharges range = VariantCharges.range(1, 3);
        Set<Integer> seen = new HashSet<>();
        for (long key = 0; key < 200; key++) {
            int n = range.apply(anchor, key, 42L, 3).getValue(RespawnAnchorBlock.CHARGE);
            assertTrue(n >= 1 && n <= 3, "in range: " + n);
            assertEquals(n, range.apply(anchor, key, 42L, 3).getValue(RespawnAnchorBlock.CHARGE));
            seen.add(n);
        }
        assertEquals(Set.of(1, 2, 3), seen, "every charge in the range should come up");
    }

    // ---- bed colours ----

    @Test
    @DisplayName("Bed colour masks: white only by default, the last colour can't be switched off")
    void bedColourMasks() {
        assertTrue(VariantBedColours.WHITE_ONLY.isDefault());
        assertEquals(VariantBedColours.WHITE_ONLY, new VariantBedColours(0), "empty mask falls back to white");
        assertSame(VariantBedColours.WHITE_ONLY, VariantBedColours.WHITE_ONLY.toggled(DyeColor.WHITE));
        VariantBedColours two = VariantBedColours.WHITE_ONLY.toggled(DyeColor.RED);
        assertEquals(2, two.count());
        assertEquals(List.of(DyeColor.WHITE, DyeColor.RED), two.colours());
        VariantBedColours redOnly = two.toggled(DyeColor.WHITE);
        assertFalse(redOnly.isDefault());
        assertEquals(List.of("red"), redOnly.names());
        assertEquals(redOnly, VariantBedColours.fromNames(redOnly.names()));
        assertEquals(two, new VariantBedColours(two.mask()));
        assertNull(VariantBedColours.fromNames(List.of("mauve")));
        assertNull(VariantBedColours.fromNames(List.of()));
        assertTrue(VariantBedColours.isWhiteBed(Blocks.WHITE_BED.defaultBlockState()));
        assertFalse(VariantBedColours.isWhiteBed(Blocks.RED_BED.defaultBlockState()));
        for (DyeColor c : DyeColor.values()) {
            assertTrue(VariantBedColours.bedFor(c) instanceof BedBlock bedBlock && bedBlock.getColor() == c,
                "bed table for " + c);
        }
    }

    @Test
    @DisplayName("Bed colours roll only enabled colours, deterministically, and cover each one")
    void bedColourRollsCoverPool() {
        VariantBedColours pool = new VariantBedColours(
            VariantBedColours.bit(DyeColor.WHITE) | VariantBedColours.bit(DyeColor.RED) | VariantBedColours.bit(DyeColor.BLUE));
        BlockState head = bed(Blocks.WHITE_BED, Direction.EAST, BedPart.HEAD).setValue(BedBlock.OCCUPIED, true);
        assertSame(head, VariantBedColours.WHITE_ONLY.apply(head, 3L, 1L, 0));
        assertSame(Blocks.STONE.defaultBlockState(), pool.apply(Blocks.STONE.defaultBlockState(), 3L, 1L, 0));

        Set<Block> seen = new HashSet<>();
        for (long key = 0; key < 200; key++) {
            BlockState out = pool.apply(head, key, 42L, 3);
            assertTrue(Set.of(Blocks.WHITE_BED, Blocks.RED_BED, Blocks.BLUE_BED).contains(out.getBlock()), out.toString());
            assertEquals(out, pool.apply(head, key, 42L, 3));
            assertEquals(Direction.EAST, out.getValue(BedBlock.FACING));
            assertEquals(BedPart.HEAD, out.getValue(BedBlock.PART));
            assertTrue(out.getValue(BedBlock.OCCUPIED));
            seen.add(out.getBlock());
        }
        assertEquals(3, seen.size(), "every enabled colour should come up");
    }

    @Test
    @DisplayName("The foot and head halves of one bed always roll the same colour")
    void bedHalvesAgree() {
        VariantBedColours all = new VariantBedColours(VariantBedColours.ALL_MASK);
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            BlockState foot = bed(Blocks.WHITE_BED, facing, BedPart.FOOT);
            BlockState head = bed(Blocks.WHITE_BED, facing, BedPart.HEAD);
            for (int x = 0; x < 20; x++) {
                BlockPos footPos = new BlockPos(x, 1, x * 3 - 7);
                BlockPos headPos = footPos.relative(facing);
                for (long seed = 1; seed < 6; seed++) {
                    BlockState f = all.apply(foot, footPos, 0, seed, 2);
                    BlockState h = all.apply(head, headPos, 0, seed, 2);
                    assertSame(f.getBlock(), h.getBlock(), facing + " " + footPos + " seed " + seed);
                    assertEquals(BedPart.FOOT, f.getValue(BedBlock.PART));
                    assertEquals(BedPart.HEAD, h.getValue(BedBlock.PART));
                }
            }
        }
        // A lock group rolls as one, wherever its cells sit.
        BlockState foot = bed(Blocks.WHITE_BED, Direction.NORTH, BedPart.FOOT);
        assertSame(all.apply(foot, new BlockPos(1, 1, 1), 5, 9L, 0).getBlock(),
            all.apply(foot, new BlockPos(8, 1, 4), 5, 9L, 0).getBlock());
    }

    // ---- sidecar ----

    @Test
    @DisplayName("Charge and colour options survive the sidecar JSON; defaults stay out of it")
    void sidecarRoundTrip() {
        VariantBedColours colours = VariantBedColours.WHITE_ONLY.toggled(DyeColor.RED).toggled(DyeColor.LIGHT_BLUE);
        List<VariantState> cell = List.of(
            VariantState.of(Blocks.RESPAWN_ANCHOR.defaultBlockState()).withCharges(VariantCharges.range(1, 3)),
            VariantState.of(Blocks.WHITE_BED.defaultBlockState()).withBedColours(colours),
            VariantState.of(Blocks.WHITE_BED.defaultBlockState()));

        StringBuilder sb = new StringBuilder();
        CarriageVariantBlocks.appendCellJson(sb, cell, 0);
        String json = sb.toString();
        assertTrue(json.contains("\"charges\": \"1-3\""), json);
        assertTrue(json.contains("\"colours\": [\"white\", \"light_blue\", \"red\"]"), json);
        assertEquals(1, json.split("\"colours\"", -1).length - 1, "an untouched bed writes no colours: " + json);

        HolderLookup.RegistryLookup<Block> blocks = BuiltInRegistries.BLOCK.asLookup();
        CarriageVariantBlocks.ParsedCell parsed = CarriageVariantBlocks.parseCellValue(
            JsonParser.parseString(json), blocks, "test", BlockPos.ZERO);
        assertNotNull(parsed);
        assertEquals(VariantCharges.range(1, 3), parsed.states().get(0).charges());
        assertEquals(colours, parsed.states().get(1).bedColours());
        assertTrue(parsed.states().get(2).bedColours().isDefault());
        assertTrue(parsed.states().get(2).charges().isDefault());
        assertTrue(parsed.states().get(2).isPlainBareString());
    }

    @Test
    @DisplayName("Malformed charges / colours in a sidecar fall back to the defaults")
    void sidecarMalformedFallsBack() {
        String json = "[{\"state\": \"minecraft:respawn_anchor\", \"charges\": \"9-12\"},"
            + " {\"state\": \"minecraft:white_bed\", \"colours\": [\"mauve\"]}]";
        HolderLookup.RegistryLookup<Block> blocks = BuiltInRegistries.BLOCK.asLookup();
        CarriageVariantBlocks.ParsedCell parsed = CarriageVariantBlocks.parseCellValue(
            JsonParser.parseString(json), blocks, "test", BlockPos.ZERO);
        assertNotNull(parsed);
        assertTrue(parsed.states().get(0).charges().isDefault());
        assertTrue(parsed.states().get(1).bedColours().isDefault());
    }

    @Test
    @DisplayName("The whole-entry apply honours the charge range and bed colours")
    void wholeEntryApply() {
        VariantState anchor = VariantState.of(Blocks.RESPAWN_ANCHOR.defaultBlockState())
            .withCharges(VariantCharges.range(3, 3));
        assertEquals(3, RotationApplier.apply(anchor.state(), anchor, new BlockPos(1, 2, 3), 5L, 0, 0)
            .getValue(RespawnAnchorBlock.CHARGE));

        VariantBedColours greenOnly = VariantBedColours.WHITE_ONLY.toggled(DyeColor.GREEN).toggled(DyeColor.WHITE);
        VariantState bedEntry = VariantState.of(bed(Blocks.WHITE_BED, Direction.SOUTH, BedPart.FOOT))
            .withBedColours(greenOnly);
        BlockState placed = RotationApplier.apply(bedEntry.state(), bedEntry, new BlockPos(1, 2, 3), 5L, 0, 0);
        assertSame(Blocks.GREEN_BED, placed.getBlock());
        assertEquals(Direction.SOUTH, placed.getValue(BedBlock.FACING));
        assertEquals(BedPart.FOOT, placed.getValue(BedBlock.PART));

        // Both halves through the whole-entry apply, as a stamp writes them.
        VariantBedColours many = new VariantBedColours(VariantBedColours.ALL_MASK);
        VariantState footEntry = VariantState.of(bed(Blocks.WHITE_BED, Direction.WEST, BedPart.FOOT)).withBedColours(many);
        VariantState headEntry = VariantState.of(bed(Blocks.WHITE_BED, Direction.WEST, BedPart.HEAD)).withBedColours(many);
        BlockPos footPos = new BlockPos(4, 1, 2);
        for (long seed = 0; seed < 30; seed++) {
            assertSame(
                RotationApplier.apply(footEntry.state(), footEntry, footPos, seed, 1, 0).getBlock(),
                RotationApplier.apply(headEntry.state(), headEntry, footPos.relative(Direction.WEST), seed, 1, 0).getBlock());
        }
    }
}
