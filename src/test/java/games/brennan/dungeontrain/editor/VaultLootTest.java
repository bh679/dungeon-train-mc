package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.block.VaultGreen;
import games.brennan.dungeontrain.item.VariantClipboardItem;
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
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vaults as loot containers: the vault is authorable, its pool carries the key that unlocks it,
 * placement stores the pool (not rolled items) plus the key in vanilla's config, and the key picks
 * the vault type and its default loot.
 */
final class VaultLootTest {

    private static final ResourceLocation DIAMOND = ResourceLocation.withDefaultNamespace("diamond");
    private static final BlockPos CELL = new BlockPos(3, 0, 2);
    private static final long SEED = 12345L;

    private static HolderLookup.Provider registries;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = VanillaRegistries.createLookup();
    }

    private static ContainerContentsPool diamondPool(String key) {
        return new ContainerContentsPool(List.of(new ContainerContentsEntry(DIAMOND, 2, 1)), 1, 3, key);
    }

    private static final ResourceLocation TOTEM = ResourceLocation.withDefaultNamespace("totem_of_undying");

    @Test
    @DisplayName("a vault reward rolled above the item's stack size pays out as several items; a chest roll cuts it to one")
    void vaultSplitsOverflow() {
        ContainerContentsPool totems = new ContainerContentsPool(
            List.of(new ContainerContentsEntry(TOTEM, 7, 1).withMinCount(3)), 1, 1);
        for (long seed = 1; seed <= 40; seed++) {
            List<ItemStack> vault = ContainerContentsRoller.rollStacks(totems, VaultLoot.VAULT_SLOTS, CELL, seed,
                0, 0, true, registries);
            int total = vault.stream().mapToInt(ItemStack::getCount).sum();
            assertTrue(total >= 3 && total <= 7, "vault totems " + total);
            assertTrue(vault.stream().allMatch(st -> st.getCount() == 1), "every totem is its own stack");

            List<ItemStack> chest = ContainerContentsRoller.rollStacks(totems, VaultLoot.VAULT_SLOTS, CELL, seed,
                0, 0, registries);
            assertEquals(1, chest.size());
            assertEquals(1, chest.get(0).getCount());
        }
    }

    @Test
    @DisplayName("overflow splits into full stacks, and nothing is added when the roll fit")
    void overflowStacks() {
        ItemStack apples = new ItemStack(Items.GOLDEN_APPLE, 64);
        List<ItemStack> extra = ContainerContentsRoller.overflowStacks(apples, 150);
        assertEquals(2, extra.size());
        assertEquals(64, extra.get(0).getCount());
        assertEquals(22, extra.get(1).getCount());
        assertTrue(ContainerContentsRoller.overflowStacks(new ItemStack(Items.GOLDEN_APPLE, 5), 5).isEmpty());
        assertTrue(ContainerContentsRoller.overflowStacks(new ItemStack(Items.TOTEM_OF_UNDYING), 1).isEmpty());
    }

    @Test
    @DisplayName("a vault is loot-authorable with the vault slot count, but is not a Container")
    void authorable() {
        BlockState vault = Blocks.VAULT.defaultBlockState();
        assertTrue(ContainerContentsRoller.isLootAuthorable(vault));
        assertFalse(ContainerContentsRoller.isContainerState(vault));
        assertEquals(VaultLoot.VAULT_SLOTS, ContainerContentsRoller.slotsForContainer(vault));
    }

    @Test
    @DisplayName("rolling a vault stores the pool and writes the key into vanilla's config")
    void rollWritesKeyAndPool() {
        CompoundTag nbt = ContainerContentsRoller.roll(diamondPool(ContainerContentsPool.DEFAULT_VAULT_KEY),
            Blocks.VAULT.defaultBlockState(), CELL, SEED, 7, null, registries, null);
        assertNotNull(nbt);
        CompoundTag config = nbt.getCompound("config");
        assertEquals("minecraft:trial_key", config.getCompound("key_item").getString("id"));
        assertEquals("minecraft:chests/trial_chambers/reward", config.getString("loot_table"));
        CompoundTag vault = nbt.getCompound("NeoForgeData").getCompound(VaultLoot.TAG_VAULT);
        ContainerContentsPool stored = LootPrefabStore.poolFromText(vault.getString("pool")).orElseThrow();
        assertTrue(VaultLoot.sameLoot(diamondPool(ContainerContentsPool.DEFAULT_VAULT_KEY), stored));
        assertFalse(nbt.contains("Items"), "a vault rolls per player, never at placement");
    }

    @Test
    @DisplayName("the ominous key makes an ominous vault with the ominous table; the trial key makes a trial vault")
    void keyPicksVaultType() {
        BlockState plain = Blocks.VAULT.defaultBlockState().setValue(VaultBlock.OMINOUS, false);
        CompoundTag ominous = ContainerContentsRoller.roll(diamondPool(VaultLoot.OMINOUS_KEY),
            plain, CELL, SEED, 0, null, registries, null);
        assertEquals("minecraft:ominous_trial_key",
            ominous.getCompound("config").getCompound("key_item").getString("id"));
        assertEquals("minecraft:chests/trial_chambers/reward_ominous", ominous.getCompound("config").getString("loot_table"));
        assertTrue(VaultLoot.stateForNbt(plain, ominous).getValue(VaultBlock.OMINOUS));

        BlockState wasOminous = plain.setValue(VaultBlock.OMINOUS, true);
        CompoundTag trial = ContainerContentsRoller.roll(diamondPool(ContainerContentsPool.DEFAULT_VAULT_KEY),
            wasOminous, CELL, SEED, 0, null, registries, null);
        assertFalse(VaultLoot.stateForNbt(wasOminous, trial).getValue(VaultBlock.OMINOUS));
    }

    @Test
    @DisplayName("the green key takes the ominous table, and a green vault is never ominous")
    void greenKeyConfig() {
        BlockState wasOminous = Blocks.VAULT.defaultBlockState().setValue(VaultBlock.OMINOUS, true);
        CompoundTag green = ContainerContentsRoller.roll(diamondPool(VaultLoot.GREEN_KEY),
            wasOminous, CELL, SEED, 0, null, registries, null);
        assertEquals(VaultLoot.GREEN_KEY, green.getCompound("config").getCompound("key_item").getString("id"));
        assertEquals("minecraft:chests/trial_chambers/reward_ominous", green.getCompound("config").getString("loot_table"));
        BlockState typed = VaultLoot.stateForNbt(wasOminous, green);
        assertFalse(typed.getValue(VaultBlock.OMINOUS));
        assertTrue(VaultGreen.isGreen(typed));
    }

    @Test
    @DisplayName("the vault has a green property, off by default, that follows the key")
    void greenPropertyFollowsKey() {
        BlockState vault = Blocks.VAULT.defaultBlockState();
        assertTrue(vault.hasProperty(VaultGreen.GREEN));
        assertFalse(VaultGreen.isGreen(vault));
        BlockState green = VaultLoot.stateFor(vault.setValue(VaultBlock.OMINOUS, true), diamondPool(VaultLoot.GREEN_KEY));
        assertTrue(VaultGreen.isGreen(green));
        assertFalse(green.getValue(VaultBlock.OMINOUS));
        BlockState ominous = VaultLoot.stateFor(green, diamondPool(VaultLoot.OMINOUS_KEY));
        assertFalse(VaultGreen.isGreen(ominous));
        assertTrue(ominous.getValue(VaultBlock.OMINOUS));
    }

    @Test
    @DisplayName("vault NBT that DT never wrote keeps its block state")
    void foreignVaultNbtUntouched() {
        BlockState ominous = Blocks.VAULT.defaultBlockState().setValue(VaultBlock.OMINOUS, true);
        assertSame(ominous, VaultLoot.stateForNbt(ominous, new CompoundTag()));
        assertSame(ominous, VaultLoot.stateForNbt(ominous, null));
    }

    @Test
    @DisplayName("the Key row cycles trial -> ominous -> green -> trial")
    void keyCycle() {
        assertEquals(VaultLoot.OMINOUS_KEY, VaultLoot.nextKey(ContainerContentsPool.DEFAULT_VAULT_KEY));
        assertEquals(VaultLoot.GREEN_KEY, VaultLoot.nextKey(VaultLoot.OMINOUS_KEY));
        assertEquals(ContainerContentsPool.DEFAULT_VAULT_KEY, VaultLoot.nextKey(VaultLoot.GREEN_KEY));
        assertEquals(ContainerContentsPool.DEFAULT_VAULT_KEY, VaultLoot.nextKey("minecraft:stick"));
    }

    @Test
    @DisplayName("an unedited default pool follows the key to the other default; an edited one keeps its items")
    void defaultLootFollowsKey() {
        ContainerContentsPool trialDefault = VaultLoot.defaultPool(ContainerContentsPool.DEFAULT_VAULT_KEY);
        ContainerContentsPool ominousDefault = VaultLoot.defaultPool(VaultLoot.OMINOUS_KEY);
        assertFalse(trialDefault.isEmpty());
        assertFalse(ominousDefault.isEmpty());
        assertFalse(VaultLoot.sameLoot(trialDefault, ominousDefault));

        ContainerContentsPool greenDefault = VaultLoot.defaultPool(VaultLoot.GREEN_KEY);
        assertEquals(VaultLoot.GREEN_KEY, greenDefault.vaultKey());
        assertFalse(greenDefault.isEmpty());
        assertFalse(VaultLoot.sameLoot(ominousDefault, greenDefault), "the green vault has its own loot");
        ContainerContentsPool toGreen = VaultLoot.withKey(ominousDefault, VaultLoot.GREEN_KEY);
        assertEquals(VaultLoot.GREEN_KEY, toGreen.vaultKey());
        assertTrue(VaultLoot.sameLoot(greenDefault, toGreen));
        assertTrue(VaultLoot.sameLoot(trialDefault, VaultLoot.withKey(toGreen, ContainerContentsPool.DEFAULT_VAULT_KEY)));

        ContainerContentsPool switched = VaultLoot.withKey(trialDefault, VaultLoot.OMINOUS_KEY);
        assertEquals(VaultLoot.OMINOUS_KEY, switched.vaultKey());
        assertTrue(VaultLoot.sameLoot(ominousDefault, switched));
        assertTrue(VaultLoot.sameLoot(trialDefault, VaultLoot.withKey(switched, ContainerContentsPool.DEFAULT_VAULT_KEY)));

        ContainerContentsPool edited = diamondPool(ContainerContentsPool.DEFAULT_VAULT_KEY);
        ContainerContentsPool editedSwitched = VaultLoot.withKey(edited, VaultLoot.OMINOUS_KEY);
        assertEquals(VaultLoot.OMINOUS_KEY, editedSwitched.vaultKey());
        assertTrue(VaultLoot.sameLoot(edited, editedSwitched));
    }

    @Test
    @DisplayName("the trial and ominous vaults can drop the Dungeon Trial Key")
    void defaultVaultsDropTheGreenKey() {
        for (String key : List.of(ContainerContentsPool.DEFAULT_VAULT_KEY, VaultLoot.OMINOUS_KEY)) {
            assertTrue(VaultLoot.defaultPool(key).entries().stream()
                    .anyMatch(e -> e.itemId().toString().equals(VaultLoot.GREEN_KEY)),
                key + " vault has no Dungeon Trial Key");
        }
    }

    @Test
    @DisplayName("the green vault rolls 3-5 items, with no low-tier items and the agreed Mace and Heavy Core odds")
    void greenVaultRewardBalance() {
        ContainerContentsPool green = VaultLoot.defaultPool(VaultLoot.GREEN_KEY);
        assertEquals(3, green.fillMin());
        assertEquals(5, green.fillMax());

        Set<ResourceLocation> lowTier = Set.of(
            ResourceLocation.withDefaultNamespace("diamond"),
            ResourceLocation.withDefaultNamespace("emerald"),
            ResourceLocation.withDefaultNamespace("tipped_arrow"),
            ResourceLocation.withDefaultNamespace("wind_charge"));
        assertTrue(green.entries().stream().noneMatch(e -> lowTier.contains(e.itemId())));

        // Expected items per unlock: average fill (4) x weight / total weight.
        assertEquals(0.005, expectedPerUnlock(green, "mace"), 0.0005);
        assertEquals(0.010, expectedPerUnlock(green, "heavy_core"), 0.0005);
    }

    @Test
    @DisplayName("the ominous vault has no Wind Charges or Emeralds, rolls 2-4 items, and has all diamond gear")
    void ominousVaultRewardBalance() {
        ContainerContentsPool ominous = VaultLoot.defaultPool(VaultLoot.OMINOUS_KEY);
        assertEquals(2, ominous.fillMin());
        assertEquals(4, ominous.fillMax());
        assertEquals(2346, ominous.totalWeight());
        assertEquals(0.005, expectedPerUnlock(ominous, "heavy_core"), 0.0005);

        Set<ResourceLocation> removed = Set.of(
            ResourceLocation.withDefaultNamespace("emerald"),
            ResourceLocation.withDefaultNamespace("wind_charge"));
        assertTrue(ominous.entries().stream().noneMatch(e -> removed.contains(e.itemId())));

        for (String piece : List.of("helmet", "chestplate", "leggings", "boots", "sword", "axe", "pickaxe", "shovel", "hoe")) {
            ResourceLocation id = ResourceLocation.withDefaultNamespace("diamond_" + piece);
            assertTrue(ominous.entries().stream().anyMatch(e -> e.itemId().equals(id)), id + " missing");
        }
    }

    @Test
    @DisplayName("Ender Blocks drop up to 2 from the ominous vault, rarer than Golden Apples; the green vault has none")
    void enderBlockRewards() {
        ContainerContentsEntry ominous = entry(VaultLoot.defaultPool(VaultLoot.OMINOUS_KEY), "betterend", "ender_block");
        assertEquals(2, ominous.count());
        assertEquals(100, ominous.weight());
        assertTrue(ominous.weight() < entry(VaultLoot.defaultPool(VaultLoot.OMINOUS_KEY), "minecraft", "golden_apple").weight());

        ResourceLocation ender = ResourceLocation.fromNamespaceAndPath("betterend", "ender_block");
        assertTrue(VaultLoot.defaultPool(VaultLoot.GREEN_KEY).entries().stream().noneMatch(e -> e.itemId().equals(ender)));
    }

    @Test
    @DisplayName("Golden Apples drop 10 to 30 from the green vault")
    void greenVaultGoldenApples() {
        ContainerContentsEntry apples = entry(VaultLoot.defaultPool(VaultLoot.GREEN_KEY), "minecraft", "golden_apple");
        assertEquals(10, apples.minCount());
        assertEquals(30, apples.count());
    }

    @Test
    @DisplayName("the green vault keeps the heavy core and mace at their per-opening odds, and the spawn eggs at their weights")
    void greenVaultRaritiesKeepTheirOdds() {
        ContainerContentsPool green = VaultLoot.defaultPool(VaultLoot.GREEN_KEY);
        assertEquals(16182, green.totalWeight());
        assertEquals(40, entry(green, "minecraft", "heavy_core").weight());
        assertEquals(20, entry(green, "minecraft", "mace").weight());
        assertEquals(22, green.entries().stream().filter(e -> e.itemId().getPath().endsWith("_spawn_egg"))
            .mapToInt(ContainerContentsEntry::weight).sum());
    }

    private static ContainerContentsEntry entry(ContainerContentsPool pool, String namespace, String path) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(namespace, path);
        return pool.entries().stream().filter(e -> e.itemId().equals(id)).findFirst().orElseThrow();
    }

    private static double expectedPerUnlock(ContainerContentsPool pool, String item) {
        ResourceLocation id = ResourceLocation.withDefaultNamespace(item);
        int weight = pool.entries().stream().filter(e -> e.itemId().equals(id)).mapToInt(ContainerContentsEntry::weight).sum();
        return (pool.fillMin() + pool.fillMax()) / 2.0 * weight / pool.totalWeight();
    }

    @Test
    @DisplayName("a vault linked to a default prefab relinks to the next key's default; other prefabs never swap")
    void linkedDefaultsRelink() {
        assertEquals(VaultLoot.OMINOUS_PREFAB, VaultLoot.otherDefaultPrefab(VaultLoot.DEFAULT_PREFAB));
        assertEquals(VaultLoot.GREEN_PREFAB, VaultLoot.otherDefaultPrefab(VaultLoot.OMINOUS_PREFAB));
        assertEquals(VaultLoot.DEFAULT_PREFAB, VaultLoot.otherDefaultPrefab(VaultLoot.GREEN_PREFAB));
        assertEquals(null, VaultLoot.otherDefaultPrefab("pot"));
    }

    @Test
    @DisplayName("each player gets their own reward salt, stable across calls")
    void playerSaltPerPlayer() {
        UUID a = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID b = UUID.fromString("00000000-0000-0000-0000-000000000002");
        assertEquals(VaultLoot.playerSalt(a), VaultLoot.playerSalt(a));
        assertNotEquals(VaultLoot.playerSalt(a), VaultLoot.playerSalt(b));
    }

    @Test
    @DisplayName("pass 0 keeps the original per-player seed; a lucky pass rolls on a seed of its own")
    void luckyPassSeed() {
        UUID a = UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertEquals(SEED ^ VaultLoot.playerSalt(a), VaultLoot.rollSeed(SEED, a, 0));
        assertNotEquals(VaultLoot.rollSeed(SEED, a, 0), VaultLoot.rollSeed(SEED, a, 1));
        assertEquals(VaultLoot.rollSeed(SEED, a, 1), VaultLoot.rollSeed(SEED, a, 1));
    }

    @Test
    @DisplayName("the key survives the store, prefab and clipboard formats; the default is never written")
    void keyRoundTrips() {
        ContainerContentsPool ominous = diamondPool(VaultLoot.OMINOUS_KEY);
        ContainerContentsPool trial = diamondPool(ContainerContentsPool.DEFAULT_VAULT_KEY);

        ContainerContentsStore store = ContainerContentsStore.detached("test");
        store.putPool(CELL, ominous);
        store.putPool(CELL.east(), trial);
        String text = store.asJsonText();
        assertEquals(1, text.split("vaultKey", -1).length - 1, "only the non-default key is written");
        ContainerContentsStore back = ContainerContentsStore.fromJsonText("test", text);
        assertEquals(VaultLoot.OMINOUS_KEY, back.poolAt(CELL).vaultKey());
        assertEquals(ContainerContentsPool.DEFAULT_VAULT_KEY, back.poolAt(CELL.east()).vaultKey());

        assertEquals(VaultLoot.OMINOUS_KEY,
            LootPrefabStore.poolFromText(LootPrefabStore.poolText(ominous, ResourceLocation.withDefaultNamespace("vault")))
                .orElseThrow().vaultKey());

        CompoundTag clip = new CompoundTag();
        clip.put(VariantClipboardItem.NBT_POOL, VariantClipboardItem.encodePool(ominous));
        assertEquals(VaultLoot.OMINOUS_KEY, VariantClipboardItem.decodePool(clip).vaultKey());
        assertFalse(VariantClipboardItem.encodePool(trial).contains("vkey"));
    }

    @Test
    @DisplayName("a pool with no key, or a blank one, takes the trial key")
    void blankKeyDefaults() {
        assertEquals(ContainerContentsPool.DEFAULT_VAULT_KEY,
            new ContainerContentsPool(List.of(), 0, ContainerContentsPool.FILL_ALL).vaultKey());
        assertEquals(ContainerContentsPool.DEFAULT_VAULT_KEY,
            new ContainerContentsPool(List.of(), 0, ContainerContentsPool.FILL_ALL, " ").vaultKey());
        assertTrue(diamondPool(null).isDefaultVaultKey());
    }
}
