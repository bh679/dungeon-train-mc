package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.perks.PerkLifeHistory;

/**
 * Client copy of the local player's past lives' perks ({@link PerkLifeHistory}), fed by
 * {@link games.brennan.dungeontrain.net.PerkLivesPacket} and read by the nap sky. Free of client-only
 * imports, like {@link PerkClientState}; cleared on logout in {@link PerkClientEvents}.
 */
public final class PerkClientLives {

    private static volatile PerkLifeHistory history = PerkLifeHistory.EMPTY;

    private PerkClientLives() {}

    public static void apply(PerkLifeHistory next) {
        history = next == null ? PerkLifeHistory.EMPTY : next;
    }

    public static PerkLifeHistory get() {
        return history;
    }

    public static void clear() {
        history = PerkLifeHistory.EMPTY;
    }
}
