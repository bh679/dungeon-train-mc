package games.brennan.dungeontrain.editor;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Conditional entries at roll time ({@link VariantConditions} through {@link VariantGroupResolver}).
 *
 * <p>The properties that carry the feature: a gated entry appears exactly when its target ends up as
 * one of its blocks, a failed gate leaves the rest of the list rolling by weight, a cell with every
 * entry gated out is empty, and a cell with no conditions rolls bit-identically to before.</p>
 */
final class VariantConditionResolveTest {

    private static final int SEEDS = 24;
    private static final int INDICES = 24;

    /** The gated cell sits directly on top of {@link #BELOW}. */
    private static final BlockPos CELL = new BlockPos(2, 2, 2);
    private static final BlockPos BELOW = CELL.below();
    private static final BlockPos GROUP_CELL = new BlockPos(5, 1, 1);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static VariantState of(Block block) {
        return VariantState.of(block.defaultBlockState());
    }

    private static VariantCondition below(Block... blocks) {
        VariantCondition c = VariantCondition.NONE.withDirection(Direction.DOWN);
        for (Block b : blocks) c = c.withValueAdded(BuiltInRegistries.BLOCK.getKey(b));
        return c;
    }

    private static Block blockOf(VariantState s) {
        return s == null ? null : s.state().getBlock();
    }

    /** Below = planks or cobble; on top = stone, or glass only over planks. */
    private static CarriageVariantBlocks glassOverPlanks() {
        CarriageVariantBlocks sc = CarriageVariantBlocks.empty();
        sc.put(BELOW, List.of(of(Blocks.OAK_PLANKS), of(Blocks.COBBLESTONE)));
        sc.put(CELL, List.of(of(Blocks.STONE), of(Blocks.GLASS).withCondition(below(Blocks.OAK_PLANKS))));
        return sc;
    }

    @Test
    @DisplayName("a gated entry only ever appears over the block it asks for, and does appear there")
    void gateFollowsTheNeighboursRoll() {
        CarriageVariantBlocks sc = glassOverPlanks();
        int glass = 0;
        for (int seed = 0; seed < SEEDS; seed++) {
            for (int idx = 0; idx < INDICES; idx++) {
                Block under = blockOf(sc.resolve(BELOW, seed, idx));
                Block top = blockOf(sc.resolve(CELL, seed, idx));
                if (top == Blocks.GLASS) {
                    glass++;
                    assertEquals(Blocks.OAK_PLANKS, under, "glass placed over " + under);
                }
                if (under == Blocks.COBBLESTONE) assertEquals(Blocks.STONE, top);
            }
        }
        assertTrue(glass > 0, "glass never placed over planks — gate never opens");
    }

    @Test
    @DisplayName("several values are OR'd together")
    void valuesAreOr() {
        CarriageVariantBlocks sc = CarriageVariantBlocks.empty();
        sc.put(BELOW, List.of(of(Blocks.OAK_PLANKS), of(Blocks.COBBLESTONE)));
        sc.put(CELL, List.of(of(Blocks.GLASS).withCondition(below(Blocks.OAK_PLANKS, Blocks.COBBLESTONE)),
            of(Blocks.STONE).withCondition(below(Blocks.DIRT))));
        for (int seed = 0; seed < SEEDS; seed++) {
            assertEquals(Blocks.GLASS, blockOf(sc.resolve(CELL, seed, 0)));
        }
    }

    @Test
    @DisplayName("several sides pass when any one of them holds a listed block")
    void anySide() {
        CarriageVariantBlocks sc = CarriageVariantBlocks.empty();
        VariantCondition northOrDown = below(Blocks.OAK_PLANKS).toggleSide(Direction.NORTH);
        sc.put(CELL, List.of(of(Blocks.STONE).withCondition(below(Blocks.DIRT)), of(Blocks.GLASS).withCondition(northOrDown)));
        // Planks only to the north, nothing below: still passes.
        try (VariantConditions.Scope ignored = VariantConditions.open(
                p -> p.equals(CELL.north()) ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.AIR.defaultBlockState())) {
            assertEquals(Blocks.GLASS, blockOf(sc.resolve(CELL, 3, 3)));
        }
        // Planks on neither side: gated out, and so is stone — the cell is empty.
        assertTrue(CarriageVariantBlocks.isEmptyPlaceholder(sc.resolve(CELL, 3, 3).state()));
    }

    @Test
    @DisplayName("every entry gated out leaves the cell empty")
    void allGatedIsEmpty() {
        CarriageVariantBlocks sc = CarriageVariantBlocks.empty();
        sc.put(BELOW, List.of(of(Blocks.COBBLESTONE), of(Blocks.MOSSY_COBBLESTONE)));
        sc.put(CELL, List.of(of(Blocks.GLASS).withCondition(below(Blocks.OAK_PLANKS)),
            of(Blocks.STONE).withCondition(below(Blocks.DIRT))));
        VariantState picked = sc.resolve(CELL, 7, 3);
        assertTrue(CarriageVariantBlocks.isEmptyPlaceholder(picked.state()));
    }

    @Test
    @DisplayName("a non-variant neighbour reads the placer's template, or air with no placer")
    void templateNeighbour() {
        CarriageVariantBlocks sc = CarriageVariantBlocks.empty();
        sc.put(CELL, List.of(of(Blocks.STONE).withCondition(below(Blocks.OAK_PLANKS)),
            of(Blocks.GLASS).withCondition(below(Blocks.AIR))));
        assertEquals(Blocks.GLASS, blockOf(sc.resolve(CELL, 1, 1)));
        try (VariantConditions.Scope ignored = VariantConditions.open(
                p -> p.equals(BELOW) ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.AIR.defaultBlockState())) {
            assertEquals(Blocks.STONE, blockOf(sc.resolve(CELL, 1, 1)));
        }
        assertEquals(Blocks.GLASS, blockOf(sc.resolve(CELL, 1, 1)), "scope must close");
    }

    @Test
    @DisplayName("a group target judges what that lock group rolled")
    void groupTarget() {
        CarriageVariantBlocks sc = CarriageVariantBlocks.empty();
        sc.put(GROUP_CELL, List.of(of(Blocks.OAK_PLANKS), of(Blocks.COBBLESTONE)));
        sc.setLockId(GROUP_CELL, 3);
        VariantCondition onPlanks = VariantCondition.NONE.withGroup(3)
            .withValueAdded(BuiltInRegistries.BLOCK.getKey(Blocks.OAK_PLANKS));
        sc.put(CELL, List.of(of(Blocks.STONE), of(Blocks.GLASS).withCondition(onPlanks)));
        int glass = 0;
        for (int seed = 0; seed < SEEDS; seed++) {
            for (int idx = 0; idx < INDICES; idx++) {
                if (blockOf(sc.resolve(CELL, seed, idx)) == Blocks.GLASS) {
                    glass++;
                    assertEquals(Blocks.OAK_PLANKS, blockOf(sc.resolve(GROUP_CELL, seed, idx)));
                }
            }
        }
        assertTrue(glass > 0);
    }

    @Test
    @DisplayName("two cells gated on each other resolve deterministically instead of recursing forever")
    void cycleTerminates() {
        CarriageVariantBlocks sc = CarriageVariantBlocks.empty();
        VariantCondition upIsGlass = VariantCondition.NONE.withDirection(Direction.UP)
            .withValueAdded(BuiltInRegistries.BLOCK.getKey(Blocks.GLASS));
        sc.put(BELOW, List.of(of(Blocks.STONE), of(Blocks.OAK_PLANKS).withCondition(upIsGlass)));
        sc.put(CELL, List.of(of(Blocks.STONE), of(Blocks.GLASS).withCondition(below(Blocks.OAK_PLANKS))));
        for (int seed = 0; seed < SEEDS; seed++) {
            assertEquals(blockOf(sc.resolve(CELL, seed, 2)), blockOf(sc.resolve(CELL, seed, 2)));
            assertEquals(blockOf(sc.resolve(BELOW, seed, 2)), blockOf(sc.resolve(BELOW, seed, 2)));
        }
    }

    @Test
    @DisplayName("a cell with no conditions rolls exactly as before, even beside a gated cell")
    void ungatedUnchanged() {
        CarriageVariantBlocks gated = glassOverPlanks();
        CarriageVariantBlocks plain = CarriageVariantBlocks.empty();
        plain.put(BELOW, List.of(of(Blocks.OAK_PLANKS), of(Blocks.COBBLESTONE)));
        boolean differs = false;
        for (int seed = 0; seed < SEEDS; seed++) {
            for (int idx = 0; idx < INDICES; idx++) {
                assertEquals(blockOf(plain.resolve(BELOW, seed, idx)), blockOf(gated.resolve(BELOW, seed, idx)));
                differs |= blockOf(gated.resolve(BELOW, seed, idx)) != Blocks.OAK_PLANKS;
            }
        }
        assertTrue(differs);
        assertNotEquals(VariantConditions.EMPTY_PICK, gated.resolve(BELOW, 0, 0));
    }

    private static final VariantDifficulty FROM_TIER_3 = new VariantDifficulty(3, VariantDifficulty.ALL);

    /** Stone, or glass gated on planks below AND / OR level 3 and up. */
    private static CarriageVariantBlocks glassWithLevel(boolean any) {
        CarriageVariantBlocks sc = CarriageVariantBlocks.empty();
        VariantConditionSet set = VariantConditionSet.of(below(Blocks.OAK_PLANKS)).withAny(any);
        sc.put(CELL, List.of(of(Blocks.STONE),
            of(Blocks.GLASS).withCondition(set).withDifficulty(FROM_TIER_3)));
        return sc;
    }

    private static boolean glassEver(CarriageVariantBlocks sc, int tier, Block below) {
        try (VariantConditions.Scope ignored = VariantConditions.open(
                p -> p.equals(BELOW) ? below.defaultBlockState() : Blocks.AIR.defaultBlockState(), tier)) {
            for (int seed = 0; seed < SEEDS; seed++) {
                for (int idx = 0; idx < INDICES; idx++) {
                    if (blockOf(sc.resolve(CELL, seed, idx)) == Blocks.GLASS) return true;
                }
            }
        }
        return false;
    }

    @Test
    @DisplayName("AND: a block row's Level band and its Touch condition must both hold")
    void levelAndTouch() {
        CarriageVariantBlocks sc = glassWithLevel(false);
        assertTrue(glassEver(sc, 3, Blocks.OAK_PLANKS));
        assertFalse(glassEver(sc, 2, Blocks.OAK_PLANKS), "under the band");
        assertFalse(glassEver(sc, 5, Blocks.DIRT), "touch fails");
    }

    @Test
    @DisplayName("OR: either the Level band or the Touch condition is enough")
    void levelOrTouch() {
        CarriageVariantBlocks sc = glassWithLevel(true);
        assertTrue(glassEver(sc, 2, Blocks.OAK_PLANKS), "touch alone");
        assertTrue(glassEver(sc, 5, Blocks.DIRT), "level alone");
        assertFalse(glassEver(sc, 2, Blocks.DIRT), "neither");
    }

    @Test
    @DisplayName("with no tier (the editor) a band is left out: AND rows answer to the rest, band-only rows always roll")
    void noTierLeavesLevelOut() {
        assertTrue(glassEver(glassWithLevel(false), VariantConditions.NO_TIER, Blocks.OAK_PLANKS));
        assertFalse(glassEver(glassWithLevel(true), VariantConditions.NO_TIER, Blocks.DIRT));
        CarriageVariantBlocks bandOnly = CarriageVariantBlocks.empty();
        bandOnly.put(CELL, List.of(of(Blocks.GLASS).withDifficulty(FROM_TIER_3), of(Blocks.GLASS).withDifficulty(FROM_TIER_3)));
        assertEquals(Blocks.GLASS, blockOf(bandOnly.resolve(CELL, 1, 1)));
    }

    @Test
    @DisplayName("two Touch conditions AND together: both neighbours must match")
    void twoConditionsAnd() {
        VariantCondition northPlanks = VariantCondition.NONE.withDirection(Direction.NORTH)
            .withValueAdded(BuiltInRegistries.BLOCK.getKey(Blocks.OAK_PLANKS));
        VariantConditionSet both = VariantConditionSet.of(below(Blocks.STONE)).withAdded(northPlanks);
        CarriageVariantBlocks sc = CarriageVariantBlocks.empty();
        sc.put(CELL, List.of(of(Blocks.GLASS).withCondition(both), of(Blocks.GLASS).withCondition(both)));
        try (VariantConditions.Scope ignored = VariantConditions.open(p ->
                p.equals(BELOW) ? Blocks.STONE.defaultBlockState()
                    : p.equals(CELL.north()) ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.AIR.defaultBlockState())) {
            assertEquals(Blocks.GLASS, blockOf(sc.resolve(CELL, 1, 1)));
        }
        try (VariantConditions.Scope ignored = VariantConditions.open(p ->
                p.equals(BELOW) ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState())) {
            assertTrue(CarriageVariantBlocks.isEmptyPlaceholder(sc.resolve(CELL, 1, 1).state()));
        }
    }

    @Test
    @DisplayName("the contents tier pick drops an out-of-band mob and agrees with its tier scope")
    void contentsTierPick() {
        CarriageContentsVariantBlocks sc = CarriageContentsVariantBlocks.empty();
        VariantState zombie = VariantState.ofMob(net.minecraft.resources.ResourceLocation.withDefaultNamespace("zombie"),
            null, 1, VariantRotation.NONE).withDifficulty(FROM_TIER_3);
        sc.put(CELL, List.of(zombie, zombie));
        assertTrue(sc.resolve(CELL, 4, 4, 5).isMob());
        assertTrue(CarriageVariantBlocks.isEmptyPlaceholder(sc.resolve(CELL, 4, 4, 1).state()));
        assertTrue(sc.resolve(CELL, 4, 4).isMob(), "the editor pick has no tier");
    }
}
