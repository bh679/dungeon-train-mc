package games.brennan.dungeontrain.compat;

import net.minecraft.util.RandomSource;

/**
 * How much of its backpack gear a Dungeon Train PlayerMob wears on its body: most wear none; the
 * rest show one item, and each further item is another coin flip. Rolled once per mob by
 * {@link PlayerMobCarriedGearEvents} and kept with it (PlayerMob's {@code CarriedGearLimit}).
 */
public final class CarriedGearRoll {

    /** One mob in this many wears any carried gear at all. */
    static final int WEARS_GEAR_ONE_IN = 8;
    /** A PlayerMob's backpack size — never ask for more items than it can hold. */
    static final int MAX_ITEMS = 8;

    private CarriedGearRoll() {}

    /** 0 (wears none) for 7 in 8 mobs; otherwise 1, then +1 per heads, capped at {@link #MAX_ITEMS}. */
    public static int roll(RandomSource rng) {
        if (rng.nextInt(WEARS_GEAR_ONE_IN) != 0) {
            return 0;
        }
        int items = 1;
        while (items < MAX_ITEMS && rng.nextBoolean()) {
            items++;
        }
        return items;
    }
}
