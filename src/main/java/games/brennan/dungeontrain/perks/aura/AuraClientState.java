package games.brennan.dungeontrain.perks.aura;

/**
 * The local player's aura state as the server last sent it. Plain data, safe to load on either side;
 * only the client writes it (from {@link AuraSyncPacket}).
 */
public final class AuraClientState {

    private static volatile AuraState.Draws draws = AuraState.Draws.empty();
    private static volatile AuraState.Toggles toggles = AuraState.Toggles.empty();

    private AuraClientState() {}

    static void set(AuraState.Draws d, AuraState.Toggles t) {
        draws = d;
        toggles = t;
    }

    public static AuraState.Draws draws() {
        return draws;
    }

    public static AuraState.Toggles toggles() {
        return toggles;
    }
}
