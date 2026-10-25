package games.brennan.dungeontrain.client;

/**
 * Client mirror of the local player's two perk balances (unlock points from advancements, active
 * points from Cosmic Debris), fed by {@link games.brennan.dungeontrain.net.PerkBalancesPacket}.
 * Free of client-only imports, like {@link PerkClientState}; cleared with it on logout.
 */
public final class PerkClientBalances {

    private static volatile int unlockPoints;
    private static volatile int activePoints;
    private static volatile int revision;

    private PerkClientBalances() {}

    public static void apply(int unlock, int active) {
        unlockPoints = Math.max(0, unlock);
        activePoints = Math.max(0, active);
        revision++;
    }

    public static void clear() {
        apply(0, 0);
    }

    public static int unlockPoints() {
        return unlockPoints;
    }

    public static int activePoints() {
        return activePoints;
    }

    public static int revision() {
        return revision;
    }
}
