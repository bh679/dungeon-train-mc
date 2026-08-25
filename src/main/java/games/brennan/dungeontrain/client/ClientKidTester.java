package games.brennan.dungeontrain.client;

/**
 * Client-side mirror of "this player is a kid-safe tester" — the last hop of a mark that starts on
 * the relay's roster ({@code kidtesters.js}), is held server-side by
 * {@link games.brennan.dungeontrain.event.KidTesterMirror}, and arrives here as a
 * {@link games.brennan.dungeontrain.net.KidTesterSyncPacket}.
 *
 * <p>Its only reader is the book vote page ({@code BookVoteClientEvents}), which draws the red
 * "Remove for kids" control beside the ⚠ report when this is true and nothing at all when it is not.
 *
 * <p><b>Advisory only</b>, in exactly the sense {@link ClientBookSuspension} is: the server drops a
 * kid-reject packet from a player its own mirror does not name, and the relay 403s one from a uuid
 * absent from its roster. Flipping this flag on a modified client buys a button that does nothing.
 * It is a drawing hint, not a permission.</p>
 *
 * <p>Defaults to false and is cleared on disconnect, so a single-player world or a server that never
 * sends the packet (an older relay, a player who declined network access) simply has no such control
 * — the state that every player is in today.</p>
 */
public final class ClientKidTester {

    private ClientKidTester() {}

    private static volatile boolean tester;

    /** Apply the synced mark. */
    public static void set(boolean isTester) {
        tester = isTester;
    }

    /** True while this client has been told its player is a kid-safe tester. */
    public static boolean isTester() {
        return tester;
    }

    /** Test seam / disconnect — forget the mark. */
    public static void clear() {
        tester = false;
    }
}
