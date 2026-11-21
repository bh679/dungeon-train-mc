package games.brennan.dungeontrain.perks;

import net.minecraft.advancements.AdvancementType;

/**
 * How many perk unlock points an advancement is worth, and the derived balance.
 *
 * <p>Unlock points are never accumulated: the balance is recomputed from the advancements the
 * player has done right now ({@link PerkPointEvents#recompute}). That makes crediting idempotent
 * by construction: a relog, a login replay or a difficulty-partition swap can never count an
 * advancement twice, and players who earned advancements before perks existed are credited for
 * them on their first login. See {@code docs/design/perks-earning.md}.</p>
 */
public final class UnlockPointLedger {

    static final int TASK_POINTS = 1;
    static final int GOAL_POINTS = 2;
    static final int CHALLENGE_POINTS = 3;
    /** A hidden advancement on The Secret Menu tab is found, not aimed at: one extra point. */
    static final int SECRET_MENU_HIDDEN_BONUS = 1;
    /** Everything Burrito, It's Not That Simple and every tab-complete advancement. */
    static final int CAPSTONE_POINTS = 10;

    private UnlockPointLedger() {}

    /**
     * Points one done advancement is worth.
     *
     * @param secretMenuHidden hidden and on The Secret Menu tab (Dungeon Train marks most of its
     *                         advancements hidden so they reveal on earn; only the secret tab's count
     *                         as genuinely hidden here)
     * @param capstone         one of the 10-point capstones
     */
    public static int weight(AdvancementType frame, boolean secretMenuHidden, boolean capstone) {
        if (capstone) return CAPSTONE_POINTS;
        int base = switch (frame) {
            case TASK -> TASK_POINTS;
            case GOAL -> GOAL_POINTS;
            case CHALLENGE -> CHALLENGE_POINTS;
        };
        return secretMenuHidden ? base + SECRET_MENU_HIDDEN_BONUS : base;
    }

    /** Unspent unlock points; never negative (a revoked advancement takes no perk back). */
    public static int available(int earned, int spent) {
        return Math.max(0, earned - spent);
    }
}
