package games.brennan.dungeontrain.editor;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Suspicious sand / gravel take an authored loot pool: one weighted pick lands under the block
 * entity's {@code item} key, and any archaeology {@code LootTable} is dropped so vanilla doesn't
 * prefer it over the authored item.
 */
final class ContainerContentsRollerBrushableTest {

    private static final ResourceLocation DIAMOND = ResourceLocation.withDefaultNamespace("diamond");
    private static final BlockPos CELL = new BlockPos(2, 1, 3);
    private static final long SEED = 12345L;

    private static HolderLookup.Provider registries;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = VanillaRegistries.createLookup();
    }

    private static ContainerContentsPool diamondPool() {
        return new ContainerContentsPool(List.of(new ContainerContentsEntry(DIAMOND, 1, 1)), 1, 1);
    }

    @Test
    @DisplayName("brushables are loot-authorable with a single slot; plain blocks are not")
    void authorable() {
        assertTrue(ContainerContentsRoller.isLootAuthorable(Blocks.SUSPICIOUS_SAND.defaultBlockState()));
        assertTrue(ContainerContentsRoller.isLootAuthorable(Blocks.SUSPICIOUS_GRAVEL.defaultBlockState()));
        assertTrue(ContainerContentsRoller.isLootAuthorable(Blocks.CHEST.defaultBlockState()));
        assertFalse(ContainerContentsRoller.isLootAuthorable(Blocks.SAND.defaultBlockState()));
        assertFalse(ContainerContentsRoller.isContainerState(Blocks.SUSPICIOUS_SAND.defaultBlockState()));
        assertEquals(1, ContainerContentsRoller.slotsForContainer(Blocks.SUSPICIOUS_GRAVEL.defaultBlockState()));
    }

    @Test
    @DisplayName("an authored pool writes item and strips the archaeology loot table")
    void rollReplacesLootTable() {
        CompoundTag base = ContainerContentsRoller.stampArchaeologyLoot(
            Blocks.SUSPICIOUS_SAND.defaultBlockState(), null, CELL, SEED, 0);
        assertTrue(base.contains("LootTable"));

        CompoundTag rolled = ContainerContentsRoller.roll(diamondPool(),
            Blocks.SUSPICIOUS_SAND.defaultBlockState(), CELL, SEED, 0, base, registries, null);

        assertFalse(rolled.contains("LootTable"));
        assertFalse(rolled.contains("LootTableSeed"));
        assertEquals("minecraft:diamond", rolled.getCompound("item").getString("id"));
    }

    @Test
    @DisplayName("same seed and cell roll the same item")
    void deterministic() {
        CompoundTag a = ContainerContentsRoller.roll(diamondPool(),
            Blocks.SUSPICIOUS_GRAVEL.defaultBlockState(), CELL, SEED, 4, null, registries, null);
        CompoundTag b = ContainerContentsRoller.roll(diamondPool(),
            Blocks.SUSPICIOUS_GRAVEL.defaultBlockState(), CELL, SEED, 4, null, registries, null);
        assertEquals(a, b);
    }
}
