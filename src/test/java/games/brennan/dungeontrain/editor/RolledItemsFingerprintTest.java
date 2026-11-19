package games.brennan.dungeontrain.editor;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A rolled container keeps a fingerprint of its roll, and it survives the trip a chest really makes:
 * rolled NBT loaded into the block entity, then read back off the live inventory. Anything taken out or
 * put in breaks the match, which is what lets a difficulty swap leave untouched train loot alone.
 */
final class RolledItemsFingerprintTest {

    private static final BlockPos CELL = new BlockPos(3, 1, 2);
    private static final long SEED = 987654321L;

    private static HolderLookup.Provider registries;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = VanillaRegistries.createLookup();
    }

    private static ContainerContentsPool pool() {
        return new ContainerContentsPool(List.of(
            new ContainerContentsEntry(ResourceLocation.withDefaultNamespace("bread"), 8, 1),
            new ContainerContentsEntry(ResourceLocation.withDefaultNamespace("iron_ingot"), 4, 1),
            new ContainerContentsEntry(ResourceLocation.withDefaultNamespace("arrow"), 16, 1)), 4, 6);
    }

    private static ChestBlockEntity rolledChest() {
        CompoundTag rolled = ContainerContentsRoller.roll(pool(), Blocks.CHEST.defaultBlockState(), CELL, SEED, 40,
            null, registries, null);
        ChestBlockEntity chest = new ChestBlockEntity(CELL, Blocks.CHEST.defaultBlockState());
        chest.loadCustomOnly(rolled, registries);
        return chest;
    }

    @Test
    @DisplayName("a rolled chest carries a fingerprint that matches it once loaded")
    void untouchedChestIsPristine() {
        ChestBlockEntity chest = rolledChest();
        assertFalse(chest.isEmpty(), "the pool always fills at least four slots");
        assertTrue(chest.getPersistentData().contains(RolledItemsFingerprint.NBT_KEY));
        assertTrue(RolledItemsFingerprint.isPristine(chest, chest, registries));
    }

    @Test
    @DisplayName("taking one item breaks the match")
    void takingAnItemBreaksIt() {
        ChestBlockEntity chest = rolledChest();
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            if (chest.getItem(slot).isEmpty()) continue;
            chest.removeItem(slot, 1);
            break;
        }
        assertFalse(RolledItemsFingerprint.isPristine(chest, chest, registries));
    }

    @Test
    @DisplayName("adding an item breaks the match")
    void addingAnItemBreaksIt() {
        ChestBlockEntity chest = rolledChest();
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            if (!chest.getItem(slot).isEmpty()) continue;
            chest.setItem(slot, new ItemStack(Items.SHULKER_BOX));
            break;
        }
        assertFalse(RolledItemsFingerprint.isPristine(chest, chest, registries));
    }

    @Test
    @DisplayName("moving a stack to another slot breaks the match")
    void movingAStackBreaksIt() {
        ChestBlockEntity chest = rolledChest();
        int from = -1;
        int to = -1;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            if (from < 0 && !chest.getItem(slot).isEmpty()) from = slot;
            if (to < 0 && chest.getItem(slot).isEmpty()) to = slot;
        }
        chest.setItem(to, chest.removeItemNoUpdate(from));
        assertFalse(RolledItemsFingerprint.isPristine(chest, chest, registries));
    }

    @Test
    @DisplayName("a chest the player filled has no fingerprint, so it is never pristine")
    void unrolledChestIsNeverPristine() {
        ChestBlockEntity chest = new ChestBlockEntity(CELL, Blocks.CHEST.defaultBlockState());
        chest.setItem(0, new ItemStack(Items.DIAMOND, 3));
        assertFalse(RolledItemsFingerprint.isPristine(chest, chest, registries));
    }

    @Test
    @DisplayName("template-baked contents are fingerprinted as placed, once, and never over a roll")
    void bakedContentsStampedAsPlaced() {
        ChestBlockEntity baked = new ChestBlockEntity(CELL, Blocks.CHEST.defaultBlockState());
        baked.setItem(4, new ItemStack(Items.HONEYCOMB, 2));
        assertTrue(RolledItemsFingerprint.stampIfAbsent(baked, baked, registries));
        assertTrue(RolledItemsFingerprint.isPristine(baked, baked, registries));
        assertFalse(RolledItemsFingerprint.stampIfAbsent(baked, baked, registries), "already stamped");
        baked.removeItem(4, 1);
        assertFalse(RolledItemsFingerprint.isPristine(baked, baked, registries));

        ChestBlockEntity rolled = rolledChest();
        rolled.removeItem(firstFilled(rolled), 1);
        assertFalse(RolledItemsFingerprint.stampIfAbsent(rolled, rolled, registries), "a roll's own fingerprint stands");
        assertFalse(RolledItemsFingerprint.isPristine(rolled, rolled, registries));

        ChestBlockEntity empty = new ChestBlockEntity(CELL, Blocks.CHEST.defaultBlockState());
        assertFalse(RolledItemsFingerprint.stampIfAbsent(empty, empty, registries), "nothing to fingerprint");
    }

    private static int firstFilled(ChestBlockEntity chest) {
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            if (!chest.getItem(slot).isEmpty()) return slot;
        }
        return -1;
    }

    @Test
    @DisplayName("saved NBT agrees with the live check, so snapshots judge the same way")
    void savedNbtMatchesLive() {
        ChestBlockEntity chest = rolledChest();
        CompoundTag saved = chest.saveWithFullMetadata(registries);
        assertTrue(RolledItemsFingerprint.isPristine(saved, registries));
        assertEquals(RolledItemsFingerprint.hashOfSaved(saved, registries).getAsInt(),
            RolledItemsFingerprint.hashOf(chest, registries));
    }

    @Test
    @DisplayName("a vase's single item is fingerprinted as slot 0")
    void vaseIsFingerprinted() {
        ContainerContentsPool always = new ContainerContentsPool(List.of(
            new ContainerContentsEntry(ResourceLocation.withDefaultNamespace("emerald"), 1, 1)), 1, 1);
        CompoundTag rolled = ContainerContentsRoller.roll(always, Blocks.DECORATED_POT.defaultBlockState(), CELL,
            SEED, 7, null, registries, null);
        DecoratedPotBlockEntity pot = new DecoratedPotBlockEntity(CELL, Blocks.DECORATED_POT.defaultBlockState());
        pot.loadCustomOnly(rolled, registries);
        assertTrue(RolledItemsFingerprint.isPristine(pot, pot, registries));
        pot.setTheItem(ItemStack.EMPTY);
        assertFalse(RolledItemsFingerprint.isPristine(pot, pot, registries));
    }
}
