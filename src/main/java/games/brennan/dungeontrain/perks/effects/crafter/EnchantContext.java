package games.brennan.dungeontrain.perks.effects.crafter;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayDeque;

/**
 * Per-thread context for the static enchantment code paths that know no player.
 *
 * <p>{@link #pushOwner}/{@link #popOwner} bracket a vanilla call that reads enchantments on behalf of
 * a known entity (an attack, a durability hit, an equipment refresh, a block drop). While a player
 * owner is on the stack, {@link CrafterEnchantBonus} raises the levels of that player's worn and
 * held gear. {@link #pushMaxLevelBonus}/{@link #popMaxLevelBonus} do the same for the enchanting
 * table roll, and {@link #pushEnchantMenu}/{@link #popEnchantMenu} name the menu whose
 * {@code slotsChanged} is running. All three are stacks so nested calls restore what they found.</p>
 */
public final class EnchantContext {

    /** Stands in for "a non-player entity" on the owner stack: ArrayDeque rejects nulls. */
    private static final Object NO_OWNER = new Object();

    private static final ThreadLocal<ArrayDeque<Object>> OWNER = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<ArrayDeque<Integer>> MAX_LEVEL_BONUS = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<ArrayDeque<Object>> ENCHANT_MENU = ThreadLocal.withInitial(ArrayDeque::new);

    private EnchantContext() {}

    public static void pushOwner(Entity entity) {
        OWNER.get().push(entity instanceof Player ? entity : NO_OWNER);
    }

    public static void popOwner() {
        ArrayDeque<Object> stack = OWNER.get();
        if (!stack.isEmpty()) stack.pop();
    }

    /** The player the current enchantment read is for, or null. */
    public static Player owner() {
        Object top = OWNER.get().peek();
        return top instanceof Player player ? player : null;
    }

    public static void pushMaxLevelBonus(int bonus) {
        MAX_LEVEL_BONUS.get().push(Math.max(0, bonus));
    }

    public static void popMaxLevelBonus() {
        ArrayDeque<Integer> stack = MAX_LEVEL_BONUS.get();
        if (!stack.isEmpty()) stack.pop();
    }

    public static int maxLevelBonus() {
        Integer top = MAX_LEVEL_BONUS.get().peek();
        return top == null ? 0 : top;
    }

    public static void pushEnchantMenu(Object menu) {
        ENCHANT_MENU.get().push(menu == null ? NO_OWNER : menu);
    }

    public static void popEnchantMenu() {
        ArrayDeque<Object> stack = ENCHANT_MENU.get();
        if (!stack.isEmpty()) stack.pop();
    }

    /** The enchanting menu whose {@code slotsChanged} is running on this thread, or null. */
    public static Object enchantMenu() {
        Object top = ENCHANT_MENU.get().peek();
        return top == NO_OWNER ? null : top;
    }
}
