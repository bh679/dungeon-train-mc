package games.brennan.dungeontrain.portal;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which shell blocks may skip the light engine, and which cells beside a corridor may not. */
class PortalQuietFillTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("opaque, dark, entity-free blocks are quiet-safe")
    void opaqueBlocks() {
        assertTrue(PortalQuietFill.quietSafe(Blocks.BEDROCK.defaultBlockState()));
        assertTrue(PortalQuietFill.quietSafe(Blocks.STONE.defaultBlockState()));
        assertTrue(PortalQuietFill.quietSafe(Blocks.DEEPSLATE.defaultBlockState()));
    }

    @Test
    @DisplayName("air, see-through, glowing and block-entity blocks keep the ordinary path")
    void loudBlocks() {
        assertFalse(PortalQuietFill.quietSafe(null));
        assertFalse(PortalQuietFill.quietSafe(Blocks.AIR.defaultBlockState()));
        assertFalse(PortalQuietFill.quietSafe(Blocks.GLASS.defaultBlockState()));
        assertFalse(PortalQuietFill.quietSafe(Blocks.GLOWSTONE.defaultBlockState()));
        assertFalse(PortalQuietFill.quietSafe(Blocks.CHEST.defaultBlockState()));
    }

    @Test
    @DisplayName("a corridor's boxes grow by one on every side, and only cells in them are loud")
    void grownMask() {
        PortalCorridorMask mask = new PortalCorridorMask(List.of(new BoundingBox(10, 20, 30, 12, 22, 32)));
        List<BoundingBox> loud = PortalQuietFill.grown(mask);
        assertEquals(new BoundingBox(9, 19, 29, 13, 23, 33), loud.get(0));
        assertTrue(PortalQuietFill.inAny(loud, 9, 19, 29));
        assertTrue(PortalQuietFill.inAny(loud, 13, 23, 33));
        assertFalse(PortalQuietFill.inAny(loud, 8, 21, 31));
        assertFalse(PortalQuietFill.inAny(loud, 11, 24, 31));
        assertFalse(PortalQuietFill.inAny(List.of(), 11, 21, 31));
    }
}
