package games.brennan.dungeontrain.editor;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A reference follows what its group <i>placed</i> — conditions included — and ranks after it; and
 * a condition's Is not ({@link VariantCondition#negate}) turns its gate round.
 */
final class VariantConditionFollowTest {

    private static final int SEEDS = 24;
    private static final int INDICES = 24;

    /** Group 8: stairs or bookshelf. */
    private static final BlockPos G8 = new BlockPos(8, 0, 0);
    /** Group 9: bookshelf over a group-8 bookshelf, empty over group-8 stairs. */
    private static final BlockPos G9 = new BlockPos(9, 0, 0);
    /** Group 10: only "follow group 9". */
    private static final BlockPos G10 = new BlockPos(6, 3, 0);
    /** Group 11: "follow group 9" while group 10 is empty, else empty. */
    private static final BlockPos G11 = new BlockPos(10, 2, 0);
    /** Group 13: "follow group 11" (or, rarely, empty). */
    private static final BlockPos G13 = new BlockPos(6, 3, 1);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static VariantState of(Block block) {
        return VariantState.of(block.defaultBlockState());
    }

    private static VariantCondition group(int id, Block block) {
        return new VariantCondition(0, id, List.of(BuiltInRegistries.BLOCK.getKey(block)));
    }

    private static Block blockOf(VariantState s) {
        return s == null ? null : VariantCondition.idOf(s.state()).equals(BuiltInRegistries.BLOCK.getKey(Blocks.AIR))
            ? Blocks.AIR : s.state().getBlock();
    }

    /** The Library Long chain in miniature: 13 → 11 → 9, with 9 gated on 8 and 11 gated on 10. */
    private static CarriageVariantBlocks libraryChain() {
        CarriageVariantBlocks sc = CarriageVariantBlocks.empty();
        sc.put(G8, List.of(of(Blocks.OAK_STAIRS), of(Blocks.BOOKSHELF)));
        sc.setLockId(G8, 8);
        List<VariantState> nine = List.of(
            of(Blocks.BOOKSHELF).withCondition(group(8, Blocks.BOOKSHELF)),
            VariantState.of(CarriageVariantBlocks.emptyPlaceholder()).withCondition(group(8, Blocks.OAK_STAIRS)));
        sc.put(G9, nine);
        sc.setLockId(G9, 9);
        sc.put(G10, List.of(VariantState.ofGroupRef(9, nine), VariantState.of(CarriageVariantBlocks.emptyPlaceholder())));
        sc.setLockId(G10, 10);
        List<VariantState> eleven = List.of(
            VariantState.ofGroupRef(9, nine).withCondition(group(10, Blocks.AIR)),
            VariantState.of(CarriageVariantBlocks.emptyPlaceholder()));
        sc.put(G11, eleven);
        sc.setLockId(G11, 11);
        // Weighted so the reference is taken on most rolls; the empty option is what the template has too.
        sc.put(G13, List.of(VariantState.ofGroupRef(11, eleven).withWeight(1000),
            VariantState.of(CarriageVariantBlocks.emptyPlaceholder())));
        sc.setLockId(G13, 13);
        return sc;
    }

    @Test
    @DisplayName("following a gated group places exactly what that group placed")
    void referenceFollowsTheGatedRoll() {
        CarriageVariantBlocks sc = libraryChain();
        int shelves = 0;
        for (int seed = 0; seed < SEEDS; seed++) {
            for (int idx = 0; idx < INDICES; idx++) {
                long worldSeed = seed * 0x9E3779B97F4A7C15L;
                try (VariantConditions.Scope ignored = VariantConditions.open(p -> Blocks.AIR.defaultBlockState())) {
                    Block eight = blockOf(sc.resolve(G8, worldSeed, idx));
                    Block nine = blockOf(sc.resolve(G9, worldSeed, idx));
                    Block ten = blockOf(sc.resolve(G10, worldSeed, idx));
                    Block eleven = blockOf(sc.resolve(G11, worldSeed, idx));
                    Block thirteen = blockOf(sc.resolve(G13, worldSeed, idx));
                    assertEquals(eight == Blocks.BOOKSHELF ? Blocks.BOOKSHELF : Blocks.AIR, nine, "group 9 follows group 8");
                    if (ten != Blocks.AIR) assertEquals(nine, ten, "group 10's reference took group 9's placed block");
                    if (ten != Blocks.AIR) assertEquals(Blocks.AIR, eleven, "group 11 is gated on an empty group 10");
                    if (thirteen != Blocks.AIR) assertEquals(eleven, thirteen, "group 13 places what group 11 placed");
                    if (thirteen == Blocks.BOOKSHELF) shelves++;
                }
            }
        }
        assertTrue(shelves > 0, "the chain should place a bookshelf on some rolls");
    }

    @Test
    @DisplayName("the roll is the same whichever cell is asked first")
    void orderOfAskingDoesNotMatter() {
        CarriageVariantBlocks sc = libraryChain();
        for (int seed = 0; seed < SEEDS; seed++) {
            for (int idx = 0; idx < INDICES; idx++) {
                long worldSeed = seed * 31L + 7;
                Block forward;
                Block backward;
                try (VariantConditions.Scope ignored = VariantConditions.open(p -> Blocks.AIR.defaultBlockState())) {
                    sc.resolve(G8, worldSeed, idx);
                    sc.resolve(G9, worldSeed, idx);
                    sc.resolve(G11, worldSeed, idx);
                    forward = blockOf(sc.resolve(G13, worldSeed, idx));
                }
                try (VariantConditions.Scope ignored = VariantConditions.open(p -> Blocks.AIR.defaultBlockState())) {
                    backward = blockOf(sc.resolve(G13, worldSeed, idx));
                }
                assertEquals(forward, backward);
            }
        }
    }

    @Test
    @DisplayName("a referencing cell ranks after the anchor of the group it follows")
    void referenceRanksAfterItsGroup() {
        CarriageVariantBlocks sc = libraryChain();
        VariantGroupResolver view = sc.groupRefs();
        assertTrue(view.rankOf(G10) > view.rankOf(G9), "group 10 follows group 9");
        assertTrue(view.rankOf(G13) > view.rankOf(G11), "group 13 follows group 11");
        assertTrue(view.rankOf(G11) > view.rankOf(G9), "group 11 follows group 9");
    }

    @Test
    @DisplayName("Is not round-trips through the token and survives every edit")
    void negateTokenRoundTrip() {
        VariantCondition c = VariantCondition.parse("!group3=minecraft:stone");
        assertTrue(c.negate());
        assertEquals(3, c.groupId());
        assertEquals("!group3=minecraft:stone", c.id());
        assertEquals("¬G3", c.targetLabel());
        assertFalse(VariantCondition.parse("group3=minecraft:stone").negate());

        VariantCondition sides = VariantCondition.parse("!down+north=minecraft:stone");
        assertTrue(sides.negate());
        assertTrue(sides.rotated(Rotation.CLOCKWISE_90).negate());
        assertTrue(sides.reflected(true, false, true).negate());
        assertTrue(sides.withValueAdded(BuiltInRegistries.BLOCK.getKey(Blocks.DIRT)).negate());
        assertTrue(sides.withAll(true).negate());
        assertTrue(sides.toKindMode().negate());
        assertTrue(sides.withGroup(2).negate());
        assertFalse(sides.withNegate(false).negate());
        assertEquals("down+north=minecraft:stone", sides.withNegate(false).id());
    }

    @Test
    @DisplayName("Is not places the entry only when the target is not one of the values")
    void negateTurnsTheGateRound() {
        BlockPos cell = new BlockPos(2, 2, 2);
        BlockPos below = cell.below();
        CarriageVariantBlocks sc = CarriageVariantBlocks.empty();
        sc.put(below, List.of(of(Blocks.OAK_PLANKS), of(Blocks.COBBLESTONE)));
        VariantCondition notPlanks = VariantCondition.NONE.withDirection(Direction.DOWN)
            .withValueAdded(BuiltInRegistries.BLOCK.getKey(Blocks.OAK_PLANKS)).withNegate(true);
        sc.put(cell, List.of(of(Blocks.STONE), of(Blocks.GLASS).withCondition(notPlanks)));
        int glass = 0;
        for (int seed = 0; seed < SEEDS; seed++) {
            for (int idx = 0; idx < INDICES; idx++) {
                Block under = blockOf(sc.resolve(below, seed, idx));
                Block top = blockOf(sc.resolve(cell, seed, idx));
                if (top == Blocks.GLASS) {
                    glass++;
                    assertEquals(Blocks.COBBLESTONE, under, "glass placed over " + under);
                }
                if (under == Blocks.OAK_PLANKS) assertEquals(Blocks.STONE, top);
            }
        }
        assertTrue(glass > 0, "glass should land over cobblestone on some rolls");
    }

    @Test
    @DisplayName("Is not with no values is still no condition")
    void negatedDefaultIsNoCondition() {
        VariantCondition c = VariantCondition.NONE.withNegate(true);
        assertTrue(c.isDefault());
        assertEquals(c, VariantCondition.parse(c.id()));
    }
}
