package games.brennan.dungeontrain.editor;

import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for the candle ({@link VariantCandles}, lit via {@link RedstoneToggle}) and flower pot
 * ({@link VariantPot}) block-variant options: tokens, spawn-time application, determinism, and the
 * sidecar round-trip that must stay diff-clean for older templates.
 */
final class CandlePotVariantsTest {

    private static final ResourceLocation POPPY = ResourceLocation.withDefaultNamespace("poppy");
    private static final ResourceLocation CACTUS = ResourceLocation.withDefaultNamespace("cactus");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // ---- candles ----

    @Test
    @DisplayName("Candles latch lit: the On / R / Off pill applies to them")
    void candlesAreLitToggles() {
        assertSame(BlockStateProperties.LIT, RedstoneToggle.propertyFor(Blocks.CANDLE.defaultBlockState()));
        assertSame(BlockStateProperties.LIT, RedstoneToggle.propertyFor(Blocks.RED_CANDLE.defaultBlockState()));
        assertSame(BlockStateProperties.LIT, RedstoneToggle.propertyFor(Blocks.CANDLE_CAKE.defaultBlockState()));
    }

    @Test
    @DisplayName("A waterlogged candle never spawns lit")
    void waterloggedCandleStaysUnlit() {
        BlockState wet = Blocks.CANDLE.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
        assertFalse(RedstoneToggle.apply(wet, VariantActive.active(), BlockPos.ZERO, 1L, 0, 0)
            .getValue(BlockStateProperties.LIT));
        assertTrue(RedstoneToggle.apply(Blocks.CANDLE.defaultBlockState(), VariantActive.active(),
            BlockPos.ZERO, 1L, 0, 0).getValue(BlockStateProperties.LIT));
    }

    @Test
    @DisplayName("A captured lit candle with no active field stays lit (legacy templates)")
    void legacyLitCandleMigratesToActive() {
        BlockState lit = Blocks.CANDLE.defaultBlockState().setValue(BlockStateProperties.LIT, true);
        VariantState v = VariantState.of(lit);
        assertEquals(VariantActive.Mode.ACTIVE, v.active().mode());
        assertTrue(v.state().getValue(BlockStateProperties.LIT));
    }

    @Test
    @DisplayName("Candle tokens round-trip; malformed ones are rejected")
    void candleTokens() {
        assertSame(VariantCandles.AS_PLACED, VariantCandles.parse(""));
        assertEquals(VariantCandles.RANDOM, VariantCandles.parse("random"));
        assertEquals(VariantCandles.fixed(3), VariantCandles.parse("3"));
        assertNull(VariantCandles.parse("5"));
        assertNull(VariantCandles.parse("0"));
        assertNull(VariantCandles.parse("lots"));
        for (VariantCandles c : List.of(VariantCandles.RANDOM, VariantCandles.fixed(1), VariantCandles.fixed(4))) {
            assertEquals(c, VariantCandles.parse(c.id()));
        }
        assertEquals("", VariantCandles.AS_PLACED.id());
    }

    @Test
    @DisplayName("A fixed count sets the candles; Random rolls 1..4, deterministically, and covers all four")
    void candleCountApplies() {
        BlockState one = Blocks.CANDLE.defaultBlockState();
        assertEquals(3, VariantCandles.fixed(3).apply(one, 7L, 1L, 0).getValue(BlockStateProperties.CANDLES));
        assertSame(one, VariantCandles.AS_PLACED.apply(one, 7L, 1L, 0));
        assertSame(Blocks.STONE.defaultBlockState(),
            VariantCandles.RANDOM.apply(Blocks.STONE.defaultBlockState(), 7L, 1L, 0));

        Set<Integer> seen = new HashSet<>();
        for (long key = 0; key < 200; key++) {
            int n = VariantCandles.RANDOM.apply(one, key, 42L, 3).getValue(BlockStateProperties.CANDLES);
            assertTrue(n >= 1 && n <= VariantCandles.MAX);
            assertEquals(n, VariantCandles.RANDOM.apply(one, key, 42L, 3).getValue(BlockStateProperties.CANDLES));
            seen.add(n);
        }
        assertEquals(VariantCandles.MAX, seen.size(), "every count should come up");
    }

    @Test
    @DisplayName("A freshly added candle row starts lit with a Random count")
    void newCandleRowDefaults() {
        VariantState added = BlockVariantMenuController.withNewRowDefaults(VariantState.of(Blocks.CANDLE.defaultBlockState()));
        assertEquals(VariantActive.Mode.ACTIVE, added.active().mode());
        assertTrue(added.candles().isRandom());
        VariantState stone = VariantState.of(Blocks.STONE.defaultBlockState());
        assertSame(stone, BlockVariantMenuController.withNewRowDefaults(stone));
    }

    // ---- flower pots ----

    @Test
    @DisplayName("The pot's own plant map names what a pot accepts")
    void flowerPotPlants() {
        List<ResourceLocation> all = FlowerPots.allPlants();
        assertTrue(all.contains(POPPY) && all.contains(CACTUS), "vanilla plants listed: " + all);
        assertFalse(all.contains(ResourceLocation.withDefaultNamespace("air")));
        assertSame(Blocks.POTTED_POPPY, FlowerPots.pottedFor(POPPY).getBlock());
        assertNull(FlowerPots.pottedFor(ResourceLocation.withDefaultNamespace("stone")));
        assertEquals(POPPY, FlowerPots.plantOf(new ItemStack(Items.POPPY)));
        assertNull(FlowerPots.plantOf(new ItemStack(Items.STONE)));
        assertTrue(FlowerPots.isPot(Blocks.FLOWER_POT.defaultBlockState()));
        assertTrue(FlowerPots.isPot(Blocks.POTTED_CACTUS.defaultBlockState()));
    }

    @Test
    @DisplayName("Pot tokens round-trip; duplicates collapse and the list is capped")
    void potTokens() {
        assertSame(VariantPot.NONE, VariantPot.parse(null));
        assertEquals(VariantPot.RANDOM, VariantPot.parse("random"));
        VariantPot two = VariantPot.parse("minecraft:poppy, minecraft:cactus,minecraft:poppy");
        assertNotNull(two);
        assertEquals(List.of(POPPY, CACTUS), two.plants());
        assertEquals(two, VariantPot.parse(two.id()));
        assertNull(VariantPot.parse("bad id!"));

        VariantPot full = VariantPot.RANDOM;
        for (ResourceLocation id : FlowerPots.allPlants()) full = full.withPlant(id);
        assertEquals(VariantPot.MAX_PLANTS, full.plants().size());
        assertTrue(two.withoutPlant(POPPY).withoutPlant(CACTUS).isRandom(), "last one out leaves Random");
    }

    @Test
    @DisplayName("A pot rolls only its listed plants, deterministically; Random reaches many plants")
    void potApplies() {
        BlockState empty = Blocks.FLOWER_POT.defaultBlockState();
        VariantPot two = VariantPot.of(List.of(POPPY, CACTUS));
        Set<Block> seen = new HashSet<>();
        for (long key = 0; key < 100; key++) {
            BlockState planted = two.apply(empty, key, 9L, 2);
            assertEquals(planted, two.apply(empty, key, 9L, 2));
            seen.add(planted.getBlock());
        }
        assertEquals(Set.of(Blocks.POTTED_POPPY, Blocks.POTTED_CACTUS), seen);

        Set<Block> random = new HashSet<>();
        for (long key = 0; key < 400; key++) random.add(VariantPot.RANDOM.apply(empty, key, 9L, 2).getBlock());
        assertTrue(random.size() > 10, "Random should spread over many plants: " + random);

        assertSame(empty, VariantPot.NONE.apply(empty, 1L, 9L, 2));
        assertSame(Blocks.STONE.defaultBlockState(), two.apply(Blocks.STONE.defaultBlockState(), 1L, 9L, 2));
    }

    // ---- sidecar ----

    @Test
    @DisplayName("Candle and pot options survive the sidecar JSON; defaults stay out of it")
    void sidecarRoundTrip() {
        List<VariantState> cell = List.of(
            VariantState.of(Blocks.CANDLE.defaultBlockState()).withCandles(VariantCandles.fixed(2)),
            VariantState.of(Blocks.FLOWER_POT.defaultBlockState()).withPot(VariantPot.of(List.of(POPPY, CACTUS))),
            VariantState.of(Blocks.FLOWER_POT.defaultBlockState()));

        StringBuilder sb = new StringBuilder();
        CarriageVariantBlocks.appendCellJson(sb, cell, 0);
        String json = sb.toString();
        assertTrue(json.contains("\"candles\": \"2\""), json);
        assertTrue(json.contains("\"pot\": \"minecraft:poppy,minecraft:cactus\""), json);
        assertEquals(1, json.split("\"pot\"", -1).length - 1, "an untouched pot writes no pot field: " + json);

        HolderLookup.RegistryLookup<Block> blocks = BuiltInRegistries.BLOCK.asLookup();
        CarriageVariantBlocks.ParsedCell parsed = CarriageVariantBlocks.parseCellValue(
            JsonParser.parseString(json), blocks, "test", BlockPos.ZERO);
        assertNotNull(parsed);
        assertEquals(VariantCandles.fixed(2), parsed.states().get(0).candles());
        assertEquals(List.of(POPPY, CACTUS), parsed.states().get(1).pot().plants());
        assertTrue(parsed.states().get(2).pot().isDefault());
        assertTrue(parsed.states().get(2).isPlainBareString());
    }

    @Test
    @DisplayName("The whole-entry apply honours the candle and pot options")
    void wholeEntryApply() {
        VariantState candle = VariantState.of(Blocks.CANDLE.defaultBlockState())
            .withActive(VariantActive.active()).withCandles(VariantCandles.fixed(4));
        BlockState placed = RotationApplier.apply(candle.state(), candle, new BlockPos(1, 2, 3), 5L, 0, 0);
        assertEquals(4, placed.getValue(BlockStateProperties.CANDLES));
        assertTrue(placed.getValue(BlockStateProperties.LIT));

        VariantState pot = VariantState.of(Blocks.FLOWER_POT.defaultBlockState()).withPot(VariantPot.of(List.of(CACTUS)));
        assertSame(Blocks.POTTED_CACTUS,
            RotationApplier.apply(pot.state(), pot, new BlockPos(1, 2, 3), 5L, 0, 0).getBlock());
    }
}
