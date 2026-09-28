package games.brennan.dungeontrain.worldgen.feature;

import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for {@link NetherRockCover} — which crossfade columns face the Nether core, and which blocks
 * count as overworld rock to repaint. Block-tag membership is not bound in unit tests, so only the
 * explicitly-listed blocks and the exclusions are pinned here.
 */
final class NetherRockCoverTest {

    /** Core occupies X in [1000, 2000). */
    private static final IntPredicate CORE = x -> x >= 1000 && x < 2000;

    @Test
    @DisplayName("isCoreWall: true within WALL_DEPTH of either core edge, false further out")
    void coreWall() {
        int d = NetherRockCover.WALL_DEPTH;
        assertTrue(NetherRockCover.isCoreWall(CORE, 999));
        assertTrue(NetherRockCover.isCoreWall(CORE, 1000 - d));
        assertFalse(NetherRockCover.isCoreWall(CORE, 1000 - d - 1));
        assertTrue(NetherRockCover.isCoreWall(CORE, 2000));
        assertTrue(NetherRockCover.isCoreWall(CORE, 1999 + d));
        assertFalse(NetherRockCover.isCoreWall(CORE, 2000 + d));
    }

    @Test
    @DisplayName("isOverworldRock: repaints ground blocks, never air, fluids, bedrock or Nether blocks")
    void overworldRock() {
        assertTrue(NetherRockCover.isOverworldRock(Blocks.GRAVEL.defaultBlockState()));
        assertTrue(NetherRockCover.isOverworldRock(Blocks.CALCITE.defaultBlockState()));
        assertFalse(NetherRockCover.isOverworldRock(Blocks.AIR.defaultBlockState()));
        assertFalse(NetherRockCover.isOverworldRock(Blocks.WATER.defaultBlockState()));
        assertFalse(NetherRockCover.isOverworldRock(Blocks.LAVA.defaultBlockState()));
        assertFalse(NetherRockCover.isOverworldRock(Blocks.BEDROCK.defaultBlockState()));
        assertFalse(NetherRockCover.isOverworldRock(Blocks.NETHERRACK.defaultBlockState()));
        assertFalse(NetherRockCover.isOverworldRock(Blocks.NETHER_GOLD_ORE.defaultBlockState()));
        assertFalse(NetherRockCover.isOverworldRock(Blocks.STONE_BRICKS.defaultBlockState()));
    }
}
