package games.brennan.dungeontrain.portal;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The underwater room's source naming and seabed rule. */
class PortalChunkUnderwaterTest {

    private static final int NO_GROUND = Integer.MIN_VALUE;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("_underwater names the underwater source; the others are unchanged")
    void sourceOf() {
        assertSame(PortalChunkTerrain.Source.UNDERWATER,
            PortalChunkTerrain.Source.of("chunk_dimension_underwater"));
        assertTrue(PortalChunkTerrain.Source.UNDERWATER.underwater());
        assertFalse(PortalChunkTerrain.Source.UNDERWATER.vanillaOnly());
        assertSame(PortalChunkTerrain.Source.OVERWORLD, PortalChunkTerrain.Source.of("chunk_dimension"));
        assertSame(PortalChunkTerrain.Source.NETHER, PortalChunkTerrain.Source.of("chunk_dimension_nether"));
        assertSame(PortalChunkTerrain.Source.OVERWORLD_WWOO, PortalChunkTerrain.Source.of("chunk_dimension_wwoo"));
        assertFalse(PortalChunkTerrain.Source.OVERWORLD.underwater());
    }

    @Test
    @DisplayName("the 3x3 underwater room samples the underwater source over a 3x3 grid")
    void threeByThreeUnderwater() {
        assertSame(PortalChunkTerrain.Source.UNDERWATER, PortalChunkTerrain.Source.of("chunk_3x3_underwater"));
        assertEquals(3, PortalChunkArea.span(new net.minecraft.core.Vec3i(48, 32, 48)));
    }

    /** Stone to {@code bed}, water to {@code surface}, air above. */
    private static IntFunction<BlockState> sea(int bed, int surface) {
        return y -> y <= bed ? Blocks.STONE.defaultBlockState()
            : y <= surface ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
    }

    @Test
    @DisplayName("seabed is the first water row over the ocean floor")
    void seabed() {
        assertEquals(41, PortalChunkTerrain.seabedRow(sea(40, 62), -64, 200));
    }

    @Test
    @DisplayName("a shallow sea (under 8 deep) and dry land are not open sea")
    void notSea() {
        assertEquals(NO_GROUND, PortalChunkTerrain.seabedRow(sea(58, 62), -64, 200));
        assertEquals(NO_GROUND, PortalChunkTerrain.seabedRow(sea(70, 62), -64, 200));
    }

    @Test
    @DisplayName("a shallow sea over a flooded cave is not open sea; the cave is never picked")
    void shallowOverCave() {
        // 4 water over rock at 58..54, a 10-deep flooded cave 53..44, rock below.
        IntFunction<BlockState> column = y -> y > 62 ? Blocks.AIR.defaultBlockState()
            : y >= 59 ? Blocks.WATER.defaultBlockState()
            : y >= 54 ? Blocks.STONE.defaultBlockState()
            : y >= 44 ? Blocks.WATER.defaultBlockState()
            : Blocks.STONE.defaultBlockState();
        assertEquals(NO_GROUND, PortalChunkTerrain.seabedRow(column, -64, 200));
    }

    @Test
    @DisplayName("ice on the surface does not hide the sea under it; kelp counts as water")
    void iceAndKelp() {
        IntFunction<BlockState> frozen = y -> y == 63 ? Blocks.ICE.defaultBlockState()
            : y == 45 ? Blocks.KELP.defaultBlockState() : sea(40, 62).apply(y);
        assertEquals(41, PortalChunkTerrain.seabedRow(frozen, -64, 200));
    }
}
