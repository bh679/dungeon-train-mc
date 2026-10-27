package games.brennan.dungeontrain.perks;

import games.brennan.dungeontrain.registry.ModDataAttachments;
import net.minecraft.server.level.ServerPlayer;

/**
 * The two perk currencies.
 *
 * <ul>
 *   <li><b>Unlock points</b> come from advancements and keep forever. Derived, see
 *       {@link UnlockPointLedger}; the earned total is cached in {@code PERK_UNLOCK_EARNED}.</li>
 *   <li><b>Active points</b> come from eating cosmic apples and last one life:
 *       {@code PERK_ACTIVE_POINTS} is not {@code copyOnDeath}, and respawn clears it explicitly.</li>
 * </ul>
 */
public final class PerkPoints {

    private PerkPoints() {}

    public static int unlockEarned(ServerPlayer player) {
        return player.getData(ModDataAttachments.PERK_UNLOCK_EARNED.get());
    }

    public static int unlockAvailable(ServerPlayer player) {
        return UnlockPointLedger.available(unlockEarned(player), PerkSpend.unlockSpent(player));
    }

    public static int active(ServerPlayer player) {
        return player.getData(ModDataAttachments.PERK_ACTIVE_POINTS.get());
    }

    public static void addActive(ServerPlayer player, int amount) {
        player.setData(ModDataAttachments.PERK_ACTIVE_POINTS.get(), active(player) + amount);
    }

    /** Spends active points; false (and nothing spent) when the player has too few. */
    public static boolean spendActive(ServerPlayer player, int amount) {
        int have = active(player);
        if (amount < 0 || have < amount) return false;
        player.setData(ModDataAttachments.PERK_ACTIVE_POINTS.get(), have - amount);
        return true;
    }

    /** A new life: active points (and the ranks they bought, once the foundation lands) are gone. */
    public static void resetForNewLife(ServerPlayer player) {
        player.setData(ModDataAttachments.PERK_ACTIVE_POINTS.get(), 0);
    }
}
