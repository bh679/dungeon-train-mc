package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RedstoneToggle}: which property counts as "the redstone toggle" for
 * a block, and the deterministic RANDOM roll. Needs the moddev unit-test
 * bootstrap for {@code Blocks.*}, like {@link RotationApplierFlipTest}.
 */
final class RedstoneToggleTest {

    private static final long WORLD_SEED = 0xBEEF_5EEDL;
    private static final int CARRIAGE_INDEX = 3;

    @Test
    @DisplayName("propertyFor: OPEN wins on doors/trapdoors/gates, then EXTENDED, TRIGGERED, LIT, ENABLED, POWERED")
    void propertyFor_precedence() {
        assertSame(BlockStateProperties.OPEN, RedstoneToggle.propertyFor(Blocks.OAK_TRAPDOOR.defaultBlockState()));
        // Doors carry both OPEN and POWERED — the visible one wins.
        assertSame(BlockStateProperties.OPEN, RedstoneToggle.propertyFor(Blocks.IRON_DOOR.defaultBlockState()));
        assertSame(BlockStateProperties.OPEN, RedstoneToggle.propertyFor(Blocks.OAK_FENCE_GATE.defaultBlockState()));
        assertSame(BlockStateProperties.EXTENDED, RedstoneToggle.propertyFor(Blocks.PISTON.defaultBlockState()));
        assertSame(BlockStateProperties.TRIGGERED, RedstoneToggle.propertyFor(Blocks.DISPENSER.defaultBlockState()));
        assertSame(BlockStateProperties.LIT, RedstoneToggle.propertyFor(Blocks.REDSTONE_LAMP.defaultBlockState()));
        assertSame(BlockStateProperties.LIT, RedstoneToggle.propertyFor(Blocks.REDSTONE_TORCH.defaultBlockState()));
        assertSame(BlockStateProperties.LIT, RedstoneToggle.propertyFor(Blocks.REDSTONE_WALL_TORCH.defaultBlockState()));
        assertSame(BlockStateProperties.ENABLED, RedstoneToggle.propertyFor(Blocks.HOPPER.defaultBlockState()));
        assertSame(BlockStateProperties.POWERED, RedstoneToggle.propertyFor(Blocks.LEVER.defaultBlockState()));
        assertSame(BlockStateProperties.POWERED, RedstoneToggle.propertyFor(Blocks.STONE_BUTTON.defaultBlockState()));
        assertSame(BlockStateProperties.POWERED, RedstoneToggle.propertyFor(Blocks.NOTE_BLOCK.defaultBlockState()));
    }

    @Test
    @DisplayName("propertyFor: fire-lit blocks (candle, furnace, campfire) and plain blocks have no toggle")
    void propertyFor_nonRedstoneLit_isNull() {
        assertNull(RedstoneToggle.propertyFor(Blocks.CANDLE.defaultBlockState()));
        assertNull(RedstoneToggle.propertyFor(Blocks.FURNACE.defaultBlockState()));
        assertNull(RedstoneToggle.propertyFor(Blocks.CAMPFIRE.defaultBlockState()));
        assertNull(RedstoneToggle.propertyFor(Blocks.STONE.defaultBlockState()));
        assertNull(RedstoneToggle.propertyFor(null));
        assertFalse(RedstoneToggle.canToggle(Blocks.STONE.defaultBlockState()));
        assertTrue(RedstoneToggle.canToggle(Blocks.OAK_TRAPDOOR.defaultBlockState()));
    }

    @Test
    @DisplayName("apply: ACTIVE / INACTIVE force the property; non-toggle blocks pass through")
    void apply_forcedModes() {
        BlockState trapdoor = Blocks.OAK_TRAPDOOR.defaultBlockState();
        BlockPos pos = new BlockPos(1, 2, 3);
        assertTrue(RedstoneToggle.apply(trapdoor, VariantActive.active(), pos, WORLD_SEED, CARRIAGE_INDEX, 0)
            .getValue(BlockStateProperties.OPEN));
        assertFalse(RedstoneToggle.apply(trapdoor.setValue(BlockStateProperties.OPEN, true), VariantActive.NONE,
            pos, WORLD_SEED, CARRIAGE_INDEX, 0).getValue(BlockStateProperties.OPEN));
        BlockState stone = Blocks.STONE.defaultBlockState();
        assertSame(stone, RedstoneToggle.apply(stone, VariantActive.active(), pos, WORLD_SEED, CARRIAGE_INDEX, 0));
        // Hopper: active = enabled.
        assertTrue(RedstoneToggle.apply(Blocks.HOPPER.defaultBlockState(), VariantActive.active(),
            pos, WORLD_SEED, CARRIAGE_INDEX, 0).getValue(BlockStateProperties.ENABLED));
    }

    @Test
    @DisplayName("apply RANDOM: deterministic per (seed, carriage, pos|lockId) and lands on both sides")
    void apply_random_deterministicAndMixed() {
        BlockState lamp = Blocks.REDSTONE_LAMP.defaultBlockState();
        int lit = 0;
        int total = 500;
        for (int i = 0; i < total; i++) {
            BlockPos pos = new BlockPos(i, 0, i * 7);
            BlockState a = RedstoneToggle.apply(lamp, VariantActive.random(), pos, WORLD_SEED, CARRIAGE_INDEX, 0);
            BlockState b = RedstoneToggle.apply(lamp, VariantActive.random(), pos, WORLD_SEED, CARRIAGE_INDEX, 0);
            assertEquals(a, b, "same inputs must roll the same result at " + pos);
            if (a.getValue(BlockStateProperties.LIT)) lit++;
        }
        assertTrue(lit > total * 0.3 && lit < total * 0.7,
            "RANDOM should land on both sides across positions, got lit=" + lit + "/" + total);

        // A lock id replaces the position in the seed so a locked cell rolls the same everywhere.
        BlockState l1 = RedstoneToggle.apply(lamp, VariantActive.random(), new BlockPos(0, 0, 0), WORLD_SEED, CARRIAGE_INDEX, 9);
        BlockState l2 = RedstoneToggle.apply(lamp, VariantActive.random(), new BlockPos(5, 5, 5), WORLD_SEED, CARRIAGE_INDEX, 9);
        assertEquals(l1, l2, "lockId-seeded roll must not depend on position");
    }

    @Test
    @DisplayName("RotationApplier 8-arg apply layers the toggle on top of facing + half")
    void rotationApplier_threadsActive() {
        BlockState trapdoor = Blocks.OAK_TRAPDOOR.defaultBlockState();
        BlockState out = RotationApplier.apply(trapdoor, VariantRotation.NONE, VariantHalf.top(),
            VariantActive.active(), new BlockPos(0, 0, 0), WORLD_SEED, CARRIAGE_INDEX, 0);
        assertTrue(out.getValue(BlockStateProperties.OPEN), "ACTIVE must survive the applier chain");
        assertEquals(net.minecraft.world.level.block.state.properties.Half.TOP,
            out.getValue(BlockStateProperties.HALF), "half override must still apply");
        // The 7-arg overload keeps today's behaviour: toggle left as captured.
        BlockState legacy = RotationApplier.apply(trapdoor.setValue(BlockStateProperties.OPEN, true),
            VariantRotation.NONE, VariantHalf.top(), new BlockPos(0, 0, 0), WORLD_SEED, CARRIAGE_INDEX, 0);
        assertFalse(legacy.getValue(BlockStateProperties.OPEN),
            "7-arg overload defaults to INACTIVE (the pre-flag placement state)");
    }
}
