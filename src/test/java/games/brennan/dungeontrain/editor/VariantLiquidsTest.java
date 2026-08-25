package games.brennan.dungeontrain.editor;

import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for liquids as block-variant candidates ({@link VariantLiquids}).
 *
 * <p>Two properties carry the feature. <b>Buckets resolve:</b> a filled bucket must yield a fluid
 * state, and everything else must yield {@code null} so the ADD paths fall through to their
 * existing block / spawn-egg / empty-hand branches untouched. <b>Sources only:</b> whatever route a
 * liquid takes into a cell, it must land at {@code level=0} — a flowing state has nothing feeding
 * it once stamped into a carriage and drains to air within a few ticks, which reads to the author
 * as "my water variant vanished".</p>
 *
 * <p>Needs a headless Minecraft bootstrap so block/item registries and {@code BlockState} resolve.</p>
 */
final class VariantLiquidsTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static int level(BlockState state) {
        return state.getValue(LiquidBlock.LEVEL);
    }

    @Test
    @DisplayName("A filled bucket resolves to its fluid's source state")
    void filledBucketsResolveToSources() {
        BlockState water = VariantLiquids.sourceStateFrom(new ItemStack(Items.WATER_BUCKET));
        assertNotNull(water, "water bucket should resolve");
        assertSame(Blocks.WATER, water.getBlock());
        assertEquals(0, level(water), "must be a source, not a flow");

        BlockState lava = VariantLiquids.sourceStateFrom(new ItemStack(Items.LAVA_BUCKET));
        assertNotNull(lava, "lava bucket should resolve");
        assertSame(Blocks.LAVA, lava.getBlock());
        assertEquals(0, level(lava));
    }

    @Test
    @DisplayName("Non-fluid stacks resolve to null so callers fall through unchanged")
    void nonFluidStacksResolveToNull() {
        // An empty bucket has no fluid; milk is not a BucketItem at all. Both must fall through
        // rather than adding an air-ish variant row.
        assertNull(VariantLiquids.sourceStateFrom(new ItemStack(Items.BUCKET)));
        assertNull(VariantLiquids.sourceStateFrom(new ItemStack(Items.MILK_BUCKET)));
        assertNull(VariantLiquids.sourceStateFrom(new ItemStack(Items.STONE)));
        assertNull(VariantLiquids.sourceStateFrom(new ItemStack(Items.ZOMBIE_SPAWN_EGG)));
        assertNull(VariantLiquids.sourceStateFrom(ItemStack.EMPTY));
        // Powder snow needs no bucket case — SolidBucketItem is a BlockItem, so it travels the
        // ordinary block path. Guard the assumption rather than the branch.
        assertNull(VariantLiquids.sourceStateFrom(new ItemStack(Items.POWDER_SNOW_BUCKET)));
        assertTrue(new ItemStack(Items.POWDER_SNOW_BUCKET).getItem()
            instanceof net.minecraft.world.item.BlockItem);
    }

    @Test
    @DisplayName("toSource normalises a flowing liquid and leaves everything else alone")
    void toSourceNormalisesFlows() {
        BlockState flowing = Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 3);
        BlockState normalised = VariantLiquids.toSource(flowing);
        assertSame(Blocks.WATER, normalised.getBlock());
        assertEquals(0, level(normalised));

        BlockState lavaFlow = Blocks.LAVA.defaultBlockState().setValue(LiquidBlock.LEVEL, 5);
        assertEquals(0, level(VariantLiquids.toSource(lavaFlow)));

        // Already a source — returned as-is.
        BlockState source = Blocks.WATER.defaultBlockState();
        assertSame(source, VariantLiquids.toSource(source));

        // Non-liquids untouched, including a waterlogged block: turning that into bare water
        // would silently discard the fence.
        BlockState stone = Blocks.STONE.defaultBlockState();
        assertSame(stone, VariantLiquids.toSource(stone));
        BlockState waterloggedFence = Blocks.OAK_FENCE.defaultBlockState()
            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, true);
        assertSame(waterloggedFence, VariantLiquids.toSource(waterloggedFence));
    }

    @Test
    @DisplayName("isLiquid and bucketIcon classify fluid blocks")
    void classification() {
        assertTrue(VariantLiquids.isLiquid(Blocks.WATER.defaultBlockState()));
        assertTrue(VariantLiquids.isLiquid(Blocks.LAVA.defaultBlockState()));
        assertTrue(!VariantLiquids.isLiquid(Blocks.STONE.defaultBlockState()));
        assertTrue(!VariantLiquids.isLiquid(null));

        // Icon fallback: water/lava have no item form, which is exactly why the menu needs this.
        assertSame(Items.AIR, Blocks.WATER.asItem());
        assertSame(Items.WATER_BUCKET, VariantLiquids.bucketIcon(Blocks.WATER.defaultBlockState()));
        assertSame(Items.LAVA_BUCKET, VariantLiquids.bucketIcon(Blocks.LAVA.defaultBlockState()));
        assertNull(VariantLiquids.bucketIcon(Blocks.STONE.defaultBlockState()));
    }

    @Test
    @DisplayName("A liquid candidate round-trips through the sidecar JSON as a source")
    void liquidRoundTripsThroughSidecarJson() {
        BlockState water = VariantLiquids.sourceStateFrom(new ItemStack(Items.WATER_BUCKET));
        assertNotNull(water);
        List<VariantState> cell = List.of(
            VariantState.of(Blocks.STONE.defaultBlockState()),
            VariantState.of(water));

        StringBuilder sb = new StringBuilder();
        CarriageVariantBlocks.appendCellJson(sb, cell, 0);
        String json = sb.toString();
        assertTrue(json.contains("minecraft:water"), "serialised cell should name the fluid: " + json);

        HolderLookup.RegistryLookup<Block> blocks = BuiltInRegistries.BLOCK.asLookup();
        CarriageVariantBlocks.ParsedCell parsed = CarriageVariantBlocks.parseCellValue(
            JsonParser.parseString(json), blocks, "test", BlockPos.ZERO);
        assertNotNull(parsed);
        assertEquals(2, parsed.states().size());
        BlockState reparsed = parsed.states().get(1).state();
        assertSame(Blocks.WATER, reparsed.getBlock());
        assertEquals(0, level(reparsed), "must survive the round-trip as a source");
    }
}
