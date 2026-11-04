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
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
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
    private static final ResourceLocation EMERALD = ResourceLocation.withDefaultNamespace("emerald");
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
    @DisplayName("the shown item's entries double, every other entry stays, and the rest of the pool is kept")
    void shownItemWeightDoubles() {
        ContainerContentsEntry diamond = new ContainerContentsEntry(DIAMOND, 2, 3).withEnchantmentChance(77);
        ContainerContentsEntry diamondAgain = new ContainerContentsEntry(DIAMOND, 5, 4);
        ContainerContentsEntry emerald = new ContainerContentsEntry(EMERALD, 1, 5);
        ContainerContentsPool pool = new ContainerContentsPool(
            List.of(diamond, emerald, diamondAgain), 1, 3, VaultLoot.GREEN_KEY);

        ContainerContentsPool boosted = VaultLoot.withShownItemBoosted(pool, new ItemStack(Items.DIAMOND));

        assertEquals(List.of(diamond.withWeight(6), emerald, diamondAgain.withWeight(8)), boosted.entries());
        assertEquals(1, boosted.fillMin());
        assertEquals(3, boosted.fillMax());
        assertEquals(VaultLoot.GREEN_KEY, boosted.vaultKey());
        assertEquals(List.of(diamond, emerald, diamondAgain), pool.entries(), "the cached pool is not touched");
    }

    @Test
    @DisplayName("nothing on show, or an item the pool lacks, changes no weight")
    void noShownItemNoBoost() {
        ContainerContentsPool pool = new ContainerContentsPool(
            List.of(new ContainerContentsEntry(DIAMOND, 1, 1), ContainerContentsEntry.air(2)));
        assertSame(pool, VaultLoot.withShownItemBoosted(pool, ItemStack.EMPTY));
        assertEquals(pool.entries(), VaultLoot.withShownItemBoosted(pool, new ItemStack(Items.EMERALD)).entries());
    }

    @Test
    @DisplayName("of two equal items, the one on show lands about two thirds of the time")
    void shownItemWinsTwoThirds() {
        ContainerContentsPool pool = new ContainerContentsPool(List.of(
            new ContainerContentsEntry(DIAMOND, 1, 1), new ContainerContentsEntry(EMERALD, 1, 1)));
        ContainerContentsPool boosted = VaultLoot.withShownItemBoosted(pool, new ItemStack(Items.EMERALD));

        int rolls = 30_000;
        int emeralds = 0;
        for (int seed = 0; seed < rolls; seed++) {
            ContainerContentsEntry picked = ContainerContentsRoller.pickFiltered(boosted, e -> true, CELL, seed, 0, 0);
            if (picked.itemId().equals(EMERALD)) emeralds++;
        }
        assertEquals(2.0 / 3.0, emeralds / (double) rolls, 0.02);
    }

    /** Share of 3000 players whose one-item reward from a real vault showing {@code shown} is an emerald. */
    private static double emeraldShare(ItemStack shown) {
        ContainerContentsPool pool = new ContainerContentsPool(List.of(
            new ContainerContentsEntry(DIAMOND, 1, 1), new ContainerContentsEntry(EMERALD, 1, 1)),
            1, 1, ContainerContentsPool.DEFAULT_VAULT_KEY);
        CompoundTag placed = VaultLoot.writeVault(pool, CELL, SEED, 0, 0, null, registries);
        VaultBlockEntity vault = new VaultBlockEntity(BlockPos.ZERO, Blocks.VAULT.defaultBlockState());
        vault.getPersistentData().put(VaultLoot.TAG_VAULT,
            placed.getCompound("NeoForgeData").getCompound(VaultLoot.TAG_VAULT));
        vault.getSharedData().setDisplayItem(shown);

        int players = 3000;
        int emeralds = 0;
        for (int i = 0; i < players; i++) {
            List<ItemStack> reward = VaultLoot.rollForPlayer(vault, new UUID(i * 31L, i), registries).orElseThrow();
            if (reward.get(0).is(Items.EMERALD)) emeralds++;
        }
        return emeralds / (double) players;
    }

    @Test
    @DisplayName("a real vault's reward leans to the item it is showing, and is even with nothing on show")
    void rollReadsTheVaultsShownItem() {
        assertEquals(0.5, emeraldShare(ItemStack.EMPTY), 0.05);
        assertEquals(2.0 / 3.0, emeraldShare(new ItemStack(Items.EMERALD)), 0.05);
        assertEquals(1.0 / 3.0, emeraldShare(new ItemStack(Items.DIAMOND)), 0.05);
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
