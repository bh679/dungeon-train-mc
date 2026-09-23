package games.brennan.dungeontrain.editor;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Loot row's ranking and its reading of where a block's loot comes from: the containers store
 * first, then what the template saved in the block, and a variant's chance of being there at all.
 */
final class TemplateLootTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static StructureTemplate.StructureBlockInfo at(int x, BlockState state, CompoundTag nbt) {
        return new StructureTemplate.StructureBlockInfo(new BlockPos(x, 0, 0), state, nbt);
    }

    private static ContainerContentsPool pool(ContainerContentsEntry... entries) {
        return new ContainerContentsPool(List.of(entries), 1, 3);
    }

    @Test
    @DisplayName("a diamond sword is worth more than bread, and air is worth nothing")
    void itemScores() {
        assertTrue(LootValue.stackScore(new ItemStack(Items.DIAMOND_SWORD))
            > LootValue.stackScore(new ItemStack(Items.BREAD)));
        assertEquals(0, LootValue.entryScore(ContainerContentsEntry.air(5)));
        assertTrue(LootValue.stackScore(new ItemStack(Items.BREAD, 16))
            > LootValue.stackScore(new ItemStack(Items.BREAD)), "a bigger stack is worth more");
    }

    @Test
    @DisplayName("air weight dilutes a pool; more slots filled is worth more")
    void poolValues() {
        ContainerContentsPool swords = pool(ContainerContentsEntry.of(Items.DIAMOND_SWORD, 1, 1));
        ContainerContentsPool diluted = pool(ContainerContentsEntry.of(Items.DIAMOND_SWORD, 1, 1),
            ContainerContentsEntry.air(3));
        assertTrue(LootValue.poolValue(swords, 27) > LootValue.poolValue(diluted, 27));
        ContainerContentsPool fuller = new ContainerContentsPool(swords.entries(), 5, 9);
        assertTrue(LootValue.poolValue(fuller, 27) > LootValue.poolValue(swords, 27));
        assertEquals(0, LootValue.poolValue(ContainerContentsPool.empty(), 27));
    }

    @Test
    @DisplayName("blocks rank by value; same block and source group with a count")
    void scanRanksAndGroups() {
        ContainerContentsStore store = ContainerContentsStore.detached("carriage:test");
        store.putPool(new BlockPos(0, 0, 0), pool(ContainerContentsEntry.of(Items.BREAD, 1, 1)));
        store.putPool(new BlockPos(1, 0, 0), pool(ContainerContentsEntry.of(Items.BREAD, 1, 1)));
        store.putPool(new BlockPos(2, 0, 0), pool(ContainerContentsEntry.of(Items.NETHERITE_SWORD, 1, 1)));

        List<TemplateLoot.LootBlock> loot = TemplateLoot.scan(List.of(
            at(0, Blocks.CHEST.defaultBlockState(), null),
            at(1, Blocks.CHEST.defaultBlockState(), null),
            at(2, Blocks.BARREL.defaultBlockState(), null),
            at(3, Blocks.STONE.defaultBlockState(), null),
            at(4, Blocks.CHEST.defaultBlockState(), null)), store, List.of());

        // The empty chest at x=4 has no loot and is left out.
        assertEquals(2, loot.size());
        assertEquals(Blocks.BARREL, loot.get(0).block(), "the netherite barrel is worth most");
        assertEquals(1, loot.get(0).count());
        assertEquals(Blocks.CHEST, loot.get(1).block());
        assertEquals(2, loot.get(1).count());
        assertEquals(TemplateLoot.Source.POOL, loot.get(1).source());
        assertEquals(List.of(Items.NETHERITE_SWORD), loot.get(0).topItems());
    }

    @Test
    @DisplayName("saved items, a vanilla table, and bare suspicious sand are all loot")
    void savedNbtAndTables() {
        CompoundTag items = new CompoundTag();
        ListTag list = new ListTag();
        CompoundTag diamond = new CompoundTag();
        diamond.putString("id", "minecraft:diamond");
        diamond.putInt("count", 4);
        list.add(diamond);
        items.put("Items", list);
        CompoundTag table = new CompoundTag();
        table.putString("LootTable", "minecraft:chests/simple_dungeon");

        List<TemplateLoot.LootBlock> loot = TemplateLoot.scan(List.of(
            at(0, Blocks.CHEST.defaultBlockState(), items),
            at(1, Blocks.BARREL.defaultBlockState(), table),
            at(2, Blocks.SUSPICIOUS_SAND.defaultBlockState(), null)), null, List.of());

        Map<net.minecraft.world.level.block.Block, TemplateLoot.LootBlock> byBlock =
            loot.stream().collect(java.util.stream.Collectors.toMap(TemplateLoot.LootBlock::block, l -> l));
        assertEquals(TemplateLoot.Source.INLINE, byBlock.get(Blocks.CHEST).source());
        assertEquals(List.of(Items.DIAMOND), byBlock.get(Blocks.CHEST).topItems());
        assertEquals(TemplateLoot.Source.TABLE, byBlock.get(Blocks.BARREL).source());
        assertEquals("minecraft:chests/simple_dungeon", byBlock.get(Blocks.BARREL).detail());
        assertEquals(TemplateLoot.ARCHAEOLOGY, byBlock.get(Blocks.SUSPICIOUS_SAND).detail());
        assertEquals(Blocks.CHEST, loot.get(0).block(), "known diamonds outrank unknown tables");
    }

    @Test
    @DisplayName("a variant cell is judged by its candidates, each at its chance")
    void variantCandidates() {
        ContainerContentsStore store = ContainerContentsStore.detached("carriage:test");
        BlockPos cell = new BlockPos(0, 0, 0);
        store.putPool(cell, pool(ContainerContentsEntry.of(Items.GOLDEN_APPLE, 1, 1)));
        CarriageVariantBlocks.Entry entry = new CarriageVariantBlocks.Entry(cell, List.of(
            new VariantState(Blocks.CHEST.defaultBlockState(), null, 1),
            new VariantState(Blocks.STONE.defaultBlockState(), null, 3)));

        // The template's own block at the variant cell is replaced, so it is not counted.
        List<TemplateLoot.LootBlock> loot = TemplateLoot.scan(
            List.of(at(0, Blocks.BARREL.defaultBlockState(), null)), store, List.of(entry));

        assertEquals(1, loot.size());
        TemplateLoot.LootBlock chest = loot.get(0);
        assertEquals(Blocks.CHEST, chest.block());
        assertEquals(25, chest.chance());
        assertTrue(chest.isVariant());
        double full = LootValue.poolValue(store.poolAt(cell), 27);
        assertEquals(full * 0.25, chest.value(), 1e-9);
        // Two chest candidates in one cell are one chest, at their summed chance.
        CarriageVariantBlocks.Entry twoChests = new CarriageVariantBlocks.Entry(cell, List.of(
            new VariantState(Blocks.CHEST.defaultBlockState(), null, 1),
            new VariantState(Blocks.CHEST.defaultBlockState(), null, 1),
            new VariantState(Blocks.STONE.defaultBlockState(), null, 2)));
        List<TemplateLoot.LootBlock> merged = TemplateLoot.scan(List.of(), store, List.of(twoChests));
        assertEquals(1, merged.size());
        assertEquals(1, merged.get(0).count(), "one cell, one chest");
        assertEquals(50, merged.get(0).chance());
        CarriageVariantBlocks.Entry allChests = new CarriageVariantBlocks.Entry(cell, List.of(
            new VariantState(Blocks.CHEST.defaultBlockState(), null, 1),
            new VariantState(Blocks.CHEST.defaultBlockState(), null, 3)));
        assertFalse(TemplateLoot.scan(List.of(), store, List.of(allChests)).get(0).isVariant(),
            "a cell whose every candidate is a chest always has a chest");

        assertFalse(TemplateLoot.scan(List.of(at(0, Blocks.CHEST.defaultBlockState(), null)), store,
            List.of()).get(0).isVariant());
    }

    @Test
    @DisplayName("an empty chiseled bookshelf shows its block default prefab at one in five")
    void blockDefault() {
        List<TemplateLoot.LootBlock> loot = TemplateLoot.scan(List.of(
            at(0, Blocks.CHISELED_BOOKSHELF.defaultBlockState(), null),
            at(1, Blocks.CHISELED_BOOKSHELF.defaultBlockState(), null)), null, List.of());
        assertEquals(1, loot.size());
        TemplateLoot.LootBlock shelf = loot.get(0);
        assertEquals(TemplateLoot.Source.DEFAULT, shelf.source());
        assertEquals("bookshelf", shelf.detail());
        assertEquals(BlockLootDefaults.chancePct(), shelf.chance());
        assertEquals(2, shelf.count());
        assertFalse(shelf.isVariant());
    }

    @Test
    @DisplayName("lights counts every block that gives off light")
    void lights() {
        Map<BlockPos, BlockState> cells = Map.of(
            new BlockPos(0, 0, 0), Blocks.TORCH.defaultBlockState(),
            new BlockPos(1, 0, 0), Blocks.LANTERN.defaultBlockState(),
            new BlockPos(2, 0, 0), Blocks.STONE.defaultBlockState(),
            new BlockPos(3, 0, 0), Blocks.GLOWSTONE.defaultBlockState());
        assertEquals(3, TemplateCells.lightCount(cells));
    }
}
