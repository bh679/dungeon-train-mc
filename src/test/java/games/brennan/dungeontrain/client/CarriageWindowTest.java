package games.brennan.dungeontrain.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CarriageWindowTest {

    /** A 7-wide carriage: walls at z = -3 and z = 3 (body at z = 0), y 0 floor level, y 1 eye level. */
    private static CarriageWindow.Blocks carriage(Map<BlockPos, BlockState> blocks) {
        return new CarriageWindow.Blocks() {
            @Override public boolean solid(BlockPos p) {
                BlockState s = blocks.get(p);
                return s != null && !s.isAir() && s != Blocks.GLASS.defaultBlockState();
            }
            @Override public BlockState state(BlockPos p) {
                return blocks.getOrDefault(p, Blocks.AIR.defaultBlockState());
            }
        };
    }

    @Test
    void prefersTheSideWithAWindow() {
        Map<BlockPos, BlockState> b = Map.of(
            new BlockPos(0, 0, 3), Blocks.STONE.defaultBlockState(), new BlockPos(0, 1, 3), Blocks.STONE.defaultBlockState(),
            new BlockPos(0, 0, -3), Blocks.STONE.defaultBlockState(), new BlockPos(0, 1, -3), Blocks.GLASS.defaultBlockState());
        CarriageWindow w = CarriageWindow.find(carriage(b), new Vector3d(0.5, 0.0, 0.5));
        assertEquals(-1, w.side());
        assertTrue(w.window());
        assertTrue(w.start().z < -3.0, "lens starts outside the wall: " + w.start().z);
        assertTrue(w.end().z < w.start().z && w.end().y > w.start().y, "pulls out and up on the same side");
    }

    @Test
    void withoutWindowsTakesTheNearerWall() {
        Map<BlockPos, BlockState> b = Map.of(
            new BlockPos(0, 0, 2), Blocks.STONE.defaultBlockState(), new BlockPos(0, 1, 2), Blocks.STONE.defaultBlockState(),
            new BlockPos(0, 0, -4), Blocks.STONE.defaultBlockState(), new BlockPos(0, 1, -4), Blocks.STONE.defaultBlockState());
        CarriageWindow w = CarriageWindow.find(carriage(b), new Vector3d(0.5, 0.0, 0.5));
        assertEquals(1, w.side());
        assertFalse(w.window());
    }

    @Test
    void openDeckStillGetsASpotOffTheEdge() {
        CarriageWindow w = CarriageWindow.find(carriage(Map.of()), new Vector3d(0.5, 0.0, 0.5));
        assertFalse(w.window());
        assertTrue(Math.abs(w.start().z) > 3.5);
    }
}
