package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * The Luck-potion bonus for train loot: a handful of extra stacks pre-rolled from the
 * chest's own pool at placement and spent the first time a player opens the chest.
 *
 * <p>Why pre-roll rather than roll at open time: the pool a chest was rolled from can
 * come from the contents store, a variant-linked loot prefab or
 * {@link BlockLootDefaults}, and none of that is recoverable from a block position
 * once the carriage is on the rails. Baking the candidates up front keeps the open-time
 * hook ({@code LuckyLootEvents}) trivial and keeps the bonus deterministic per seed —
 * only <em>whether</em> it is claimed depends on who opens the chest.</p>
 *
 * <p>Storage: the BE's NeoForge persistent data ({@code NeoForgeData.dt_lucky_bonus}),
 * which every vanilla container BE round-trips through
 * {@code loadAdditional}/{@code saveAdditional}, so the bonus survives placement,
 * Sable block moves, carriage snapshots and save/load with no extra plumbing.</p>
 */
public final class LuckyBonusRoller {

    /** NBT key under {@code NeoForgeData} holding the pre-rolled bonus stacks. */
    public static final String NBT_KEY = "dt_lucky_bonus";
    /** NeoForge's per-BE persistent-data compound (see {@code BlockEntity#getPersistentData}). */
    static final String NBT_NEOFORGE_DATA = "NeoForgeData";

    /** How many candidate stacks are baked per chest — the most Luck can ever add. */
    public static final int MAX_LUCKY_BONUS = 5;
    /** The fewest a lucky opener ever claims. */
    public static final int MIN_LUCKY_BONUS = 3;
    /** Luck II and up always claims at least this many. */
    private static final int LUCK_II_MIN_BONUS = 4;
    /** Luck attribute value at which the Luck II floor kicks in. */
    private static final float LUCK_II_THRESHOLD = 2.0f;

    /**
     * Slot key base for the bonus rolls. Real container slots top out at 54, so bonus
     * keys can never collide with — and therefore never correlate with — a real slot's roll.
     */
    private static final int BONUS_SLOT_BASE = 0x1000;

    private LuckyBonusRoller() {}

    /** Only chests and barrels carry a bonus — the same set the achievement counter calls "loot". */
    public static boolean isBonusContainer(BlockState state) {
        return state.getBlock() instanceof ChestBlock || state.getBlock() instanceof BarrelBlock;
    }

    /**
     * Roll the {@link #MAX_LUCKY_BONUS} candidate stacks for one chest from {@code pool},
     * using the same seed frame as the base roll. Book placeholders are excluded so no
     * pending community-book placeholder is baked into a bonus that may never be handed out.
     *
     * @return the saved stacks, possibly empty if the pool held nothing eligible
     */
    static ListTag preRoll(ContainerContentsPool pool, BlockPos localPos, long worldSeed,
                           int carriageIndex, int diffIndex, HolderLookup.Provider registries) {
        ContainerContentsPool eligible = withoutBookPlaceholders(pool);
        ListTag out = new ListTag();
        int totalWeight = eligible.totalWeight();
        if (eligible.isEmpty() || totalWeight <= 0) return out;
        for (int i = 0; i < MAX_LUCKY_BONUS; i++) {
            int slotKey = BONUS_SLOT_BASE + i;
            ContainerContentsEntry picked =
                ContainerContentsRoller.pickEntry(eligible, totalWeight, localPos, worldSeed, carriageIndex, slotKey);
            if (picked == null || picked.isAir()) continue;
            int count = ContainerContentsRoller.rollItemCount(picked.count(), localPos, worldSeed, carriageIndex, slotKey);
            ItemStack stack = ContainerContentsRoller.rollItemStack(
                picked, count, localPos, worldSeed, carriageIndex, diffIndex, slotKey, registries);
            if (stack.isEmpty()) continue;
            out.add(stack.save(registries, new CompoundTag()));
        }
        return out;
    }

    /**
     * Return a copy of {@code beNbt} with the bonus list merged into its {@code NeoForgeData}
     * compound. An empty {@code bonus} leaves the tag untouched.
     */
    static CompoundTag withBonus(CompoundTag beNbt, ListTag bonus) {
        if (bonus.isEmpty()) return beNbt;
        CompoundTag out = beNbt.copy();
        CompoundTag data = out.contains(NBT_NEOFORGE_DATA, Tag.TAG_COMPOUND)
            ? out.getCompound(NBT_NEOFORGE_DATA).copy()
            : new CompoundTag();
        data.put(NBT_KEY, bonus);
        out.put(NBT_NEOFORGE_DATA, data);
        return out;
    }

    /** True if this BE still carries an unspent bonus. */
    public static boolean hasBonus(BlockEntity be) {
        return be.getPersistentData().contains(NBT_KEY, Tag.TAG_LIST);
    }

    /**
     * Read and <b>remove</b> the pre-rolled bonus from {@code be} — one shot, so a chest
     * opened without Luck can't be farmed later with a potion. Returns an empty list when
     * nothing was stored.
     */
    public static List<ItemStack> takeBonus(BlockEntity be, HolderLookup.Provider registries) {
        CompoundTag data = be.getPersistentData();
        if (!data.contains(NBT_KEY, Tag.TAG_LIST)) return List.of();
        ListTag list = data.getList(NBT_KEY, Tag.TAG_COMPOUND);
        data.remove(NBT_KEY);
        be.setChanged();
        List<ItemStack> stacks = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            ItemStack stack = ItemStack.parseOptional(registries, list.getCompound(i));
            if (!stack.isEmpty()) stacks.add(stack);
        }
        return List.copyOf(stacks);
    }

    /**
     * How many of the candidates a lucky opener claims: Luck I rolls 3–5, Luck II and up
     * rolls 4–5. Zero for no or negative luck (Bad Luck never removes items).
     */
    public static int bonusCountFor(float luck, RandomSource random) {
        if (luck <= 0f) return 0;
        int rolled = MIN_LUCKY_BONUS + random.nextInt(MAX_LUCKY_BONUS - MIN_LUCKY_BONUS + 1);
        int floor = luck >= LUCK_II_THRESHOLD ? LUCK_II_MIN_BONUS : MIN_LUCKY_BONUS;
        return Math.min(MAX_LUCKY_BONUS, Math.max(floor, rolled));
    }

    private static ContainerContentsPool withoutBookPlaceholders(ContainerContentsPool pool) {
        List<ContainerContentsEntry> kept = new ArrayList<>(pool.size());
        for (ContainerContentsEntry e : pool.entries()) {
            if (!isBookPlaceholder(e)) kept.add(e);
        }
        if (kept.size() == pool.size()) return pool;
        return new ContainerContentsPool(List.copyOf(kept), pool.fillMin(), pool.fillMax());
    }

    static boolean isBookPlaceholder(ContainerContentsEntry entry) {
        Item item = ContainerContentsRoller.resolveItem(entry.itemId());
        return item != null && (item == ModItems.RANDOM_BOOK.get()
            || item == ModItems.RANDOM_PLAYERBOOK.get()
            || item == ModItems.RANDOM_LEADERBOARD_BOOK.get()
            || item == ModItems.RANDOM_STAT_BOOK.get()
            || item == ModItems.STATS_BOOK.get());
    }
}
