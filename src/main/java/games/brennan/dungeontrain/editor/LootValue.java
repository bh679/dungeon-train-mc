package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.difficulty.ItemPowerScore;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How much a container's loot is worth, as one comparable number — what the editor's Loot row
 * sorts by.
 *
 * <p>Nothing else in the mod ranks loot, so this borrows the one ranking it does have: gear is
 * scored by {@link ItemPowerScore#baseStat} plus its rarity, the same shape the echo encounter
 * story uses to pick a player's best item. One is added on top so food and materials, which have
 * no combat stat, still count for something; air counts for nothing.</p>
 *
 * <p>A pool's value is what one roll of it is expected to put in the container: the average number
 * of slots filled times the weight-averaged score of what fills them. A stack is worth the square
 * root of its size in single items — sixteen bread is more than one bread, but not sixteen swords.</p>
 */
public final class LootValue {

    /** What a vanilla loot table is worth here: its contents are server data the client cannot see. */
    public static final double UNKNOWN_TABLE = 1.0;

    /** How many of the best items a Loot tooltip shows — one tooltip row of icons. */
    public static final int TOP_ITEMS = 9;

    private static final double RARITY_WEIGHT = 2.0;
    private static final double ENCHANT_LEVEL_WEIGHT = 0.5;
    /** A guaranteed random enchantment is worth this many enchantment levels. */
    private static final double RANDOM_ENCHANT_LEVELS = 4.0;

    private LootValue() {}

    /** One item's worth: 1 + its headline stat + rarity and enchantments; 0 for air. */
    public static double itemScore(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        return 1.0
            + ItemPowerScore.baseStat(stack)
            + RARITY_WEIGHT * stack.getRarity().ordinal()
            + ENCHANT_LEVEL_WEIGHT * ItemPowerScore.totalEnchantmentLevels(stack);
    }

    /** A stack's worth: one item's score times the square root of the stack size. */
    public static double stackScore(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        return itemScore(stack) * Math.sqrt(stack.getCount());
    }

    /** One pool entry's worth when it is the entry picked, counting its enchantment chance. */
    public static double entryScore(ContainerContentsEntry entry) {
        if (entry == null || entry.isAir()) return 0;
        Item item = entry.resolveItem();
        if (item == Items.AIR) return 0;
        double score = stackScore(new ItemStack(item, Math.max(1, entry.count())));
        if (entry.randomEnchantment()) {
            score += ENCHANT_LEVEL_WEIGHT * RANDOM_ENCHANT_LEVELS * entry.enchantmentChance() / 100.0;
        }
        return score;
    }

    /**
     * The expected worth of one roll of {@code pool} into a container of {@code slots} slots.
     *
     * @param slots the container's slot count, which bounds a {@link ContainerContentsPool#FILL_ALL}
     *              fill; 0 or less is read as one slot
     */
    public static double poolValue(ContainerContentsPool pool, int slots) {
        if (pool == null || pool.isEmpty()) return 0;
        int total = pool.totalWeight();
        if (total <= 0) return 0;
        double weighted = 0;
        for (ContainerContentsEntry e : pool.entries()) {
            weighted += e.weight() * entryScore(e);
        }
        return expectedFill(pool, slots) * weighted / total;
    }

    /** The average number of slots one roll fills: the midpoint of the pool's fill range. */
    static double expectedFill(ContainerContentsPool pool, int slots) {
        int cap = Math.max(1, slots);
        int max = pool.fillMax() == ContainerContentsPool.FILL_ALL ? cap : Math.min(pool.fillMax(), cap);
        int min = Math.min(pool.fillMin(), max);
        return (min + max) / 2.0;
    }

    /** The worth of items already sitting in a container: every stack counted in full. */
    public static double stacksValue(List<ItemStack> stacks) {
        double total = 0;
        for (ItemStack s : stacks) total += stackScore(s);
        return total;
    }

    /** An item a container can give, with its {@link #itemScore}. */
    public record Scored(Item item, double score) {}

    /** Every distinct item a pool can give, scored as one item, best first. */
    public static List<Scored> items(ContainerContentsPool pool) {
        if (pool == null) return List.of();
        List<Scored> out = new ArrayList<>();
        for (ContainerContentsEntry e : pool.entries()) {
            if (e.isAir() || e.resolveItem() == Items.AIR) continue;
            out.add(new Scored(e.resolveItem(), itemScore(new ItemStack(e.resolveItem()))));
        }
        return merged(out);
    }

    /** Every distinct item among {@code stacks}, scored as one item, best first. */
    public static List<Scored> stackItems(List<ItemStack> stacks) {
        List<Scored> out = new ArrayList<>();
        for (ItemStack s : stacks) {
            if (!s.isEmpty()) out.add(new Scored(s.getItem(), itemScore(s.copyWithCount(1))));
        }
        return merged(out);
    }

    /** One entry per item — its best score — ordered best first. */
    public static List<Scored> merged(List<Scored> items) {
        Map<Item, Double> best = new LinkedHashMap<>();
        for (Scored s : items) best.merge(s.item(), s.score(), Math::max);
        List<Scored> out = new ArrayList<>(best.size());
        best.forEach((item, score) -> out.add(new Scored(item, score)));
        out.sort(Comparator.comparingDouble(Scored::score).reversed());
        return List.copyOf(out);
    }
}
