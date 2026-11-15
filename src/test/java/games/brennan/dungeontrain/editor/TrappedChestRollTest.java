package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.item.VariantClipboardItem;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A plain chest's trapped-chest chance: 0 never traps, 100 always does, the roll is seeded, both
 * halves of a double chest agree, and only a plain chest ever swaps.
 */
final class TrappedChestRollTest {

    private static final ResourceLocation DIAMOND = ResourceLocation.withDefaultNamespace("diamond");
    private static final long SEED = 12345L;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static ContainerContentsPool pool(int chance) {
        return new ContainerContentsPool(List.of(new ContainerContentsEntry(DIAMOND, 2, 1)), 0,
            ContainerContentsPool.FILL_ALL).withTrappedChance(chance);
    }

    private static int trappedCount(int chance, BlockState state) {
        int hits = 0;
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 20; z++) {
                if (TrappedChestRoll.rolls(pool(chance), state, new BlockPos(x, 1, z), SEED, 7)) hits++;
            }
        }
        return hits;
    }

    @Test
    @DisplayName("0% never traps, 100% always does, 50% traps about half")
    void chanceBounds() {
        BlockState chest = Blocks.CHEST.defaultBlockState();
        assertEquals(0, trappedCount(0, chest));
        assertEquals(400, trappedCount(100, chest));
        int half = trappedCount(50, chest);
        assertTrue(half > 140 && half < 260, "50% rolled " + half + " of 400");
    }

    @Test
    @DisplayName("the roll is the same every time for the same seed, carriage and cell")
    void deterministic() {
        BlockState chest = Blocks.CHEST.defaultBlockState();
        BlockPos cell = new BlockPos(4, 1, 2);
        boolean first = TrappedChestRoll.rolls(pool(40), chest, cell, SEED, 3);
        for (int i = 0; i < 10; i++) {
            assertEquals(first, TrappedChestRoll.rolls(pool(40), chest, cell, SEED, 3));
        }
    }

    @Test
    @DisplayName("both halves of a double chest land on the same answer")
    void doubleChestHalvesAgree() {
        BlockState left = Blocks.CHEST.defaultBlockState()
            .setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.LEFT);
        BlockState right = left.setValue(ChestBlock.TYPE, ChestType.RIGHT);
        for (int x = 0; x < 30; x++) {
            BlockPos leftPos = new BlockPos(x, 1, 5);
            BlockPos rightPos = leftPos.relative(ChestBlock.getConnectedDirection(left));
            assertEquals(leftPos, rightPos.relative(ChestBlock.getConnectedDirection(right)));
            assertEquals(TrappedChestRoll.rolls(pool(50), left, leftPos, SEED, 2),
                TrappedChestRoll.rolls(pool(50), right, rightPos, SEED, 2), "halves at x=" + x);
        }
    }

    @Test
    @DisplayName("only a plain chest swaps; the trapped chest keeps facing and chest type")
    void onlyPlainChest() {
        assertEquals(0, trappedCount(100, Blocks.BARREL.defaultBlockState()));
        assertEquals(0, trappedCount(100, Blocks.TRAPPED_CHEST.defaultBlockState()));
        BlockState half = Blocks.CHEST.defaultBlockState()
            .setValue(ChestBlock.FACING, Direction.EAST).setValue(ChestBlock.TYPE, ChestType.RIGHT);
        BlockState trapped = TrappedChestRoll.trapped(half);
        assertTrue(trapped.is(Blocks.TRAPPED_CHEST));
        assertEquals(Direction.EAST, trapped.getValue(ChestBlock.FACING));
        assertEquals(ChestType.RIGHT, trapped.getValue(ChestBlock.TYPE));
    }

    @Test
    @DisplayName("the chance is clamped to 0-100 and survives the store, prefab and clipboard formats")
    void chanceRoundTrips() {
        assertEquals(100, pool(250).trappedChance());
        assertEquals(0, pool(-5).trappedChance());

        BlockPos cell = new BlockPos(3, 0, 2);
        ContainerContentsStore store = ContainerContentsStore.detached("test");
        store.putPool(cell, pool(30));
        store.putPool(cell.east(), pool(0));
        String text = store.asJsonText();
        assertEquals(1, text.split("trappedChance", -1).length - 1, "only a non-zero chance is written");
        ContainerContentsStore back = ContainerContentsStore.fromJsonText("test", text);
        assertEquals(30, back.poolAt(cell).trappedChance());
        assertEquals(0, back.poolAt(cell.east()).trappedChance());

        assertEquals(30, LootPrefabStore.poolFromText(
            LootPrefabStore.poolText(pool(30), ResourceLocation.withDefaultNamespace("chest")))
            .orElseThrow().trappedChance());

        CompoundTag clip = new CompoundTag();
        clip.put(VariantClipboardItem.NBT_POOL, VariantClipboardItem.encodePool(pool(30)));
        assertEquals(30, VariantClipboardItem.decodePool(clip).trappedChance());
        assertFalse(VariantClipboardItem.encodePool(pool(0)).contains("trap"));

        assertEquals(30, pool(30).withFillMin(2).withVaultKey("minecraft:trial_key").trappedChance());
    }

    @Test
    @DisplayName("a trap-only pool with no items is kept, not dropped as empty")
    void trapOnlyPoolKept() {
        ContainerContentsStore store = ContainerContentsStore.detached("test");
        BlockPos cell = new BlockPos(1, 0, 1);
        store.putPool(cell, ContainerContentsPool.empty().withTrappedChance(100));
        assertEquals(100, ContainerContentsStore.fromJsonText("test", store.asJsonText()).poolAt(cell).trappedChance());
    }
}
