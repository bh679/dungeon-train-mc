package games.brennan.dungeontrain.perks.effects.builder;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player sixteenths that have been earned but not yet paid as a whole emerald, so a run of cheap
 * gifts still adds up. In memory only, per life: cleared on death and on logout. Also where a
 * take-back is charged first, before any emerald leaves the inventory.
 *
 * <p>Balances are kept in {@code [0, 16)}; the pure helpers below say how many whole emeralds a
 * credit pays out or a charge costs, and the new balance.</p>
 */
public final class DriftGiftLedger {

    /** Result of applying a credit or charge: emeralds to move and the balance afterwards. */
    public record Move(int emeralds, int balance) {}

    private static final Map<UUID, Integer> BALANCE = new ConcurrentHashMap<>();

    private DriftGiftLedger() {}

    /** Credit {@code sixteenths}: pay out whole emeralds, keep the remainder. */
    public static Move credit(int balance, long sixteenths) {
        long total = balance + Math.max(0, sixteenths);
        return new Move(DriftGiftPayout.emeralds(total), DriftGiftPayout.remainder(total));
    }

    /**
     * Charge {@code sixteenths}: spend the balance first, then whole emeralds for the rest, and keep
     * any change as the new balance.
     */
    public static Move charge(int balance, long sixteenths) {
        long shortfall = Math.max(0, sixteenths) - balance;
        if (shortfall <= 0) return new Move(0, (int) -shortfall);
        int emeralds = DriftGiftPayout.emeraldsToCover(shortfall);
        long change = (long) emeralds * DriftGiftPayout.SIXTEENTHS_PER_EMERALD - shortfall;
        return new Move(emeralds, (int) change);
    }

    /** Whether a charge of {@code sixteenths} is affordable with {@code emeraldsHeld} emeralds. */
    public static boolean canCharge(int balance, long sixteenths, int emeraldsHeld) {
        return charge(balance, sixteenths).emeralds() <= emeraldsHeld;
    }

    public static int balance(UUID player) {
        return BALANCE.getOrDefault(player, 0);
    }

    public static void setBalance(UUID player, int balance) {
        if (balance <= 0) BALANCE.remove(player);
        else BALANCE.put(player, balance);
    }

    /** Forget a player's balance: logout, or a new life. */
    public static void forget(UUID player) {
        BALANCE.remove(player);
    }
}
