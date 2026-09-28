package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.editor.VariantLiquids;
import games.brennan.dungeontrain.editor.VariantState;
import games.brennan.dungeontrain.portal.PortalRoomCopiesVariant.Plane;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A filled bucket on the Floor / Roof row.
 *
 * <p>{@code /dt editor portals copies <plane> held} turns a bucket into the plane's palette through
 * {@link VariantLiquids#sourceStateFrom} — the same seam the Block Variant menu's Add gesture uses.
 * Two things must hold for the row to work: the bucket must yield the fluid's <b>source</b> state
 * (a flowing state drains to air once stamped), and that state must survive the palette's file
 * round-trip so the plane stamps water and not the BARRIER fallback.</p>
 */
class PortalRoomCopiesLiquidTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static PortalRoomCopiesVariant reread(PortalRoomCopiesVariant palette) {
        return PortalRoomCopiesVariant.parse(
            new StringReader(palette.toJsonText()), "test", "memory");
    }

    @Test
    @DisplayName("A water bucket yields the water source state")
    void waterBucketIsSource() {
        BlockState water = VariantLiquids.sourceStateFrom(new ItemStack(Items.WATER_BUCKET));
        assertNotNull(water);
        assertEquals(Blocks.WATER, water.getBlock());
        assertEquals(0, water.getValue(LiquidBlock.LEVEL));
    }

    @Test
    @DisplayName("A lava bucket yields the lava source state")
    void lavaBucketIsSource() {
        BlockState lava = VariantLiquids.sourceStateFrom(new ItemStack(Items.LAVA_BUCKET));
        assertNotNull(lava);
        assertEquals(Blocks.LAVA, lava.getBlock());
        assertEquals(0, lava.getValue(LiquidBlock.LEVEL));
    }

    @Test
    @DisplayName("An empty or milk bucket is not a liquid — the row falls through to its rejection")
    void nonLiquidBucketsAreNull() {
        assertNull(VariantLiquids.sourceStateFrom(new ItemStack(Items.BUCKET)));
        assertNull(VariantLiquids.sourceStateFrom(new ItemStack(Items.MILK_BUCKET)));
        assertNull(VariantLiquids.sourceStateFrom(ItemStack.EMPTY));
    }

    @Test
    @DisplayName("A water floor under a lava roof round-trips through the palette file")
    void liquidPlanesRoundTrip() {
        BlockState water = VariantLiquids.sourceStateFrom(new ItemStack(Items.WATER_BUCKET));
        BlockState lava = VariantLiquids.sourceStateFrom(new ItemStack(Items.LAVA_BUCKET));
        PortalRoomCopiesVariant palette = PortalRoomCopiesVariant
            .of(List.of(new VariantState(water, null)))
            .withStates(Plane.ROOF, List.of(new VariantState(lava, null)));

        PortalRoomCopiesVariant back = reread(palette);

        assertEquals(List.of("minecraft:water"), back.blockIds(Plane.FLOOR), palette.toJsonText());
        assertEquals(List.of("minecraft:lava"), back.blockIds(Plane.ROOF), palette.toJsonText());
        VariantState floor = back.resolve(Plane.FLOOR, new BlockPos(3, 0, 4), 42L, 0);
        assertNotNull(floor);
        assertEquals(Blocks.WATER, floor.state().getBlock());
        assertEquals(0, floor.state().getValue(LiquidBlock.LEVEL), "stamped plane must be a source");
    }
}
