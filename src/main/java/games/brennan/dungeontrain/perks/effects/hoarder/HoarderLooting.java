package games.brennan.dungeontrain.perks.effects.hoarder;

import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Hoarder tier 3 perk <b>Looting</b>: a mob you kill drops one extra item {@code rank}% of the time
 * (rank 1 = 1%, rank 50 = 50%, rank 100 and up = always).
 *
 * <p>Pure rules only; {@link HoarderLootingEvents} wires them to {@code LivingDropsEvent}. The roll is made
 * <em>once per kill</em>, not once per drop stack, so a mob with three drop stacks is not three times as
 * generous as one with one. On success one of the mob's drops is picked uniformly and a single item of it
 * is added. A mob that drops nothing still drops nothing.</p>
 */
public final class HoarderLooting {

    public static final String PERK_ID = "looting";
    /** Chance of the extra item per rank. */
    public static final double CHANCE_PER_RANK = 0.01;

    private HoarderLooting() {}

    /** The chance of an extra item at {@code rank}, 0..1. */
    public static double chanceFor(int rank) {
        if (rank <= 0) return 0.0;
        return Math.min(1.0, rank * CHANCE_PER_RANK);
    }

    /** True when this kill drops an extra item at {@code rank}. */
    public static boolean rollsExtra(int rank, RandomSource random) {
        double chance = chanceFor(rank);
        if (chance <= 0.0) return false;
        if (chance >= 1.0) return true;
        return random.nextDouble() < chance;
    }

    /**
     * The extra item: a count-1 copy of one of {@code drops}' non-empty stacks, picked uniformly.
     * Empty when there is nothing to copy.
     */
    public static ItemStack pickExtra(Collection<ItemStack> drops, RandomSource random) {
        List<ItemStack> candidates = new ArrayList<>();
        for (ItemStack stack : drops) {
            if (stack != null && !stack.isEmpty()) candidates.add(stack);
        }
        if (candidates.isEmpty()) return ItemStack.EMPTY;
        return candidates.get(random.nextInt(candidates.size())).copyWithCount(1);
    }

    /** {@link #pickExtra} over item entities, as {@code LivingDropsEvent} hands them out. */
    public static ItemStack pickExtraFromEntities(Collection<ItemEntity> drops, RandomSource random) {
        List<ItemStack> stacks = new ArrayList<>(drops.size());
        for (ItemEntity entity : drops) stacks.add(entity.getItem());
        return pickExtra(stacks, random);
    }
}
