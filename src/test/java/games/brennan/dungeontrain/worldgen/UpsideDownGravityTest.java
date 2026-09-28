package games.brennan.dungeontrain.worldgen;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure tests for {@link UpsideDownGravity#supportPos} — the one direction flip every upside-down gravity
 * mixin routes through. The frozen/reversed gates depend on a live level and are verified in-game.
 */
final class UpsideDownGravityTest {

    @Test
    @DisplayName("normal gravity rests on the block below")
    void normalSupportIsBelow() {
        BlockPos pos = new BlockPos(10, 64, -3);
        assertEquals(new BlockPos(10, 63, -3), UpsideDownGravity.supportPos(pos, false));
    }

    @Test
    @DisplayName("reversed gravity rests on the block above")
    void reversedSupportIsAbove() {
        BlockPos pos = new BlockPos(10, 64, -3);
        assertEquals(new BlockPos(10, 65, -3), UpsideDownGravity.supportPos(pos, true));
    }
}
