package games.brennan.dungeontrain.moderation;

/**
 * When the Live Eye text filter is on. The rule Brennan chose: <b>only while someone is live</b> —
 * the filter exists to keep words off the Twitch mirror, so with nobody on air it stays out of the way.
 *
 * <p>Pure booleans so each side can answer from what it knows: the server from
 * {@code player/ServerLiveStatus}, the client from {@code client/live/LiveStreamController} and the
 * Live Chat bridge. {@link #devForce} lets a dev build switch the filter on without a real stream
 * ({@code /dungeontrain debug live-moderation force on}).</p>
 */
public final class LiveModerationScope {

    /** Dev-only override: the filter behaves as if someone were live. Never set on a release build. */
    public static volatile boolean devForce;

    private LiveModerationScope() {}

    /** Server chat and book signing are checked while anyone on this server, or on the relay, is live. */
    public static boolean serverActive(boolean anyoneLive) {
        return anyoneLive || devForce;
    }

    /**
     * The client checks its own outgoing chat while it is streaming, or while it is in the stream's chat
     * room (a TV wearer whose lines go to Twitch too).
     */
    public static boolean clientActive(boolean streaming, boolean bridgeConnected) {
        return streaming || bridgeConnected || devForce;
    }

    /** A book being written is on stream only when its writer is the one broadcasting. */
    public static boolean bookFieldActive(boolean streaming) {
        return streaming || devForce;
    }
}
