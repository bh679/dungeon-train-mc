package games.brennan.dungeontrain.event;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for {@link AchievementEvents#hasLoot(net.minecraft.world.level.block.entity.BlockEntity)} —
 * "Respect The Rules" only counts chests with loot in them, so an empty chest must read as empty
 * and both kinds of loot (items already in it, or a loot table not yet rolled) must read as loot.
 */
class ChestLootGateTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("an empty chest or barrel has no loot")
    void emptyIsNotLoot() {
        assertFalse(AchievementEvents.hasLoot(new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState())));
        assertFalse(AchievementEvents.hasLoot(new BarrelBlockEntity(BlockPos.ZERO, Blocks.BARREL.defaultBlockState())));
        assertFalse(AchievementEvents.hasLoot(null), "no block entity");
    }

    @Test
    @DisplayName("items in the chest are loot")
    void itemsAreLoot() {
        ChestBlockEntity chest = new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
        chest.setItem(3, new ItemStack(Items.BREAD));
        assertTrue(AchievementEvents.hasLoot(chest));
    }

    @Test
    @DisplayName("an unrolled loot table is loot")
    void lootTableIsLoot() {
        BarrelBlockEntity barrel = new BarrelBlockEntity(BlockPos.ZERO, Blocks.BARREL.defaultBlockState());
        barrel.setLootTable(ResourceKey.create(Registries.LOOT_TABLE,
            ResourceLocation.withDefaultNamespace("chests/simple_dungeon")));
        assertTrue(AchievementEvents.hasLoot(barrel));
    }
}
