package games.brennan.dungeontrain.train;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which block changes dirty a shared carriage. The churn cases are taken verbatim from production
 * carriage 32, whose 127 recorded changes were 126 flips of one pressure plate and one door plus a
 * single real edit. Needs a headless Minecraft bootstrap so {@link BlockState} resolves.
 */
class SharedCarriageChangeFilterTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private static BlockState plate(boolean powered) {
        return Blocks.STONE_PRESSURE_PLATE.defaultBlockState()
                .setValue(BlockStateProperties.POWERED, powered);
    }

    private static BlockState door(BlockState base, DoubleBlockHalf half, boolean open, boolean powered) {
        return base.setValue(DoorBlock.HALF, half)
                .setValue(BlockStateProperties.OPEN, open)
                .setValue(BlockStateProperties.POWERED, powered);
    }

    private static BlockState oakDoor(DoubleBlockHalf half, boolean open, boolean powered) {
        return door(Blocks.OAK_DOOR.defaultBlockState(), half, open, powered);
    }

    // ---- transient flips: NOT build changes (the carriage-32 churn) ----

    @Test
    void pressurePlatePoweringIsNotABuildChange() {
        assertFalse(SharedCarriageChangeFilter.isBuildChange(plate(false), plate(true)));
        assertFalse(SharedCarriageChangeFilter.isBuildChange(plate(true), plate(false)));
    }

    @Test
    void doorSwingingIsNotABuildChange() {
        for (DoubleBlockHalf half : DoubleBlockHalf.values()) {
            assertFalse(SharedCarriageChangeFilter.isBuildChange(
                    oakDoor(half, false, false), oakDoor(half, true, true)),
                    "closed → open+powered on the " + half + " half");
            assertFalse(SharedCarriageChangeFilter.isBuildChange(
                    oakDoor(half, true, true), oakDoor(half, false, false)),
                    "open+powered → closed on the " + half + " half");
            assertFalse(SharedCarriageChangeFilter.isBuildChange(
                    oakDoor(half, true, false), oakDoor(half, true, true)),
                    "powered alone on the " + half + " half");
        }
    }

    @Test
    void aRedundantSetToTheSameStateIsNotABuildChange() {
        assertFalse(SharedCarriageChangeFilter.isBuildChange(plate(false), plate(false)));
    }

    // ---- real edits: ARE build changes ----

    @Test
    void breakingADoorIsABuildChange() {
        assertTrue(SharedCarriageChangeFilter.isBuildChange(
                oakDoor(DoubleBlockHalf.LOWER, false, false), AIR));
    }

    @Test
    void breakingAStoneBrickSlabIsABuildChange() {
        // Carriage 32's single genuine edit: a top slab removed from the ceiling.
        BlockState slab = Blocks.STONE_BRICK_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, net.minecraft.world.level.block.state.properties.SlabType.TOP);
        assertTrue(SharedCarriageChangeFilter.isBuildChange(slab, AIR));
    }

    @Test
    void swappingOneDoorForAnotherIsABuildChange() {
        // Same properties, different block — a re-skin is a build change however similar it looks.
        assertTrue(SharedCarriageChangeFilter.isBuildChange(
                oakDoor(DoubleBlockHalf.LOWER, false, false),
                door(Blocks.SPRUCE_DOOR.defaultBlockState(), DoubleBlockHalf.LOWER, false, false)));
    }

    @Test
    void waterloggingIsABuildChange() {
        // `waterlogged` is deliberately NOT transient — putting water in a fence is building.
        BlockState dry = Blocks.OAK_FENCE.defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED, false);
        assertTrue(SharedCarriageChangeFilter.isBuildChange(
                dry, dry.setValue(BlockStateProperties.WATERLOGGED, true)));
    }

    @Test
    void placingAndBreakingPlainBlocksAreBuildChanges() {
        assertTrue(SharedCarriageChangeFilter.isBuildChange(Blocks.STONE.defaultBlockState(), AIR));
        assertTrue(SharedCarriageChangeFilter.isBuildChange(AIR, Blocks.STONE.defaultBlockState()));
    }

    // ---- the pre-existing loot rule still holds ----

    @Test
    void breakingALootContainerIsNotABuildChange() {
        assertFalse(SharedCarriageChangeFilter.isBuildChange(Blocks.CHEST.defaultBlockState(), AIR));
        assertFalse(SharedCarriageChangeFilter.isBuildChange(Blocks.BARREL.defaultBlockState(), AIR));
        assertFalse(SharedCarriageChangeFilter.isBuildChange(Blocks.DECORATED_POT.defaultBlockState(), AIR));
        assertFalse(SharedCarriageChangeFilter.isBuildChange(Blocks.SUSPICIOUS_SAND.defaultBlockState(), AIR));
    }

    @Test
    void placingALootContainerIsStillABuildChange() {
        // The exclusion is one-directional — only breaking one is exempt.
        assertTrue(SharedCarriageChangeFilter.isBuildChange(AIR, Blocks.CHEST.defaultBlockState()));
    }
}
