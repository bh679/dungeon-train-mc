package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Vaults as loot containers. A vanilla vault rewards each player once, from a loot table, when
 * they use its key. Here the reward comes from a pool authored in the loot (C) menu instead, and
 * the pool's {@link ContainerContentsPool#vaultKey()} picks the key, which also picks the vault's
 * type: the ominous trial key makes an ominous vault.
 *
 * <p><b>Rolled per player, not at placement.</b> Placement ({@link #writeVault}) stores the pool
 * and its roll frame in the block entity's NeoForge data; {@code VaultServerMixin} rolls it when a
 * player unlocks the vault, salted with that player's UUID, so two players get two rewards while
 * one player gets the same reward from the same vault every time. Vanilla keeps doing the rest:
 * checking and taking the key, and remembering who has been rewarded.</p>
 *
 * <p>A vault with no authored pool takes the bundled {@link #DEFAULT_PREFAB} (or
 * {@link #OMINOUS_PREFAB}), hand-copied from vanilla's trial-chamber reward tables, every time.
 * A vault is never empty the way a 20%-default chest can be.</p>
 */
public final class VaultLoot {

    /** Most items one player gets from one unlock: the pool's fill range is clamped to this. */
    public static final int VAULT_SLOTS = 9;

    public static final String OMINOUS_KEY = "minecraft:ominous_trial_key";

    /** Keys the menu's Key row cycles through, in order. Append to offer another. */
    static final List<String> KEY_CYCLE = List.of(ContainerContentsPool.DEFAULT_VAULT_KEY, OMINOUS_KEY);

    public static final String DEFAULT_PREFAB = "trial_vault";
    public static final String OMINOUS_PREFAB = "ominous_trial_vault";

    /** Sub-compound of the block entity's {@code NeoForgeData} holding the pool and its roll frame. */
    static final String TAG_VAULT = "dungeontrain_vault";
    private static final String NBT_NEOFORGE_DATA = "NeoForgeData";
    private static final String TAG_POOL = "pool";
    private static final String TAG_SEED = "seed";
    private static final String TAG_CARRIAGE = "carriage";
    private static final String TAG_DIFF = "diff";
    private static final String TAG_POS = "pos";

    private static final String NBT_CONFIG = "config";
    private static final String NBT_KEY_ITEM = "key_item";
    private static final String NBT_LOOT_TABLE = "loot_table";
    private static final String REWARD_TABLE = "minecraft:chests/trial_chambers/reward";
    private static final String REWARD_TABLE_OMINOUS = "minecraft:chests/trial_chambers/reward_ominous";

    private static final long PLAYER_MIX_HI = 0x9E3779B97F4A7C15L;
    private static final long PLAYER_MIX_LO = 0xC2B2AE3D27D4EB4FL;

    /** Parsed pools by their text: the display item re-reads the pool every couple of seconds. */
    private static final int POOL_CACHE_SIZE = 64;
    private static final Map<String, ContainerContentsPool> POOL_CACHE =
        new LinkedHashMap<>(POOL_CACHE_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ContainerContentsPool> eldest) {
                return size() > POOL_CACHE_SIZE;
            }
        };

    private VaultLoot() {}

    public static boolean isVault(BlockState state) {
        return state.is(Blocks.VAULT);
    }

    public static boolean isOminousKey(String key) {
        return OMINOUS_KEY.equals(key);
    }

    /** The key after {@code key} in {@link #KEY_CYCLE}; an unknown key starts the cycle over. */
    public static String nextKey(String key) {
        int i = KEY_CYCLE.indexOf(key);
        return KEY_CYCLE.get((i + 1) % KEY_CYCLE.size());
    }

    public static String defaultPrefabFor(String key) {
        return isOminousKey(key) ? OMINOUS_PREFAB : DEFAULT_PREFAB;
    }

    /** The other default vault prefab for one of the two, or null for any other prefab id. */
    @Nullable
    public static String otherDefaultPrefab(String prefabId) {
        if (DEFAULT_PREFAB.equals(prefabId)) return OMINOUS_PREFAB;
        if (OMINOUS_PREFAB.equals(prefabId)) return DEFAULT_PREFAB;
        return null;
    }

    /** The bundled vanilla-loot pool for a vault taking {@code key}. */
    public static ContainerContentsPool defaultPool(String key) {
        return LootPrefabStore.load(defaultPrefabFor(key))
            .map(LootPrefabStore.Data::pool)
            .orElse(ContainerContentsPool.empty())
            .withVaultKey(key);
    }

    /**
     * {@code pool} switched to {@code key}. A pool that is still exactly its key's default loot
     * follows the vault type to the other default; one the author has edited keeps its items.
     */
    public static ContainerContentsPool withKey(ContainerContentsPool pool, String key) {
        if (sameLoot(pool, defaultPool(pool.vaultKey()))) return defaultPool(key);
        return pool.withVaultKey(key);
    }

    static boolean sameLoot(ContainerContentsPool a, ContainerContentsPool b) {
        return a.fillMin() == b.fillMin() && a.fillMax() == b.fillMax() && a.entries().equals(b.entries());
    }

    /** {@code state} as the vault type {@code pool}'s key makes: ominous for the ominous key. */
    public static BlockState stateFor(BlockState state, ContainerContentsPool pool) {
        if (!isVault(state)) return state;
        return state.setValue(VaultBlock.OMINOUS, isOminousKey(pool.vaultKey()));
    }

    /**
     * {@code state} as the vault type its rolled NBT says: ominous when {@link #writeVault} gave it
     * the ominous key. Anything else, or a vault NBT this class never wrote, comes back unchanged.
     */
    public static BlockState stateForNbt(BlockState state, @Nullable CompoundTag nbt) {
        if (!isVault(state) || nbt == null) return state;
        if (!nbt.getCompound(NBT_NEOFORGE_DATA).contains(TAG_VAULT, Tag.TAG_COMPOUND)) return state;
        String key = nbt.getCompound(NBT_CONFIG).getCompound(NBT_KEY_ITEM).getString("id");
        return state.setValue(VaultBlock.OMINOUS, isOminousKey(key));
    }

    /**
     * Re-type the placed vault at {@code worldPos} to match the key its block entity now carries.
     * Same block, so the block entity (and the pool just loaded into it) stays put.
     */
    public static void syncState(ServerLevel level, BlockPos worldPos) {
        BlockState state = level.getBlockState(worldPos);
        if (!isVault(state)) return;
        BlockEntity be = level.getBlockEntity(worldPos);
        if (be == null) return;
        BlockState typed = stateForNbt(state, be.saveWithFullMetadata(level.registryAccess()));
        if (typed != state) level.setBlock(worldPos, typed, Block.UPDATE_CLIENTS);
    }

    /**
     * The vault's block entity NBT with {@code pool} installed: vanilla's config takes the pool's
     * key (and the matching vanilla table, which only shows if the pool goes missing), and the
     * pool plus its roll frame go under {@link #TAG_VAULT} for {@link #rollForPlayer}.
     */
    public static CompoundTag writeVault(ContainerContentsPool pool, BlockPos localPos, long worldSeed,
                                         int carriageIndex, int diffIndex, @Nullable CompoundTag baseNbt,
                                         HolderLookup.Provider registries) {
        CompoundTag out = baseNbt == null ? new CompoundTag() : baseNbt.copy();
        CompoundTag config = out.getCompound(NBT_CONFIG).copy();
        config.put(NBT_KEY_ITEM, new ItemStack(keyItem(pool.vaultKey())).save(registries));
        config.putString(NBT_LOOT_TABLE, isOminousKey(pool.vaultKey()) ? REWARD_TABLE_OMINOUS : REWARD_TABLE);
        out.put(NBT_CONFIG, config);

        CompoundTag vault = new CompoundTag();
        vault.putString(TAG_POOL, LootPrefabStore.poolText(pool, BuiltInRegistries.BLOCK.getKey(Blocks.VAULT)));
        vault.putLong(TAG_SEED, worldSeed);
        vault.putInt(TAG_CARRIAGE, carriageIndex);
        vault.putInt(TAG_DIFF, diffIndex);
        vault.putIntArray(TAG_POS, new int[] {localPos.getX(), localPos.getY(), localPos.getZ()});
        CompoundTag neo = out.getCompound(NBT_NEOFORGE_DATA).copy();
        neo.put(TAG_VAULT, vault);
        out.put(NBT_NEOFORGE_DATA, neo);
        return out;
    }

    /** The key item for {@code key}; an id that names no item falls back to the trial key. */
    static Item keyItem(String key) {
        ResourceLocation id = ResourceLocation.tryParse(key);
        Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
        return item == Items.AIR ? Items.TRIAL_KEY : item;
    }

    /**
     * {@code player}'s reward from a DT vault, or empty when the vault carries no pool (a vanilla
     * vault, which keeps its loot table).
     */
    public static Optional<List<ItemStack>> rollForPlayer(BlockEntity be, UUID player,
                                                          HolderLookup.Provider registries) {
        CompoundTag vault = vaultTag(be);
        if (vault == null) return Optional.empty();
        ContainerContentsPool pool = poolOf(vault);
        if (pool == null) return Optional.empty();
        int[] p = vault.getIntArray(TAG_POS);
        BlockPos localPos = p.length == 3 ? new BlockPos(p[0], p[1], p[2]) : BlockPos.ZERO;
        long seed = vault.getLong(TAG_SEED) ^ playerSalt(player);
        int carriage = vault.getInt(TAG_CARRIAGE);
        int diff = vault.getInt(TAG_DIFF);
        // At least one item: vanilla refuses the key on an empty reward, and the roll is the same
        // for this player every time, so an empty one would lock them out of this vault for good.
        ContainerContentsPool atLeastOne = pool.withFillMin(Math.max(1, pool.fillMin()));
        List<ItemStack> rolled = ContainerContentsRoller.rollStacks(atLeastOne, VAULT_SLOTS, localPos, seed,
            carriage, diff, registries);
        if (rolled.isEmpty()) {
            rolled = ContainerContentsRoller.rollStacks(defaultPool(pool.vaultKey()), VAULT_SLOTS, localPos, seed,
                carriage, diff, registries);
        }
        return Optional.of(rolled);
    }

    /** Seed salt for one player: distinct per UUID, stable across sessions. */
    static long playerSalt(UUID player) {
        return player.getMostSignificantBits() * PLAYER_MIX_HI ^ player.getLeastSignificantBits() * PLAYER_MIX_LO;
    }

    /** An item to show spinning inside the vault, weighted like the pool; empty for a vanilla vault. */
    public static Optional<ItemStack> displayItem(BlockEntity be, RandomSource random) {
        CompoundTag vault = vaultTag(be);
        if (vault == null) return Optional.empty();
        ContainerContentsPool pool = poolOf(vault);
        if (pool == null) return Optional.empty();
        int total = pool.totalWeight();
        if (total <= 0) return Optional.of(ItemStack.EMPTY);
        int pick = random.nextInt(total);
        for (ContainerContentsEntry e : pool.entries()) {
            pick -= e.weight();
            if (pick < 0) {
                if (e.isAir()) return Optional.of(ItemStack.EMPTY);
                Item item = ContainerContentsRoller.resolveItem(e.itemId());
                return Optional.of(item == null ? ItemStack.EMPTY : new ItemStack(item));
            }
        }
        return Optional.of(ItemStack.EMPTY);
    }

    @Nullable
    private static CompoundTag vaultTag(BlockEntity be) {
        if (be == null) return null;
        CompoundTag neo = be.getPersistentData();
        return neo.contains(TAG_VAULT, Tag.TAG_COMPOUND) ? neo.getCompound(TAG_VAULT) : null;
    }

    @Nullable
    private static ContainerContentsPool poolOf(CompoundTag vault) {
        String text = vault.getString(TAG_POOL);
        if (text.isEmpty()) return null;
        synchronized (POOL_CACHE) {
            ContainerContentsPool cached = POOL_CACHE.get(text);
            if (cached != null) return cached;
        }
        ContainerContentsPool parsed = LootPrefabStore.poolFromText(text).orElse(null);
        if (parsed == null) return null;
        synchronized (POOL_CACHE) {
            POOL_CACHE.put(text, parsed);
        }
        return parsed;
    }
}
