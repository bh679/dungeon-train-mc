package games.brennan.dungeontrain.editor;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A contents template's edge cells reading the carriage round them ({@link VariantConditions#bounded}
 * with a ring of 1): the ring is read from the world, anything further out is still air, and a ring of
 * 0 keeps the old "the box ends at its edge" answer.
 */
final class VariantConditionCarriageRingTest {

    /** A 7x5x5 interior, the size of the dripstone creeper room. */
    private static final Vec3i SIZE = new Vec3i(7, 5, 5);
    /** A ceiling-row cell; the block above it is the carriage's ceiling. */
    private static final BlockPos CEILING_CELL = new BlockPos(3, 4, 2);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** A world that is stone everywhere: the carriage shell, and whatever lies beyond it. */
    private static final VariantConditions.TemplateLookup STONE_WORLD = p -> Blocks.STONE.defaultBlockState();

    /** Dripstone where the block above is not air, as the dripstone room's ceiling row asks; glass where it is. */
    private static VariantDoc hangsFromCeiling() {
        VariantCondition aboveNotAir = VariantCondition.NONE.withDirection(Direction.UP)
            .withValueAdded(VariantCondition.valueFor(null)).withNegate(true);
        VariantDoc sc = CarriageVariantBlocks.empty();
        sc.put(CEILING_CELL, List.of(
            VariantState.of(Blocks.POINTED_DRIPSTONE.defaultBlockState()).withCondition(aboveNotAir),
            VariantState.of(Blocks.GLASS.defaultBlockState()).withCondition(aboveNotAir.withNegate(false))));
        return sc;
    }

    @Test
    @DisplayName("ring 1 reads the world one block out, and nothing further")
    void ringBounds() {
        VariantConditions.TemplateLookup ring = VariantConditions.bounded(STONE_WORLD, SIZE, 1);
        assertEquals(Blocks.STONE, ring.at(new BlockPos(-1, 0, 0)).getBlock());
        assertEquals(Blocks.STONE, ring.at(new BlockPos(7, 5, 5)).getBlock());
        assertEquals(Blocks.AIR, ring.at(new BlockPos(-2, 0, 0)).getBlock());
        assertEquals(Blocks.AIR, ring.at(new BlockPos(3, 6, 2)).getBlock());
        VariantConditions.TemplateLookup box = VariantConditions.bounded(STONE_WORLD, SIZE, 0);
        assertEquals(Blocks.STONE, box.at(new BlockPos(6, 4, 4)).getBlock());
        assertEquals(Blocks.AIR, box.at(new BlockPos(7, 4, 4)).getBlock());
    }

    @Test
    @DisplayName("an edge cell gates on the carriage block beyond it with the ring, and on air without")
    void edgeCellSeesCarriage() {
        VariantDoc sc = hangsFromCeiling();
        try (VariantConditions.Scope ignored = VariantConditions.open(VariantConditions.bounded(STONE_WORLD, SIZE, 1))) {
            assertEquals(Blocks.POINTED_DRIPSTONE, sc.resolve(CEILING_CELL, 5, 3).state().getBlock());
        }
        try (VariantConditions.Scope ignored = VariantConditions.open(VariantConditions.bounded(STONE_WORLD, SIZE, 0))) {
            assertEquals(Blocks.GLASS, sc.resolve(CEILING_CELL, 5, 3).state().getBlock());
        }
    }

    @Test
    @DisplayName("withinMargin grows the box evenly on every side")
    void withinMargin() {
        assertTrue(VariantConditions.withinMargin(new BlockPos(-1, -1, -1), SIZE, 1));
        assertFalse(VariantConditions.withinMargin(new BlockPos(-1, 0, 0), SIZE, 0));
        assertFalse(VariantConditions.withinMargin(new BlockPos(0, 0, 6), SIZE, 1));
    }
}
