package games.brennan.dungeontrain.client;

/**
 * Client-side cache of the dimension id the debug screen prints for the player's band. Empty means
 * "print the real dimension", which is what a fresh client assumes.
 *
 * <p>Set by {@code BandDimensionPacket} and read by {@code DebugScreenOverlayDepthMixin}; dropped on
 * disconnect so one world's band never labels the next.</p>
 */
public final class ClientBandDimension {

    private static volatile String id = "";

    private ClientBandDimension() {}

    public static String id() {
        return id;
    }

    /** Apply a server update. */
    public static void update(String bandId) {
        id = bandId == null ? "" : bandId;
    }

    /** Forget the band. Wired to logging out. */
    public static void reset() {
        id = "";
    }
}
